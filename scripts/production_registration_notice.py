"""Reviewed registration localization migrations under the upgrade lease."""
import argparse
import copy
import hashlib
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
from urllib.parse import quote
from urllib.request import Request

ROOT = Path(__file__).resolve().parents[1]
LEGACY = '343d55e8eec8a53784685006f4e898aebe01214d34fbbdceec7b644a4e6153d3'
APPROVED = '21ebcec77a5715ace91dca73ab8614242f0ba9cbf5546c45fb254d40ace8a2da'
APPROVED_LIVE = 'c87cf17a8447347e4e892ca0d37009bc40bc6ddab8231684654ff05f6833c047'
ELIGIBILITY = 'ff44d22d9eff88e4b150eb900d5673efc30a669a104ddd7ff2d52d07adde53b0'
HOST_TEMPLATE = 'https://${APPLICATION_PUBLIC_HOST}'
PUBLIC_ORIGIN = 'https://gameometro.com'
LABEL_HASHES = {
    'acceptTerms': ('6baee47a426eefaa78ac3fe77cf0a4638cb4b44b18ffec5ed1712390fb9fb495',
                    '824aabd5d00ac045734a1d748a5393f806662948f7680183d7cf1c2e564b24e6'),
    'termsAcceptanceRequired': ('c5b4ed03270cd03b14f8513507c8024a78242b843e8bec2b6e33acc19febd86e',
                                '604ed2cf1159e7646b6c16c2e67bfd8d5cc12ad2f8777cee9fa3668bfb6bfd72'),
}
IMPORT = 'deploy/public-production/keycloak/import/gameometro-realm.json'
MAX_LOCALES, MAX_TEXTS, MAX_BYTES = 32, 2048, 1024 * 1024
SESSION_SETTINGS = ('rememberMe', 'ssoSessionIdleTimeout', 'ssoSessionMaxLifespan',
                    'ssoSessionIdleTimeoutRememberMe', 'ssoSessionMaxLifespanRememberMe')


class BoundedRealm:
    """Bound every metadata response before decoding; reuse the reviewed PUT actor."""

    def __init__(self, realm):
        self.realm = realm

    def get(self, path):
        admin = self.realm.admin
        request = Request(admin.origin + self.realm.admin_path + path,
                          headers={'Authorization': 'Bearer ' + admin.access_token, 'Accept': 'application/json'})
        with admin.opener.open(request, timeout=20) as response:
            require(response.status == 200, 'Unexpected realm metadata response.')
            payload = response.read(MAX_BYTES + 1)
        require(len(payload) <= MAX_BYTES, 'Realm metadata response exceeds its bound.')
        return json.loads(payload)

    def representation(self):
        state = self.get('')
        require(isinstance(state, dict) and state.get('realm') == 'gameometro', 'Invalid realm representation.')
        return state

    def set_localization(self, key, value):
        self.realm.set_localization(key, value)


def require(value, reason):
    if not value:
        raise ValueError(reason)


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def notice(realm):
    return realm.get('localizationTexts', {}).get('es', {}).get('termsText', '')


def target_id(realm):
    return digest(notice(realm))


def target_values(realm):
    target = target_id(realm)
    require(target in (APPROVED, ELIGIBILITY), 'The target notice is outside the reviewed legal publications.')
    values = {'termsText': notice(realm)}
    if target == ELIGIBILITY:
        texts = realm['localizationTexts']['es']
        for key, (_, approved) in LABEL_HASHES.items():
            value = texts.get(key, '')
            require(digest(value) == approved, 'Unreviewed registration acceptance copy.')
            values[key] = value
    return values


def expected_localizations(texts, target, live=False):
    values = target_values(target)
    eligibility = target_id(target) == ELIGIBILITY
    expected = copy.deepcopy(texts)
    require(isinstance(expected.get('es'), dict), 'Missing Spanish registration copy.')
    for key, value in values.items():
        current = expected['es'].get(key, '')
        if key == 'termsText':
            previous = {LEGACY}
            if eligibility:
                previous.add(APPROVED)
                if live:
                    previous.add(APPROVED_LIVE)
            if live:
                value = value.replace(HOST_TEMPLATE, PUBLIC_ORIGIN)
        else:
            previous = {LABEL_HASHES[key][0]}
        require(current == value or digest(current) in previous, 'Unexpected existing registration copy.')
        expected['es'][key] = value
    return expected


def reviewed_delta(previous, target):
    """Remove exactly the approved registration copy changes before the realm comparison."""
    if target_id(target) not in (APPROVED, ELIGIBILITY) and notice(previous) == notice(target):
        return previous
    before = copy.deepcopy(previous)
    before['localizationTexts'] = expected_localizations(previous.get('localizationTexts', {}), target)
    return before


def localizations(realm):
    locales = realm.get('/localization')
    require(isinstance(locales, list) and len(locales) <= MAX_LOCALES
            and all(isinstance(x, str) and len(x) <= 64 for x in locales)
            and len(set(locales)) == len(locales), 'Unbounded or invalid locale inventory.')
    result = {}
    for locale in locales:
        texts = realm.get('/localization/' + quote(locale, safe='') + '?useRealmDefaultLocaleFallback=false')
        require(isinstance(texts, dict) and len(texts) <= MAX_TEXTS
                and all(isinstance(k, str) and isinstance(v, str) for k, v in texts.items()),
                'Unbounded or invalid localization map.')
        result[locale] = texts
        require(len(json.dumps(result).encode()) <= MAX_BYTES, 'Localization snapshot exceeds its bound.')
    return result


def realm_digest(realm):
    state = realm.representation()
    state.pop('localizationTexts', None)  # Independently compared in full, across all locales.
    for key in (*SESSION_SETTINGS, 'passwordPolicy'):  # The session and password actor independently verifies these.
        state.pop(key, None)
    return digest(json.dumps(state, sort_keys=True))


def align(realm, target, snapshot, check=False):
    target_hash = target_id(target)
    texts = localizations(realm)
    expected = expected_localizations(texts, target, live=True)
    fingerprint = realm_digest(realm)
    if snapshot.exists():
        require(snapshot.is_file() and not snapshot.is_symlink() and snapshot.stat().st_size <= 2 * MAX_BYTES + 4096,
                'Invalid notice snapshot.')
        saved = json.loads(snapshot.read_text())
        require(saved.get('targetSha256') == target_hash and saved.get('expected') == expected
                and isinstance(saved.get('before'), dict)
                and expected_localizations(saved['before'], target, live=True) == expected
                and saved.get('realmSha256') == fingerprint, 'Notice snapshot or preserved realm differs.')
    elif check:
        require(texts == expected, 'Registration notice needs alignment.')
        return
    else:
        # Persist before PUT; retries preserve the original multilingual snapshot.
        with tempfile.NamedTemporaryFile(mode='w', dir=snapshot.parent, suffix='.pending', delete=False) as stream:
            json.dump({'targetSha256': target_hash, 'before': texts, 'expected': expected, 'realmSha256': fingerprint}, stream)
            stream.flush()
            os.fchmod(stream.fileno(), 0o600)
            os.fsync(stream.fileno())
            temporary = stream.name
        os.replace(temporary, snapshot)
        directory = os.open(snapshot.parent, os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    if check:
        require(texts == expected, 'Registration notice needs alignment.')
        return
    for key in target_values(target):
        if texts['es'].get(key) != expected['es'][key]:
            realm.set_localization(key, expected['es'][key])
    require(localizations(realm) == expected and realm_digest(realm) == fingerprint,
            'State changed outside the reviewed registration notice.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--origin', required=True, choices=['http://keycloak:8080'])
    parser.add_argument('--admin-username', required=True)
    parser.add_argument('--admin-password-file', type=Path, required=True)
    parser.add_argument('--snapshot-dir', type=Path, default=Path('/snapshots'))
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    loader = importlib.machinery.SourceFileLoader('notice_identity', str(ROOT / 'deploy/private-dev/bin/configure-keycloak-identity'))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    identity = importlib.util.module_from_spec(spec)
    loader.exec_module(identity)
    admin = identity.provisioning.KeycloakAdmin(args.origin)
    admin.authenticate(args.admin_username, args.admin_password_file.read_text().rstrip('\r\n'))
    align(BoundedRealm(identity.Realm(admin, 'gameometro')), json.loads((ROOT / IMPORT).read_text()),
          args.snapshot_dir / 'registration-notice.json', args.check)


if __name__ == '__main__':
    sys.dont_write_bytecode = True
    os.umask(0o077)
    try:
        main()
    except Exception:
        print('Registration notice alignment refused or failed; raw diagnostics withheld.', file=sys.stderr)
        raise SystemExit(1)
