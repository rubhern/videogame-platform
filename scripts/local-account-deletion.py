#!/usr/bin/env python3
"""Prepare the owner-side #274 runner for an explicit local synthetic-data rehearsal.

Local files exercise the durable protocol, never physical independence. No credentials,
GPG keys, archives or Docker socket enter the web application. State survives restarts.
"""
import argparse
import importlib.machinery
import importlib.util
import json
import os
import pathlib
import secrets
import signal
import subprocess
import sys
import urllib.parse

ROOT = pathlib.Path(__file__).resolve().parents[1]
STATE = ROOT / '.local-secrets/account-deletion'


def private_directory(path):
    if path.is_symlink():
        raise ValueError('Refusing a symlink for local deletion state')
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    if path.stat().st_uid != os.getuid():
        raise ValueError('Local deletion state must belong to this owner')
    path.chmod(0o700)


def write_private(path, text):
    # Refuse links, preserve existing secrets, and never publish temporary plaintext.
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'w') as stream:
        os.fchmod(stream.fileno(), 0o600)
        stream.write(text)


def run(*command):
    result = subprocess.run(command, capture_output=True, timeout=60, check=True)
    return result.stdout.decode().strip()


def prepare(args):
    if os.getuid() == 0:
        raise ValueError('Run the local rehearsal as the non-root workstation owner')
    origin = urllib.parse.urlsplit(args.origin)
    if (origin.scheme != 'http' or origin.hostname not in ('localhost', '127.0.0.1')
            or origin.username or origin.password or origin.query or origin.fragment
            or origin.path not in ('', '/')):
        raise ValueError('Only the loopback local Keycloak origin is supported')
    # The launcher pins its existing project; never select private-dev/production here.
    private_directory(STATE.parent)
    private_directory(STATE)
    for name in ('primary', 'copy', 'archive', 'archive-copy', 'proof', 'holds', 'keys'):
        private_directory(STATE / name)
    credential = STATE / 'executor.key'
    if not credential.exists():
        write_private(credential, secrets.token_hex(32))
    if credential.is_symlink() or credential.stat().st_mode & 0o077:
        raise ValueError('Executor capability must be a private regular file')
    password = sys.stdin.read(4097).strip()
    if not password or len(password) > 4096:
        raise ValueError('Missing local administration credential')
    write_private(STATE / 'admin-password', password)
    keys = STATE / 'keys'
    listing = run('gpg', '--homedir', str(keys), '--batch', '--with-colons', '--list-secret-keys')
    if not any(line.startswith('fpr:') for line in listing.splitlines()):
        run('gpg', '--homedir', str(keys), '--batch', '--pinentry-mode', 'loopback',
            '--passphrase', '', '--quick-generate-key',
            'Synthetic local deletion <local-deletion@example.test>', 'ed25519', 'sign', '0')
        listing = run('gpg', '--homedir', str(keys), '--with-colons', '--list-secret-keys')
    fingerprint = next(line.split(':')[9] for line in listing.splitlines() if line.startswith('fpr:'))
    if not any(line.startswith('ssb:') and ':e:' in line for line in listing.splitlines()):
        run('gpg', '--homedir', str(keys), '--batch', '--pinentry-mode', 'loopback',
            '--passphrase', '', '--quick-add-key', fingerprint, 'cv25519', 'encr', '0')
    config = {
        'issuer': args.origin.rstrip('/') + '/realms/videogame-platform',
        'ledger': str(STATE / 'primary/ledger.jsonl'),
        'ledger_copy': str(STATE / 'copy/ledger.jsonl'),
        'archive_root': str(STATE / 'archive'), 'archive_copy': str(STATE / 'archive-copy'),
        'proof_root': str(STATE / 'proof'), 'holds': str(STATE / 'holds'),
        'gnupg_home': str(keys), 'recipient': fingerprint, 'signer': fingerprint,
        'management_url': 'http://127.0.0.1:8081/actuator/accountdeletionexecutor',
        'credential_file': str(credential),
        'host_command': [sys.executable, '-I', str(ROOT / 'deploy/private-dev/bin/personal-data'),
                         '--origin', args.origin, '--admin-username', args.admin_username,
                         '--admin-password-file', str(STATE / 'admin-password'),
                         '--issuer', args.origin.rstrip('/') + '/realms/videogame-platform',
                         '--postgres-container', 'videogame-platform-postgres-1',
                         '--application-container', 'videogame-platform-application-1'],
    }
    write_private(STATE / 'runner.json', json.dumps(config))


def serve():
    # Validate the runtime before allowing any administrative engine operation.
    config = json.loads((STATE / 'runner.json').read_text())
    for service in ('postgres', 'keycloak', 'application'):
        details = json.loads(run('docker', 'inspect', f'videogame-platform-{service}-1'))[0]
        labels = details['Config'].get('Labels') or {}
        if (labels.get('com.docker.compose.project') != 'videogame-platform'
                or labels.get('com.docker.compose.service') != service
                or pathlib.Path(labels.get('com.docker.compose.project.working_dir', '')).resolve() != ROOT):
            raise ValueError('Refusing a runtime outside this local checkout')
        if service == 'application':
            environment = dict(item.split('=', 1) for item in details['Config']['Env'] if '=' in item)
            if environment.get('OIDC_ISSUER_URI') != config['issuer']:
                raise ValueError('The local application issuer does not match the administrative runtime')
    path = ROOT / 'deploy/private-dev/bin/personal-data-self-service-runner'
    loader = importlib.machinery.SourceFileLoader('local_deletion_runner', str(path))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    runner = module.Runner(STATE / 'runner.json')
    signal.signal(signal.SIGTERM, lambda *_: runner.stopped.set())
    signal.signal(signal.SIGINT, lambda *_: runner.stopped.set())
    print('Starting local automatic deletion for synthetic accounts. Mi perfil: http://localhost:8080/mi-perfil', flush=True)
    print('Keep this terminal open. Ctrl+C stops the executor; preserved local evidence supports restart.', flush=True)
    runner.run()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    setup = sub.add_parser('prepare')
    setup.add_argument('--origin', required=True)
    setup.add_argument('--admin-username', required=True)
    sub.add_parser('run')
    args = parser.parse_args()
    if args.command == 'prepare':
        prepare(args)
    else:
        serve()


if __name__ == '__main__':
    try:
        main()
    except Exception:
        sys.exit('Local account-deletion setup failed; no successful deletion is implied. Check local prerequisites and protected state.')
