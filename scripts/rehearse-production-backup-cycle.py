#!/usr/bin/env python3
"""The scheduled cycle on a disposable real runtime, with trusted-main images and synthetic data.

Real PostgreSQL, Keycloak, edge, application, Grafana, fences, Compose and encryption; only S3
is replaced, because no provider resource is authorized here. Run it by hand before a change
to the cycle reaches a host. It proves the mechanism, never the real host's fence duration.

A client reads through the real edge during the online and the fenced cycle (ADR-0043): the
online one must serve every request, the fenced one must answer with maintenance, never with a
refused connection. The online pair is restored into a separate server and compared.
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


# Reads through the edge every half second until stdin closes; prints outcome counts only.
AVAILABILITY = r"""
import http.client, json, select, ssl, sys, time
context = ssl.create_default_context(cafile='/run/pki/ca.crt')
counts, index = {}, 0
while not select.select([sys.stdin], [], [], 0)[0]:
    # The shell and the BFF session read; neither needs a published catalogue, which this runtime lacks.
    path = '/api/v1/session' if index % 2 else '/'
    index += 1
    try:
        connection = http.client.HTTPSConnection('gameometro.com', context=context, timeout=20)
        connection.request('GET', path)
        response = connection.getresponse()
        body = response.read()
        connection.close()
        maintenance = response.status == 503 and (b'SERVICE_MAINTENANCE' in body or 'mantenimiento' in body.decode('utf-8', 'replace'))
        kind = 'served' if response.status == 200 else 'maintenance' if maintenance else str(response.status)
    except Exception:
        kind = 'unreachable'
    counts[kind] = counts.get(kind, 0) + 1
    time.sleep(0.5)
print(json.dumps(counts), flush=True)
"""


class Availability:
    """A client on the edge's test network, counting what each request received."""

    def __init__(self, source):
        self.source, self.result = source, None

    def __enter__(self):
        self.process = subprocess.Popen(
            ['docker', 'run', '--rm', '-i', '--label', f'vgp.production-test={self.source.project}',
             '--network', self.source.project + '-test-client', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true',
             '--memory', '256m', '--pids-limit', '64', '-v', f'{self.source.directory}/pki:/run/pki:ro',
             integration.PLAYWRIGHT, 'python3', '-c', AVAILABILITY],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        time.sleep(5)  # Several served reads before the capture starts.
        return self

    def __exit__(self, *_):
        output = self.process.communicate(timeout=120)[0]  # Closing stdin ends the client's loop.
        self.result = json.loads(output.decode().strip().splitlines()[-1]) if output.strip() else {'probe': 'failed'}


def snapshot_copies(project):
    return core.run(['docker', 'ps', '-aq', '--filter', f'label={core.SNAPSHOT_LABEL}={project}']).split()


COUNTS = (('videogame_platform', 'SELECT count(*) FROM ratings.rating;'), ('videogame_platform', 'SELECT count(*) FROM catalogue.game;'),
          ('videogame_keycloak', 'SELECT count(*) FROM user_entity;'))


def restored_counts(source, pair, private_key):
    """Decrypt the pair and restore both dumps into a separate, unnetworked server."""
    name = source.project + '-restore-check'
    image = json.loads(source.file.read_text())['services']['postgres']['image']
    with core.scratch() as directory:
        for database in core.DATABASES:
            core.crypto('bc_decrypt_file', pair / (database + '.dump.gpg'), directory / database, private_key)
        core.run(['docker', 'run', '-d', '--name', name, '--label', f'vgp.production-test={source.project}', '--network', 'none',
                  '-e', 'POSTGRES_PASSWORD=synthetic', image])
        try:
            for _ in range(60):
                if subprocess.run(['docker', 'exec', name, 'pg_isready', '-q', '-U', 'postgres'], capture_output=True).returncode == 0:
                    break
                time.sleep(1)
            time.sleep(2)  # The image restarts its server once after initialization.
            for database in core.DATABASES:
                core.run(['docker', 'exec', name, 'createdb', '-U', 'postgres', database])
                with (directory / database).open('rb') as stream:
                    result = subprocess.run(['docker', 'exec', '-i', name, 'pg_restore', '--no-owner', '--no-privileges',
                                             '--exit-on-error', '-U', 'postgres', '-d', database], stdin=stream, capture_output=True)
                core.require(result.returncode == 0, 'The online pair did not restore.')
            return [core.run(['docker', 'exec', name, 'psql', '-U', 'postgres', '-XqAt', '-d', database, '-c', query]).decode().strip()
                    for database, query in COUNTS]
        finally:
            subprocess.run(['docker', 'rm', '-f', '-v', name], capture_output=True)


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
                       's3Python': sys.executable, 'status': str(work / 'status.json'), 'captureMode': 'online'}

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
            print('Scenario: an online cycle while a client reads.', flush=True)
            application = harness.runtime().compose('ps', '-q', 'application').strip()
            started = time.monotonic()
            with Availability(source) as client:
                run = harness.cycle()
                core.require(run.run() == 0, 'The online cycle failed.')
            receipt['onlineCycleSeconds'] = round(time.monotonic() - started, 1)
            receipt['onlineCycleRequests'] = client.result
            core.require(set(client.result) == {'served'} and client.result['served'] >= 10,
                         'A client saw an interruption during the online capture.')
            core.require(harness.runtime().compose('ps', '-q', 'application').strip() == application, 'The online capture restarted the application.')
            harness.serving()
            pair = Path(run.journal['pair'])
            core.require(harness.uploads[-1] == 'publish' and core.verify(pair)['fence'] == core.SNAPSHOT,
                         'The online cycle did not produce an uploadable online pair.')
            core.require(run.journal['captureMode'] == 'online' and not snapshot_copies(source.project), 'An online copy was left behind.')
            live = [harness.runtime().sql(database, query).decode().strip() for database, query in COUNTS]
            core.require(restored_counts(source, pair, private_key) == live, 'The restored online pair differs from the runtime.')
            receipt['onlineCycle'] = 'passed'

            print('Scenario: a fenced cycle keeps the edge answering.', flush=True)
            harness.config['captureMode'] = 'fenced'
            started = time.monotonic()
            with Availability(source) as client:
                run = harness.cycle()
                core.require(run.run() == 0, 'The fenced cycle failed.')
            receipt['fencedCycleSeconds'] = round(time.monotonic() - started, 1)
            receipt['fencedCycleRequests'] = client.result
            core.require('unreachable' not in client.result and client.result.get('maintenance', 0) > 0,
                         'The fenced capture refused connections instead of answering with maintenance.')
            harness.serving()
            core.require(core.verify(Path(run.journal['pair']))['fence'] == core.FENCED, 'The fenced cycle did not produce a fenced pair.')
            core.require('grafana' in run.journal['optionalRunning'] and not run.journal['optionalDegraded'], 'Grafana did not come back.')
            receipt['normalCycle'] = 'passed'

            def failing_runtime():
                runtime = harness.runtime()
                def broken(*_):
                    raise core.Refused('Injected capture failure.')
                runtime.identity = broken
                return runtime
            for mode in ('online', 'fenced'):
                print(f'Scenario: the {mode} capture fails.', flush=True)
                harness.config['captureMode'] = mode
                uploads = len(harness.uploads)
                run = harness.cycle(failing_runtime)
                core.require(run.run() == 1 and run.journal['lastFailure'] == 'capture-failed', 'The failed capture was not reported.')
                core.require(harness.uploads[uploads:].count('publish') == 0, 'A failed capture was uploaded.')
                core.require(not snapshot_copies(source.project), 'A failed online copy was left behind.')
                harness.serving()
            receipt['failedCaptureReopened'] = 'passed'

            print('Scenario: the host dies during an online capture.', flush=True)
            harness.config['captureMode'] = 'online'
            runtime = harness.runtime()
            with runtime.lock():
                core.Snapshot(runtime, runtime.snapshot_size()).__enter__()  # Never left: what a crash leaves behind.
            core.require(snapshot_copies(source.project), 'The interrupted copy was not created.')
            run = harness.cycle()
            run.journal.update(status='snapshotting', runId='a' * 32, pair=str(run.pairs / ('20261107T000000Z-' + 'a' * 32)))
            run.save()
            core.require(harness.cycle().reconcile() == 0 and not snapshot_copies(source.project), 'Reconciliation left the copy.')
            core.require(harness.cycle().journal['status'] == 'idle', 'The interrupted online capture was not settled.')
            harness.serving()
            receipt['onlineCrashRecovered'] = 'passed'

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
