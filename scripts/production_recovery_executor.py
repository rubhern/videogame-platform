#!/usr/bin/env python3
"""Ephemeral GitHub-hosted isolated restore; never connects to the production VPS."""
import argparse
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import sys

import private_dev_promotion as trust
import public_production_integration as integration
import private_production_upgrade as upgrade
import production_deployment_evidence as evidence
from production_deploy import invoke

ROOT = Path(__file__).resolve().parents[1]
require = trust.require


def paths(run_id, attempt):
    trust.validate_source_run(os.environ['GITHUB_SHA'], run_id, attempt)
    require(os.environ.get('GITHUB_RUN_ID') == run_id and os.environ.get('GITHUB_RUN_ATTEMPT') == attempt,
            'Recovery paths must belong to this runner attempt.')
    return Path('/dev/shm') / f'gameometro-recovery-{run_id}-{attempt}'


def gpg(home, *args, data=None):
    return invoke(['gpg', '--homedir', home, '--batch', '--no-tty', *args], data=data, timeout=60)


def key_identity(listing, scope):
    """GnuPG DETAILS field 15: '#' is a stub, '+' is locally available private material."""
    keys, current = [], None
    for line in listing.decode().splitlines():
        fields = line.split(':')
        if fields[0] in ('sec', 'ssb'):
            require(len(fields) > 14 and fields[1] not in ('r', 'e', 'd'), 'Invalid or expired recovery key.')
            current = {'type': fields[0], 'capabilities': fields[11], 'secret': fields[14], 'fingerprint': None}
            keys.append(current)
        elif fields[0] == 'fpr' and current and current['fingerprint'] is None:
            current['fingerprint'] = fields[9]
    primary = [k for k in keys if k['type'] == 'sec']
    usable = [k for k in keys if k['secret'] == '+']
    require(len(primary) == 1 and len(usable) == 1 and all(k['secret'] in ('+', '#') for k in keys),
            'Exactly one locally available decryption key is required; hardware tokens are unsupported.')
    selected = usable[0]
    require('e' in selected['capabilities'].lower() and re.fullmatch('[A-F0-9]{40}', selected['fingerprint'] or ''),
            'The selected secret cannot decrypt.')
    if scope == 'subkey':
        require(primary[0]['secret'] == '#' and selected['type'] == 'ssb'
                and set(selected['capabilities'].lower()) <= {'e'}, 'Only the decryption subkey may be imported; primary/signing secrets refused.')
    else:
        require(scope == 'primary-encryption' and selected['type'] == 'sec', 'Explicit primary-encryption scope required.')
    return primary[0]['fingerprint'], selected['fingerprint']


def load_core():
    loader = importlib.machinery.SourceFileLoader('actions_recovery', str(ROOT / 'deploy/public-production/bin/recovery'))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    loader.exec_module(module)
    return module


def context(directory):
    return json.loads((directory / 'context.json').read_text())


def prepare(revision, run_id, attempt):
    from production_deploy import admission, approved
    require(os.environ.get('GITHUB_ACTIONS') == 'true' and os.environ.get('RUNNER_ENVIRONMENT') == 'github-hosted',
            'Recovery requires an ephemeral GitHub-hosted runner.')
    api = trust.GitHub(os.environ['GH_TOKEN'])
    admission(api, revision, run_id, attempt)
    approved(api, run_id, 'production-recovery')
    integration.verify_local_docker()
    directory = paths(run_id, attempt)
    directory.mkdir(mode=0o700)
    home = directory / 'gnupg'
    home.mkdir(mode=0o700)
    data = os.environ.pop('RECOVERY_GPG_KEY', '')
    password = os.environ.pop('RECOVERY_GPG_PASSPHRASE', '')
    require(0 < len(data.encode()) <= 48000, 'Missing or oversized recovery key.')
    passfile = directory / 'passphrase'
    passfile.write_text(password + '\n')
    passfile.chmod(0o600)
    (home / 'gpg.conf').write_text('pinentry-mode loopback\npassphrase-file ' + str(passfile) + '\n')
    gpg(home, '--import', data=data.encode())
    primary, subkey = key_identity(gpg(home, '--with-colons', '--with-subkey-fingerprint', '--list-secret-keys'),
                                   os.environ.get('RECOVERY_KEY_SCOPE', 'subkey'))
    require(primary == os.environ['RECOVERY_PRIMARY_FINGERPRINT'] and subkey == os.environ['RECOVERY_DECRYPTION_FINGERPRINT'],
            'Imported key differs from the owner-pinned recovery fingerprints.')
    # A synthetic encrypt/decrypt round trip proves the imported secret and passphrase now.
    sample = directory / 'probe'
    sample.write_bytes(secrets.token_bytes(32))
    encrypted = directory / 'probe.gpg'
    plain = directory / 'probe.out'
    gpg(home, '--trust-model', 'always', '--recipient', subkey + '!', '--output', encrypted, '--encrypt', sample)
    gpg(home, '--output', plain, '--decrypt', encrypted)
    require(sample.read_bytes() == plain.read_bytes(), 'Recovery key probe did not decrypt.')
    s3 = directory / 's3.json'
    s3.write_text(os.environ.pop('RECOVERY_S3_CONFIG', ''))
    s3.chmod(0o600)
    ceiling = int(os.environ.get('RECOVERY_MAX_BYTES', str(200 * 1024**3)))
    require(0 < ceiling <= 200 * 1024**3, 'Recovery byte limit exceeds the existing storage ceiling.')
    invoke([sys.executable, '-I', ROOT / 'deploy/public-production/bin/recovery', 'admission',
            '--s3-config', s3, '--max-copy-bytes', str(ceiling), '--receipt', directory / 'admission.json'], timeout=1900)
    nonce = secrets.token_hex(32)
    record = {'revision': revision, 'runId': run_id, 'attempt': attempt, 'nonce': nonce,
              'project': 'vgp-production-test-' + secrets.token_hex(6)}
    upgrade.control.durable(directory / 'context.json', record)
    disk = shutil.disk_usage(os.environ['RUNNER_TEMP'])
    ready = {**{k: record[k] for k in ('revision', 'runId', 'attempt', 'nonce')}, 'kind': 'recovery-ready',
             'outcome': 'success', 'primaryFingerprint': primary, 'decryptionFingerprint': subkey,
             'diskFreeBytes': max(0, disk.free - 1024**3), 'tmpfsFreeBytes': shutil.disk_usage('/dev/shm').free,
             'maxCopyBytes': min(ceiling, max(0, disk.free - 1024**3))}
    Path('recovery-ready.json').write_text(json.dumps(ready))


def restore(revision, run_id, attempt):
    directory = paths(run_id, attempt)
    record = context(directory)
    api = trust.GitHub(os.environ['GH_TOKEN'])
    request = evidence.read(api, 'request', revision, run_id, attempt, wait_seconds=5400)
    require(request.get('kind') == 'recovery-request' and request.get('outcome') == 'success'
            and request.get('nonce') == record['nonce'], 'Backup request is not bound to this prepared runner.')
    publication, resources = request['publication'], request['resources']
    require(publication.get('kind') == 's3-publish' and publication.get('outcome') == 'success'
            and re.fullmatch('[0-9a-f]{32}', publication.get('backupId', '')), 'Canonical S3 publication required.')
    require(set(resources) == {'databaseBytes', 'largestDatabaseBytes'} and
            all(type(v) is int and v > 0 for v in resources.values()) and
            resources['largestDatabaseBytes'] <= resources['databaseBytes'], 'Invalid database capacity request.')
    ready = json.loads(Path('recovery-ready.json').read_text())
    require(shutil.disk_usage('/dev/shm').free >= resources['databaseBytes'] + 64 * 1024**2 and
            shutil.disk_usage(os.environ['RUNNER_TEMP']).free >= 3*resources['databaseBytes'] + 512*1024**2,
            'Runner capacity no longer covers the actual database sizes.')
    work = Path(os.environ['RUNNER_TEMP']) / f'production-restore-{run_id}-{attempt}'
    work.mkdir(mode=0o700)
    upgrade.control.durable(directory / 'context.json', {**record, 'work': str(work)})
    proof = directory / 'publication.json'
    proof.write_text(json.dumps(publication))
    proof.chmod(0o600)
    pair = work / 'pair'
    invoke([sys.executable, '-I', ROOT / 'deploy/public-production/bin/recovery', 'fetch', '--s3-config', directory / 's3.json',
            '--publish-receipt', proof, '--destination', pair, '--max-copy-bytes', str(ready['maxCopyBytes']),
            '--receipt', directory / 'fetch.json'], timeout=2000)
    # Existing #455 actor validates hashes, schema identity and application/Keycloak linkage.
    loader = importlib.machinery.SourceFileLoader('actions_restore_check', str(ROOT / 'scripts/rehearse-private-production-upgrade.py'))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    checker = importlib.util.module_from_spec(spec)
    loader.exec_module(checker)
    scratch = directory / 'restore'
    scratch.mkdir(mode=0o755)
    # Synthetic role files need the original helper's umask for Docker consumers.
    old_umask = os.umask(0o022)
    try:
        result = checker.restore_check(pair, directory / 'gnupg', directory / 'restore.json', scratch, project=record['project'])
    finally:
        os.umask(old_umask)
    require(result.get('outcome') == 'success' and result.get('kind') == 'isolated-restore'
            and result.get('backupId') == publication['backupId'], 'Isolated restore did not verify this pair.')
    result = {**{k: record[k] for k in ('revision', 'runId', 'attempt', 'nonce')},
              **{k: result[k] for k in ('kind', 'outcome', 'backupId')},
              'requestSha256': upgrade.digest(request)}
    # Stored without keys/data; the cleanup step must succeed before this can be published.
    Path('recovery-result.pending.json').write_text(json.dumps(result))


def cleanup(revision, run_id, attempt):
    directory = paths(run_id, attempt)
    clean = True
    if directory.exists():
        record = context(directory) if (directory / 'context.json').exists() else {}
        project = record.get('project')
        if project:
            require(re.fullmatch('vgp-production-test-[0-9a-f]{12}', project), 'Unexpected cleanup project.')
            file = directory / 'restore/restore-compose.json'
            try:
                if file.exists():
                    invoke(['docker', 'compose', '-p', project, '-f', file, '--profile', '*',
                            'down', '--volumes', '--remove-orphans', '--timeout', '5'], timeout=180)
                for args in (['ps', '-aq'], ['volume', 'ls', '-q'], ['network', 'ls', '-q']):
                    require(not invoke(['docker', *args, '--filter', f'label=com.docker.compose.project={project}']).strip(),
                            'Disposable recovery resources remain.')
            except BaseException:
                clean = False
        try:
            invoke(['gpgconf', '--homedir', directory / 'gnupg', '--kill', 'all'], timeout=30)
        except BaseException:
            clean = False
        # Remove credentials even when Docker cleanup failed. The hosted VM is also destroyed.
        shutil.rmtree(directory)
        work = Path(os.environ['RUNNER_TEMP']) / f'production-restore-{run_id}-{attempt}'
        if work.exists():
            shutil.rmtree(work)
    require(clean, 'Recovery cleanup failed; apply remains forbidden.')
    pending = Path('recovery-result.pending.json')
    if pending.exists():
        result = json.loads(pending.read_text())
        result['cleanup'] = 'verified'
        Path('recovery-result.json').write_text(json.dumps(result))
        pending.unlink()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('prepare', 'restore', 'cleanup'))
    parser.add_argument('revision')
    parser.add_argument('run_id')
    parser.add_argument('attempt')
    args = parser.parse_args()
    os.umask(0o077)
    sys.dont_write_bytecode = True
    def interrupted(*_):
        raise trust.Refused('Interrupted recovery.')
    for number in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
        signal.signal(number, interrupted)
    try:
        globals()[args.mode](args.revision, args.run_id, args.attempt)
        return 0
    except BaseException:
        print('Production recovery refused or failed; raw diagnostics withheld.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
