#!/usr/bin/env python3
"""Offline proof of the social sign-in actor and its production tool (#548, ADR-0045).

No Docker or network: the reviewed provider and flow shapes, the linking invariants, credential
validation, disabling without deletion, and the host tool's admission, owner-file and stdin-only
credential contracts. scripts/validate-social-sign-in.sh proves the journeys on real Keycloak.
"""
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
sys.dont_write_bytecode = True

import production_social_sign_in as tool  # noqa: E402

loader = importlib.machinery.SourceFileLoader('social_actor', str(ROOT / 'deploy/private-dev/bin/configure-social-sign-in'))
spec = importlib.util.spec_from_loader(loader.name, loader)
actor = importlib.util.module_from_spec(spec)
loader.exec_module(actor)

GOOGLE = {'clientId': 'client.apps.googleusercontent.com', 'clientSecret': 'not-a-real-secret'}


class ReviewedShape(unittest.TestCase):
    def test_no_step_links_by_email_or_automatically(self):
        steps = {name for _, name, _, _ in actor.FLOW_SHAPE}
        for forbidden in ('idp-email-verification', 'idp-auto-link', 'idp-confirm-override-link'):
            self.assertNotIn(forbidden, steps)
        existing = [s for s in actor.FLOW_SHAPE if s[0] == 2]
        self.assertEqual(existing, [(2, 'idp-confirm-link', 'REQUIRED', None),
                                    (2, 'idp-username-password-form', 'REQUIRED', None)])

    def test_new_accounts_are_created_only_for_an_unused_email_and_then_accept_the_terms(self):
        self.assertIn((1, 'idp-create-user-if-unique', 'ALTERNATIVE', None), actor.FLOW_SHAPE)
        self.assertEqual(actor.FLOW_SHAPE[-1], (0, 'gameometro-brokered-terms', 'REQUIRED', None))
        source = (ROOT / 'docker/keycloak/brokered-terms/src/com/videogameplatform/keycloak/BrokeredTermsAuthenticatorFactory.java').read_text()
        self.assertIn('"gameometro-brokered-terms"', source)
        self.assertIn('"BROKER_REGISTERED_NEW_USER"', source)
        self.assertIn('RequiredAction.TERMS_AND_CONDITIONS', source)

    def test_owner_decision_on_email_trust_and_minimum_scopes(self):
        google = actor.desired_provider('google', GOOGLE)
        microsoft = actor.desired_provider('microsoft', GOOGLE)
        self.assertIs(google['trustEmail'], True)
        self.assertIs(microsoft['trustEmail'], False)
        self.assertEqual(google['config']['defaultScope'], 'openid email')
        self.assertEqual(microsoft['config']['tenantId'], 'consumers')
        for provider in (google, microsoft):
            self.assertEqual(provider['firstBrokerLoginFlowAlias'], actor.FLOW)
            self.assertIs(provider['storeToken'], False)
            self.assertEqual(provider['config']['syncMode'], 'IMPORT')

    def test_the_edge_routes_exactly_the_two_reviewed_broker_aliases(self):
        caddyfile = (ROOT / 'deploy/public-production/caddy/Caddyfile').read_text()
        identity = next(line for line in caddyfile.splitlines() if line.strip().startswith('@identity path'))
        brokers = sorted(part for part in identity.split() if '/broker/' in part)
        self.assertEqual(brokers, ['/realms/gameometro/broker/google/*', '/realms/gameometro/broker/microsoft/*'])
        self.assertIn('path /realms/gameometro/login-actions/authenticate /realms/gameometro/login-actions/first-broker-login',
                      caddyfile)


class Credentials(unittest.TestCase):
    def test_validation(self):
        self.assertEqual(actor.validate_credentials({'google': GOOGLE}), {'google': GOOGLE, 'microsoft': None})
        for bad in ({'github': GOOGLE}, {'google': {'clientId': 'x'}}, {'google': {**GOOGLE, 'clientSecret': ' padded'}},
                    {'google': {**GOOGLE, 'clientId': ''}}, {'google': {**GOOGLE, 'clientSecret': 'a\nb'}}, []):
            with self.subTest(bad=bad), self.assertRaises(actor.Refused):
                actor.validate_credentials(bad)

    def test_drift_ignores_the_masked_secret_and_a_disabled_absent_provider(self):
        desired = actor.desired_provider('google', GOOGLE)
        stored = json.loads(json.dumps(desired))
        stored['config']['clientSecret'] = '**********'
        self.assertEqual(actor.SocialSignIn.provider_drift(stored, desired), [])
        self.assertEqual(actor.SocialSignIn.provider_drift(None, actor.desired_provider('google', None)), [])
        self.assertEqual(actor.SocialSignIn.provider_drift(None, desired), ['absent'])
        stored['config']['clientId'] = 'another'
        self.assertEqual(actor.SocialSignIn.provider_drift(stored, desired), ['config.clientId'])


class FakeRealm:
    def __init__(self, providers):
        self.providers = providers
        self.calls = []

    def get(self, path):
        if path == '/identity-provider/instances':
            return list(self.providers.values())
        raise AssertionError(path)

    def send(self, method, path, body):
        self.calls.append((method, path, body))


class Disabling(unittest.TestCase):
    def test_a_provider_without_credentials_is_disabled_never_deleted(self):
        current = {**actor.desired_provider('google', GOOGLE), 'internalId': 'x'}
        realm = FakeRealm({'google': current})
        self.assertTrue(actor.SocialSignIn(realm).ensure_provider('google', None))
        [(method, path, body)] = realm.calls
        self.assertEqual((method, path, body['enabled']), ('PUT', '/identity-provider/instances/google', False))
        self.assertFalse(actor.SocialSignIn(FakeRealm({})).ensure_provider('microsoft', None))


class Runner:
    def __init__(self, fail_check=False):
        self.calls = []
        self.fail_check = fail_check

    def __call__(self, args, *, data=None, timeout=120, **_):
        self.calls.append((args, data))
        if '--check' in args and self.fail_check:
            raise tool.trust.Refused('Host prerequisite or operation failed; diagnostics withheld.')
        providers = json.loads(data)['providers']
        return json.dumps({'providers': {a: 'enabled' if providers.get(a) else 'disabled' for a in tool.PROVIDERS},
                           'changed': [], 'written': []})


class Gate:
    def __init__(self, state=b'open', fence=b'none'):
        self.state, self.fence = state, fence

    def gate(self, action):
        return self.state if action == 'check' else self.fence


class HostTool(unittest.TestCase):
    def setUp(self):
        self.directory = Path(tempfile.mkdtemp())
        self.admin = self.directory / 'admin'
        (self.admin / 'upgrades').mkdir(parents=True)
        bundle = self.directory / 'bundle'
        (bundle / 'candidate' / Path(tool.ACTOR).parent).mkdir(parents=True)
        (bundle / 'candidate' / tool.ACTOR).write_text('#!/usr/bin/env python3\n')
        password = self.directory / 'admin-password'
        password.write_text('admin-password-value\n')
        config = {'networks': {'identity-data': {'name': 'gameometro-production_identity-data'}},
                  'secrets': {'keycloak_admin_password': {'file': str(password)}},
                  'services': {'keycloak': {'environment': {'KC_BOOTSTRAP_ADMIN_USERNAME': 'admin'}}}}
        path = bundle / 'compose.target.json'
        path.write_text(json.dumps(config))
        (self.admin / 'upgrades/ACTIVE.json').write_text(json.dumps(
            {'revision': 'a' * 40, 'configurationFile': str(path), 'configurationSha256': tool.install.sha(path)}))
        self.credentials = self.directory / 'social-sign-in'
        self.credentials.mkdir(mode=0o700)
        os.chmod(self.credentials, 0o700)
        for name, value in (('google-client-id', GOOGLE['clientId']), ('google-client-secret', GOOGLE['clientSecret'])):
            (self.credentials / name).write_text(value + '\n')
            os.chmod(self.credentials / name, 0o600)
        self.uid = mock.patch.object(tool, 'OWNER_UID', os.getuid())
        self.uid.start()
        self.addCleanup(self.uid.stop)
        self.gate = Gate()
        recovery = mock.patch.object(tool.acceptance, 'load_recovery', lambda *_: self.gate)
        recovery.start()
        self.addCleanup(recovery.stop)

    def tool(self, runner):
        return tool.SocialSignIn(runner=runner, admin=self.admin, credentials=self.credentials)

    def test_apply_sends_credentials_only_on_stdin_and_records_no_secret(self):
        runner = Runner()
        result = self.tool(runner).apply('owner-548')
        self.assertEqual(result['providers'], {'google': 'enabled', 'microsoft': 'disabled'})
        for args, data in runner.calls:
            joined = ' '.join(args)
            self.assertNotIn(GOOGLE['clientSecret'], joined)
            self.assertNotIn('admin-password-value', joined)
            self.assertIn('--network gameometro-production_identity-data', joined)
            self.assertEqual(json.loads(data)['adminPassword'], 'admin-password-value')
        self.assertEqual(runner.calls[-1][0][-1], '--check')
        record = (self.admin / 'social-sign-in/SOCIAL-SIGN-IN.json').read_text()
        self.assertNotIn(GOOGLE['clientSecret'], record)
        self.assertIn('owner-548', record)

    def test_plan_is_read_only(self):
        runner = Runner(fail_check=True)
        result = self.tool(runner).plan()
        self.assertEqual(result['changes'], ['differs; --apply reconciles it'])
        self.assertTrue(all('--check' in args for args, _ in runner.calls))
        self.assertFalse((self.admin / 'social-sign-in').exists())

    def test_disable_sends_no_credentials(self):
        runner = Runner()
        self.tool(runner).disable('owner-548')
        [(args, data)] = runner.calls
        self.assertIn('--disable', args)
        self.assertEqual(json.loads(data)['providers'], {})

    def test_admission_refusals(self):
        for gate in (Gate(state=b'closed'), Gate(fence=b'run-uuid')):
            self.gate = gate
            with self.subTest(gate=vars(gate)), self.assertRaises(tool.trust.Refused):
                self.tool(Runner()).apply('owner-548')
        self.gate = Gate()
        journal = self.admin / 'upgrades/run/journal.json'
        journal.parent.mkdir()
        journal.write_text(json.dumps({'status': 'failed'}))
        with self.assertRaises(tool.trust.Refused):
            self.tool(Runner()).apply('owner-548')

    def test_owner_files(self):
        os.chmod(self.credentials / 'google-client-secret', 0o644)
        with self.assertRaises(tool.trust.Refused):
            tool.owner_credentials(self.credentials)
        os.chmod(self.credentials / 'google-client-secret', 0o600)
        (self.credentials / 'google-client-secret').unlink()
        with self.assertRaises(tool.trust.Refused):
            tool.owner_credentials(self.credentials)
        (self.credentials / 'google-client-id').unlink()
        self.assertEqual(tool.owner_credentials(self.credentials), {'google': None, 'microsoft': None})
        (self.credentials / 'github-client-id').write_text('x')
        with self.assertRaises(tool.trust.Refused):
            tool.owner_credentials(self.credentials)
        self.assertEqual(tool.owner_credentials(self.directory / 'absent'), {'google': None, 'microsoft': None})


if __name__ == '__main__':
    unittest.main()
