#!/usr/bin/env python3
"""Owner-run cloud-only reconstruction of the newest real production pair (#276).

Uses only S3 and the owner's protected inputs: the observer lists the bucket, the recovery reader
fetches the newest verified pair, and the two cloud ledger copies are rebuilt with ledger-fetch.
The pair is restored into a disposable target on this Docker engine under the production project
name, the deletion ledger is replayed, the runtime reopens and readiness is recorded. The VPS is
never contacted. The result is the `RESTORE_EVIDENCE` envelope for the backup observer.

Run from `scripts/` on the owner machine that holds the decryption keyring:

  python3 reconstruct-production-recovery.py --revision <release sha> --evidence <new dir> \\
    --observer-config <json> --recovery-config <json> --cloud-copies <cloud.json> \\
    --s3-python <venv python> --keyring ~/.gnupg \\
    --admin-username <production Keycloak admin> --admin-password-file <owner-only file>
"""
import argparse
import datetime as dt
import hashlib
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import shlex
import signal
import subprocess
import sys
import tempfile
import time
import traceback

import public_production_integration as integration

ROOT = Path(__file__).resolve().parents[1]
PROJECT = 'gameometro-production'
MAX_BYTES = 200 * 1024 ** 3
sys.dont_write_bytecode = True


def load(name, path):
    loader = importlib.machinery.SourceFileLoader(name, str(path))
    spec = importlib.util.spec_from_loader(name, loader)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    loader.exec_module(module)
    return module


rehearsal = load('reconstruction_rehearsal', ROOT / 'scripts/rehearse-production-recovery.py')
core, monitoring = rehearsal.core, rehearsal.monitoring
require = core.require


def s3(python, command, config, receipt, *arguments):
    """Run one S3 command of the canonical actor; its sanitized receipt is the only output."""
    result = subprocess.run([str(python), '-I', str(ROOT / 'deploy/public-production/bin/recovery'), command,
                             '--s3-config', str(config), '--max-copy-bytes', str(MAX_BYTES), *map(str, arguments),
                             '--receipt', str(receipt)], stdin=subprocess.DEVNULL, capture_output=True, timeout=3600)
    require(result.returncode in (0, 2) and receipt.exists(), f'S3 {command} failed; see its sanitized receipt.')
    return json.loads(receipt.read_text())


def newest_pair(observation):
    pairs = [p for p in observation.get('pairs', []) if isinstance(p.get('objects'), dict)]
    require(pairs, 'The observer found no complete pair to reconstruct.')
    return max(pairs, key=lambda pair: pair['recoveryPoint'])


def release_for(manifest, revision):
    """The trusted-main release that published exactly the pair's application and Keycloak images."""
    api = integration.trust.GitHub(integration.command(['gh', 'auth', 'token']).stdout.strip())
    candidates = [revision] if revision != 'auto' else integration.command(
        ['gh', 'api', 'repos/rubhern/videogame-platform/commits?sha=main&per_page=40', '--jq', '.[].sha']).stdout.split()
    for candidate in candidates:
        try:
            selected = integration.resolve(api, candidate)
        except Exception:
            continue  # Not a promoted trusted-main publication, or past its retention.
        if all(selected['images'].get(name) == manifest['images'][name] for name in ('application', 'keycloak')):
            return candidate, selected
    raise core.Refused('No retained trusted-main release matches the pair images; pass --revision explicitly.')


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    parser.add_argument('--revision', default='auto',
                        help="Trusted-main release whose images the pair recorded; 'auto' searches recent main commits")
    parser.add_argument('--evidence', type=Path, required=True)
    for name in ('observer-config', 'recovery-config', 'cloud-copies', 's3-python', 'keyring', 'admin-password-file'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--admin-username', required=True)
    parser.add_argument('--publish-evidence', action='store_true',
                        help='Store the envelope as the backup-observer RESTORE_EVIDENCE secret')
    a = parser.parse_args()
    os.umask(0o077)
    a.evidence.mkdir(mode=0o700, parents=True, exist_ok=False)
    for path in (a.observer_config, a.recovery_config, a.cloud_copies, a.admin_password_file):
        core.private(path)
    started = time.monotonic()
    receipt = {'kind': 'recovery-reconstruction', 'outcome': 'failure', 'productionReady': False, 'scope': PROJECT}
    exercise, temporary = None, None

    def interrupted(*_):
        raise core.Refused('Interrupted.')
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    try:
        integration.verify_local_docker()
        for listing in (['ps', '-aq'], ['volume', 'ls', '-q'], ['network', 'ls', '-q']):
            require(not integration.command(['docker', *listing, '--filter', f'label=com.docker.compose.project={PROJECT}']).stdout.strip(),
                    'This engine already holds production-project resources; use an isolated owner engine.')
        temporary = tempfile.TemporaryDirectory(prefix='vgp-reconstruction-')
        work = Path(temporary.name)
        owner, independent = work / 'owner', work / 'independent'
        owner.mkdir(mode=0o700); independent.mkdir(mode=0o700)

        print('Listing the bucket with the observer and fetching the newest pair with the recovery reader.', flush=True)
        observation = s3(a.s3_python, 'observe', a.observer_config, owner / 'observation.json')
        pair = newest_pair(observation)
        reference = owner / 'pair-reference.json'
        core.write(reference, {k: v for k, v in pair.items() if k != 'receiptObject'})
        backup = independent / 'pair'
        fetched = s3(a.s3_python, 'fetch', a.recovery_config, owner / 'fetch.json', '--publish-receipt', reference,
                     '--destination', backup)
        require(fetched.get('outcome') == 'success' and fetched['backupId'] == pair['backupId'], 'Cloud fetch did not verify the pair.')
        manifest = core.verify(backup)
        receipt.update(backupId=manifest['backupId'], manifestSha256=core.digest(backup / 'manifest.json'),
                       recoveryPoint=pair['recoveryPoint'])

        print('Rebuilding the deletion ledger from its two cloud copies.', flush=True)
        (owner / 'ledger').mkdir(mode=0o700); (independent / 'ledger').mkdir(mode=0o700)
        ledger, ledger_copy = owner / 'ledger/deletions.jsonl', independent / 'ledger/deletions.jsonl'
        checkpoint = owner / 'ledger/checkpoint.json'
        core.run([a.s3_python, '-I', ROOT / 'deploy/private-dev/bin/personal-data-ledger', '--cloud-copies', a.cloud_copies,
                  '--ledger', ledger, '--ledger-copy', ledger_copy, 'ledger-fetch', '--checkpoint', checkpoint])
        receipt['ledgerSha256'] = core.digest(ledger)

        print('Preparing the disposable target from the trusted release images.', flush=True)
        revision, selected = release_for(manifest, a.revision)
        for image in selected['images'].values():
            integration.command(['docker', 'pull', image])
        info = json.loads(integration.command(['docker', 'image', 'inspect', selected['images']['application']]).stdout)[0]
        selected['applicationVersion'] = info['Config']['Labels']['org.opencontainers.image.version']
        receipt['revision'] = revision
        target = work / 'target'; target.mkdir()
        exercise = rehearsal.RecoveryExercise(target, a.evidence, selected, None)
        exercise.project = PROJECT  # Production project name: only then is the scope an owner reconstruction.
        os.umask(0o022)
        exercise.prepare()
        monitoring(exercise)
        os.umask(0o077)
        # The restored master realm keeps the production administrator; its credentials come from
        # the owner's separately retained reconstruction material. Database roles get fresh secrets.
        admin_password = target / 'secrets/keycloak-admin-password'
        admin_password.write_bytes(a.admin_password_file.read_bytes())
        reader_password = owner / 'grafana-password'
        config = json.loads(exercise.file.read_text())
        reader_password.write_bytes(Path(config['secrets']['grafana_database_password']['file']).read_bytes())
        reader_password.chmod(0o600)
        state = target / 'recovery-journal'
        restored = core.Runtime(exercise.file, PROJECT, state)
        recovery_started = time.monotonic()

        # The restore decrypts non-interactively; unlock the owner key now, on this terminal only.
        recipient = manifest.get('recipient') or core.run(['gpg', '--homedir', a.keyring, '--batch', '--with-colons',
                                                           '--list-secret-keys']).decode().split('fpr:::::::::')[1].split(':')[0]
        warm = subprocess.run(['gpg', '--homedir', str(a.keyring), '--batch', '--yes', '--trust-model', 'always', '--encrypt',
                               '-r', recipient], input=b'reconstruction-unlock\n', capture_output=True, check=True).stdout
        subprocess.run(['gpg', '--homedir', str(a.keyring), '--decrypt'], input=warm, stdout=subprocess.DEVNULL, check=True)
        print('Restoring the cloud pair; plaintext stays in volatile scratch.', flush=True)
        endpoint = os.environ.get('DOCKER_HOST') or json.loads(core.run(['docker', 'context', 'inspect']))[0]['Endpoints']['docker']['Host']
        require(endpoint.startswith('unix://'), 'The owner-local target must be this machine\'s own Docker engine.')
        result = restored.restore(backup, a.keyring, reader_password, owner_local=True)
        receipt['restoreSeconds'] = result['durationSeconds']
        host_command = shlex.join([str(x) for x in [sys.executable, ROOT / 'deploy/public-production/bin/recovery-personal-data',
            '--config', exercise.file, '--project', PROJECT, '--state', state,
            '--admin-username', a.admin_username, '--admin-password-file', admin_password]])
        print('Replaying the deletion ledger with only Keycloak running.', flush=True)
        restored.replay(ledger, ledger_copy, checkpoint, host_command)
        receipt['deletionReplay'] = True
        restored.reopen(ledger, ledger_copy, checkpoint)
        exercise.up('mailpit', 'caddy', 'application', 'grafana')
        rehearsal.grafana_datasource(exercise)
        proof = restored.record_recovery(backup, ledger, ledger_copy, checkpoint)
        require(proof['scope'] == 'owner-reconstructed-target', 'Reconstruction did not run under the production project.')
        receipt['restoreToReadinessSeconds'] = round(time.monotonic() - recovery_started, 3)
        receipt['databaseBytes'] = int(exercise.sql("SELECT sum(pg_database_size(datname)) FROM pg_database WHERE datname IN ('videogame_platform','videogame_keycloak');", 'postgres'))
        envelope = {**proof, 'outcome': 'success', 'target': 'owner-local-engine', 'verifiedAt': core.utc().isoformat(timespec='seconds')}
        core.write(a.evidence / 'restore-evidence.json', envelope)
        if a.publish_evidence:
            # The observer reads it from its protected environment; the value never reaches a log.
            with (a.evidence / 'restore-evidence.json').open('rb') as stream:
                subprocess.run(['gh', 'secret', 'set', 'RESTORE_EVIDENCE', '--env', 'backup-observer',
                                '--repo', 'rubhern/videogame-platform'], stdin=stream, check=True, capture_output=True)
            receipt['restoreEvidencePublished'] = True
        receipt.update(readiness=True, outcome='success', verifiedAt=envelope['verifiedAt'])
    except Exception as error:
        receipt['failedAt'] = [{'file': Path(f.filename).name, 'line': f.lineno} for f in traceback.extract_tb(error.__traceback__)][-4:]
        receipt['failureType'] = type(error).__name__
        if isinstance(error, core.Refused):
            receipt['reason'] = str(error)
        print('Reconstruction failed; raw diagnostics withheld.', file=sys.stderr)
    finally:
        clean = True
        if exercise is not None:
            try:
                exercise.cleanup()
            except Exception:
                clean = False
        if temporary:
            temporary.cleanup()
        receipt['cleanup'] = clean
        receipt['exerciseSeconds'] = round(time.monotonic() - started, 3)
        if not clean:
            receipt['outcome'] = 'failure'
        core.write(a.evidence / 'reconstruction.json', receipt)
    print(json.dumps({k: receipt.get(k) for k in ('outcome', 'backupId', 'recoveryPoint', 'restoreToReadinessSeconds', 'reason')}))
    return 0 if receipt['outcome'] == 'success' else 1


if __name__ == '__main__':
    sys.exit(main())
