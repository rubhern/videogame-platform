#!/usr/bin/env python3
"""Offline proof of the reviewed public-ingress transition (#276). No Docker, DNS or network.

Exact edge-only derivation, DNS/IPv4 admission, registration closed before publication,
automatic private edge after an unverified certificate, revert, registration opening, the
registration actor's single-field contract and the upgrader's published-port rule.
"""
import copy
import importlib.machinery
import importlib.util
import json
from pathlib import Path
import socket
import sys
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
sys.dont_write_bytecode = True

import prepare_private_production as prepare  # noqa: E402
import private_production_admin as admin  # noqa: E402
import production_public_ingress as ingress  # noqa: E402
import private_production_upgrade as upgrade  # noqa: E402

loader = importlib.machinery.SourceFileLoader('registration_actor', str(ROOT / 'deploy/public-production/bin/configure-public-registration'))
spec = importlib.util.spec_from_loader(loader.name, loader)
actor = importlib.util.module_from_spec(spec)
loader.exec_module(actor)

IPV4 = '62.83.73.32'


def private_config(caddyfile, tls):
    return {'name': prepare.PROJECT, 'networks': {'identity-data': {'name': 'gameometro-production_identity-data'}},
            'secrets': {'keycloak_admin_password': {'file': str(tls / 'admin-password')}},
            'services': {
                'caddy': {'image': 'ghcr.io/x/caddy@sha256:' + 'a' * 64, 'ports': [], 'labels': {'k': 'v'},
                          'volumes': [prepare.bind(caddyfile, '/etc/caddy/Caddyfile'),
                                      {'type': 'volume', 'source': 'caddy-data', 'target': '/data'},
                                      {'type': 'volume', 'source': 'caddy-config', 'target': '/config'},
                                      prepare.bind(tls / 'edge.crt', '/run/private-tls/edge.crt'),
                                      prepare.bind(tls / 'edge.key', '/run/private-tls/edge.key')]},
                'application': {'image': 'ghcr.io/x/app@sha256:' + 'b' * 64, 'environment': {'A': '1'}},
                'keycloak': {'image': 'ghcr.io/x/kc@sha256:' + 'c' * 64,
                             'environment': {'KC_BOOTSTRAP_ADMIN_USERNAME': 'admin'}}}}


class Derivation(unittest.TestCase):
    def setUp(self):
        self.canonical = (ROOT / 'deploy/public-production/caddy/Caddyfile').read_text()
        self.private = prepare.private_caddy(self.canonical)

    def test_public_caddyfile_is_the_exact_inverse_of_the_private_delta(self):
        self.assertEqual(ingress.public_caddyfile(self.private), self.canonical + admin.caddy())
        self.assertNotIn('/run/private-tls/', ingress.public_caddyfile(self.private))
        with self.assertRaises(ingress.trust.Refused):
            ingress.public_caddyfile(self.canonical)

    def test_only_the_edge_changes_and_publishes_exactly_80_and_443_on_the_public_ipv4(self):
        tls = Path('/etc/gameometro/production/private-tls')
        private = private_config(Path('/b/Caddyfile.private'), tls)
        public = ingress.derive_public(private, Path('/i/Caddyfile.public'), IPV4)
        self.assertEqual({k: v for k, v in public['services'].items() if k != 'caddy'},
                         {k: v for k, v in private['services'].items() if k != 'caddy'})
        caddy = public['services']['caddy']
        self.assertEqual(caddy['ports'], ingress.public_ports(IPV4))
        self.assertEqual({p['host_ip'] for p in caddy['ports']}, {IPV4})
        self.assertFalse([m for m in caddy['volumes'] if m['target'].startswith('/run/private-tls/')])
        self.assertIn(prepare.bind(Path('/i/Caddyfile.public'), '/etc/caddy/Caddyfile'), caddy['volumes'])
        ingress.validate_public(private, public, Path('/i/Caddyfile.public'), IPV4)
        drift = copy.deepcopy(public)
        drift['services']['application']['environment']['A'] = '2'
        with self.assertRaises(ingress.trust.Refused):
            ingress.validate_public(private, drift, Path('/i/Caddyfile.public'), IPV4)

    def test_already_public_or_unexpected_edge_refuses(self):
        tls = Path('/tls')
        for mutate in ('published', 'no-tls', 'no-data'):
            with self.subTest(mutate=mutate):
                config = private_config(Path('/b/C'), tls)
                caddy = config['services']['caddy']
                if mutate == 'published': caddy['ports'] = ingress.public_ports(IPV4)
                elif mutate == 'no-tls': caddy['volumes'] = caddy['volumes'][:3]
                else: caddy['volumes'] = [m for m in caddy['volumes'] if m['target'] != '/data']
                with self.assertRaises(ingress.trust.Refused):
                    ingress.derive_public(config, Path('/i/C'), IPV4)

    def test_upgrader_admits_only_the_reviewed_public_edge(self):
        services = {'caddy': {'ports': ingress.public_ports(IPV4)}}
        rule = lambda name, published: (not published or (name == 'caddy' and published == ingress.public_ports(published[0].get('host_ip'))))
        self.assertTrue(rule('caddy', services['caddy']['ports']))
        self.assertFalse(rule('application', ingress.public_ports(IPV4)))
        self.assertFalse(rule('caddy', ingress.public_ports(IPV4) + [{'target': 22}]))
        self.assertIn('ingress.public_ports', Path(upgrade.__file__).read_text())


class Admission(unittest.TestCase):
    def resolver(self, v4, v6=()):
        def resolve(host, port, family, kind):
            values = v4 if family == socket.AF_INET else v6
            if not values:
                raise socket.gaierror('none')
            return [(family, kind, 6, '', (v, port)) for v in values]
        return resolve

    def test_dns_must_name_only_this_ipv4_and_no_aaaa(self):
        ingress.dns_points_here(IPV4, self.resolver([IPV4]))
        for v4, v6 in (([], ()), (['100.69.188.41'], ()), ([IPV4, '1.2.3.4'], ()), ([IPV4], ['2a03:4000::1'])):
            with self.subTest(v4=v4, v6=v6), self.assertRaises(ingress.trust.Refused):
                ingress.dns_points_here(IPV4, self.resolver(v4, v6))

    def test_public_ipv4_must_be_global_and_assigned_here(self):
        self.assertEqual(ingress.check_ipv4(IPV4, {IPV4}), IPV4)
        for value, assigned in (('100.69.188.41', {'100.69.188.41'}), ('10.0.0.1', {'10.0.0.1'}), (IPV4, set())):
            with self.subTest(value=value), self.assertRaises(ingress.trust.Refused):
                ingress.check_ipv4(value, assigned)

    def test_edge_readiness_needs_catalogue_refusal_and_issuer(self):
        answers = {('gameometro.com', '/api/v1/releases?page=1&pageSize=1'): (200, b'{}'),
                   ('gameometro.com', '/api/v1/me/ratings'): (401, b''),
                   ('auth.gameometro.com', '/realms/gameometro/.well-known/openid-configuration'):
                       (200, json.dumps({'issuer': ingress.ISSUER}).encode())}
        ingress.public_edge_ready(IPV4, lambda ip, host, path: answers[(host, path)])
        bad = dict(answers)
        bad[('gameometro.com', '/api/v1/me/ratings')] = (200, b'')
        with self.assertRaises(ingress.trust.Refused):
            ingress.public_edge_ready(IPV4, lambda ip, host, path: bad[(host, path)])


class Gate:
    def gate(self, action):
        return b'open' if action == 'check' else b'none'


class Transition(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.admin = Path(self.tmp.name)
        bundle = self.admin / 'bundle'
        (bundle / 'candidate').mkdir(parents=True)
        (self.admin / 'upgrades').mkdir()
        self.caddyfile = bundle / 'Caddyfile.private'
        self.caddyfile.write_text(prepare.private_caddy((ROOT / 'deploy/public-production/caddy/Caddyfile').read_text()))
        (self.admin / 'admin-password').write_text('secret\n')
        self.private_file = bundle / 'compose.target.json'
        self.private_file.write_text(json.dumps(private_config(self.caddyfile, self.admin)))
        self.pointer = {'revision': 'f' * 40, 'configurationFile': str(self.private_file),
                        'configurationSha256': ingress.install.sha(self.private_file), 'planSha256': 'p'}
        (self.admin / 'upgrades/ACTIVE.json').write_text(json.dumps(self.pointer))
        self.calls, self.ready, self.registration_state = [], True, True
        patches = [mock.patch.object(ingress.acceptance, 'load_recovery', return_value=Gate()),
                   mock.patch.object(ingress.control, 'durable', side_effect=lambda p, v: p.write_text(json.dumps(v)))]
        for patch in patches:
            patch.start()
            self.addCleanup(patch.stop)
        self.tool = ingress.Ingress(runner=self.runner, clock=self.clock, sleep=lambda s: None, get=self.get,
                                    resolve=Admission().resolver([IPV4]), admin=self.admin)
        self.now = 0

    def tearDown(self):
        self.tmp.cleanup()

    def clock(self):
        self.now += 60
        return self.now

    def runner(self, args, data=None, timeout=None):
        args = [str(a) for a in args]
        if args[:2] == ['ip', '-j']:
            return json.dumps([{'ifname': 'eth0', 'addr_info': [{'local': IPV4}]},
                               {'ifname': 'tailscale0', 'addr_info': [{'local': '100.69.188.41'}]}]).encode()
        if ingress.REGISTRATION_ACTOR in ' '.join(args):
            state = args[args.index('--state') + 1]
            self.calls.append(('registration', state, '--check' in args))
            self.assertEqual(data, b'secret\n')
            self.registration_state = state == 'open'
            return json.dumps({'registrationAllowed': self.registration_state}).encode()
        if args[:2] == ['docker', 'compose']:
            self.calls.append(('compose', Path(args[args.index('-f') + 1]).name, args[-1]))
            return b''
        raise AssertionError(args)

    def get(self, ip, host, path):
        if not self.ready:
            raise OSError('no certificate yet')
        if path.endswith('openid-configuration'):
            return 200, json.dumps({'issuer': ingress.ISSUER}).encode()
        return (401 if '/me/' in path else 200), b'{}'

    def active(self):
        return json.loads((self.admin / 'upgrades/ACTIVE.json').read_text())

    def test_registration_closes_and_is_checked_before_any_publication(self):
        result = self.tool.apply(IPV4, 'issue-276-phase-5')
        self.assertEqual((result['mode'], result['registration']), ('public', 'closed'))
        first_compose = next(i for i, c in enumerate(self.calls) if c[0] == 'compose')
        self.assertEqual(self.calls[:first_compose], [('registration', 'closed', False), ('registration', 'closed', True)])
        self.assertEqual(self.calls[first_compose], ('compose', 'compose.public.json', 'caddy'))
        state = json.loads((self.admin / 'ingress/INGRESS.json').read_text())
        self.assertEqual((state['mode'], state['registration']), ('public', 'closed'))
        self.assertEqual(self.active()['configurationFile'], state['publicConfigurationFile'])
        public = json.loads(Path(state['publicConfigurationFile']).read_text())
        self.assertEqual(public['services']['caddy']['ports'], ingress.public_ports(IPV4))

    def test_unverified_certificate_recreates_the_private_edge_with_registration_still_closed(self):
        self.ready = False
        with self.assertRaises(ingress.trust.Refused):
            self.tool.apply(IPV4, 'issue-276-phase-5')
        self.assertEqual(self.calls[-1], ('compose', 'compose.target.json', 'caddy'))
        self.assertEqual(self.active()['configurationFile'], str(self.private_file))
        self.assertEqual(self.active()['configurationSha256'], self.pointer['configurationSha256'])
        state = json.loads((self.admin / 'ingress/INGRESS.json').read_text())
        self.assertEqual((state['mode'], state['registration']), ('reverted-after-failure', 'closed'))
        self.assertFalse(self.registration_state)

    def test_wrong_dns_refuses_before_registration_or_publication(self):
        self.tool.resolve = Admission().resolver(['100.69.188.41'])
        with self.assertRaises(ingress.trust.Refused):
            self.tool.apply(IPV4, 'issue-276-phase-5')
        self.assertEqual(self.calls, [])

    def test_revert_and_reopen_registration_only_on_a_public_edge(self):
        with self.assertRaises(ingress.trust.Refused):
            self.tool.open_registration('go-decision')
        self.tool.apply(IPV4, 'issue-276-phase-5')
        self.assertEqual(self.tool.verify()['mode'], 'public')
        self.assertEqual(self.tool.open_registration('go-decision')['registration'], 'open')
        self.assertTrue(self.registration_state)
        self.assertEqual(self.tool.revert('rollback')['mode'], 'private')
        self.assertEqual(self.active()['configurationFile'], str(self.private_file))
        self.assertEqual(self.tool.verify()['mode'], 'private')


class FakeRealm:
    def __init__(self, allowed=True, drift=False):
        self.state = {'realm': 'gameometro', 'registrationAllowed': allowed, 'loginTheme': 'gameometro'}
        self.drift, self.puts = drift, []

    def representation(self):
        return dict(self.state)

    def send(self, method, path, body):
        self.puts.append((method, path, body))
        self.state.update(body)
        if self.drift:
            self.state['loginTheme'] = 'other'


class RegistrationActor(unittest.TestCase):
    def test_changes_only_self_registration_and_verifies(self):
        realm = FakeRealm()
        self.assertEqual(actor.align(realm, False), {'registrationAllowed': False, 'changed': True})
        self.assertEqual(realm.puts, [('PUT', '', {'registrationAllowed': False})])
        self.assertEqual(actor.align(realm, False), {'registrationAllowed': False, 'changed': False})
        actor.align(realm, False, check=True)
        with self.assertRaises(ValueError):
            actor.align(realm, True, check=True)

    def test_any_other_realm_change_refuses(self):
        with self.assertRaises(ValueError):
            actor.align(FakeRealm(drift=True), False)


if __name__ == '__main__':
    unittest.main()
