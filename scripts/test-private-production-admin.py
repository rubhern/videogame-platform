#!/usr/bin/env python3
"""#464 focused contract tests. No host, Serve or tailnet changes."""
import copy
import importlib.machinery
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import private_production_admin as admin
import private_production_upgrade as upgrade
import private_dev_promotion as trust

ROOT = Path(__file__).resolve().parents[1]
loader = importlib.machinery.SourceFileLoader('admin_alignment', str(ROOT / 'deploy/public-production/bin/configure-keycloak-admin'))
spec = importlib.util.spec_from_loader(loader.name, loader)
alignment = importlib.util.module_from_spec(spec)
loader.exec_module(alignment)


def serve():
    return {'TCP': {'443': {'TCPForward': '127.0.0.1:18444'}}, 'Web': {}, 'AllowFunnel': {}}


def expected_routes(value):
    value = copy.deepcopy(value)
    for port, backend in admin.ENDPOINTS.values():
        value['TCP'][str(port)] = {'HTTPS': True}
        value['Web'][f'{admin.HOST}:{port}'] = {'Handlers': {'/': {'Proxy': f'http://127.0.0.1:{backend}'}}}
    return value


class Ingress(unittest.TestCase):
    def test_conflicts_and_funnel_refused_before_commands(self):
        mutations = [lambda v: v.update(Foreground={'pid': {}}),
                     lambda v: v.update(AllowFunnel={'other:443': True}),
                     lambda v: v['TCP'].update({'8443': {'TCPForward': 'elsewhere:443'}}),
                     lambda v: v['Web'].update({f'{admin.HOST}:8443': {'Handlers': {'/': {'Proxy': 'http://127.0.0.1:9999'}}}}),
                     lambda v: v['Web'].update({'elsewhere:10000': {}})]
        for mutation in mutations:
            value = serve()
            mutation(value)
            with self.subTest(value=value), patch.object(admin, 'status', return_value=value), patch.object(admin, 'command') as command:
                with self.assertRaises(ValueError):
                    admin.routes()
                command.assert_not_called()

    def test_install_reapply_and_remove_preserve_tcp443(self):
        original = serve()
        enabled = expected_routes(original)
        for before, after, remove in [(original, enabled, False), (enabled, enabled, False), (enabled, original, True), (original, original, True)]:
            with self.subTest(remove=remove), patch.object(admin, 'status', side_effect=[before, after]), patch.object(admin, 'command') as command:
                admin.routes(remove)
                for call in command.call_args_list:
                    args = call.args
                    self.assertNotIn('reset', args)
                    self.assertNotIn('funnel', args)
                    self.assertNotIn('--https=443', args)
                    self.assertIn('--https=8443' if '--https=8443' in args else '--https=10000', args)

    def test_postcondition_and_unrelated_change_are_detected(self):
        for after in [serve(), {**expected_routes(serve()), 'TCP': {'443': {'TCPForward': 'other'}}}]:
            with patch.object(admin, 'status', side_effect=[serve(), after]), patch.object(admin, 'command'):
                with self.assertRaises(ValueError):
                    admin.routes()

    def test_upgrade_exceptions_are_exact_and_all_other_identity_settings_stay_protected(self):
        cases = [('keycloak', 'KC_HOSTNAME_ADMIN', None, admin.KEYCLOAK),
                 ('grafana', 'GF_SERVER_ROOT_URL', 'http://localhost:3000/', admin.GRAFANA),
                 ('grafana', 'GF_SECURITY_COOKIE_SECURE', None, 'true')]
        for name, key, before, after in cases:
            current = {'environment': {key: before} if before is not None else {}}
            target = {'environment': {key: after}}
            upgrade.validate_delta(name, current, target, ['environment'], Path('/target'))
            for bad in ['http://public.example', '', None]:
                if before == bad:
                    continue
                with self.subTest(name=name, bad=bad), self.assertRaises(trust.Refused):
                    upgrade.validate_delta(name, current, {'environment': {key: bad}}, ['environment'], Path('/target'))
        for key in ('KC_HOSTNAME', 'KC_PROXY_HEADERS', 'OIDC_ISSUER_URI', 'KC_DB_PASSWORD_FILE'):
            with self.subTest(key=key), self.assertRaises(trust.Refused):
                upgrade.validate_delta('keycloak', {'environment': {key: 'original'}}, {'environment': {key: admin.KEYCLOAK}}, ['environment'], Path('/target'))
        self.assertFalse(admin.allowed_setting('keycloak', 'KC_HOSTNAME_ADMIN', 'https://unexpected.ts.net', admin.KEYCLOAK))

    def test_private_adapter_does_not_change_issuer_ports_volumes_or_base(self):
        import prepare_private_production as prepare
        base = {'services': {name: {'environment': {'KC_HOSTNAME': 'https://auth.gameometro.com'} if name == 'keycloak' else {}, 'volumes': ['persisted']} for name in admin.ENDPOINTS}}
        before = copy.deepcopy(base)
        result = admin.adapt(copy.deepcopy(base))
        self.assertEqual(before, base)
        self.assertEqual(result['services']['keycloak']['environment']['KC_HOSTNAME'], 'https://auth.gameometro.com')
        for name in admin.ENDPOINTS:
            self.assertEqual(result['services'][name]['volumes'], ['persisted'])
            self.assertNotIn('ports', result['services'][name])
        public = (ROOT / 'deploy/public-production/caddy/Caddyfile').read_text()
        self.assertNotIn(admin.HOST, public)
        self.assertTrue(prepare.private_caddy(public).endswith(admin.caddy()))

    def test_units_have_only_loopback_listeners_and_static_edge_targets(self):
        units = admin.unit_files(Path('/srv/operations/scripts/private_production_admin.py'))
        for name, (_, backend) in admin.ENDPOINTS.items():
            unit = units[f'gameometro-admin-{name}.service']
            self.assertIn(f'TCP4-LISTEN:{backend},bind=127.0.0.1', unit)
            self.assertIn(f'TCP4:{admin.EDGE}:{backend}', unit)
            self.assertIn(',bind=10.203.1.1,connect-timeout=5', unit)
            for setting in ['DynamicUser=yes', 'NoNewPrivileges=yes', 'MemoryMax=32M', 'TasksMax=32', 'max-children=16', 'Restart=always']:
                self.assertIn(setting, unit)
        self.assertNotIn('18444', ''.join(units.values()))


class Master(unittest.TestCase):
    class API:
        def __init__(self, frontend=None):
            self.realm = {'realm': 'master', 'enabled': True, 'attributes': {'other': 'preserved'}, 'clients': ['untouched']}
            if frontend is not None:
                self.realm['attributes']['frontendUrl'] = frontend
            self.puts = []

        def _request(self, method, path, body=None, expected=None):
            assert path == '/admin/realms/master'
            if method == 'PUT':
                self.puts.append(body)
                self.realm.update(copy.deepcopy(body))
            return json.dumps(self.realm).encode(), None

    def test_alignment_changes_only_attribute_and_retries_preserve_snapshot(self):
        with tempfile.TemporaryDirectory() as directory:
            snapshot = Path(directory) / 'master.json'
            api = self.API()
            before = copy.deepcopy(api.realm)
            alignment.align(api, admin.KEYCLOAK, snapshot)
            saved = snapshot.read_bytes()
            alignment.align(api, admin.KEYCLOAK, snapshot)
            alignment.align(api, admin.KEYCLOAK, snapshot, True)
            self.assertEqual(snapshot.read_bytes(), saved)
            self.assertIsNone(json.loads(saved)['previous'])
            self.assertEqual(api.puts, [{**before, 'attributes': {'other': 'preserved', 'frontendUrl': admin.KEYCLOAK}}])
            del api.realm['attributes']['frontendUrl']
            self.assertEqual(api.realm, before)

    def test_resume_and_check_detect_other_master_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            snapshot = Path(directory) / 'master.json'
            api = self.API()
            alignment.align(api, admin.KEYCLOAK, snapshot)
            api.realm['enabled'] = False
            for check in (True, False):
                with self.subTest(check=check), self.assertRaises(RuntimeError):
                    alignment.align(api, admin.KEYCLOAK, snapshot, check)
            self.assertEqual(len(api.puts), 1)

    def test_only_identical_typed_aliases_may_materialize(self):
        before = {'attributes': {'frontendUrl': admin.KEYCLOAK}, 'oauth2DeviceCodeLifespan': 600, 'scimApiEnabled': False}
        after = copy.deepcopy(before)
        after['attributes'].update(oauth2DeviceCodeLifespan='600', scimApiEnabled='false')
        self.assertEqual(alignment.effective(before), alignment.effective(after))
        after['attributes']['oauth2DeviceCodeLifespan'] = '900'
        self.assertNotEqual(alignment.effective(before), alignment.effective(after))

    def test_foreign_value_and_failed_check_never_write(self):
        for value in ['https://elsewhere', None]:
            with tempfile.TemporaryDirectory() as directory:
                api = self.API(value)
                with self.assertRaises(RuntimeError):
                    alignment.align(api, admin.KEYCLOAK, Path(directory) / 'master.json', check=value is None)
                self.assertFalse(api.puts)


if __name__ == '__main__':
    unittest.main()
