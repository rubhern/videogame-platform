#!/usr/bin/env python3
"""#455 in-place upgrade contract: fake host and Docker, no runtime mutation."""
import ast
import copy
from datetime import datetime, timedelta, timezone
import contextlib
import io
import sys
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import install_private_production as install
import prepare_private_production as prepare
import private_dev_promotion as trust
import private_production_acceptance as acceptance
import private_production_control as control
import private_production_upgrade as upgrade

ROOT = Path(__file__).resolve().parents[1]
TARGET = 'a' * 40
NEWER = 'b' * 40
RUN = '11111111-1111-4111-8111-111111111111'
CONFIGURATION = 'c' * 64
BACKUP = 'd' * 32
BUNDLE = upgrade.BUNDLES / f'{TARGET[:12]}-private'
OLD_BUNDLE = upgrade.BUNDLES / '9ffc05a-private'
IMAGES = {'application': 'ghcr.io/r/app@sha256:' + '2' * 64, 'keycloak': 'ghcr.io/r/kc@sha256:' + '3' * 64,
          'caddy': 'ghcr.io/r/caddy@sha256:' + '4' * 64, 'catalogue-localizer': 'ghcr.io/r/loc@sha256:' + '5' * 64}
OLD = {'postgres': 'postgres:18@sha256:' + '1' * 64, 'application': 'ghcr.io/r/app@sha256:' + '6' * 64, 'keycloak': 'ghcr.io/r/kc@sha256:' + '7' * 64}
FILES = [{'version': f'2026010{n}.120000', 'script': f'V2026010{n}_120000__m{n}.sql', 'checksum': n} for n in range(1, 6)]
NOW = datetime(2026, 11, 7, 12, tzinfo=timezone.utc)
FIRST_TARGET = '46e7252e9980c1c7495e9dcae16ce289bf3b1607'


def bind(source, target):
    return {'type': 'bind', 'source': str(source), 'target': target, 'read_only': True, 'bind': {'create_host_path': False}}


def config(bundle, application=OLD['application'], keycloak=OLD['keycloak'], caddy='ghcr.io/r/caddy@sha256:' + '8' * 64,
           localizer='ghcr.io/r/loc@sha256:' + '9' * 64, scheduling='true'):
    def service(image, **fields):
        return {'image': image, 'restart': acceptance.POLICY, 'mem_limit': '100', 'cpus': 1.0, 'pids_limit': 64,
                'labels': {control.RUN_LABEL: RUN, control.CONFIG_LABEL: CONFIGURATION},
                'volumes': [], 'environment': {}, **fields}
    candidate = bundle / 'candidate'
    services = {name: service('observability@sha256:' + 'e' * 64) for name in ('blackbox', 'node-exporter', 'prometheus', 'telemetry')}
    services['postgres'] = service(OLD['postgres'], volumes=[
        bind(candidate / 'deploy/public-production/bin/run-postgres', '/opt/run-postgres'),
        {'type': 'volume', 'source': 'postgres-data', 'target': '/var/lib/postgresql/18/docker'}])
    services['keycloak'] = service(keycloak, environment={'KC_HOSTNAME': 'https://auth.gameometro.com', 'KC_DB_PASSWORD_FILE': '/run/secrets/x'},
                                   volumes=[bind(candidate / 'deploy/public-production/bin/run-keycloak', '/opt/run-keycloak')])
    services['caddy'] = service(caddy, volumes=[bind(bundle / 'Caddyfile.private', '/etc/caddy/Caddyfile'),
                                                {'type': 'volume', 'source': 'caddy-data', 'target': '/data'}])
    services['application'] = service(application, environment={'CATALOGUE_SYNC_SCHEDULING_ENABLED': scheduling, 'TELEMETRY_SERVICE_VERSION': '1',
                                                                'OIDC_ISSUER_URI': 'https://auth.gameometro.com/realms/gameometro'},
                                      volumes=[bind(candidate / 'deploy/public-production/bin/run-application', '/opt/run-application')])
    services['migration'] = {**service(application, volumes=[bind(candidate / 'deploy/public-production/bin/run-migrations', '/opt/run-migrations'),
                                                             bind(candidate / 'deploy/private-dev/bin/run-migrations', upgrade.MIGRATION_ENTRYPOINT)]), 'restart': 'no'}
    services['catalogue-localizer'] = service(localizer, mem_limit=acceptance.LOCALIZER_MEMORY)
    services['grafana'] = service('grafana@sha256:' + 'f' * 64, environment={
        'GRAFANA_TELEGRAM_ENABLED': 'false', 'GF_PLUGINS_DISABLE_PLUGINS': acceptance.DISABLED_PLUGINS},
        volumes=[bind(candidate / 'deploy/private-dev/grafana/dashboards', '/etc/grafana/canonical-dashboards')])
    return {'name': prepare.PROJECT, 'services': services, 'secrets': {'keycloak_admin_password': {'file': '/s/admin'}},
            'networks': {'identity-data': {'name': 'n'}}, 'volumes': {'postgres-data': {'name': 'v'}}}


def rendered(bundle=BUNDLE):
    return config(bundle, IMAGES['application'], IMAGES['keycloak'], IMAGES['caddy'], IMAGES['catalogue-localizer'])


class ActiveLineage(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name)
        self.admin = self.base / 'admin'
        (self.admin / upgrade.UPGRADES).mkdir(parents=True)
        self.bundles = self.base / 'installations'
        self.bundle = self.bundles / f'{TARGET[:12]}-private'
        self.source = self.bundle / 'candidate'
        actor = self.source / 'deploy/public-production/bin/recovery'
        actor.parent.mkdir(parents=True)
        actor.write_text('IDENTITY = "installed-revision"\n')
        self.actor = actor
        self.path = self.admin / 'ingress/compose.public.json'
        self.path.parent.mkdir()
        self.path.write_text(json.dumps({'services': {'caddy': {'ports': upgrade.ingress.public_ports('62.83.73.32')}}}))
        self.pointer = {'revision': TARGET, 'configurationFile': str(self.path),
                        'configurationSha256': install.sha(self.path)}
        self.pointer_file = self.admin / upgrade.UPGRADES / upgrade.ACTIVE
        self.write_pointer()
        self.value = upgrade.Upgrade(NEWER, admin=self.admin, bundles=self.bundles)

    def write_pointer(self):
        self.pointer_file.write_text(json.dumps(self.pointer))

    def test_public_compose_keeps_recovery_in_the_installed_revision_bundle(self):
        active = self.value.active_state(None)
        self.assertEqual(active['file'], self.path)
        self.assertEqual(active['source'], self.source)
        self.assertEqual(active['bundle'], self.bundle)
        self.assertEqual(acceptance.recovery_module(active['source']).IDENTITY, 'installed-revision')
        self.assertEqual(active['config']['services']['caddy']['ports'], upgrade.ingress.public_ports('62.83.73.32'))

    def test_private_compose_still_uses_the_same_installed_bundle(self):
        private = self.bundle / upgrade.TARGET_CONFIG
        private.write_text(self.path.read_text())
        self.pointer['configurationFile'] = str(private)
        self.write_pointer()
        active = self.value.active_state(None)
        self.assertEqual(active['file'], private)
        self.assertEqual(active['source'], self.source)

    def test_changed_configuration_and_invalid_revision_refuse(self):
        with self.subTest('changed configuration'):
            self.path.write_text('{}')
            with self.assertRaises(trust.Refused):
                self.value.active_state(None)
        self.pointer['configurationSha256'] = install.sha(self.path)
        for invalid in ('main', '../candidate', TARGET + '/x'):
            with self.subTest(revision=invalid):
                self.pointer['revision'] = invalid
                self.write_pointer()
                with self.assertRaises(trust.Refused):
                    self.value.active_state(None)

    def test_missing_installed_actor_never_falls_back_to_the_compose_directory(self):
        self.actor.unlink()
        decoy = self.path.parent / 'candidate/deploy/public-production/bin/recovery'
        decoy.parent.mkdir(parents=True)
        decoy.write_text('IDENTITY = "unrelated-compose-directory"\n')
        with self.assertRaises(FileNotFoundError):
            acceptance.recovery_module(self.value.active_state(None)['source'])


class LivePublicExposure(unittest.TestCase):
    def setUp(self):
        self.host = object.__new__(upgrade.Host)
        self.host.run_id, self.host.configuration = 'run', 'configuration'
        self.host.config = {'services': {'caddy': {'image': 'reviewed-image', 'restart': 'unless-stopped',
            'mem_limit': '100', 'cpus': 1, 'pids_limit': 64, 'networks': {'edge': {}},
            'ports': upgrade.ingress.public_ports('62.83.73.32')}},
            'networks': {'edge': {'name': 'edge-network', 'internal': True}}}
        self.bindings = {f'{port}/tcp': [{'HostIp': '62.83.73.32', 'HostPort': str(port)}] for port in (80, 443)}
        self.info = {'State': {'Running': True, 'Health': {'Status': 'healthy'}}, 'Image': 'sha256:reviewed',
            'Config': {'Image': 'reviewed-image', 'Labels': {control.RUN_LABEL: 'run', control.CONFIG_LABEL: 'configuration'}},
            'HostConfig': {'PortBindings': copy.deepcopy(self.bindings), 'RestartPolicy': {'Name': 'unless-stopped'},
                           'Memory': 100, 'NanoCpus': 10**9, 'PidsLimit': 64, 'IpcMode': 'private', 'NetworkMode': 'edge-network'},
            'NetworkSettings': {'Ports': copy.deepcopy(self.bindings), 'Networks': {'edge-network': {'NetworkID': 'network-id'}}}}
        self.network = {'Id': 'network-id', 'EnableIPv6': False, 'EnableIPv4': True, 'Driver': 'bridge',
            'Internal': True, 'Scope': 'local', 'IPAM': {'Driver': 'default', 'Config': [{'Subnet': '10.203.1.0/24', 'Gateway': '10.203.1.1'}]},
            'Options': {'com.docker.network.bridge.gateway_mode_ipv4': 'nat'}}
        self.host.compose = lambda *_, **__: b'container'
        self.calls = []

    def inspect_run(self, args):
        self.calls.append(args)
        if args[:3] == ['docker', 'image', 'inspect']:
            return json.dumps([{'Id': 'sha256:reviewed', 'Config': {}}]).encode()
        if args[:3] == ['docker', 'network', 'inspect']:
            return json.dumps([self.network]).encode()
        self.assertEqual(args[:2], ['docker', 'inspect'])
        return json.dumps([self.info]).encode()

    def test_live_public_edge_passes_the_complete_inherited_runtime_contract(self):
        with patch.object(install, 'run', side_effect=self.inspect_run):
            self.host.running(['caddy'])
        self.assertTrue(any(c[:3] == ['docker', 'network', 'inspect'] for c in self.calls))

    def test_wrong_missing_extra_ipv6_and_duplicate_bindings_refuse(self):
        for field in ('HostConfig', 'NetworkSettings'):
            key = 'PortBindings' if field == 'HostConfig' else 'Ports'
            for mutation in ('ipv4', 'wildcard', 'ipv6', 'port', 'protocol', 'extra', 'missing', 'duplicate'):
                with self.subTest(field=field, mutation=mutation):
                    bindings = copy.deepcopy(self.bindings)
                    if mutation in ('ipv4', 'wildcard', 'ipv6'):
                        bindings['443/tcp'][0]['HostIp'] = {'ipv4': '62.83.73.33', 'wildcard': '0.0.0.0', 'ipv6': '::'}[mutation]
                    elif mutation == 'port': bindings['443/tcp'][0]['HostPort'] = '8443'
                    elif mutation == 'protocol': bindings['443/udp'] = bindings.pop('443/tcp')
                    elif mutation == 'extra': bindings['10000/tcp'] = [{'HostIp': '62.83.73.32', 'HostPort': '10000'}]
                    elif mutation == 'missing': del bindings['80/tcp']
                    else: bindings['443/tcp'].append(copy.deepcopy(bindings['443/tcp'][0]))
                    self.info[field][key] = bindings
                    with patch.object(install, 'run', side_effect=self.inspect_run), self.assertRaises(trust.Refused):
                        self.host.running(['caddy'])
                    self.info[field][key] = copy.deepcopy(self.bindings)

    def test_public_exposure_does_not_hide_other_live_runtime_drift(self):
        for field, key, value in [('Config', 'Image', 'other-image'), ('State', 'Running', False),
                                  ('State', 'Health', {'Status': 'unhealthy'}), ('HostConfig', 'Privileged', True),
                                  ('HostConfig', 'PublishAllPorts', True), ('HostConfig', 'Memory', 101),
                                  ('HostConfig', 'RestartPolicy', {'Name': 'always'})]:
            with self.subTest(field=field, key=key):
                original = copy.deepcopy(self.info[field])
                self.info[field][key] = value
                with patch.object(install, 'run', side_effect=self.inspect_run), self.assertRaises(trust.Refused):
                    self.host.running(['caddy'])
                self.info[field] = original

    def test_probe_selects_system_roots_only_for_the_reviewed_public_edge(self):
        self.assertIsNone(self.host.probe_ca_file())
        self.host.config['services']['application'] = {'environment': {'OIDC_ISSUER_URI': 'https://auth.gameometro.com/realms/gameometro',
            'OIDC_CONTRACT_CA_FILE': '/unreviewed/ca.crt'}}
        self.host.config['networks']['telemetry'] = {'name': 'telemetry-network'}
        self.host.source = Path('/reviewed/candidate')
        with patch.object(install, 'run', return_value=b'') as run:
            self.host.probe('synthetic-probe')
        arguments = run.call_args.args[0]
        self.assertFalse(any('OIDC_CONTRACT_CA_FILE=' in str(a) for a in arguments))
        self.assertNotIn('/unreviewed/ca.crt', arguments)
        self.host.config['services']['caddy']['ports'] = []
        self.assertEqual(self.host.probe_ca_file(), '/run/private-tls/ca.crt')
        private = object.__new__(install.Host)
        self.assertEqual(private.probe_ca_file(), '/run/private-tls/ca.crt')

    def test_authorization_probe_uses_the_selected_trust_roots(self):
        import ssl
        from types import SimpleNamespace
        class Connection:
            def __init__(self, *args, **kwargs):
                pass
            def request(self, method, path):
                self.path = path
            def getresponse(self):
                return SimpleNamespace(status=401 if self.path == '/api/v1/me/ratings' else 200, read=lambda _: b'{}')
            def close(self):
                pass
        for ca in (None, '/run/private-tls/ca.crt'):
            environment = {'OIDC_CONTRACT_CA_FILE': ca} if ca else {}
            with self.subTest(ca=ca), patch.dict(os.environ, environment, clear=True), \
                    patch('http.client.HTTPSConnection', Connection), patch.object(ssl, 'create_default_context') as context:
                exec(upgrade.AUTHORIZATION_PROBE, {})
                context.assert_called_once_with(cafile=ca)

    def test_unreviewed_publication_cannot_select_public_tls_mode(self):
        self.host.config['services']['caddy']['ports'][0]['host_ip'] = '0.0.0.0'
        with self.assertRaises(trust.Refused):
            self.host.probe_ca_file()

    def test_first_install_still_rejects_public_bindings(self):
        private = object.__new__(install.Host)
        private.__dict__.update(self.host.__dict__)
        with patch.object(install, 'run', side_effect=self.inspect_run), self.assertRaises(trust.Refused):
            private.running(['caddy'])

    def test_non_edge_or_unreviewed_configured_publication_refuses(self):
        for mutation in ('service', 'admin-port'):
            with self.subTest(mutation=mutation):
                services = copy.deepcopy(self.host.config['services'])
                if mutation == 'service':
                    services['keycloak'] = services.pop('caddy')
                    name = 'keycloak'
                else:
                    services['caddy']['ports'].append({'mode': 'ingress', 'host_ip': '62.83.73.32', 'target': 10000, 'published': '10000', 'protocol': 'tcp'})
                    name = 'caddy'
                with patch.dict(self.host.config, services=services), patch.object(install, 'run', side_effect=self.inspect_run), self.assertRaises(trust.Refused):
                    self.host.running([name])

    def test_stopped_public_edge_preserves_the_same_bindings_during_a_fence(self):
        value = upgrade.Upgrade(TARGET)
        value.host = lambda: self.host
        value.scope = lambda: ['caddy']
        self.host.compose = lambda *args, **__: b'' if args[1] == '-q' else b'container'
        value.run = self.inspect_run
        self.assertEqual(value.observe_services({'ready': False, 'pending': True}), {'caddy': 'stopped'})
        self.info['HostConfig']['PortBindings']['443/tcp'][0]['HostIp'] = '::'
        with self.assertRaises(trust.Refused):
            value.observe_services({'ready': False, 'pending': True})


class Pure(unittest.TestCase):
    def test_checksum_ignores_line_endings_and_bom_and_is_a_signed_integer(self):
        self.assertEqual(upgrade.flyway_checksum(b'a\nb\n'), upgrade.flyway_checksum(b'a\r\nb'))
        self.assertEqual(upgrade.flyway_checksum(b'a\n'), upgrade.flyway_checksum('﻿a'.encode()))
        self.assertNotEqual(upgrade.flyway_checksum(b'a\nb'), upgrade.flyway_checksum(b'b\na'))
        self.assertTrue(all(-2**31 <= upgrade.flyway_checksum(bytes([i])) < 2**31 for i in range(32, 127)))

    def test_inventory_orders_like_flyway_and_refuses_other_names(self):
        result = upgrade.migration_inventory([('V20261106_120000__b.sql', b'b'), ('V20261023_130000__a.sql', b'a')])
        self.assertEqual([m['version'] for m in result], ['20261023.130000', '20261106.120000'])
        for name in ('R__repeatable.sql', 'V1__short.sql'):
            with self.assertRaises(trust.Refused):
                upgrade.migration_inventory([(name, b'')])

    def history(self, count=3):
        return [{'rank': i + 1, 'success': True, **{k: FILES[i][k] for k in ('version', 'script', 'checksum')}} for i in range(count)]

    def test_flyway_change_set_is_the_pending_suffix(self):
        delta = upgrade.flyway_delta(self.history(), FILES)
        self.assertEqual((delta['currentRank'], delta['currentVersion'], delta['targetVersion']), (3, FILES[2]['version'], FILES[4]['version']))
        self.assertEqual(delta['pending'], [FILES[3]['script'], FILES[4]['script']])
        self.assertEqual(upgrade.flyway_delta(self.history(5), FILES)['pending'], [])

    def test_flyway_refusals_are_forward_recovery_not_repair(self):
        failed = self.history()
        failed[1]['success'] = False
        edited = self.history()
        edited[0]['checksum'] = 99
        ahead = [*self.history(), {'rank': 4, 'version': '20270101.120000', 'script': 'V20270101_120000__x.sql', 'checksum': 1, 'success': True}]
        gap = [h for i, h in enumerate(self.history(5)) if i != 2]
        for name, history in {'failed row': failed, 'edited': edited, 'ahead': ahead, 'out of order': gap}.items():
            with self.subTest(name), self.assertRaises(trust.Refused):
                upgrade.flyway_delta(history, FILES)

    def test_session_settings_match_the_shared_alignment_command(self):
        command = (ROOT / upgrade.REALM_COMMAND).read_text()
        block = re.search(r'SESSION_SETTINGS = \((.*?)\)', command, re.S)[1]
        self.assertEqual(tuple(re.findall(r'"(\w+)"', block)), upgrade.SESSION_SETTINGS)
        self.assertIn('"--sessions-only"', command)
        self.assertIn('SHARED_SETTINGS = (*SESSION_SETTINGS, "passwordPolicy")', command)

    def test_the_approved_password_policy_is_the_import_value_and_a_reviewed_delta(self):
        target = self.realm()
        self.assertEqual(target['passwordPolicy'], upgrade.APPROVED_PASSWORD_POLICY)
        previous = copy.deepcopy(target)
        previous['passwordPolicy'] = 'length(12) and maxLength(128) and notEmail and notContainsUsername'
        self.assertEqual(upgrade.realm_import_delta(previous, target), {'settings': ['passwordPolicy'], 'mappers': []})
        self.assertEqual(upgrade.desired_session_state(target)['passwordPolicy'], upgrade.APPROVED_PASSWORD_POLICY)
        self.assertIn("'password_policy'", upgrade.IDENTITY_SQL)
        self.assertIn("'passwordPolicy',coalesce(password_policy,'')", upgrade.REALM_SQL)
        for unreviewed in ('length(8) and maxLength(128)', 'length(6)', upgrade.APPROVED_PASSWORD_POLICY + ' and digits(1)'):
            with self.subTest(unreviewed):
                changed = copy.deepcopy(target)
                changed['passwordPolicy'] = unreviewed
                with self.assertRaises(trust.Refused):
                    upgrade.realm_import_delta(previous, changed)

    def realm(self):
        return json.loads((ROOT / upgrade.REALM_IMPORT).read_text())

    def test_only_the_reviewed_session_delta_is_an_allowed_realm_change(self):
        target = self.realm()
        previous = copy.deepcopy(target)
        previous.update(rememberMe=False, ssoSessionIdleTimeoutRememberMe=0, ssoSessionMaxLifespanRememberMe=0)
        bff = next(c for c in previous['clients'] if c['clientId'] == upgrade.BFF_CLIENT)
        bff['protocolMappers'] = [m for m in bff['protocolMappers'] if m['name'] != upgrade.REMEMBER_MAPPER]
        self.assertEqual(upgrade.realm_import_delta(previous, target),
                         {'settings': ['rememberMe', 'ssoSessionIdleTimeoutRememberMe', 'ssoSessionMaxLifespanRememberMe'],
                          'mappers': [upgrade.REMEMBER_MAPPER]})

    def test_profile_email_requires_the_exact_userinfo_mapper(self):
        target = self.realm()
        previous = copy.deepcopy(target)
        bff = next(c for c in previous['clients'] if c['clientId'] == upgrade.BFF_CLIENT)
        bff['protocolMappers'].remove(upgrade.PROFILE_EMAIL_MAPPER)
        self.assertEqual(upgrade.realm_import_delta(previous, target),
                         {'settings': [], 'mappers': [upgrade.PROFILE_EMAIL_MAPPER['name']]})
        self.assertTrue(upgrade.desired_session_state(target)['profileEmailMapper'])
        self.assertFalse(upgrade.desired_session_state(previous)['profileEmailMapper'])
        for key in ('id.token.claim', 'access.token.claim', 'introspection.token.claim'):
            with self.subTest(key=key):
                unexpected = copy.deepcopy(target)
                mapper = next(m for c in unexpected['clients'] for m in c['protocolMappers']
                              if m['name'] == upgrade.PROFILE_EMAIL_MAPPER['name'])
                mapper['config'][key] = 'true'
                with self.assertRaises(trust.Refused):
                    upgrade.realm_import_delta(previous, unexpected)

    def test_the_real_first_candidate_pair_is_exactly_that_delta(self):
        shown = [subprocess.run(['git', 'show', f'{revision}:{upgrade.REALM_IMPORT}'], cwd=ROOT, capture_output=True)
                 for revision in (prepare.REVISION, FIRST_TARGET)]
        if any(result.returncode for result in shown):
            self.skipTest('The first upgrade pair is not available in this checkout.')
        delta = upgrade.realm_import_delta(json.loads(shown[0].stdout), json.loads(shown[1].stdout))
        self.assertEqual(delta['mappers'], [upgrade.REMEMBER_MAPPER])
        self.assertEqual(delta['settings'], ['rememberMe', 'ssoSessionIdleTimeoutRememberMe', 'ssoSessionMaxLifespanRememberMe'])

    def test_any_other_realm_change_needs_its_own_review(self):
        def client(realm):
            return next(c for c in realm['clients'] if c['clientId'] == upgrade.BFF_CLIENT)
        mutations = {'open registration': lambda r: r.__setitem__('verifyEmail', False),
                     'password policy': lambda r: r.__setitem__('passwordPolicy', ''),
                     'registration notice': lambda r: r['localizationTexts']['es'].__setitem__('termsText', 'other'),
                     'wider redirect': lambda r: client(r)['redirectUris'].append('https://*'),
                     'public client': lambda r: client(r).__setitem__('publicClient', True),
                     'new client': lambda r: r['clients'].append({'clientId': 'other'}),
                     'removed client': lambda r: r['clients'].remove(client(r)),
                     'other mapper': lambda r: client(r)['protocolMappers'].append({'name': 'audience', 'protocolMapper': 'x'}),
                     'removed mapper': lambda r: client(r)['protocolMappers'].pop(0)}
        for name, mutate in mutations.items():
            with self.subTest(name):
                target = self.realm()
                mutate(target)
                with self.assertRaises(trust.Refused):
                    upgrade.realm_import_delta(self.realm(), target)

    def manifest(self, **changes):
        return {'format': 1, 'environment': 'production', 'fence': 'roles-nologin-drained', 'backupId': BACKUP,
                'databases': ['videogame_platform', 'videogame_keycloak'], 'images': OLD, 'configurationSha256': 'h',
                'recoveryPoint': (NOW - timedelta(hours=1)).isoformat(), 'artifacts': {'x': {'bytes': 10}}, **changes}

    def test_backup_evidence_requires_this_exact_canonical_pair_everywhere(self):
        offhost = {'kind': 'encrypted-pull', 'backupId': BACKUP, 'outcome': 'success'}
        restore = {'kind': 'isolated-restore', 'backupId': BACKUP, 'outcome': 'success'}
        self.assertEqual(upgrade.verify_backup_evidence(self.manifest(), OLD, 'h', offhost, restore, now=NOW), BACKUP)
        self.assertEqual(upgrade.verify_backup_evidence(self.manifest(), OLD, 'h', {**offhost, 'kind': 's3-publish'}, restore, now=NOW), BACKUP)
        refusals = {
            'stale': (self.manifest(recoveryPoint=(NOW - timedelta(hours=25)).isoformat()), offhost, restore),
            'manual dump': ({'format': 'pg_dump', 'sha256': 'x'}, offhost, restore),
            'unfenced': (self.manifest(fence='none'), offhost, restore),
            'other configuration': (self.manifest(configurationSha256='other'), offhost, restore),
            'other images': (self.manifest(images={'postgres': 'x'}), offhost, restore),
            'local copy': (self.manifest(), {**offhost, 'kind': 'encrypted-copy'}, restore),
            'integrity only': (self.manifest(), {**offhost, 'kind': 'ciphertext-integrity'}, restore),
            'other pair off host': (self.manifest(), {**offhost, 'backupId': 'e' * 32}, restore),
            'failed copy': (self.manifest(), {**offhost, 'outcome': 'failure'}, restore),
            'no restore': (self.manifest(), offhost, {}),
            'other pair restored': (self.manifest(), offhost, {**restore, 'backupId': 'e' * 32}),
            'failed restore': (self.manifest(), offhost, {**restore, 'outcome': 'failure'})}
        for name, (manifest, copy_receipt, restored) in refusals.items():
            with self.subTest(name), self.assertRaises((trust.Refused, KeyError)):
                upgrade.verify_backup_evidence(manifest, OLD, 'h', copy_receipt, restored, now=NOW)


class DeltaFixture(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        patcher = patch.object(upgrade, 'BUNDLES', self.root)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.old_bundle, self.new_bundle = self.root / '9ffc05a-private', self.root / 'aaaaaaaaaaaa-private'
        for bundle in (self.old_bundle, self.new_bundle):
            for mounted in ('candidate/deploy/public-production/bin/run-postgres', 'candidate/deploy/public-production/bin/run-keycloak',
                            'candidate/deploy/public-production/bin/run-application', 'candidate/deploy/public-production/bin/run-migrations',
                            'candidate/deploy/private-dev/bin/run-migrations',
                            'Caddyfile.private'):
                path = bundle / mounted
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(mounted)
            dashboards = bundle / 'candidate/deploy/private-dev/grafana/dashboards'
            dashboards.mkdir(parents=True)
            (dashboards / 'vgp-synchronization.json').write_text('{}')
        self.active = config(self.old_bundle)
        # The #452 dashboard override: one extra read-only file, gone once the target tree carries it.
        self.active['services']['grafana']['volumes'].append(bind('/operations/dashboard.json', acceptance.DASHBOARD_TARGET))
        Path(self.root / 'operations').mkdir()

    def reconcile(self, target=None):
        return upgrade.reconcile(self.active, target or rendered(self.new_bundle), self.new_bundle)


class Delta(DeltaFixture):
    def test_only_changed_services_change_and_the_rest_stay_verbatim(self):
        result, changed = self.reconcile()
        self.assertEqual(sorted(changed), ['application', 'caddy', 'catalogue-localizer', 'grafana', 'keycloak', 'migration'])
        self.assertEqual(changed['catalogue-localizer'], ['image'])
        self.assertEqual(changed['grafana'], ['volumes'])
        for name in ('postgres', 'telemetry', 'prometheus', 'node-exporter', 'blackbox'):
            self.assertEqual(result['services'][name], self.active['services'][name])
        self.assertEqual(result['services']['postgres']['volumes'][0]['source'], str(self.old_bundle / 'candidate/deploy/public-production/bin/run-postgres'),
                         'An unchanged service keeps its running definition, so it is not recreated.')

    def test_installation_lineage_survives_on_every_service(self):
        result, _ = self.reconcile()
        for service in result['services'].values():
            self.assertEqual(service['labels'][control.RUN_LABEL], RUN)
            self.assertEqual(service['labels'][control.CONFIG_LABEL], CONFIGURATION)

    def test_changed_label_marks_only_recreated_services(self):
        result, changed = self.reconcile()
        upgrade.mark_changed(result, changed, TARGET)
        self.assertEqual({n for n, s in result['services'].items() if upgrade.UPGRADE_LABEL in s['labels']}, set(changed))

    def test_a_changed_bound_file_alone_recreates_its_service(self):
        (self.new_bundle / 'candidate/deploy/public-production/bin/run-application').write_text('changed')
        same_images = config(self.new_bundle)
        same_images['services']['grafana']['volumes'].append(bind('/operations/dashboard.json', acceptance.DASHBOARD_TARGET))
        _, changed = self.reconcile(same_images)
        self.assertEqual(changed, {'application': ['bound-files']})

    def test_nothing_to_change_is_detected(self):
        same = config(self.new_bundle)
        same['services']['grafana']['volumes'].append(bind('/operations/dashboard.json', acceptance.DASHBOARD_TARGET))
        self.assertEqual(self.reconcile(same)[1], {})

    def test_unreviewed_differences_refuse(self):
        def set_service(name, field, value):
            return lambda c: c['services'][name].__setitem__(field, value)
        mutations = {
            'postgres image': set_service('postgres', 'image', 'postgres:19@sha256:' + 'a' * 64),
            'observability image': set_service('grafana', 'image', 'grafana@sha256:' + 'b' * 64),
            'telemetry image': set_service('telemetry', 'image', 'otel@sha256:' + 'b' * 64),
            'cpu ceiling': set_service('application', 'cpus', 8.0),
            'lower pid ceiling': set_service('grafana', 'pids_limit', 16),
            'lower memory': set_service('keycloak', 'mem_limit', '50'),
            'new capability': set_service('caddy', 'cap_add', ['SYS_ADMIN']),
            'issuer': lambda c: c['services']['application']['environment'].__setitem__('OIDC_ISSUER_URI', 'https://evil.example'),
            'existing setting': lambda c: c['services']['application']['environment'].__setitem__('CATALOGUE_SYNC_SCHEDULING_ENABLED', 'x'),
            'removed setting': lambda c: c['services']['grafana']['environment'].pop('GF_PLUGINS_DISABLE_PLUGINS'),
            'new identity setting': lambda c: c['services']['application']['environment'].__setitem__('OIDC_CLIENT_SECRET', 'x'),
            'new secret-file setting': lambda c: c['services']['application']['environment'].__setitem__('OTHER_FILE', '/x'),
            'removed bind': lambda c: c['services']['application'].__setitem__('volumes', []),
            'new top-level section': lambda c: c.__setitem__('configs', {'x': {'file': '/x'}}),
            'service secret': set_service('application', 'secrets', [{'source': 'keycloak_admin_password'}]),
            'network membership': set_service('application', 'networks', {'identity-data': None}),
            'read-only filesystem': set_service('application', 'read_only', False),
            'user': set_service('application', 'user', '0'),
            'entrypoint': set_service('application', 'entrypoint', ['/bin/sh']),
            'logging': set_service('application', 'logging', {'driver': 'none'}),
            'healthcheck': set_service('keycloak', 'healthcheck', {'disable': True}),
            'tmpfs': set_service('keycloak', 'tmpfs', ['/x']),
            'identity origin': lambda c: c['services']['keycloak']['environment'].__setitem__('KC_HOSTNAME', 'https://evil.example'),
            'secret path': lambda c: c['services']['keycloak']['environment'].__setitem__('KC_DB_PASSWORD_FILE', '/tmp/x'),
            'new secret': lambda c: c['secrets'].__setitem__('other', {'file': '/x'}),
            'replaced secret': lambda c: c['secrets']['keycloak_admin_password'].__setitem__('file', '/x'),
            'new network': lambda c: c['networks'].__setitem__('public', {'name': 'public'}),
            'renamed volume': lambda c: c['volumes']['postgres-data'].__setitem__('name', 'other'),
            'detached volume': lambda c: c['services']['caddy'].__setitem__('volumes', [c['services']['caddy']['volumes'][0]]),
            'new service': lambda c: c['services'].__setitem__('adminer', {'image': 'a@sha256:' + 'a' * 64}),
            'removed service': lambda c: c['services'].pop('blackbox'),
            'writable bind': lambda c: c['services']['application']['volumes'].append({**bind(self.new_bundle / 'x', '/x'), 'read_only': False}),
            'foreign bind': lambda c: c['services']['application']['volumes'].append(bind('/var/run/docker.sock', '/d')),
        }
        for name, mutate in mutations.items():
            with self.subTest(name):
                target = rendered(self.new_bundle)
                mutate(target)
                with self.assertRaises(trust.Refused):
                    self.reconcile(target)

    def test_a_new_setting_and_the_version_are_the_only_allowed_environment_changes(self):
        target = rendered(self.new_bundle)
        target['services']['application']['environment'].update(TELEMETRY_SERVICE_VERSION='2', NEW_PRODUCT_LIMIT='5')
        result, changed = self.reconcile(target)
        self.assertEqual(changed['application'], ['environment', 'image'])
        self.assertEqual(upgrade.environment_delta(self.active['services']['application'], result['services']['application']),
                         ['NEW_PRODUCT_LIMIT', 'TELEMETRY_SERVICE_VERSION'])

    def test_recurring_acquisition_is_activated_only_from_off_to_on(self):
        self.active['services']['application']['environment']['CATALOGUE_SYNC_SCHEDULING_ENABLED'] = 'false'
        result, changed = self.reconcile()
        self.assertEqual(result['services']['application']['environment']['CATALOGUE_SYNC_SCHEDULING_ENABLED'], 'true')
        upgrade.validate_runtime(result, IMAGES)
        for value in ('TRUE', '1'):
            with self.subTest(value):
                target = rendered(self.new_bundle)
                target['services']['application']['environment']['CATALOGUE_SYNC_SCHEDULING_ENABLED'] = value
                self.assertRaises(trust.Refused, self.reconcile, target)
        # Once on, switching it off again is a separate reviewed change, not an upgrade delta.
        self.active['services']['application']['environment']['CATALOGUE_SYNC_SCHEDULING_ENABLED'] = 'true'
        target = rendered(self.new_bundle)
        target['services']['application']['environment']['CATALOGUE_SYNC_SCHEDULING_ENABLED'] = 'false'
        self.assertRaises(trust.Refused, self.reconcile, target)

    def test_a_postgres_bound_file_change_refuses(self):
        (self.new_bundle / 'candidate/deploy/public-production/bin/run-postgres').write_text('changed')
        with self.assertRaises(trust.Refused):
            self.reconcile()

    def test_resulting_runtime_keeps_private_accepted_and_one_shot_invariants(self):
        result = self.reconcile()[0]
        upgrade.validate_runtime(result, IMAGES)
        def set_service(name, field, value):
            return lambda c: c['services'][name].__setitem__(field, value)
        def environment(name, key, value):
            return lambda c: c['services'][name]['environment'].__setitem__(key, value)
        mutations = {
            'published port': set_service('caddy', 'ports', [{'target': 443, 'published': '443'}]),
            'privilege': set_service('application', 'privileged', True),
            'host network': set_service('grafana', 'network_mode', 'host'),
            'build': set_service('application', 'build', {'context': '.'}),
            'mutable image': set_service('postgres', 'image', 'postgres:latest'),
            'one-shot restart': set_service('migration', 'restart', 'unless-stopped'),
            'restart dropped': set_service('postgres', 'restart', 'no'),
            'always restart': set_service('caddy', 'restart', 'always'),
            'migration other image': set_service('migration', 'image', 'ghcr.io/r/app@sha256:' + '0' * 64),
            'unselected digest': set_service('keycloak', 'image', OLD['keycloak']),
            'scheduling unknown': environment('application', 'CATALOGUE_SYNC_SCHEDULING_ENABLED', 'TRUE'),
            'schedule override': environment('application', 'CATALOGUE_SYNC_NEAR_TERM_CRON', '0 * * * * *'),
            'telegram on': environment('grafana', 'GRAFANA_TELEGRAM_ENABLED', 'true'),
            'deletion overlay': environment('application', 'ACCOUNT_DELETION_EXECUTOR_ENABLED', 'true'),
            'deletion capability': lambda c: c['secrets'].__setitem__('account_deletion_executor_capability', {'file': '/x'}),
            'localizer ceiling': set_service('catalogue-localizer', 'mem_limit', '100'),
            'plugin policy': environment('grafana', 'GF_PLUGINS_DISABLE_PLUGINS', ''),
            'dashboard unmounted': set_service('grafana', 'volumes', []),
            'other project': lambda c: c.__setitem__('name', 'other'),
            'no guarded migration entrypoint': lambda c: c['services']['migration'].__setitem__('volumes', c['services']['migration']['volumes'][:1]),
        }
        for name, mutate in mutations.items():
            with self.subTest(name):
                value = copy.deepcopy(result)
                mutate(value)
                with self.assertRaises(trust.Refused):
                    upgrade.validate_runtime(value, IMAGES)


class ReviewedAdditions(DeltaFixture):
    """Reviewed runtime-input, secret and setting additions an upgrade applies itself."""

    def with_loki(self, target=None):
        target = target or rendered(self.new_bundle)
        target['services']['loki'] = {
            'image': 'grafana/loki@sha256:' + 'c' * 64, 'restart': acceptance.POLICY, 'user': '10001:10001',
            'read_only': True, 'cap_drop': ['ALL'], 'security_opt': ['no-new-privileges:true'],
            'mem_limit': '402653184', 'pids_limit': 128, 'networks': {'telemetry': None}, 'environment': {'GOMEMLIMIT': '300MiB'}, 'labels': {},
            'volumes': [bind(self.new_bundle / 'candidate/deploy/public-production/observability/loki.yaml', '/etc/loki/config.yaml'),
                        {'type': 'volume', 'source': 'loki-data', 'target': '/loki'}]}
        target['volumes']['loki-data'] = {'name': prepare.PROJECT + '_loki-data'}
        return target

    def test_the_reviewed_log_store_is_added_with_its_own_volume(self):
        result, changed = self.reconcile(self.with_loki())
        self.assertEqual(changed['loki'], ['added'])
        # The live checks require the installation lineage on every container, the added one too.
        lineage = {k: self.active['services']['application']['labels'][k] for k in (control.RUN_LABEL, control.CONFIG_LABEL)}
        self.assertEqual({k: result['services']['loki']['labels'][k] for k in lineage}, lineage)
        self.assertEqual(result['volumes']['loki-data'], {'name': prepare.PROJECT + '_loki-data'})
        self.assertEqual(result['volumes']['postgres-data'], self.active['volumes']['postgres-data'])
        upgrade.validate_runtime(result, IMAGES)
        # The plan reviews it as a new image, with no active environment to compare against.
        images, keys = upgrade.review_delta(self.active, result, changed)
        self.assertEqual(images['loki'], result['services']['loki']['image'])
        self.assertNotIn('loki', keys)

    def test_any_other_service_or_volume_change_still_refuses(self):
        mutations = {
            'unreviewed service': lambda c: c['services'].__setitem__('other', c['services'].pop('loki')),
            'removed service': lambda c: c['services'].pop('blackbox'),
            'published port': lambda c: c['services']['loki'].__setitem__('ports', [{'target': 3100, 'published': '3100'}]),
            'another network': lambda c: c['services']['loki']['networks'].__setitem__('application-data', None),
            'writable root': lambda c: c['services']['loki'].__setitem__('read_only', False),
            'capability': lambda c: c['services']['loki'].__setitem__('cap_add', ['SYS_ADMIN']),
            'secret': lambda c: c['services']['loki'].__setitem__('secrets', [{'source': 'keycloak_admin_password', 'target': '/s'}]),
            'host bind': lambda c: c['services']['loki']['volumes'][0].__setitem__('source', '/var/lib/docker'),
            'writable bind': lambda c: c['services']['loki']['volumes'][0].__setitem__('read_only', False),
            'database volume': lambda c: c['services']['loki']['volumes'][1].__setitem__('source', 'postgres-data'),
            'unreviewed volume': lambda c: c['volumes'].__setitem__('other-data', {'name': 'x'}),
            'existing volume changed': lambda c: c['volumes']['postgres-data'].__setitem__('name', 'other'),
            'new network': lambda c: c['networks'].__setitem__('logs', {'name': 'logs'}),
        }
        for name, mutate in mutations.items():
            with self.subTest(name):
                target = self.with_loki()
                mutate(target)
                with self.assertRaises(trust.Refused):
                    self.reconcile(target)

    def test_grafana_loads_only_loki_and_may_raise_its_process_ceiling(self):
        target = rendered(self.new_bundle)
        grafana = target['services']['grafana']
        grafana['environment']['GF_PLUGINS_DISABLE_PLUGINS'] = upgrade.GRAFANA_DISABLED_PLUGINS
        grafana['pids_limit'] = 160
        result, changed = self.reconcile(target)
        self.assertTrue({'environment', 'pids_limit'} <= set(changed['grafana']))
        upgrade.validate_runtime(result, IMAGES)
        for policy in ('elasticsearch', upgrade.GRAFANA_DISABLED_PLUGINS.replace(',tempo', '')):
            with self.subTest(policy):
                grafana['environment']['GF_PLUGINS_DISABLE_PLUGINS'] = policy
                with self.assertRaises(trust.Refused):
                    self.reconcile(target)

    def with_feedback(self, target=None):
        target = target or rendered(self.new_bundle)
        target['secrets'].update({name: {'file': '/s/' + name.replace('_', '-')} for name in upgrade.SECRET_ADDITIONS})
        target['services']['application']['secrets'] = [
            {'source': name, 'target': '/run/secrets/' + name} for name in upgrade.SECRET_ADDITIONS]
        target['services']['application']['environment'].update(
            {key: '/run/secrets/' + name for name, (_, key) in upgrade.SECRET_ADDITIONS.items()})
        return target

    def test_reviewed_secret_additions_are_accepted_with_their_file_settings(self):
        result, changed = self.reconcile(self.with_feedback())
        self.assertEqual(upgrade.secret_additions(self.active, result), sorted(upgrade.SECRET_ADDITIONS))
        self.assertIn('secrets', changed['application'])

    def test_any_other_secret_change_still_refuses(self):
        mutations = {
            'unreviewed secret': lambda c: c['secrets'].__setitem__('other_secret', {'file': '/s/other-secret'}),
            'secret outside the inventory': lambda c: c['secrets']['feedback_smtp_username'].__setitem__('file', '/tmp/x'),
            'existing secret moved': lambda c: c['secrets']['keycloak_admin_password'].__setitem__('file', '/s/other'),
            'reviewed secret in another service': lambda c: c['services']['keycloak'].__setitem__(
                'secrets', [{'source': 'feedback_smtp_username', 'target': '/run/secrets/feedback_smtp_username'}]),
            'reviewed secret at another path': lambda c: c['services']['application']['secrets'][0].__setitem__('target', '/x'),
            'file setting without its secret': lambda c: c['services']['application']['environment'].__setitem__(
                'FEEDBACK_SMTP_USERNAME_FILE', '/run/secrets/other'),
            'file setting on another service': lambda c: c['services']['keycloak']['environment'].__setitem__(
                'FEEDBACK_SMTP_USERNAME_FILE', '/run/secrets/feedback_smtp_username'),
        }
        for name, mutate in mutations.items():
            with self.subTest(name):
                target = self.with_feedback()
                mutate(target)
                self.assertRaises(trust.Refused, self.reconcile, target)

    def test_a_setting_transition_is_exact(self):
        before, after = upgrade.SETTING_TRANSITIONS[('keycloak', 'KC_LOG_LEVEL')]
        self.active['services']['keycloak']['environment']['KC_LOG_LEVEL'] = before
        target = rendered(self.new_bundle)
        target['services']['keycloak']['environment']['KC_LOG_LEVEL'] = after
        self.assertIn('environment', self.reconcile(target)[1]['keycloak'])
        for value in ('debug', after + ',org.keycloak:trace'):
            with self.subTest(value):
                target['services']['keycloak']['environment']['KC_LOG_LEVEL'] = value
                self.assertRaises(trust.Refused, self.reconcile, target)
        self.active['services']['keycloak']['environment']['KC_LOG_LEVEL'] = 'warn'
        target['services']['keycloak']['environment']['KC_LOG_LEVEL'] = after
        self.assertRaises(trust.Refused, self.reconcile, target)

    def test_feedback_switch_follows_owner_inputs_and_needs_an_inbox_when_on(self):
        self.active = self.with_feedback(self.active)
        self.active['services']['application']['environment'].update(FEEDBACK_ENABLED='false', FEEDBACK_INBOX='')
        target = self.with_feedback()
        target['services']['application']['environment'].update(FEEDBACK_ENABLED='true', FEEDBACK_INBOX='owner@example.invalid')
        result, _ = self.reconcile(target)
        upgrade.validate_runtime(result, IMAGES)
        for value in ({'FEEDBACK_ENABLED': 'true', 'FEEDBACK_INBOX': ''}, {'FEEDBACK_ENABLED': 'yes'}):
            with self.subTest(value):
                broken = copy.deepcopy(result)
                broken['services']['application']['environment'].update(value)
                self.assertRaises(trust.Refused, upgrade.validate_runtime, broken, IMAGES)

    def test_runtime_inputs_add_only_reviewed_defaults_and_owner_keys(self):
        previous = {'PRODUCTION_TELEGRAM_ENABLED': 'false', 'PRODUCTION_SECRETS_GID': '900'}
        example = {**previous, **upgrade.INPUT_DEFAULTS}
        values, inputs = upgrade.runtime_inputs(previous, example, {})
        self.assertEqual(values, example)
        self.assertEqual(inputs, {'defaulted': sorted(upgrade.INPUT_DEFAULTS), 'ownerKeys': [], 'ownerSha256': None})
        owner = {'PRODUCTION_FEEDBACK_ENABLED': 'true', 'PRODUCTION_FEEDBACK_INBOX': 'owner@example.invalid'}
        values, inputs = upgrade.runtime_inputs(previous, example, owner)
        self.assertEqual(values['PRODUCTION_FEEDBACK_INBOX'], 'owner@example.invalid')
        self.assertNotIn('owner@example.invalid', json.dumps(inputs), 'The plan never carries the mailbox.')
        refusals = {
            'unreviewed new input': (previous, {**example, 'PRODUCTION_OTHER': 'x'}, {}),
            'reviewed input with another default': (previous, {**example, 'PRODUCTION_FEEDBACK_ENABLED': 'true'}, {}),
            'removed input': ({**previous, 'PRODUCTION_GONE': 'x'}, example, {}),
            'owner key outside the reviewed set': (previous, example, {'PRODUCTION_TELEGRAM_ENABLED': 'true'}),
            'feedback on without an inbox': (previous, example, {'PRODUCTION_FEEDBACK_ENABLED': 'true'}),
        }
        for name, arguments in refusals.items():
            with self.subTest(name):
                self.assertRaises(trust.Refused, upgrade.runtime_inputs, *arguments)

    def test_owner_inputs_come_only_from_the_protected_file(self):
        actor = upgrade.Upgrade.__new__(upgrade.Upgrade)
        path = self.root / 'runtime-inputs.env'
        with patch.object(upgrade, 'OWNER_INPUTS', path), patch.object(upgrade.install, 'secure') as secure:
            self.assertEqual(actor.owner_inputs(), {})
            path.write_text('PRODUCTION_FEEDBACK_ENABLED=true\nPRODUCTION_FEEDBACK_INBOX=owner@example.invalid\n')
            self.assertEqual(actor.owner_inputs()['PRODUCTION_FEEDBACK_ENABLED'], 'true')
            secure.assert_called_with(path, mode=0o600)
            path.write_text('PRODUCTION_TELEGRAM_ENABLED=true\n')
            self.assertRaises(trust.Refused, actor.owner_inputs)

    def test_staging_creates_missing_reviewed_secrets_empty_and_never_overwrites(self):
        actor = upgrade.Upgrade.__new__(upgrade.Upgrade)
        secrets_dir = self.root / 'secrets'
        secrets_dir.mkdir()
        (secrets_dir / 'feedback-smtp-password').write_text('kept')
        target = self.with_feedback()
        target['secrets'].update({name: {'file': str(secrets_dir / name.replace('_', '-'))} for name in upgrade.SECRET_ADDITIONS})
        target['services']['application']['group_add'] = ['900']
        target['services']['application']['environment']['FEEDBACK_ENABLED'] = 'false'
        owners = []
        with patch.object(upgrade.install, 'secure'), \
                patch.object(upgrade.os, 'fchown', side_effect=lambda fd, uid, gid: owners.append((uid, gid))):
            self.assertEqual(actor.missing_secret_additions(target, sorted(upgrade.SECRET_ADDITIONS)), {'feedback-smtp-username'})
            self.assertEqual(actor.create_secret_additions(target, sorted(upgrade.SECRET_ADDITIONS)), ['feedback-smtp-username'])
            created = secrets_dir / 'feedback-smtp-username'
            self.assertEqual((created.read_bytes(), created.stat().st_mode & 0o777, owners), (b'', 0o640, [(0, 900)]))
            self.assertEqual((secrets_dir / 'feedback-smtp-password').read_text(), 'kept')
            self.assertEqual(actor.create_secret_additions(target, sorted(upgrade.SECRET_ADDITIONS)), [])
            created.unlink()
            target['services']['application']['environment']['FEEDBACK_ENABLED'] = 'true'
            with self.assertRaisesRegex(trust.Refused, 'provisioned before the upgrade'):
                actor.create_secret_additions(target, sorted(upgrade.SECRET_ADDITIONS))
            self.assertFalse(created.exists())


class FakeCore:
    """The recovery actor's module functions."""

    @staticmethod
    def verify(path):
        manifest = json.loads((path / 'manifest.json').read_text())
        if manifest.get('format') != 1:
            raise RuntimeError('Wrong backup identity.')
        return manifest

    @staticmethod
    def write(path, value):
        path.write_text(json.dumps(value))


class FakeRecovery:
    gate_volume = 'gameometro-production_recovery-state'
    config_hash = 'h'
    images = OLD

    def __init__(self, state, log):
        self.state, self.log = state, log
        self.ready, self.pending, self.marker = False, True, None
        self.history = 3
        self.failed_row = False
        self.keycloak_schema = 'kc'
        self.closed(upgrade.FENCED_PHASE, BACKUP, quiet=True)
        self.running = b''

    def lock(self, maintenance=False):
        log = self.log

        class Lock:
            def __enter__(self):
                log.append('lock')

            def __exit__(self, *_):
                log.append('unlock')
        assert maintenance, 'The upgrade owns the fence across invocations.'
        return Lock()

    def closed(self, phase, backup_id=None, quiet=False):
        self.ready, self.pending = False, True
        (self.state / 'PENDING.json').write_text(json.dumps({'phase': phase, 'backupId': backup_id}))
        if not quiet:
            self.log.append('closed:' + phase)

    def gate(self, action, run_id=None):
        if action == 'open':
            self.ready, self.pending = True, False
            self.log.append('gate-open')
        elif action == 'maintenance-check':
            return (self.marker or 'none').encode()
        elif action == 'maintenance-enter':
            self.log.append('fence-enter')
            if self.marker not in (None, run_id):
                return b'conflict'
            self.marker = run_id
            return b'entered'
        elif action == 'maintenance-exit':
            self.log.append('fence-exit')
            self.marker = None
            return b'released'
        elif action == 'check':
            return b'open' if self.ready and not self.pending else b'closed'
        return b''

    def check_lease(self):
        pass

    def sql(self, database, statement):
        self.log.append('sql:' + statement.strip())

    def role_login(self, enabled):
        self.log.append(f'roles-login:{enabled}')

    def fence(self):
        self.log.append('fence')
        self.closed('quiescing', quiet=True)

    def assert_fenced(self):
        self.log.append('assert-fenced')

    def metadata(self):
        return {'postgresMajor': '18', 'applicationSchema': FILES[self.history - 1]['version'], 'keycloakSchema': self.keycloak_schema}

    def compose(self, *args, **_):
        return self.running


class FakeHost:
    def __init__(self, test):
        self.test, self.source = test, test.source
        self.config = rendered(test.bundle)
        self.degraded = ()

    def smoke_services(self):
        return [n for n in upgrade.PERSISTENT if n not in self.degraded]

    def compose(self, *args, **_):
        if args[0] == 'up':
            self.test.assertIn('--no-start', args)
            self.test.log.append('no-start:' + ','.join(a for a in args if a in upgrade.PERSISTENT))
            return b''
        self.test.log.append(' '.join(a for a in args if a in ('run', 'migration')))
        if args[0] == 'run':
            recovery = self.test.recovery
            self.test.assertFalse(recovery.ready or not recovery.pending, 'The gate opened for the migration actor.')
            self.test.assertEqual(args[args.index('--entrypoint') + 1], upgrade.MIGRATION_ENTRYPOINT)
            if self.test.migration_failure:
                raise self.test.migration_failure
            recovery.history = len(FILES)
        return b''

    def reader(self):
        self.test.log.append('reader')

    def running(self, names):
        self.test.log.append('running:' + ','.join(names))

    def smoke(self):
        self.test.log.append('smoke')

    def gate_open(self):
        self.test.assertTrue(self.test.recovery.ready)

    def probe(self, script):
        self.test.log.append('probe:' + ('authorization' if script == upgrade.AUTHORIZATION_PROBE else 'prometheus' if 'prometheus' in script else 'grafana'))


class FakeUpgrade(upgrade.Upgrade):
    fault = None

    def now(self):
        return NOW

    def github(self):
        return None

    def verify_operational(self, api):
        self.test.log.append('operational')

    def verify_claimed_operator(self, record):
        self.test.log.append('operational')

    def host_checks(self):
        return {}

    def verify_staged(self, reviewed, config_value, api=None):
        self.test.log.append('staged')
        if self.test.staged_failure:
            raise self.test.staged_failure

    def check_inputs(self, *_, **__):
        pass

    def build(self, directory, api):
        reviewed = {'current': {'revision': prepare.REVISION}, 'target': {'revision': self.target, 'version': '2'},
                    'changedServices': {n: ['image'] for n in ('caddy', 'application', 'catalogue-localizer', 'grafana', 'keycloak', 'migration')},
                    'targetConfigurationSha256': upgrade.install.sha(self.bundle / upgrade.TARGET_CONFIG),
                    'migrationInventory': FILES}
        return {'reviewed': reviewed, 'planSha256': upgrade.digest(reviewed), 'config': rendered(self.bundle), 'local': lambda p: p}

    def target_host(self, plan):
        return FakeHost(self.test)

    def host(self, *_, **__):
        return FakeHost(self.test)

    def gate_state(self):
        recovery = self.test.recovery
        return {'ready': recovery.ready, 'pending': recovery.pending, 'maintenance': recovery.marker}

    def query(self, database, statement):
        recovery = self.test.recovery
        if statement == upgrade.HISTORY_SQL:
            rows = [{'rank': i + 1, 'success': True, **{k: FILES[i][k] for k in ('version', 'script', 'checksum')}} for i in range(recovery.history)]
            if recovery.failed_row:
                rows[-1]['success'] = False
            return rows
        if statement == upgrade.WORK_SQL:
            return self.test.work
        if statement == upgrade.DATA_SQL:
            return {'games': 164000, 'releases': 303000, 'ratings': 5}
        if statement == upgrade.IDENTITY_SQL:
            return self.test.identity
        if statement == upgrade.READER_SQL:
            return self.test.reader
        if statement == upgrade.REALM_SQL:
            return upgrade.desired_session_state(json.loads((self.test.source / upgrade.REALM_IMPORT).read_text()))
        return True

    def migration_actor_running(self):
        return self.test.actor_running

    def start(self, host, *names, timeout=600):
        self.test.log.append('up:' + ','.join(names))
        if names[0] in self.test.start_failures:
            raise trust.Refused('Service did not become healthy.')

    def realm_command(self, host, *arguments, command=upgrade.REALM_COMMAND):
        self.test.log.append(('notice:' if command == 'scripts/production_registration_notice.py' else 'realm:') + ('check' if '--check' in arguments else 'apply'))
        self.test.assertTrue(self.test.recovery.keycloak_login, 'The realm migrated without the target Keycloak.')

    def phase_reached(self, phase):
        self.test.log.append('phase:' + phase)
        if phase == type(self).fault:
            type(self).fault = None
            raise trust.Refused('Injected interruption.')


class Flow(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.admin = self.base / 'admin'
        (self.admin / 'first-install/recovery').mkdir(parents=True)
        self.bundles = self.base / 'installations'
        self.bundle = self.bundles / f'{TARGET[:12]}-private'
        self.source = self.bundle / 'candidate'
        (self.source / Path(upgrade.REALM_IMPORT).parent).mkdir(parents=True)
        (self.source / upgrade.REALM_IMPORT).write_text((ROOT / upgrade.REALM_IMPORT).read_text())
        (self.bundle / upgrade.TARGET_CONFIG).write_text(upgrade.canonical(rendered(self.bundle)))
        self.first_install = self.admin / 'first-install/runtime.json'
        self.first_install.write_text(json.dumps({'immutable': True}))
        self.log = []
        self.work = {'runningSynchronizations': 0, 'activeTranslationClaims': 0}
        self.identity = {'realm': 'r', **{k: [2, k] for k in (*upgrade.ACCOUNT_DIGESTS, *upgrade.CONFIGURATION_DIGESTS)}}
        self.reader = {'settings': ['jit=off', 'statement_timeout=3s'], 'connectionLimit': 2}
        self.start_failures = set()
        self.actor_running = b''
        self.migration_failure = None
        self.staged_failure = None
        self.recovery = FakeRecovery(self.admin / 'first-install/recovery', self.log)
        self.recovery.keycloak_login = False
        original_sql = self.recovery.sql

        def sql(database, statement):
            if 'videogame_keycloak LOGIN' in statement:
                self.recovery.keycloak_login = True
            original_sql(database, statement)
        self.recovery.sql = sql
        self.pair = self.base / 'pair'
        self.pair.mkdir()
        (self.pair / 'manifest.json').write_text(json.dumps(self.manifest()))
        self.offhost = self.base / 'offhost.json'
        self.restore = self.base / 'restore.json'
        self.offhost.write_text(json.dumps({'kind': 'encrypted-pull', 'backupId': BACKUP, 'outcome': 'success'}))
        self.restore.write_text(json.dumps({'kind': 'isolated-restore', 'backupId': BACKUP, 'outcome': 'success'}))
        for item in (patch.object(install, 'secure'), patch.object(upgrade, 'BUNDLES', self.bundles)):
            item.start()
            self.addCleanup(item.stop)
        FakeUpgrade.fault = None
        self.addCleanup(control.CANCEL.clear)

    def manifest(self):
        return {'format': 1, 'environment': 'production', 'fence': 'roles-nologin-drained', 'backupId': BACKUP,
                'databases': ['videogame_platform', 'videogame_keycloak'], 'images': OLD, 'configurationSha256': 'h',
                'recoveryPoint': (NOW - timedelta(hours=1)).isoformat(), 'artifacts': {'x': {'bytes': 10}},
                'postgresMajor': '18', 'applicationSchema': FILES[2]['version'], 'keycloakSchema': 'kc'}

    def upgrader(self, target=TARGET, forward_of=None):
        value = FakeUpgrade(target, operational='9' * 40, adapter='8' * 64, admin=self.admin, bundles=self.bundles, forward_of=forward_of,
                            runner=self.runner)
        value.test = self
        value.evidence = {'runId': RUN, 'configuration': CONFIGURATION}
        value.recovery = self.recovery
        value.core = FakeCore
        value.active = {'file': self.first_install, 'config': config(OLD_BUNDLE), 'revision': prepare.REVISION,
                        'source': self.source, 'bundle': self.bundle}
        return value

    def runner(self, args, **_):
        args = [str(a) for a in args]
        if args[:2] == ['docker', 'run']:
            return f'{RUN} released\n'.encode()
        raise AssertionError(args)

    def sha(self):
        value = self.upgrader()
        return value.build(None, None)['planSha256']

    def apply(self, **changes):
        evidence = dict(backup=self.pair, offhost=self.offhost, restore=self.restore, reference='#455 owner')
        evidence.update(changes)
        return self.upgrader().apply(self.sha(), **evidence)

    def journal(self, target=TARGET):
        return json.loads((self.admin / upgrade.UPGRADES / target / upgrade.JOURNAL).read_text())

    def filtered(self):
        return [e for e in self.log if not e.startswith(('operational', 'staged', 'phase:', 'running:', 'probe:', 'assert-fenced', 'lock', 'unlock'))]

    def test_order_keeps_every_writer_closed_until_the_identity_migration_is_verified(self):
        result = self.apply()
        self.assertEqual(result['mode'], 'applied')
        self.assertFalse(result['productionReady'])
        self.assertEqual(self.filtered(), [
            'closed:upgrade-claimed', 'fence-enter',
            'sql:ALTER ROLE videogame_app_migrator LOGIN;', 'run migration', 'sql:ALTER ROLE videogame_app_migrator NOLOGIN;',
            'reader', 'sql:ALTER ROLE videogame_keycloak LOGIN;', 'up:keycloak', 'notice:apply', 'realm:apply', 'realm:check', 'notice:check',
            'no-start:caddy,application,catalogue-localizer,grafana', 'roles-login:True', 'gate-open', 'fence-exit',
            'up:telemetry', 'up:prometheus', 'up:node-exporter', 'up:blackbox',
            'up:caddy', 'up:application', 'up:loki', 'up:catalogue-localizer', 'up:grafana', 'smoke'])
        self.assertEqual(self.log.count('gate-open'), 1, 'The gate opens exactly once, at the reviewed reopening.')
        for probe in ('authorization', 'prometheus', 'grafana'):
            self.assertIn('probe:' + probe, self.log)

    def test_completion_records_lineage_and_changes_no_installation_evidence(self):
        before = self.first_install.read_bytes()
        self.apply()
        record = self.journal()
        self.assertEqual(record['status'], 'complete')
        self.assertEqual(record['migration']['version'], FILES[-1]['version'])
        self.assertEqual(record['backup']['backupId'], BACKUP)
        self.assertEqual(self.first_install.read_bytes(), before)
        pointer = json.loads((self.admin / upgrade.UPGRADES / upgrade.ACTIVE).read_text())
        self.assertEqual(pointer['revision'], TARGET)
        self.assertEqual(pointer['previous']['revision'], prepare.REVISION)
        self.assertEqual(pointer['configurationSha256'], install.sha(self.bundle / upgrade.TARGET_CONFIG))
        self.assertFalse((self.admin / 'first-install/recovery/PENDING.json').exists())
        self.assertEqual(json.loads((self.admin / 'first-install/recovery/READY').read_text())['kind'], 'private-upgrade')
        retained = sorted(p.name for p in self.admin.glob(f'{upgrade.UPGRADES}/{TARGET}/*.json'))
        self.assertEqual(retained, ['backup-manifest.json', 'offhost-receipt.json', 'restore-receipt.json', 'upgrade-plan.json', 'upgrade.json'])

    def assert_refused_without_change(self, **changes):
        foreign_marker = self.recovery.marker
        with self.assertRaises(RuntimeError):
            self.apply(**changes)
        self.assertFalse((self.admin / upgrade.UPGRADES / TARGET / upgrade.JOURNAL).exists())
        self.assertFalse((self.admin / upgrade.UPGRADES / upgrade.ACTIVE).exists())
        self.assertEqual(self.recovery.marker, foreign_marker)
        self.assertFalse(any(str(e).startswith(('closed', 'gate-open', 'fence', 'sql', 'up:', 'realm', 'run', 'roles')) for e in self.log), self.log)

    def test_a_gate_opened_by_thaw_refuses(self):
        self.recovery.ready, self.recovery.pending = True, False
        self.assert_refused_without_change()

    def test_a_fence_from_another_pair_or_operation_refuses(self):
        for pending in ({'phase': upgrade.FENCED_PHASE, 'backupId': 'e' * 32}, {'phase': 'quiescing', 'backupId': None},
                        {'phase': 'restoring', 'backupId': BACKUP}):
            with self.subTest(pending):
                (self.admin / 'first-install/recovery/PENDING.json').write_text(json.dumps(pending))
                self.assert_refused_without_change()

    def test_identity_maintenance_of_another_run_refuses(self):
        self.recovery.marker = '22222222-2222-4222-8222-222222222222'
        self.assert_refused_without_change()
        self.assertEqual(self.recovery.marker, '22222222-2222-4222-8222-222222222222')

    def test_changed_schema_identity_since_the_pair_refuses(self):
        self.recovery.keycloak_schema = 'other'
        self.assert_refused_without_change()

    def test_a_running_writer_refuses(self):
        self.recovery.running = b'container'
        self.assert_refused_without_change()

    def test_active_synchronization_or_localization_refuses(self):
        for name in self.work:
            with self.subTest(name):
                self.work = {'runningSynchronizations': 0, 'activeTranslationClaims': 0, name: 1}
                self.assert_refused_without_change()

    def test_missing_or_manual_backup_evidence_refuses(self):
        manual = self.base / 'manual'
        manual.mkdir()
        (manual / 'manifest.json').write_text(json.dumps({'sha256': 'x'}))
        self.assert_refused_without_change(backup=manual)
        self.assert_refused_without_change(backup=None)
        self.assert_refused_without_change(offhost=None)
        self.assert_refused_without_change(restore=None)
        local = self.base / 'local.json'
        local.write_text(json.dumps({'kind': 'encrypted-copy', 'backupId': BACKUP, 'outcome': 'success'}))
        self.assert_refused_without_change(offhost=local)

    def test_a_changed_staged_bundle_refuses(self):
        self.staged_failure = trust.Refused('changed')
        self.assert_refused_without_change()

    def test_unreviewed_plan_and_missing_reference_refuse(self):
        with self.assertRaises(trust.Refused):
            self.upgrader().apply('0' * 64, backup=self.pair, offhost=self.offhost, restore=self.restore, reference='#455')
        with self.assertRaises(trust.Refused):
            self.apply(reference='')
        self.assertFalse(any(str(e).startswith(('closed', 'gate-open', 'sql')) for e in self.log))

    def test_interruption_at_every_phase_is_contained_and_resumes_to_the_same_end(self):
        for phase in ('migration', 'migration-actor', 'reader', 'identity', 'realm-migration', 'replacing', 'reopened', 'activation', 'smoke', 'finalizing'):
            with self.subTest(phase):
                self.setUp()
                FakeUpgrade.fault = phase
                with self.assertRaises(trust.Refused):
                    self.apply()
                record = self.journal()
                recorded = {'migration-actor': 'migration', 'realm-migration': 'identity'}.get(phase, phase)
                self.assertEqual((record['status'], record['failedPhase'], record['containment']), ('failed', recorded, 'confirmed'))
                self.assertEqual(record['reason'], 'Injected interruption.')
                self.assertFalse(self.recovery.ready)
                self.assertTrue(self.recovery.pending)
                self.assertEqual(self.recovery.marker, record['runUuid'], 'The identity fence survives the attempt.')
                pending = json.loads((self.admin / 'first-install/recovery/PENDING.json').read_text())
                self.assertTrue(pending['phase'].startswith('upgrade-'), 'recovery thaw must refuse this state.')
                self.assertEqual(pending['backupId'], BACKUP)
                self.assertEqual((self.admin / upgrade.UPGRADES / upgrade.ACTIVE).exists(), phase in ('reopened', 'activation', 'smoke', 'finalizing'),
                                 'The superseded configuration is named until its containers are replaced.')
                self.log.clear()
                result = self.upgrader().resume(record['planSha256'])
                self.assertEqual(result['mode'], 'applied')
                self.assertEqual(self.journal()['status'], 'complete')
                self.assertEqual(self.journal()['attempts'], 2)
                self.assertTrue(self.recovery.ready)
                self.assertIsNone(self.recovery.marker)
                self.assertEqual(self.log.count('fence'), 1, 'Resume starts from the canonical fence.')
                self.assertEqual(self.log.count('run migration') == 1, phase in ('migration', 'migration-actor'),
                                 'Flyway runs only when its pending set is not empty.')

    def test_migration_failure_never_activates_and_leaves_roles_and_gate_closed(self):
        self.migration_failure = trust.Refused('Flyway failed.')
        with self.assertRaises(trust.Refused):
            self.apply()
        record = self.journal()
        self.assertEqual((record['status'], record['failedPhase']), ('failed', 'migration'))
        self.assertTrue(record['migrationStarted'])
        self.assertFalse(self.recovery.ready)
        self.assertIn('sql:ALTER ROLE videogame_app_migrator NOLOGIN;', self.log)
        self.assertNotIn('gate-open', self.log)
        self.assertFalse(any(e.startswith(('reader', 'up:', 'no-start', 'realm', 'roles-login:True')) for e in self.log), self.log)

    def test_cancellation_is_recorded_and_contained(self):
        def cancelled(*_, **__):
            control.cancel('session-closed')
            control.UNCERTAIN_COMMAND = True
            raise trust.Refused('Command interrupted or timed out; daemon work may remain in flight.')
        self.migration_failure = trust.Refused('x')
        self.addCleanup(setattr, control, 'UNCERTAIN_COMMAND', False)
        self.addCleanup(setattr, control, 'CAUSE', None)
        with patch.object(FakeHost, 'compose', cancelled), self.assertRaises(trust.Refused):
            self.apply()
        record = self.journal()
        self.assertEqual(record['cancellation'], 'session-closed')
        self.assertEqual(record['commandCompletion'], 'inconclusive')
        self.assertFalse(self.recovery.ready)

    def test_a_failed_flyway_row_or_live_actor_refuses_resume_without_a_retry(self):
        FakeUpgrade.fault = 'migration'
        with self.assertRaises(trust.Refused):
            self.apply()
        sha = self.journal()['planSha256']
        self.recovery.failed_row = True
        self.log.clear()
        with self.assertRaises(trust.Refused):
            self.upgrader().resume(sha)
        self.assertNotIn('run migration', self.log)
        self.assertEqual(self.journal()['status'], 'failed')
        self.assertFalse(self.recovery.ready)
        self.actor_running = b'actor'
        self.log.clear()
        with self.assertRaises(trust.Refused):
            self.upgrader().resume(sha)
        self.assertNotIn('fence', self.log)

    def test_resume_refuses_another_plan_or_no_unfinished_upgrade(self):
        with self.assertRaises(trust.Refused):
            self.upgrader().resume(self.sha())
        FakeUpgrade.fault = 'identity'
        with self.assertRaises(trust.Refused):
            self.apply()
        with self.assertRaises(trust.Refused):
            self.upgrader().resume('0' * 64)

    def test_second_apply_is_never_a_retry(self):
        FakeUpgrade.fault = 'reader'
        with self.assertRaises(trust.Refused):
            self.apply()
        with self.assertRaises(trust.Refused):
            self.apply()
        self.assertEqual(self.journal()['status'], 'failed')

    def test_identity_state_change_keeps_the_fence_closed(self):
        changed = dict(self.identity, users=[1, 'lost'])
        original = FakeUpgrade.realm_command

        def lose(upgrader, host, *arguments, **kwargs):
            self.identity = changed
            return original(upgrader, host, *arguments, **kwargs)
        with patch.object(FakeUpgrade, 'realm_command', lose), self.assertRaises(trust.Refused):
            self.apply()
        record = self.journal()
        self.assertEqual(record['failedPhase'], 'identity')
        self.assertFalse(record['reopened'])
        self.assertFalse(self.recovery.ready)
        self.assertNotIn('roles-login:True', self.log)

    def claim_killed(self):
        """A process death in the claim, after the journal but before the fence changed hands."""
        class Killed(BaseException):
            pass
        original = self.recovery.closed

        def closed(phase, backup_id=None, quiet=False):
            if phase == 'upgrade-claimed':
                raise Killed()
            original(phase, backup_id, quiet)
        with patch.object(self.recovery, 'closed', closed), self.assertRaises(Killed):
            self.apply()
        self.assertEqual(self.journal()['status'], 'running')
        self.log.clear()

    def thaw(self):
        self.recovery.ready, self.recovery.pending = True, False
        (self.admin / 'first-install/recovery/PENDING.json').unlink()

    def test_a_process_death_in_the_claim_resumes_while_the_fence_is_unbroken(self):
        self.claim_killed()
        self.assertEqual(self.upgrader().resume(self.journal()['planSha256'])['mode'], 'applied')

    def test_a_thaw_or_another_pair_after_a_killed_claim_makes_the_pair_stale(self):
        for name, breach in {'thaw': self.thaw, 'other pair': lambda: (self.admin / 'first-install/recovery/PENDING.json').write_text(
                json.dumps({'phase': upgrade.FENCED_PHASE, 'backupId': 'e' * 32})),
                'crashed backup': lambda: (self.admin / 'first-install/recovery/PENDING.json').write_text(
                    json.dumps({'phase': 'quiescing', 'backupId': None}))}.items():
            with self.subTest(name):
                self.setUp()
                self.claim_killed()
                breach()
                sha = self.journal()['planSha256']
                with self.assertRaises(trust.Refused):
                    self.upgrader().resume(sha)
                self.assertNotIn('fence', self.log)
                pending = self.admin / 'first-install/recovery/PENDING.json'
                before = pending.read_bytes() if pending.exists() else None
                result = self.upgrader().abandon()
                self.assertIn('stale', result['next'])
                self.assertEqual(self.journal()['status'], 'abandoned')
                self.assertEqual(pending.read_bytes() if pending.exists() else None, before, 'A stale fence is never recreated.')
                self.assertNotIn('fence', self.log)

    def test_optional_service_failure_is_recorded_and_never_fences_the_product(self):
        for name in upgrade.OPTIONAL:
            with self.subTest(name):
                self.setUp()
                self.start_failures = {name}
                result = self.apply()
                self.assertEqual(result['optionalDegraded'], [name])
                self.assertEqual(self.journal()['status'], 'complete')
                self.assertTrue(self.recovery.ready)
                self.assertEqual(self.log.count('fence'), 0)
                self.assertNotIn(('probe:grafana' if name == 'grafana' else 'none'), self.log)

    def test_a_required_service_failure_fences_the_runtime(self):
        for name in ('caddy', 'application', 'telemetry'):
            with self.subTest(name):
                self.setUp()
                self.start_failures = {name}
                with self.assertRaises(trust.Refused):
                    self.apply()
                self.assertEqual(self.journal()['failedPhase'], 'activation')
                self.assertFalse(self.recovery.ready)
                self.assertIsNotNone(self.recovery.marker)

    def test_cancellation_during_an_optional_service_is_not_tolerated(self):
        original = FakeUpgrade.start

        def cancelled(upgrader, host, *names, timeout=600):
            if names[0] == 'grafana':
                control.CANCEL.set()
                raise trust.Refused('Operation cancelled; controlled cleanup required.')
            return original(upgrader, host, *names, timeout=timeout)
        with patch.object(FakeUpgrade, 'start', cancelled), self.assertRaises(trust.Refused):
            self.apply()
        self.assertEqual(self.journal()['status'], 'failed')
        self.assertFalse(self.recovery.ready)

    def test_a_lost_reader_setting_fences_the_runtime(self):
        original = FakeUpgrade.smoke

        def lose(upgrader, host, record):
            self.reader = {'settings': ['statement_timeout=3s'], 'connectionLimit': 2}
            return original(upgrader, host, record)
        with patch.object(FakeUpgrade, 'smoke', lose), self.assertRaises(trust.Refused):
            self.apply()
        self.assertEqual(self.journal()['failedPhase'], 'smoke')

    def test_abandon_returns_to_the_backup_fence_only_before_anything_advanced(self):
        FakeUpgrade.fault = 'migration'
        with self.assertRaises(trust.Refused):
            self.apply()
        self.assertEqual(self.upgrader().abandon()['mode'], 'abandoned')
        self.assertEqual(json.loads((self.admin / 'first-install/recovery/PENDING.json').read_text()),
                         {'phase': upgrade.FENCED_PHASE, 'backupId': BACKUP})
        self.assertIsNone(self.recovery.marker)
        self.assertEqual(self.journal()['status'], 'abandoned')
        self.assertEqual(self.apply()['mode'], 'applied', 'The same still-closed fence can claim again.')

    def test_abandon_refuses_after_the_schema_or_identity_advanced(self):
        FakeUpgrade.fault = 'identity'
        with self.assertRaises(trust.Refused):
            self.apply()
        with self.assertRaises(trust.Refused):
            self.upgrader().abandon()
        self.assertEqual(self.journal()['status'], 'failed')
        self.setUp()
        FakeUpgrade.fault = 'reopened'
        with self.assertRaises(trust.Refused):
            self.apply()
        with self.assertRaises(trust.Refused):
            self.upgrader().abandon()

    def prepare_forward_fix(self, phase='activation'):
        FakeUpgrade.fault = phase
        with self.assertRaises(trust.Refused):
            self.apply()
        bundle = self.bundles / f'{NEWER[:12]}-private'
        (bundle / Path('candidate') / Path(upgrade.REALM_IMPORT).parent).mkdir(parents=True)
        (bundle / 'candidate' / upgrade.REALM_IMPORT).write_text((ROOT / upgrade.REALM_IMPORT).read_text())
        (bundle / upgrade.TARGET_CONFIG).write_text(upgrade.canonical(rendered(bundle)))
        self.source = bundle / 'candidate'
        self.bundle = bundle
        value = self.upgrader(NEWER, TARGET)
        value.active = value.active_state(None)
        return value

    def test_forward_fix_replaces_a_failed_target_under_the_original_recovery_point(self):
        value = self.prepare_forward_fix()
        failed = self.journal()
        self.log.clear()
        result = value.apply(value.build(None, None)['planSha256'], reference='#455 forward fix')
        self.assertEqual(result['targetRevision'], NEWER)
        record = self.journal(NEWER)
        self.assertEqual(record['status'], 'complete')
        self.assertEqual(record['predecessor'], TARGET)
        self.assertEqual(record['backup'], failed['backup'], 'The original pair stays the recovery point.')
        self.assertEqual(record['runUuid'], failed['runUuid'], 'The fence is carried, not reopened.')
        self.assertEqual(self.journal()['status'], 'superseded')
        self.assertEqual(self.journal()['supersededBy'], NEWER)
        self.assertEqual(self.log.count('fence'), 1)
        self.assertNotIn('run migration', self.log)

    def predecessor_notice_snapshot(self, target=TARGET):
        before = {'es': copy.deepcopy(NOTICE_LEGACY['localizationTexts']['es']), 'fr': {'keep': 'original'}}
        expected = copy.deepcopy(before)
        expected['es']['termsText'] = notice.notice(NOTICE_TARGET).replace(
            'https://${APPLICATION_PUBLIC_HOST}', 'https://gameometro.com')
        saved = {'targetSha256': notice.APPROVED, 'before': before, 'expected': expected, 'realmSha256': 'f' * 64}
        path = self.admin / upgrade.UPGRADES / target / 'registration-notice.json'
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(saved))
        path.chmod(0o600)
        return path

    def test_forward_fix_carries_original_notice_snapshot_after_legal_text_changed(self):
        value = self.prepare_forward_fix()
        (value.bundle / 'candidate' / upgrade.REALM_IMPORT).write_text(json.dumps(NOTICE_TARGET))
        original = self.predecessor_notice_snapshot()
        saved = original.read_bytes()
        self.identity['realmLocalizations'] = [2, 'aligned-notice']
        value.apply(value.build(None, None)['planSha256'], reference='#276 forward fix')
        inherited = value.directory / original.name
        self.assertEqual(inherited.read_bytes(), saved)
        self.assertEqual(inherited.stat().st_mode & 0o777, 0o600)
        self.assertEqual(json.loads(inherited.read_text())['before']['fr'], {'keep': 'original'})

    def test_notice_baseline_survives_repeated_forward_fixes_and_interrupted_copy(self):
        value = self.prepare_forward_fix()
        (value.bundle / 'candidate' / upgrade.REALM_IMPORT).write_text(json.dumps(NOTICE_TARGET))
        original = self.predecessor_notice_snapshot()
        predecessor = value.active['predecessor']
        value.carry_notice_snapshot(predecessor)
        value.carry_notice_snapshot(predecessor)  # Retry before the new claim uses identical bytes.
        successor = self.upgrader('c' * 40, NEWER)
        successor.bundle.mkdir(parents=True)
        import_path = successor.bundle / 'candidate' / upgrade.REALM_IMPORT
        import_path.parent.mkdir(parents=True)
        import_path.write_text(json.dumps(NOTICE_TARGET))
        successor.carry_notice_snapshot(predecessor)
        self.assertEqual((successor.directory / original.name).read_bytes(), original.read_bytes())
        (value.directory / original.name).write_text('{}')
        with self.assertRaises(trust.Refused): value.carry_notice_snapshot(predecessor)

    def test_missing_notice_baseline_after_mutation_refuses_before_superseding_predecessor(self):
        value = self.prepare_forward_fix()
        self.identity['realmLocalizations'] = [2, 'aligned-notice']
        with self.assertRaises(trust.Refused):
            value.apply(value.build(None, None)['planSha256'], reference='#276 forward fix')
        self.assertEqual(self.journal()['status'], 'failed')
        self.assertFalse((value.directory / upgrade.JOURNAL).exists())

    def test_invalid_predecessor_notice_snapshot_refuses_before_claim(self):
        value = self.prepare_forward_fix()
        for invalid in ('missing-fields', 'wrong-target', 'unexpected-text', 'symlink'):
            with self.subTest(invalid):
                path = self.predecessor_notice_snapshot()
                if invalid == 'symlink':
                    other = path.with_suffix('.saved')
                    path.rename(other)
                    path.symlink_to(other)
                else:
                    saved = json.loads(path.read_text())
                    if invalid == 'missing-fields': saved = {}
                    elif invalid == 'wrong-target': saved['targetSha256'] = '0' * 64
                    else: saved['expected']['fr']['keep'] = 'adopted drift'
                    path.write_text(json.dumps(saved))
                with self.assertRaises(trust.Refused):
                    value.apply(value.build(None, None)['planSha256'], reference='#276 forward fix')
                self.assertEqual(self.journal()['status'], 'failed')
                self.assertFalse((value.directory / upgrade.JOURNAL).exists())
                path.unlink()

    def test_forward_fix_refuses_a_stale_pair_before_any_write_was_possible(self):
        value = self.prepare_forward_fix('identity')
        (self.admin / 'first-install/recovery/PENDING.json').write_text(json.dumps({'phase': upgrade.FENCED_PHASE, 'backupId': 'e' * 32}))
        with self.assertRaises(trust.Refused):
            value.apply(value.build(None, None)['planSha256'], reference='#455 forward fix')
        self.assertEqual(self.journal()['status'], 'failed')

    def test_forward_fix_is_only_for_an_interrupted_target_past_its_migration_start(self):
        FakeUpgrade.fault = 'migration'
        with self.assertRaises(trust.Refused):
            self.apply()
        self.assertFalse(self.journal()['migrationStarted'])
        value = self.upgrader(NEWER, TARGET)
        with self.assertRaises(trust.Refused):
            value.active_state(None)
        record = self.journal()
        record['status'] = 'complete'
        record['migrationStarted'] = True
        control.durable(self.admin / upgrade.UPGRADES / TARGET / upgrade.JOURNAL, record)
        with self.assertRaises(trust.Refused):
            value.active_state(None)

    def test_an_unfinished_upgrade_blocks_any_other_target(self):
        FakeUpgrade.fault = 'reader'
        with self.assertRaises(trust.Refused):
            self.apply()
        bundle = self.bundles / f'{NEWER[:12]}-private'
        bundle.mkdir()
        (bundle / upgrade.TARGET_CONFIG).write_text(upgrade.canonical(rendered(bundle)))
        value = self.upgrader(NEWER)
        with self.assertRaises(trust.Refused):
            value.apply(value.build(None, None)['planSha256'], backup=self.pair, offhost=self.offhost, restore=self.restore, reference='#455')

    def test_inspect_states_the_forward_recovery_boundary(self):
        self.assertEqual(upgrade.inspect(self.upgrader())['status'], 'not-started')
        FakeUpgrade.fault = 'migration'
        with self.assertRaises(trust.Refused):
            self.apply()
        self.assertEqual(upgrade.inspect(self.upgrader())['advice'], upgrade.ADVICE['before-schema'])
        self.setUp()
        FakeUpgrade.fault = 'identity'
        with self.assertRaises(trust.Refused):
            self.apply()
        result = upgrade.inspect(self.upgrader())
        self.assertEqual(result['advice'], upgrade.ADVICE['after-schema'])
        self.assertIn('Never an older image', result['advice'])
        self.assertEqual((result['status'], result['phase'], result['gate']['ready']), ('failed', 'identity', False))

    def test_acceptance_defers_to_an_upgrade_that_owns_the_runtime(self):
        self.assertFalse(acceptance.upgrade_owns_runtime(self.admin))
        FakeUpgrade.fault = 'reader'
        with self.assertRaises(trust.Refused):
            self.apply()
        self.assertTrue(acceptance.upgrade_owns_runtime(self.admin))
        self.log.clear()
        self.upgrader().resume(self.journal()['planSha256'])
        self.assertTrue(acceptance.upgrade_owns_runtime(self.admin))


class Provenance(unittest.TestCase):
    """The first real pair, rendered exactly as a host renders it. No Docker daemon is needed."""

    def render(self, revision, base, bundle):
        source = bundle / 'candidate'
        source.mkdir(parents=True)
        prepare.export_source(ROOT, revision, source)
        values = prepare.parse_runtime_env((source / 'deploy/public-production/runtime.env.example').read_text())
        values['APPLICATION_VERSION'] = revision[:7]
        environment = bundle / 'runtime.env'
        environment.write_text(''.join(f'{k}={v}\n' for k, v in sorted(values.items())))
        rendered_base = prepare.render_base(environment, source)
        prepare.rebind_source(rendered_base, source, bundle)
        (bundle / 'Caddyfile.private').write_text(prepare.private_caddy((source / 'deploy/public-production/caddy/Caddyfile').read_text()))
        return prepare.adapt(rendered_base, bundle / 'Caddyfile.private', install.TLS)

    def test_the_first_candidate_to_46e7252_changes_only_the_reviewed_fields(self):
        target = FIRST_TARGET
        for revision in (prepare.REVISION, target):
            if subprocess.run(['git', 'cat-file', '-e', revision + '^{commit}'], cwd=ROOT, capture_output=True).returncode:
                self.skipTest('The first upgrade pair is not available in this checkout.')
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            operations = root / 'operations'
            (operations / Path(acceptance.DASHBOARD).parent).mkdir(parents=True)
            old, new = root / '9ffc05a-private', root / '46e7252e9980-private'
            installed = self.render(prepare.REVISION, 'old', old)
            (operations / acceptance.DASHBOARD).write_bytes(
                subprocess.check_output(['git', 'show', f'{target}:{acceptance.DASHBOARD}'], cwd=ROOT))
            for service in installed['services'].values():
                service.setdefault('labels', {}).update({control.RUN_LABEL: RUN, control.CONFIG_LABEL: CONFIGURATION})
            accepted = acceptance.accepted_configuration(installed, operations)
            acceptance.validate_accepted(installed, accepted, operations)
            target_config = self.render(target, 'new', new)
            for name_, service in target_config['services'].items():
                service['restart'] = 'no' if name_ in upgrade.ONE_SHOT else acceptance.POLICY
            with patch.object(upgrade, 'BUNDLES', root):
                result, changed = upgrade.reconcile(accepted, target_config, new)
                images = {n: result['services'][n]['image'] for n in ('application', 'keycloak', 'caddy', 'catalogue-localizer')}
                upgrade.validate_runtime(result, images)
            # Image placeholders are equal in this render. Keycloak's bound realm import changed, which an existing realm ignores.
            self.assertEqual(changed, {'application': ['environment'], 'grafana': ['volumes'], 'keycloak': ['bound-files']})
            self.assertEqual(upgrade.environment_delta(accepted['services']['application'], result['services']['application']),
                             [upgrade.VERSION_ENVIRONMENT])
            for section in ('networks', 'secrets', 'volumes', 'name'):
                self.assertEqual(result[section], accepted[section])
            for service_name, service in result['services'].items():
                current = accepted['services'][service_name]
                allowed = {'volumes', 'environment', 'labels'} if service_name in changed else set()
                for field in set(service) | set(current):
                    if field not in allowed:
                        self.assertEqual(service.get(field), current.get(field), (service_name, field))
                self.assertEqual(service['labels'][control.RUN_LABEL], RUN)
            grafana = {m['target']: m for m in result['services']['grafana']['volumes']}
            self.assertNotIn(acceptance.DASHBOARD_TARGET, grafana, 'The accepted override is replaced by the target tree.')
            self.assertEqual(grafana['/etc/grafana/canonical-dashboards']['source'],
                             str(new / 'candidate/deploy/private-dev/grafana/dashboards'))
            self.assertEqual((new / 'candidate' / acceptance.DASHBOARD).read_bytes(), (operations / acceptance.DASHBOARD).read_bytes())


class ClaimedOperator(unittest.TestCase):
    RECORD = {'operationalRevision': '9' * 40, 'adapterFingerprint': '8' * 64}

    def upgrader(self, revision='9' * 40, adapter='8' * 64):
        value = upgrade.Upgrade(TARGET, operational=revision, adapter=adapter)
        value.github = lambda: self.fail('The claimed tool must resume without the network.')
        return value

    def test_the_claimed_tool_resumes_offline_and_edited_bytes_refuse(self):
        with patch.object(install, 'operational_fingerprint', return_value='8' * 64):
            self.upgrader().verify_claimed_operator(dict(self.RECORD))
        with patch.object(install, 'operational_fingerprint', return_value='7' * 64), self.assertRaises(trust.Refused):
            self.upgrader().verify_claimed_operator(dict(self.RECORD))

    def test_a_newer_tool_must_be_published_and_is_recorded(self):
        record = dict(self.RECORD)
        value = self.upgrader('6' * 40, '5' * 64)
        value.github = lambda: 'api'
        with patch.object(install, 'operational_fingerprint', return_value='5' * 64), \
                patch.object(install, 'verify_operational', side_effect=trust.Refused('Unpublished')), self.assertRaises(trust.Refused):
            value.verify_claimed_operator(record)
        self.assertEqual(record, self.RECORD)
        with patch.object(install, 'operational_fingerprint', return_value='5' * 64), patch.object(install, 'verify_operational') as verified:
            value.verify_claimed_operator(record)
        verified.assert_called_once_with('api', '6' * 40, '5' * 64)
        self.assertEqual(record, {'operationalRevision': '6' * 40, 'adapterFingerprint': '5' * 64})


class Inputs(unittest.TestCase):
    def owner(self, **fields):
        return type('O', (), {'evidence': {'runId': RUN, 'configuration': CONFIGURATION}, 'recovery': None, **fields})()

    def test_host_checks_require_a_synchronized_clock_and_a_reviewed_docker_pair(self):
        def runner(clock=b'yes\n', engine='29.9.2', compose=b'5.6.1\n'):
            def run(args, **_):
                if args[0] == 'timedatectl':
                    return clock
                return json.dumps({'ServerVersion': engine}).encode() if 'info' in args else compose
            return run
        self.assertEqual(upgrade.Upgrade(TARGET, runner=runner()).host_checks()['engine'], '29.9.2')
        for name, fake in {'clock': runner(clock=b'no\n'), 'engine': runner(engine='29.9.3'), 'compose': runner(compose=b'5.7.0\n')}.items():
            with self.subTest(name), self.assertRaises(trust.Refused):
                upgrade.Upgrade(TARGET, runner=fake).host_checks()

    def test_the_administrator_probe_answers_only_when_keycloak_can_prove_it(self):
        with tempfile.TemporaryDirectory() as name:
            secret = Path(name) / 'admin'
            secret.write_text('synthetic')
            host = type('H', (), {'config': {'secrets': {'keycloak_admin_password': {'file': str(secret)}}, 'networks': {'identity-data': {'name': 'n'}},
                                             'services': {'keycloak': {'environment': {'KC_BOOTSTRAP_ADMIN_USERNAME': 'admin'}}}}})()
            seen = []

            def accepted(args, *, data=None, **_):
                seen.append(data)
                self.assertNotIn(b'synthetic', ' '.join(args).encode(), 'The credential crosses stdin only.')
                return b''

            def refused(args, **_):
                raise trust.Refused('Denied')
            self.assertIsNone(upgrade.Upgrade(TARGET, runner=accepted).administrator_authenticates(host, {'keycloak': 'stopped'}))
            self.assertEqual(seen, [])
            self.assertIs(upgrade.Upgrade(TARGET, runner=accepted).administrator_authenticates(host, {'keycloak': 'running'}), True)
            self.assertEqual(seen, [b'synthetic'])
            self.assertIs(upgrade.Upgrade(TARGET, runner=refused).administrator_authenticates(host, {'keycloak': 'running'}), False)

    def test_the_accepted_dashboard_is_never_replaced_by_an_older_copy(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            override = root / 'override.json'
            source = root / 'candidate'
            (source / Path(acceptance.DASHBOARD).parent).mkdir(parents=True)
            (source / acceptance.DASHBOARD).write_text('same')
            override.write_text('same')
            control.durable(root / acceptance.RECORD, {'operationalRevision': NEWER})
            value = upgrade.Upgrade(TARGET, admin=root)
            value.active = {'config': {'services': {'grafana': {'volumes': [bind(override, acceptance.DASHBOARD_TARGET)]}}}}

            class Api:
                status = 'ahead'

                def request(self, path):
                    return {'status': self.status}
            api = Api()
            self.assertEqual(value.accepted_dashboard(api, source), 'identical')
            override.write_text('newer')
            self.assertEqual(value.accepted_dashboard(api, source), 'superseded-by-a-newer-target')
            api.status = 'behind'
            with self.assertRaises(trust.Refused):
                value.accepted_dashboard(api, source)
            value.active = {'config': {'services': {'grafana': {'volumes': []}}}}
            self.assertEqual(value.accepted_dashboard(api, source), 'carried-by-the-active-tree')

    def test_the_installer_input_contract_runs_against_the_target_truststore_and_scratch_binds(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            tls = root / 'tls'
            tls.mkdir()
            for file in ('ca.crt', 'edge.crt', 'edge.key', 'truststore.p12'):
                (tls / file).write_text('public')
            model = Path('/var/lib/gameometro/production/catalogue-models/tcbig-initial')
            scratch = root / 'scratch'
            scratch.mkdir()
            (scratch / 'edge').write_text('x')
            config = {'services': {
                'application': {'image': 'target', 'group_add': ['20101'], 'secrets': [{'source': 'app'}]},
                'caddy': {'volumes': [bind(root / 'bundle/edge', '/edge')]},
                'catalogue-localizer': {'volumes': [bind(model, '/model')]},
                'node-exporter': {'volumes': [bind(root / 'metrics', '/textfile')]}},
                'secrets': {'app': {'file': '/etc/gameometro/production/secrets/application-db-password'}}}
            (root / 'metrics').mkdir()
            host = upgrade.Host(self.owner(), config, Path('/c'), root, scope=[])
            host.images = {'application': {'user': '10001'}}
            host.local = lambda path: scratch / path.name if path.name == 'edge' else path
            original_read, original_exists, original_iterdir, original_resolve = Path.read_bytes, Path.exists, Path.iterdir, Path.resolve
            certificate = b'-----BEGIN CERTIFICATE-----\nx\n-----END CERTIFICATE-----\n'
            fake = lambda args, **_: b'public' if 'pkey' in args or '-pubkey' in args else certificate if '-nokeys' in args else b''
            info = type('R', (), {'returncode': 0, 'stderr': b''})()
            with contextlib.ExitStack() as scope:
                scope.enter_context(patch.object(install, 'TLS', tls))
                scope.enter_context(patch.object(install, 'ADMIN', root))
                scope.enter_context(patch.object(install, 'secure', side_effect=lambda path, **_: type('S', (), {'st_size': 9, 'st_mode': 0o755})()))
                scope.enter_context(patch.object(install, 'run', side_effect=fake))
                scope.enter_context(patch.object(subprocess, 'run', return_value=info))
                scope.enter_context(patch.object(Path, 'read_bytes', lambda path: b'synthetic' if path.name == 'application-db-password' else original_read(path)))
                scope.enter_context(patch.object(Path, 'exists', lambda path: path == model or original_exists(path)))
                scope.enter_context(patch.object(Path, 'iterdir', lambda path: iter(()) if path == model else original_iterdir(path)))
                scope.enter_context(patch.object(Path, 'resolve', lambda path, strict=False: path if path == model else original_resolve(path)))
                scope.enter_context(patch.object(install.integration, 'verify_model'))
                scope.enter_context(patch.object(install, 'ssl', type('S', (), {'PEM_cert_to_DER_cert': staticmethod(lambda p: b'')})))
                with patch.object(upgrade.Host, 'verify_truststore') as store:
                    host.inputs(recovery=True)
                store.assert_called_once_with(tls / 'truststore.p12')
                host.local = lambda path: path
                with patch.object(upgrade.Host, 'verify_truststore'), self.assertRaises(trust.Refused):
                    host.inputs(recovery=True)

    def test_a_plan_reviews_binds_through_its_scratch_copy(self):
        owner = type('O', (), {'evidence': {'runId': RUN, 'configuration': CONFIGURATION}, 'recovery': None})()
        host = upgrade.Host(owner, {}, Path('/c'), Path('/s'), scope=[])
        host.local = lambda path: Path('/scratch') / path.name
        self.assertEqual(host.bind_path(Path('/bundle/file')), Path('/scratch/file'))
        self.assertEqual(install.Host('a', 'b', 'c').bind_path(Path('/bundle/file')), Path('/bundle/file'))

    def test_truststore_must_hold_the_target_jvm_anchors_and_the_private_ca(self):
        owner = type('O', (), {'evidence': {'runId': RUN, 'configuration': CONFIGURATION}, 'recovery': None,
                               'local_images': staticmethod(lambda images: {'application': True})})()
        host = upgrade.Host(owner, {'services': {'application': {'image': 'i'}}}, Path('/c'), Path('/s'), scope=[])
        store = Path('/store')
        with tempfile.TemporaryDirectory() as name, patch.object(install, 'TLS', Path(name)), \
                patch.object(upgrade.stores, 'jvm_entries', return_value=['jvm']), patch.object(upgrade, 'run', return_value=b''):
            (Path(name) / 'ca.crt').write_text('ca')
            with patch.object(upgrade.stores, 'certificates', side_effect=[['jvm', 'ca'], ['ca']]):
                host.verify_truststore(store)
            with patch.object(upgrade.stores, 'certificates', side_effect=[['ca'], ['ca']]), self.assertRaises(trust.Refused):
                host.verify_truststore(store)
            with patch.object(upgrade.stores, 'certificates', side_effect=[['jvm'], ['ca']]), self.assertRaises(trust.Refused):
                host.verify_truststore(store)

    def test_truststore_waits_for_a_local_target_image_without_pulling(self):
        owner = type('O', (), {'evidence': {'runId': RUN, 'configuration': CONFIGURATION}, 'recovery': None,
                               'local_images': staticmethod(lambda images: {'application': False})})()
        host = upgrade.Host(owner, {'services': {'application': {'image': 'i'}}}, Path('/c'), Path('/s'), scope=[])
        with patch.object(upgrade, 'run', side_effect=AssertionError('No container may start.')):
            host.verify_truststore(Path('/store'))


class Cli(unittest.TestCase):
    def test_no_force_or_rollback_mode_exists(self):
        script = Path(upgrade.__file__)
        for option in ('--force', '--rollback', '--reset', '--downgrade', '--skip-backup', '--no-backup'):
            result = subprocess.run(['python3', str(script), '--target-revision', TARGET, option], capture_output=True, timeout=30)
            self.assertEqual(result.returncode, 2)
            self.assertIn(b'unrecognized arguments', result.stderr)

    def test_non_root_invocation_refuses_before_any_host_access(self):
        result = subprocess.run(['python3', str(Path(upgrade.__file__)), '--target-revision', TARGET], capture_output=True, timeout=30)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(json.loads(result.stdout)['outcome'], 'refused-or-failed')

    def test_each_mode_reaches_only_its_own_operation_and_failures_stay_sanitized(self):
        modes = {(): 'plan', ('--stage',): 'stage', ('--apply',): 'apply', ('--resume',): 'resume', ('--abandon',): 'abandon', ('--verify',): 'verify'}
        for flags, method in modes.items():
            with self.subTest(method), patch.object(upgrade.Upgrade, 'load', autospec=True, side_effect=lambda value: value), \
                    contextlib.ExitStack() as scope:
                for name in ('plan', 'stage', 'apply', 'resume', 'abandon', 'verify'):
                    scope.enter_context(patch.object(upgrade.Upgrade, name, return_value={'mode': name}))
                scope.enter_context(patch.object(upgrade.os, 'geteuid', return_value=0))
                scope.enter_context(patch.object(upgrade.integration, 'verify_local_docker'))
                scope.enter_context(patch.object(upgrade.install, 'secure'))
                scope.enter_context(patch.object(upgrade.control, 'signal_handlers'))
                watch = scope.enter_context(patch.object(upgrade.control, 'watch_session'))
                scope.enter_context(patch.object(sys, 'argv', ['upgrade', '--target-revision', TARGET, '--plan-sha256', 'h', *flags]))
                printed = io.StringIO()
                with contextlib.redirect_stdout(printed):
                    self.assertEqual(upgrade.main(), 0)
                self.assertEqual(json.loads(printed.getvalue())['mode'], method)
                called = [n for n in modes.values() if getattr(upgrade.Upgrade, n).called]
                self.assertEqual(called, [method])
                self.assertEqual(watch.called, method in ('apply', 'resume', 'abandon'), 'Session loss must cancel only the mutating runs.')
        with patch.object(upgrade.os, 'geteuid', return_value=0), patch.object(upgrade.integration, 'verify_local_docker'), \
                patch.object(upgrade.install, 'secure'), patch.object(upgrade.control, 'signal_handlers'), \
                patch.object(upgrade.Upgrade, 'load', side_effect=OSError('/secret/path raw detail')), \
                patch.object(sys, 'argv', ['upgrade', '--target-revision', TARGET]):
            printed = io.StringIO()
            with contextlib.redirect_stdout(printed):
                self.assertEqual(upgrade.main(), 1)
            self.assertEqual(json.loads(printed.getvalue())['reason'], 'inspect-locally')

    def test_destructive_commands_are_absent_from_the_tool(self):
        tree = ast.parse(Path(upgrade.__file__).read_text())
        literals = {n.value for n in ast.walk(tree) if isinstance(n, ast.Constant) and isinstance(n.value, str)}
        self.assertFalse(literals & {'down', 'rm', 'prune', 'kill', 'repair', 'clean', 'undo', '--force-recreate', '--volumes', '-v --force'})
        source = Path(upgrade.__file__).read_text()
        self.assertNotIn('pg_restore', source)
        self.assertNotIn('dropdb', source)
        self.assertNotIn('DROP ', source)

    def test_the_tool_never_writes_installation_evidence(self):
        source = Path(upgrade.__file__).read_text()
        self.assertNotIn("'first-install/runtime.json'", source)
        self.assertNotRegex(source, r"durable\([^\n]*first-install")

    def test_tool_is_bound_by_the_operational_fingerprint(self):
        self.assertIn('scripts/private_production_upgrade.py', install.OPERATIONAL_FILES)
        self.assertFalse((ROOT / 'scripts/private_production_upgrade.py').stat().st_mode & 0o111)

    def test_target_selection_is_exact_never_latest(self):
        for revision in ('latest', 'main', 'A' * 40, 'a' * 39, ''):
            with self.assertRaises(trust.Refused):
                upgrade.Upgrade(revision)


import production_registration_notice as notice
import private_production_upgrade as upgrade
import private_dev_promotion as trust

ELIGIBILITY_TARGET = json.loads((notice.ROOT / notice.IMPORT).read_text())
NOTICE_TARGET = copy.deepcopy(ELIGIBILITY_TARGET)
old_copy = json.loads((notice.ROOT / 'scripts/fixtures/private-dev-identity/legacy-videogame-platform-realm.json').read_text())
for key in ('termsText', *notice.LABEL_HASHES):
    NOTICE_TARGET['localizationTexts']['es'][key] = old_copy['localizationTexts']['es'][key].replace(
        '${APPLICATION_PUBLIC_ORIGIN}', notice.HOST_TEMPLATE)
NOTICE_LEGACY = copy.deepcopy(NOTICE_TARGET)
NOTICE_LEGACY['localizationTexts']['es']['termsText'] = '<p>Gameómetro es un servicio gratuito y sin fines comerciales para personas adultas. Para crear tu cuenta solo te pedimos tu email.</p>'


class NoticeRealm:
    def __init__(self):
        self.texts = {'es': copy.deepcopy(NOTICE_LEGACY['localizationTexts']['es']), 'fr': {'other': 'preserved'}}
        self.state = {'realm': 'gameometro', 'registrationAllowed': True, 'smtpServer': {'host': 'preserved'}}
        self.writes = 0
        self.fail = False
        self.drift = False

    def representation(self): return copy.deepcopy(self.state)

    def get(self, path):
        if path == '/localization': return list(self.texts)
        return copy.deepcopy(self.texts[path.split('/')[2].split('?')[0]])

    def set_localization(self, key, value):
        self.writes += 1
        self.texts['es'][key] = value
        if self.drift: self.texts['fr']['other'] = 'unexpected'
        if self.fail: raise RuntimeError('lost response after durable write')


class RegistrationNoticeMigration(unittest.TestCase):
    def test_metadata_reads_are_bounded_before_json_decoding(self):
        from types import SimpleNamespace
        from unittest.mock import MagicMock
        response = MagicMock()
        response.__enter__.return_value = response
        response.status = 200
        response.read.return_value = b'x' * (notice.MAX_BYTES + 1)
        admin = SimpleNamespace(origin='http://keycloak:8080', access_token='synthetic', opener=MagicMock())
        admin.opener.open.return_value = response
        realm = notice.BoundedRealm(SimpleNamespace(admin=admin, admin_path='/admin/realms/gameometro'))
        with self.assertRaisesRegex(ValueError, 'exceeds its bound'):
            realm.get('/localization/es')
        response.read.assert_called_once_with(notice.MAX_BYTES + 1)
        response.read.return_value = b'{"realm":"gameometro"}'
        self.assertEqual(realm.representation(), {'realm': 'gameometro'})
        response.read.return_value = b'{"realm":"other"}'
        with self.assertRaisesRegex(ValueError, 'Invalid realm'):
            realm.representation()

    def test_plan_requires_the_matching_actor_in_target_before_any_live_query(self):
        with tempfile.TemporaryDirectory() as directory:
            previous, target = Path(directory) / 'previous', Path(directory) / 'target'
            for source, content in ((previous, NOTICE_LEGACY), (target, NOTICE_TARGET)):
                path = source / upgrade.REALM_IMPORT
                path.parent.mkdir(parents=True)
                path.write_text(json.dumps(content))
            operator = upgrade.Upgrade('a' * 40)
            operator.active = {'source': previous}
            actor = target / upgrade.NOTICE_COMMAND
            with patch.object(operator, 'query') as query:
                with self.assertRaises(trust.Refused): operator.realm_plan(target)
                query.assert_not_called()
                actor.parent.mkdir(parents=True)
                actor.write_text('incompatible actor')
                with self.assertRaises(trust.Refused): operator.realm_plan(target)
                query.assert_not_called()
                actor.write_bytes(Path(notice.__file__).read_bytes())
                query.return_value = upgrade.desired_session_state(NOTICE_TARGET)
                self.assertEqual(operator.realm_plan(target)['registrationNotice']['targetSha256'], notice.APPROVED)

    def test_plan_accepts_only_exact_approved_notice_delta(self):
        upgrade.realm_import_delta(NOTICE_LEGACY, NOTICE_TARGET)
        for locale, key in (('es', 'termsText'), ('es', 'acceptTerms')):
            changed = copy.deepcopy(NOTICE_TARGET)
            changed['localizationTexts'][locale][key] = 'unreviewed'
            with self.assertRaises(trust.Refused): upgrade.realm_import_delta(NOTICE_LEGACY, changed)
        self.assertEqual(NOTICE_LEGACY['localizationTexts']['es']['termsText'], notice.notice(NOTICE_LEGACY))

    def test_eligibility_upgrade_accepts_only_reviewed_spanish_copy(self):
        upgrade.realm_import_delta(NOTICE_TARGET, ELIGIBILITY_TARGET)
        upgrade.realm_import_delta(NOTICE_LEGACY, ELIGIBILITY_TARGET)
        for key in ('termsText', *notice.LABEL_HASHES):
            changed = copy.deepcopy(ELIGIBILITY_TARGET)
            changed['localizationTexts']['es'][key] = 'unreviewed'
            with self.assertRaises(trust.Refused):
                upgrade.realm_import_delta(NOTICE_TARGET, changed)

    def test_eligibility_upgrade_resumes_after_first_key_is_written(self):
        realm = NoticeRealm()
        realm.texts['es']['termsText'] = notice.notice(NOTICE_TARGET).replace(
            notice.HOST_TEMPLATE, notice.PUBLIC_ORIGIN)
        expected = notice.expected_localizations(realm.texts, ELIGIBILITY_TARGET, live=True)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'snapshot.json'
            realm.fail = True
            with self.assertRaises(RuntimeError):
                notice.align(realm, ELIGIBILITY_TARGET, path)
            snapshot = path.read_bytes()
            realm.fail = False
            notice.align(realm, ELIGIBILITY_TARGET, path)
            notice.align(realm, ELIGIBILITY_TARGET, path, check=True)
            self.assertEqual(realm.texts, expected)
            self.assertEqual(realm.writes, 3)
            self.assertEqual(path.read_bytes(), snapshot)

    def test_apply_check_and_repeat_preserve_every_other_locale_and_realm(self):
        realm = NoticeRealm()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'snapshot.json'
            notice.align(realm, NOTICE_TARGET, path)
            saved = path.read_bytes()
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            notice.align(realm, NOTICE_TARGET, path, check=True)
            notice.align(realm, NOTICE_TARGET, path)
            self.assertEqual(path.read_bytes(), saved)
            self.assertEqual(realm.writes, 1)
            self.assertEqual(realm.texts['fr'], {'other': 'preserved'})
            self.assertEqual(realm.texts['es']['acceptTerms'], NOTICE_TARGET['localizationTexts']['es']['acceptTerms'])

    def test_check_old_notice_does_not_snapshot_or_write(self):
        realm = NoticeRealm()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'snapshot.json'
            with self.assertRaises(ValueError): notice.align(realm, NOTICE_TARGET, path, check=True)
            self.assertFalse(path.exists())
            self.assertEqual(realm.writes, 0)

    def test_lost_acknowledgement_resumes_without_losing_snapshot(self):
        realm = NoticeRealm()
        realm.fail = True
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'snapshot.json'
            with self.assertRaises(RuntimeError): notice.align(realm, NOTICE_TARGET, path)
            saved = path.read_bytes()
            realm.fail = False
            notice.align(realm, NOTICE_TARGET, path)
            self.assertEqual(path.read_bytes(), saved)
            self.assertEqual(realm.writes, 1)

    def test_other_locale_or_realm_drift_refuses_and_cannot_be_adopted_on_retry(self):
        for drift in ('locale', 'realm'):
            realm = NoticeRealm()
            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / 'snapshot.json'
                notice.align(realm, NOTICE_TARGET, path)
                if drift == 'locale': realm.texts['fr']['other'] = 'unexpected'
                else: realm.state['registrationAllowed'] = False
                with self.assertRaises(ValueError): notice.align(realm, NOTICE_TARGET, path)
                self.assertEqual(realm.writes, 1)

    def test_unknown_live_notice_and_write_side_effects_refuse(self):
        realm = NoticeRealm()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'snapshot.json'
            realm.texts['es']['termsText'] = 'custom'
            with self.assertRaises(ValueError): notice.align(realm, NOTICE_TARGET, path)
            self.assertEqual(realm.writes, 0)
            self.assertFalse(path.exists())
            realm = NoticeRealm()
            realm.drift = True
            with self.assertRaises(ValueError): notice.align(realm, NOTICE_TARGET, path)

    def test_session_alignment_is_independently_verified_without_resetting_notice_snapshot(self):
        self.assertEqual(notice.SESSION_SETTINGS, upgrade.SESSION_SETTINGS)
        realm = NoticeRealm()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'snapshot.json'
            notice.align(realm, NOTICE_TARGET, path)
            saved = path.read_bytes()
            realm.state['rememberMe'] = True
            notice.align(realm, NOTICE_TARGET, path, check=True)
            self.assertEqual(path.read_bytes(), saved)

    def test_notice_cli_addresses_gameometro_instead_of_private_dev_default(self):
        from types import SimpleNamespace
        from unittest.mock import Mock
        identity = SimpleNamespace(provisioning=SimpleNamespace(KeycloakAdmin=Mock()), Realm=Mock())
        with tempfile.TemporaryDirectory() as directory:
            password = Path(directory) / 'password'
            password.write_text('synthetic-password')
            argv = ['notice', '--origin', 'http://keycloak:8080', '--admin-username', 'synthetic',
                    '--admin-password-file', str(password), '--check']
            with patch.object(sys, 'argv', argv), \
                 patch.object(notice.importlib.machinery, 'SourceFileLoader'), \
                 patch.object(notice.importlib.util, 'spec_from_loader'), \
                 patch.object(notice.importlib.util, 'module_from_spec', return_value=identity), \
                 patch.object(notice, 'align') as align:
                notice.main()
            identity.Realm.assert_called_once_with(identity.provisioning.KeycloakAdmin.return_value, 'gameometro')
            self.assertTrue(align.call_args.args[-1])

    def test_locale_bound_refuses_before_any_write(self):
        realm = NoticeRealm()
        realm.texts.update({f'x{i}': {} for i in range(notice.MAX_LOCALES)})
        with tempfile.TemporaryDirectory() as directory, self.assertRaises(ValueError):
            notice.align(realm, NOTICE_TARGET, Path(directory) / 'snapshot.json')
        self.assertEqual(realm.writes, 0)




if __name__ == '__main__':
    unittest.main()
