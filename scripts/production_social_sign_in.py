#!/usr/bin/env python3
"""Owner-operated Google and Microsoft sign-in on the installed production realm (#548, ADR-0045).

Runs as root on the production host from the reviewed operational source, like the public-ingress
tool. The reviewed actor (deploy/private-dev/bin/configure-social-sign-in) of the active
installation runs in a disposable helper on the private identity network; the administrator
password and the provider credentials cross its stdin as one JSON document and nothing prints
them. Accounts, credentials, clients and every other realm setting stay untouched.

Provider credentials are owner files, never Compose secrets or runtime inputs, so this tool needs
no in-place upgrade (#548): CREDENTIALS/<alias>-client-id and <alias>-client-secret, root 0600 in
a root 0700 directory. A provider with neither file is disabled, never deleted, so the accounts
that linked it keep their links; one file alone refuses.

  --plan                              read-only: what --apply would change
  --apply --owner-reference R         enable the providers that have credentials, disable the rest
  --check                             read-only: the realm holds the reviewed shape
  --disable --owner-reference R       turn both providers off, keeping linked identities
"""
import argparse
import json
import os
from pathlib import Path
import stat
import sys
import time

sys.dont_write_bytecode = True
SCRIPTS = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS))

import install_private_production as install  # noqa: E402
import prepare_private_production as prepare  # noqa: E402
import private_dev_promotion as trust  # noqa: E402
import private_production_acceptance as acceptance  # noqa: E402
import private_production_control as control  # noqa: E402

require = trust.require
ADMIN = Path('/var/lib/gameometro/production')
CREDENTIALS = Path('/etc/gameometro/production/social-sign-in')
ACTOR = 'deploy/private-dev/bin/configure-social-sign-in'
PROVIDERS = ('google', 'microsoft')
MAX_CREDENTIAL = 512
OWNER_UID = 0


def read_owner_file(path):
    """Root-owned 0600 regular file, one printable line; anything else refuses."""
    info = path.lstat()
    require(stat.S_ISREG(info.st_mode) and info.st_uid == OWNER_UID and stat.S_IMODE(info.st_mode) == 0o600
            and info.st_size <= MAX_CREDENTIAL + 2, f'{path.name} must be a root-owned 0600 file.')
    value = path.read_text(encoding='utf-8').rstrip('\r\n')
    require(value and value.isprintable() and value.strip() == value and len(value) <= MAX_CREDENTIAL,
            f'{path.name} is empty or malformed.')
    return value


def owner_credentials(directory=CREDENTIALS):
    if not directory.exists():
        return {alias: None for alias in PROVIDERS}
    info = directory.lstat()
    require(stat.S_ISDIR(info.st_mode) and info.st_uid == OWNER_UID and stat.S_IMODE(info.st_mode) == 0o700,
            f'{directory} must be a root-owned 0700 directory.')
    expected = {f'{alias}-client-{part}' for alias in PROVIDERS for part in ('id', 'secret')}
    require(set(os.listdir(directory)) <= expected, f'{directory} holds unexpected files.')
    result = {}
    for alias in PROVIDERS:
        paths = [directory / f'{alias}-client-id', directory / f'{alias}-client-secret']
        present = [p.exists() for p in paths]
        require(all(present) or not any(present), f'Provide both {alias} files, or neither.')
        result[alias] = ({'clientId': read_owner_file(paths[0]), 'clientSecret': read_owner_file(paths[1])}
                         if all(present) else None)
    return result


class SocialSignIn:
    def __init__(self, runner=control.run, admin=ADMIN, credentials=CREDENTIALS):
        self.run, self.admin, self.credentials = runner, admin, credentials
        self.active_file = admin / 'upgrades/ACTIVE.json'
        self.directory = admin / 'social-sign-in'
        self.state_file = self.directory / 'SOCIAL-SIGN-IN.json'

    def active(self):
        pointer = json.loads(self.active_file.read_text())
        path = Path(pointer['configurationFile'])
        require(install.sha(path) == pointer['configurationSha256'], 'The active configuration changed.')
        return pointer, path, json.loads(path.read_text())

    def unfinished_upgrade(self):
        return any(json.loads(p.read_text()).get('status') in ('running', 'failed')
                   for p in (self.admin / 'upgrades').glob('*/journal.json'))

    def admitted(self):
        """The same admission as opening registration: an open gate, no fence, no upgrade owner."""
        require(not self.unfinished_upgrade(), 'An unfinished upgrade owns the runtime.')
        pointer, path, config = self.active()
        # The installed candidate's own actor; the reviewed source of that revision.
        source = path.parent / 'candidate'
        require((source / ACTOR).is_file(), 'The active installation lacks the reviewed social sign-in actor; upgrade first.')
        recovery = acceptance.load_recovery(source, path, self.admin / 'first-install/recovery')
        require(recovery.gate('check') == b'open' and recovery.gate('maintenance-check') == b'none',
                'The recovery or identity fence is closed; no identity change.')
        return pointer, config, source

    def actor(self, config, source, providers, *arguments):
        environment = config['services']['keycloak']['environment']
        password = Path(config['secrets']['keycloak_admin_password']['file']).read_text().rstrip('\r\n')
        document = json.dumps({'adminPassword': password, 'providers': providers}).encode()
        output = self.run(['docker', 'run', '--rm', '-i', '--log-driver', 'none',
                           '--network', config['networks']['identity-data']['name'], '--read-only', '--cap-drop', 'ALL',
                           '--security-opt', 'no-new-privileges:true', '--memory', '96m', '--pids-limit', '32',
                           '--label', f'com.docker.compose.project={prepare.PROJECT}',
                           '--label', 'com.docker.compose.service=private-probe', '--label', 'gameometro.recovery-helper=true',
                           '-v', f'{source}:/candidate:ro', install.HELPER, 'python3', '-I', '/candidate/' + ACTOR,
                           '--origin', 'http://keycloak:8080', '--realm', 'gameometro',
                           '--admin-username', environment['KC_BOOTSTRAP_ADMIN_USERNAME'], '--input-file', '/dev/stdin',
                           *arguments], data=document, timeout=120)
        return json.loads(output)

    def record(self, pointer, result, owner, action):
        self.directory.mkdir(mode=0o700, exist_ok=True)
        control.durable(self.state_file, {'action': action, 'revision': pointer['revision'], 'ownerReference': owner,
                                          'providers': result['providers'], 'changed': result['changed'],
                                          'atUnix': int(time.time())})

    def plan(self):
        pointer, config, source = self.admitted()
        providers = owner_credentials(self.credentials)
        try:
            self.actor(config, source, providers, '--check')
            changes = []
        except trust.Refused:
            changes = ['differs; --apply reconciles it']
        return {'mode': 'plan', 'revision': pointer['revision'],
                'providers': {a: 'enabled' if providers[a] else 'disabled' for a in PROVIDERS}, 'changes': changes}

    def apply(self, owner):
        pointer, config, source = self.admitted()
        result = self.actor(config, source, owner_credentials(self.credentials))
        self.actor(config, source, owner_credentials(self.credentials), '--check')
        self.record(pointer, result, owner, 'apply')
        return {'mode': 'applied', **result}

    def check(self):
        _, config, source = self.admitted()
        return {'mode': 'check', **self.actor(config, source, owner_credentials(self.credentials), '--check')}

    def disable(self, owner):
        pointer, config, source = self.admitted()
        result = self.actor(config, source, {}, '--disable')
        self.record(pointer, result, owner, 'disable')
        return {'mode': 'disabled', **result}


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    action = parser.add_mutually_exclusive_group(required=True)
    for name in ('plan', 'apply', 'check', 'disable'):
        action.add_argument('--' + name, action='store_true')
    parser.add_argument('--owner-reference')
    args = parser.parse_args()
    require(os.geteuid() == 0, 'Run as root on the production host.')
    os.umask(0o077)
    if args.apply or args.disable:
        require(args.owner_reference and len(args.owner_reference) <= 128, '--owner-reference is required.')
    tool = SocialSignIn()
    if args.plan:
        value = tool.plan()
    elif args.apply:
        value = tool.apply(args.owner_reference)
    elif args.check:
        value = tool.check()
    else:
        value = tool.disable(args.owner_reference)
    print(json.dumps(value, sort_keys=True))


if __name__ == '__main__':
    try:
        main()
    except trust.Refused as error:
        print(json.dumps({'mode': 'refused', 'reason': str(error)}), file=sys.stderr)
        raise SystemExit(1)
