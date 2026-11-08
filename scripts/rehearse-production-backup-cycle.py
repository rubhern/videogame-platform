#!/usr/bin/env python3
"""The scheduled cycle on a disposable real runtime, with trusted-main images and synthetic data.

Real PostgreSQL, Keycloak, edge, application, Grafana, fences, Compose and encryption; only S3
is replaced, because no provider resource is authorized here. Run it by hand before a change
to the cycle reaches a host. It proves the mechanism, never the real host's fence duration.
"""
import argparse
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
from unittest.mock import patch

import public_production_integration as integration

ROOT = Path(__file__).resolve().parents[1]
sys.dont_write_bytecode = True


def load(name, path):
    loader = importlib.machinery.SourceFileLoader(name, str(path))
    spec = importlib.util.spec_from_loader(name, loader)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    loader.exec_module(module)
    return module


rehearsal = load('cycle_rehearsal_recovery', ROOT / 'scripts/rehearse-production-recovery.py')
cycle = load('cycle_rehearsal_cycle', ROOT / 'deploy/public-production/bin/backup-cycle')
core = rehearsal.core
monitoring = rehearsal.monitoring


def role_logins(runtime):
    return runtime.sql('postgres', "SELECT string_agg(rolname, ',' ORDER BY rolname) FROM pg_roles WHERE rolcanlogin AND rolname LIKE 'videogame_%';").decode().strip()


class Harness:
    def __init__(self, source, state, work, fingerprint, keyring):
        self.source, self.state, self.work = source, state, work
        self.uploads = []
        self.directory = work / 'cycle'
        self.directory.mkdir(mode=0o700)
        credentials = {}
        for name in ('uploader', 'admission'):
            credentials[name] = work / f'{name}.json'
            credentials[name].write_text(json.dumps({'accessKey': 'synthetic-' + name}))
            credentials[name].chmod(0o600)
        self.config = {'authorized': True, 'admin': str(work / 'admin'), 'project': source.project, 'state': str(state),
                       'keyring': str(keyring), 'recipient': fingerprint, 'work': str(self.directory),
                       'uploaderConfig': str(credentials['uploader']), 'admissionConfig': str(credentials['admission']),
                       's3Python': sys.executable, 'status': str(work / 'status.json')}

    def invoke(self, command, *arguments, **_):
        self.uploads.append(command)
        receipt = self.work / f's3-{len(self.uploads)}.json'
        receipt.write_text(json.dumps({'alerts': []}))
        return receipt

    def runtime(self):
        return core.Runtime(self.source.file, self.source.project, self.state)

    def cycle(self, runtime=None):
        return cycle.Cycle(self.config, core, runtime=runtime or self.runtime, invoke=self.invoke).load()

    def serving(self):
        runtime = self.runtime()
        core.require(runtime.gate('check') == b'open' and not (self.state / 'PENDING.json').exists(), 'The gate did not reopen.')
        core.require(set(role_logins(runtime).split(',')) >= {'videogame_app', 'videogame_app_migrator', 'videogame_keycloak'}, 'Roles did not reopen.')
        for name in cycle.REQUIRED:
            core.require(runtime.compose('ps', '-q', '--status', 'running', name).strip(), 'A required service did not come back.')
        runtime.compose('exec', '-T', 'application', 'wget', '-q', '--spider', '-T', '3', cycle.READINESS, timeout=30)
        # A query as the reopened role, beside the login check above: the fence is gone, not merely the flag.
        runtime.sql(core.DATABASES[0], "SET ROLE videogame_app; SELECT count(*) FROM ratings.rating;")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--revision', required=True, help='Trusted main revision whose published images to run')
    parser.add_argument('--evidence', type=Path, required=True, help='New private directory for the sanitized receipt')
    arguments = parser.parse_args()
    os.umask(0o077)
    arguments.evidence.mkdir(mode=0o700, parents=True, exist_ok=False)
    receipt = {'kind': 'backup-cycle-rehearsal', 'outcome': 'failure', 'scope': 'isolated-local-synthetic-only',
               'productionReady': False, 'providerExercised': False, 'revision': arguments.revision}
    source = None

    def interrupted(*_):
        raise core.Refused('Interrupted.')
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    keyring = None
    try:
        integration.verify_local_docker()
        token = integration.command(['gh', 'auth', 'token']).stdout.strip()
        selected = integration.resolve(integration.trust.GitHub(token), arguments.revision)
        for image in selected['images'].values():
            integration.command(['docker', 'pull', image])
        info = json.loads(integration.command(['docker', 'image', 'inspect', selected['images']['application']]).stdout)[0]
        selected['applicationVersion'] = info['Config']['Labels']['org.opencontainers.image.version']
        with tempfile.TemporaryDirectory(prefix='vgp-backup-cycle-') as name:
            work = Path(name).resolve()
            private_key, keyring = work / 'owner-key', work / 'public-key'
            private_key.mkdir(mode=0o700)
            keyring.mkdir(mode=0o700)
            core.run(['gpg', '--homedir', private_key, '--batch', '--faked-system-time', str(int(time.time()) - 300), '--passphrase', '',
                      '--quick-generate-key', 'Synthetic cycle rehearsal', 'rsa2048', 'encr', '1d'])
            listing = core.run(['gpg', '--homedir', private_key, '--batch', '--with-colons', '--list-keys']).decode()
            fingerprint = next(x.split(':')[9] for x in listing.splitlines() if x.startswith('fpr:'))
            core.run(['gpg', '--homedir', keyring, '--batch', '--import'], data=core.run(['gpg', '--homedir', private_key, '--batch', '--export', fingerprint]))
            directory = work / 'source'
            directory.mkdir()
            source = rehearsal.RecoveryExercise(directory, arguments.evidence, selected, None)
            print('Preparing the disposable runtime.', flush=True)
            os.umask(0o022)
            source.prepare()
            monitoring(source)
            config = json.loads(source.file.read_text())
            os.umask(0o077)
            source.stage_a()
            state = directory / 'recovery-journal'
            password = work / 'grafana-password'
            password.write_bytes(Path(config['secrets']['grafana_database_password']['file']).read_bytes())
            password.chmod(0o600)
            core.Runtime(source.file, source.project, state).provision_reader(password)
            source.up('mailpit', 'keycloak', 'caddy', 'application', 'grafana')
            harness = Harness(source, state, work, fingerprint, keyring)
            harness.serving()
            print('Scenario: a normal cycle.', flush=True)
            started = time.monotonic()
            run = harness.cycle()
            core.require(run.run() == 0, 'The normal cycle failed.')
            receipt['normalCycleSeconds'] = round(time.monotonic() - started, 1)
            harness.serving()
            core.require(harness.uploads[-1] == 'publish' and core.verify(Path(run.journal['pair'])), 'The normal cycle did not produce an uploadable pair.')
            core.require('grafana' in run.journal['optionalRunning'] and not run.journal['optionalDegraded'], 'Grafana did not come back.')
            receipt['normalCycle'] = 'passed'

            print('Scenario: the capture fails after the fence.', flush=True)
            def failing_runtime():
                runtime = harness.runtime()
                def broken():
                    raise core.Refused('Injected capture failure.')
                runtime.identity = broken
                return runtime
            uploads = len(harness.uploads)
            run = harness.cycle(failing_runtime)
            core.require(run.run() == 1 and run.journal['lastFailure'] == 'capture-failed', 'The failed capture was not reported.')
            core.require(harness.uploads[uploads:].count('publish') == 0, 'A failed capture was uploaded.')
            harness.serving()
            receipt['failedCaptureReopened'] = 'passed'

            print('Scenario: the host dies during the capture.', flush=True)
            # The real fence, then no reopening: exactly what a crash leaves behind.
            run = harness.cycle()
            runtime = harness.runtime()
            run.journal.update(status='capturing', runId='e' * 32, pair=str(run.pairs / ('20261107T000000Z-' + 'e' * 32)), fenceEntered=True, optionalRunning=['grafana'])
            run.save()
            runtime.cycle_run = 'e' * 32
            with runtime.lock():
                runtime.fence()
            core.require(harness.runtime().gate('check') == b'closed', 'The fence was not closed.')
            core.require(not role_logins(harness.runtime()), 'Roles were not fenced.')
            core.require(harness.cycle().reconcile() == 0, 'Reconciliation failed.')
            harness.serving()
            receipt['crashRecovered'] = 'passed'

            print('Scenario: a fence that belongs to the owner.', flush=True)
            run = harness.cycle()
            manual = work / 'manual-pair'
            runtime = harness.runtime()
            with runtime.lock():
                runtime.backup(manual, keyring, fingerprint)
            run.journal.update(status='capturing', runId='f' * 32, pair=str(run.pairs / ('20261107T000000Z-' + 'f' * 32)), fenceEntered=True)
            run.save()
            fresh = harness.cycle()
            core.require(fresh.reconcile() == 1 and fresh.journal['status'] == 'owner-required', 'A foreign fence was not escalated.')
            core.require(harness.runtime().gate('check') == b'closed' and not role_logins(harness.runtime()), 'A foreign fence was reopened.')
            runtime = harness.runtime()
            with runtime.lock():
                runtime.thaw()
            # The owner restarts the product and acknowledges; captures resume.
            restart = harness.cycle()
            restart.journal['optionalRunning'] = ['grafana']
            restart.start_services(harness.runtime())
            core.require(restart.acknowledge() == 0, 'The acknowledgement was refused.')
            harness.serving()
            receipt['foreignFenceLeftAlone'] = 'passed'

            print('Scenario: a preflight refusal costs no downtime.', flush=True)
            runtime = harness.runtime()
            before = runtime.compose('ps', '-q', '--status', 'running', 'application').strip()
            with patch.object(core, 'public_key', side_effect=core.Refused('Private decryption keys are forbidden on the runtime.')):
                core.require(harness.cycle().run() == 1, 'The preflight did not refuse.')
            core.require(harness.runtime().compose('ps', '-q', '--status', 'running', 'application').strip() == before, 'A refused preflight touched the application.')
            receipt['preflightRefusalWithoutDowntime'] = 'passed'
            source.cleanup()
            source = None
            receipt['outcome'] = 'success'
            for ring in (private_key, keyring):
                subprocess.run(['gpgconf', '--homedir', str(ring), '--kill', 'all'], capture_output=True)
    except (Exception, KeyboardInterrupt) as error:
        receipt['reason'] = str(error) if isinstance(error, core.Refused) else type(error).__name__
        print('Rehearsal failed: ' + receipt['reason'], file=sys.stderr)
        return 1
    finally:
        if source is not None:
            try:
                source.cleanup()
            except Exception:
                print('Disposable runtime cleanup needs inspection.', file=sys.stderr)
        (arguments.evidence / 'backup-cycle-rehearsal.json').write_text(json.dumps(receipt, sort_keys=True, indent=2) + '\n')
    print('Rehearsal passed.', flush=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())
