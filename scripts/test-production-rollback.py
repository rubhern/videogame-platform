#!/usr/bin/env python3
"""Decision matrix, faults, concurrency and interruption of the production rollback (#488), on a fake host.

Real files, journals, hashes and the upgrader's own handover, smoke and finish code run unchanged;
Docker, PostgreSQL, Keycloak, Git and the backup cycle are simulated. Real disposable services are a
separate proof, and none of this qualifies the VPS.
"""
import sys
sys.dont_write_bytecode = True

import contextlib
import json
import os
from pathlib import Path
import tempfile
import unittest
import unittest.mock

import install_private_production as install
import private_dev_promotion as trust
import private_production_upgrade as upgrade
import production_rollback as rollback

ROOT = Path(__file__).resolve().parents[1]
LKG, CURRENT, OLDER, OPERATOR = 'a' * 40, 'b' * 40, 'c' * 40, 'd' * 40
FILES = [{'version': '20260101.100000', 'script': 'V20260101_100000__base.sql', 'checksum': 11},
         {'version': '20260201.100000', 'script': 'V20260201_100000__next.sql', 'checksum': 22}]
ADDED = {'version': '20260301.100000', 'script': 'V20260301_100000__added.sql', 'checksum': 33}
REALM = json.loads((ROOT / upgrade.REALM_IMPORT).read_text())
FINGERPRINT = install.operational_fingerprint()


def runtime(bundle, edge, revision, version, *, keycloak='keycloak@sha256:' + '1' * 64, rebuilt=False, environment=None,
            extra=None, public=False):
    """rebuilt: every dependency republished with a new digest, as each main build does."""
    application = 'application@sha256:' + revision[0] * 64
    rebuild = (lambda name, digit: f'{name}@sha256:' + (revision[0] if rebuilt else digit) * 64)
    caddyfile = bundle / 'Caddyfile.private' if public else edge
    services = {
        'postgres': {'image': 'postgres@sha256:' + '2' * 64, 'environment': {}},
        'keycloak': {'image': rebuild('keycloak', '1') if keycloak.endswith('1' * 64) else keycloak, 'environment': {}},
        'caddy': {'image': rebuild('caddy', '3'), 'environment': {},
                  'volumes': [{'type': 'bind', 'source': str(caddyfile), 'target': '/etc/caddy/Caddyfile', 'read_only': True},
                              *([{'type': 'bind', 'source': '/protected/tls/edge.crt', 'target': '/run/private-tls/edge.crt', 'read_only': True},
                                 {'type': 'bind', 'source': '/protected/tls/edge.key', 'target': '/run/private-tls/edge.key', 'read_only': True},
                                 {'type': 'volume', 'source': 'caddy-data', 'target': '/data'}] if public else [])]},
        'catalogue-localizer': {'image': rebuild('localizer', '4'), 'environment': {}},
        'application': {'image': application, 'mem_limit': str(512 * 1024 ** 2),
                        'environment': {upgrade.VERSION_ENVIRONMENT: version, 'SETTING': '1', **(environment or {})},
                        'networks': {'edge': {'ipv4_address': upgrade.BFF_ADDRESS}},
                        'labels': {upgrade.UPGRADE_LABEL: revision},
                        'volumes': [{'type': 'bind', 'source': str(bundle / 'candidate/static'), 'target': '/static', 'read_only': True}]},
        'migration': {'image': application, 'environment': {}},
        **(extra or {}),
    }
    return {'name': 'gameometro-production', 'services': services, 'networks': {'edge': {}},
            'secrets': {'keycloak_admin_password': {'file': '/protected/keycloak'}}, 'volumes': {'postgres-data': {}}}


class Recovery:
    def __init__(self, test):
        self.test = test

    @contextlib.contextmanager
    def lock(self, maintenance=False):
        require_free = self.test.state
        trust.require(not require_free['recoveryBusy'], 'Another recovery/backup actor is active.')
        trust.require(maintenance or not require_free['maintenance'], 'Identity maintenance is active; release it before this operation.')
        require_free['recoveryBusy'] = True
        try:
            yield
        finally:
            require_free['recoveryBusy'] = False

    def check_lease(self):
        self.test.log.append('lease')


class Host:
    def __init__(self, test, name):
        self.test, self.name, self.degraded = test, name, ()

    def compose(self, *args, **_):
        if args[:1] == ('stop',) and upgrade.CANDIDATE in args:
            self.test.state['candidate'] = False
        self.test.log.append(f'{self.name}:' + ' '.join(a for a in args if not a.startswith('-') and not a.isdigit() and 'http' not in a))
        return b''

    def smoke_services(self):
        return ['application']

    def running(self, names):
        self.test.log.append(f'{self.name}:running:' + ','.join(names))

    def smoke(self):
        self.test.log.append(f'{self.name}:smoke')

    def gate_open(self):
        trust.require(self.test.state['gate']['ready'], 'Recovery or identity fence closed.')

    def probe(self, script):
        self.test.log.append(f'{self.name}:probe')


class FakeRollback(rollback.Rollback):
    test = None

    def load(self):
        test = self.test
        pointer = json.loads((self.admin / upgrade.UPGRADES / upgrade.ACTIVE).read_text())
        bundle = self.bundles / f'{pointer["revision"][:12]}-private'
        path = Path(pointer['configurationFile'])
        self.active = {'file': path, 'config': json.loads(path.read_text()), 'revision': pointer['revision'],
                       'source': bundle / 'candidate', 'bundle': bundle}
        self.recovery = Recovery(test)
        self.run = self.fake_run  # The upgrader binds its runner per instance.
        return self

    def host_checks(self):
        return {}

    def github(self):
        return None

    def verify_operational(self, api):
        self.test.log.append('operator-online')

    def verify_staged(self, reviewed, config, api=None):
        trust.require(not self.test.state['bundleDrift'], 'The staged bundle differs from the reviewed plan.')
        trust.require((self.bundle / upgrade.TARGET_CONFIG).read_text() == upgrade.canonical(config)
                      and reviewed['targetConfigurationSha256'] == install.sha(self.bundle / upgrade.TARGET_CONFIG),
                      'The staged bundle differs from the reviewed plan.')

    def local_images(self, images):
        return {image: image not in self.test.state['missingImages'] for image in images.values()}

    def local_image(self, image):
        revision = self.test.state['labels'].get(image, image.split('@')[1][7] * 40 if image.startswith('application@') else None)
        return {'Config': {'Labels': {'org.opencontainers.image.revision': revision}}}

    def query(self, database, statement):
        state = self.test.state
        if statement == upgrade.HISTORY_SQL:
            rows = [{'rank': i + 1, 'success': True, **f} for i, f in enumerate(state['history'])]
            if state['failedRow']:
                rows[-1]['success'] = False
            return rows
        if statement == upgrade.WORK_SQL:
            return state['work']
        if statement == upgrade.DATA_SQL:
            return state['data']
        if statement == upgrade.READER_SQL:
            return {'settings': ['jit=off'], 'connectionLimit': 2}
        if statement == upgrade.REALM_SQL:
            return state['realm']
        if statement == upgrade.IDENTITY_SQL:
            value = {k: [1, k] for k in (*upgrade.ACCOUNT_DIGESTS, *upgrade.CONFIGURATION_DIGESTS)}
            value['realm'] = state['realmDigest']
            if state['identityShiftAfter'] is not None:
                state['identityShiftAfter'] -= 1
                if state['identityShiftAfter'] < 0:
                    value['realm'] = 'changed'
            return value
        return True

    def gate_state(self):
        return dict(self.test.state['gate'])

    def observe_services(self, gate):
        trust.require(not self.test.state['drift'], 'A required service is stopped or ambiguous.')
        return {'application': 'running', **self.test.state.get('stopped', {})}

    def candidate_running(self):
        return [b'candidate'] if self.test.state['candidate'] else []

    def available_memory(self):
        return self.test.state['memory']

    def changed_paths(self, base, head, paths):
        trust.require(self.test.state['range'] is not None, 'Host prerequisite or operation failed; diagnostics withheld.')
        return [p for p in self.test.state['range'] if p.startswith(tuple(paths))]

    @contextlib.contextmanager
    def cycle_lock(self):
        trust.require(not self.test.state['cycleBusy'], 'Another backup cycle step is active.')
        yield

    def reconcile_services(self):
        self.test.log.append('backup-units')
        trust.require(not self.test.state['unitsFail'], 'Backup service has an unexpected source.')

    def host(self, *_, **__):
        return Host(self.test, self.active['revision'][:1])

    def target_host(self, reviewed):
        self.test.target_file = self.directory / rollback.DERIVED
        return Host(self.test, self.target[:1])

    def candidate_host(self, reviewed):
        self.test.state['candidate'] = True
        return Host(self.test, 'candidate')

    def start(self, host, *names, timeout=600):
        self.test.log.append(f'{host.name}:up:' + ','.join(names))
        if (host.name, names[0]) in self.test.state['startFailures']:
            raise trust.Refused('Service did not become healthy.')

    def migration_actor_running(self):
        return b''

    def fake_run(self, args, **_):
        if args[:2] == ['docker', 'stop']:
            self.test.state['candidate'] = False
            self.test.log.append('candidate:stopped')
        return b''

    def phase_reached(self, phase):
        self.test.log.append('phase:' + phase)
        if phase == self.test.state['fault']:
            self.test.state['fault'] = None
            raise trust.Refused('Injected interruption.')


class Fixture(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        base = Path(self.tmp.name)
        self.admin, self.bundles = base / 'admin', base / 'installations'
        (self.admin / upgrade.UPGRADES).mkdir(parents=True)
        self.edge = base / 'Caddyfile'
        self.edge.write_text(f'{upgrade.CANDIDATE_UPSTREAM}\n  # {upgrade.CANDIDATE_ADDRESS}\n}}\n')
        self.log = []
        self.state = {'history': list(FILES), 'failedRow': False, 'work': {'runningSynchronizations': 0, 'activeTranslationClaims': 0},
                      'data': {'games': 10, 'releases': 20, 'ratings': 5},
                      'realm': upgrade.desired_session_state(REALM), 'realmDigest': 'r', 'identityShiftAfter': None,
                      'gate': {'ready': True, 'pending': False, 'maintenance': None}, 'maintenance': False,
                      'drift': False, 'candidate': False, 'memory': 4 * 1024 ** 3, 'range': [], 'cycleBusy': False,
                      'recoveryBusy': False, 'unitsFail': False, 'missingImages': set(), 'labels': {}, 'bundleDrift': False,
                      'startFailures': set(), 'fault': None}
        patch = unittest.mock.patch.object(install, 'secure', lambda *_, **__: None)
        patch.start()
        self.addCleanup(patch.stop)
        bundles = unittest.mock.patch.object(upgrade, 'BUNDLES', self.bundles)
        bundles.start()
        self.addCleanup(bundles.stop)
        FakeRollback.test = self
        self.release(OLDER, None, '1.0.0')
        self.release(LKG, OLDER, '1.1.0')
        self.release(CURRENT, LKG, '1.2.0')

    def release(self, revision, previous, version, **changes):
        """A completed upgrade: retained bundle, plan, journal, and ACTIVE naming it."""
        bundle = self.bundles / f'{revision[:12]}-private'
        source = bundle / 'candidate'
        (source / Path(upgrade.REALM_IMPORT).parent).mkdir(parents=True, exist_ok=True)
        (source / 'static').mkdir(exist_ok=True)
        (source / 'static/index.html').write_text(revision)
        realm = changes.pop('realm', REALM)
        (source / upgrade.REALM_IMPORT).write_text(json.dumps(realm))
        options = changes.pop('runtime', {})
        config = runtime(bundle, self.edge, revision, version, **options)
        path = bundle / upgrade.TARGET_CONFIG
        if options.get('public'):
            bundle.mkdir(parents=True, exist_ok=True)
            (bundle / 'Caddyfile.private').write_text(self.edge.read_text())
            (bundle / upgrade.PUBLIC_CADDYFILE).write_text(self.edge.read_text())
            (bundle / upgrade.PRIVATE_TWIN).write_text(upgrade.canonical(config))
            config = rollback.ingress.derive_public(config, bundle / upgrade.PUBLIC_CADDYFILE, '198.51.100.7')
            (self.admin / 'ingress').mkdir(exist_ok=True)
            upgrade.control.durable(self.admin / 'ingress/INGRESS.json', {
                'mode': 'public', 'revision': revision, 'privateConfigurationFile': str(bundle / upgrade.PRIVATE_TWIN),
                'publicConfigurationFile': str(path)})
        path.write_text(upgrade.canonical(config))
        active = self.pointer() if previous else None
        current = {'revision': previous, 'configurationFile': active['configurationFile'],
                   'configurationSha256': active['configurationSha256'],
                   'images': {n: json.loads(Path(active['configurationFile']).read_text())['services'][n]['image']
                              for n in upgrade.PUBLISHED}} if previous else {'revision': 'installed'}
        plan = {'current': current, 'target': {'revision': revision, 'version': version},
                'targetConfigurationSha256': install.sha(path), 'migrationInventory': changes.pop('inventory', FILES),
                'realm': changes.pop('realmPlan', {'changes': {}, 'registrationNotice': None})}
        (bundle / upgrade.PLAN).write_text(upgrade.canonical(plan))
        directory = self.admin / upgrade.UPGRADES / revision
        directory.mkdir(exist_ok=True)
        journal = {'status': 'complete', 'planSha256': upgrade.digest(plan), 'targetRevision': revision,
                   'targetConfigurationSha256': install.sha(path), 'previous': current, 'finishedAtUnix': 1,
                   'operationalRevision': OPERATOR, 'adapterFingerprint': FINGERPRINT}
        upgrade.control.durable(directory / upgrade.JOURNAL, journal)
        upgrade.control.durable(self.admin / upgrade.UPGRADES / upgrade.ACTIVE, {
            'revision': revision, 'configurationFile': str(path), 'configurationSha256': install.sha(path),
            'planSha256': journal['planSha256'], 'previous': current})
        return path

    def pointer(self):
        return json.loads((self.admin / upgrade.UPGRADES / upgrade.ACTIVE).read_text())

    def journal(self, directory):
        return json.loads((self.admin / upgrade.UPGRADES / directory / upgrade.JOURNAL).read_text())

    def tool(self, target=LKG):
        return FakeRollback(target, operational=OPERATOR, adapter=FINGERPRINT, admin=self.admin, bundles=self.bundles).load()

    def assess(self, target=LKG):
        return self.tool(target).assess()

    def apply(self, sha=None, target=LKG):
        tool = self.tool(target)
        return tool.apply(sha or self.assess(target)['assessmentSha256'], '#488 owner')

    def rollback_journals(self):
        return sorted(p.parent.name for p in (self.admin / upgrade.UPGRADES).glob(rollback.ROLLBACK_PREFIX + '*/' + upgrade.JOURNAL))

    def redeploy_current(self, **changes):
        """Replace the current release with a variant, the last-known-good staying its predecessor."""
        self.release(LKG, OLDER, '1.1.0')
        return self.release(CURRENT, LKG, '1.2.0', **changes)


class DecisionMatrix(Fixture):
    def test_application_only_release_with_unchanged_persistence_is_allowed(self):
        result = self.assess()
        self.assertEqual((result['verdict'], result['reasons']), (rollback.ALLOW, []))
        self.assertEqual(set(result['changedServices']), {'application', 'migration'})
        self.assertEqual(result['flyway'], {'version': FILES[-1]['version'], 'rank': 2})
        self.assertEqual(result['lastKnownGood']['revision'], LKG)
        self.assertEqual(result['lastKnownGood']['images']['application'], 'application@sha256:' + 'a' * 64)
        self.assertEqual(result['realmImport'], 'identical')
        self.assertTrue(result['expectedImpact'])
        self.assertEqual(self.assess()['assessmentSha256'], result['assessmentSha256'], 'The assessment is deterministic.')
        self.assertEqual(self.rollback_journals(), [], 'Assessment wrote a journal.')

    def test_incompatible_changes_need_a_forward_fix_or_recovery(self):
        cases = {
            'additive migration applied': ({'inventory': [*FILES, ADDED]}, lambda: self.state['history'].append(ADDED),
                                           {'schema-advanced', 'migration-inventory-differs'}),
            'failed Flyway row': ({}, lambda: self.state.update(failedRow=True), {'schema-advanced'}),
            'Keycloak rebuilt from changed sources': ({'runtime': {'keycloak': 'keycloak@sha256:' + '9' * 64}},
                                                      lambda: self.state.update(range=['deploy/private-dev/keycloak/Dockerfile']),
                                                      {'another-service-changes'}),
            'edge rebuilt from changed sources': ({'runtime': {'rebuilt': True}},
                                                  lambda: self.state.update(range=['deploy/public-production/caddy/Dockerfile']),
                                                  {'another-service-changes'}),
            'realm import': ({'realm': {**REALM, 'ssoSessionIdleTimeout': 1}}, None, {'identity-changes'}),
            'realm migration planned': ({'realmPlan': {'changes': {'rememberMe': {'from': False, 'to': True}}}}, None,
                                        {'identity-changes'}),
            'live realm drift': ({}, lambda: self.state['realm'].update(rememberMe=not self.state['realm']['rememberMe']),
                                 {'identity-changes'}),
            'application setting': ({'runtime': {'environment': {'SETTING': '2'}}}, None, {'settings-change'}),
            'added service': ({'runtime': {'extra': {'loki': {'image': 'loki@sha256:' + '5' * 64}}}}, None,
                              {'service-inventory-changes'}),
            'persisted contract in range': ({}, lambda: self.state.update(range=['docker/keycloak/themes/x.ftl']),
                                            {'persisted-contract-paths'}),
        }
        for name, (changes, mutate, expected) in cases.items():
            with self.subTest(name):
                self.setUp()
                self.redeploy_current(**changes)
                if mutate:
                    mutate()
                result = self.assess()
                self.assertEqual(result['verdict'], rollback.FORWARD)
                self.assertTrue(expected <= set(result['reasons']), result['reasons'])
                self.assertEqual(result['expectedImpact'], [])
                with self.assertRaises(trust.Refused):
                    self.apply(result['assessmentSha256'])
                self.assertEqual(self.rollback_journals(), [])
                self.assertEqual(self.pointer()['revision'], CURRENT)

    def test_dependencies_rebuilt_from_unchanged_sources_keep_running(self):
        self.redeploy_current(runtime={'rebuilt': True})
        current = json.loads(Path(self.pointer()['configurationFile']).read_text())
        result = self.assess()
        self.assertEqual((result['verdict'], result['reasons']), (rollback.ALLOW, []), result['reasons'])
        self.assertEqual(result['keptServices'], {n: current['services'][n]['image'] for n in ('keycloak', 'caddy', 'catalogue-localizer')})
        self.assertEqual(set(result['changedServices']), {'application', 'migration'})
        self.assertNotEqual(result['targetConfigurationSha256'], result['lastKnownGood']['configurationSha256'])
        self.apply(result['assessmentSha256'])
        active = json.loads(Path(self.pointer()['configurationFile']).read_text())
        self.assertEqual(install.sha(Path(self.pointer()['configurationFile'])), result['targetConfigurationSha256'])
        for name in ('keycloak', 'caddy', 'catalogue-localizer'):
            self.assertEqual(active['services'][name], current['services'][name], f'{name} was replaced by an older build.')
        self.assertEqual(active['services']['application']['image'], 'application@sha256:' + 'a' * 64)
        self.assertNotIn('b:up:keycloak', self.log)

    def test_public_edge_keeps_its_private_twin(self):
        self.release(LKG, OLDER, '1.1.0', runtime={'public': True})
        self.release(CURRENT, LKG, '1.2.0', runtime={'public': True, 'rebuilt': True})
        result = self.assess()
        self.assertEqual((result['verdict'], result['reasons']), (rollback.ALLOW, []), result['reasons'])
        self.apply(result['assessmentSha256'])
        state = json.loads((self.admin / 'ingress/INGRESS.json').read_text())
        twin = json.loads(Path(state['privateConfigurationFile']).read_text())
        public = json.loads(Path(state['publicConfigurationFile']).read_text())
        self.assertEqual((state['revision'], state['publicConfigurationFile']), (LKG, self.pointer()['configurationFile']))
        edge = next(m['source'] for m in public['services']['caddy']['volumes'] if m['target'] == '/etc/caddy/Caddyfile')
        self.assertEqual(rollback.ingress.derive_public(twin, edge, '198.51.100.7'), public)
        self.assertEqual(state['privateConfigurationSha256'], install.sha(Path(state['privateConfigurationFile'])))

    def test_a_public_edge_without_a_valid_twin_refuses(self):
        self.release(LKG, OLDER, '1.1.0', runtime={'public': True})
        self.release(CURRENT, LKG, '1.2.0', runtime={'public': True})
        (self.bundles / f'{LKG[:12]}-private' / upgrade.PRIVATE_TWIN).write_text('{"services": {}}')
        self.assertIn('last-known-good-unverified', self.assess()['reasons'])

    def test_an_idempotent_registration_notice_is_not_an_identity_change(self):
        self.redeploy_current(realmPlan={'changes': {}, 'registrationNotice': {'targetSha256': 'n', 'field': 'f'}})
        self.assertEqual(self.assess()['verdict'], rollback.ALLOW)

    def test_secret_changes_need_a_forward_fix(self):
        path = self.pointer()['configurationFile']
        config = json.loads(Path(path).read_text())
        config['secrets']['telegram'] = {'file': '/protected/telegram'}
        Path(path).write_text(upgrade.canonical(config))
        self.rewrite_current_hash(path)
        self.assertIn('secret-changes', self.assess()['reasons'])

    def rewrite_current_hash(self, path):
        journal = self.journal(CURRENT)
        journal['targetConfigurationSha256'] = install.sha(Path(path))
        upgrade.control.durable(self.admin / upgrade.UPGRADES / CURRENT / upgrade.JOURNAL, journal)
        pointer = self.pointer()
        pointer['configurationSha256'] = install.sha(Path(path))
        upgrade.control.durable(self.admin / upgrade.UPGRADES / upgrade.ACTIVE, pointer)

    def test_missing_ambiguous_or_busy_evidence_refuses(self):
        cases = {
            'gate closed': (lambda: self.state['gate'].update(ready=False, pending=True), 'fence-closed'),
            'identity maintenance': (lambda: self.state['gate'].update(maintenance='run'), 'fence-closed'),
            'synchronization running': (lambda: self.state['work'].update(runningSynchronizations=1), 'active-work'),
            'candidate running': (lambda: self.state.update(candidate=True), 'candidate-running'),
            'memory': (lambda: self.state.update(memory=512 * 1024 ** 2), 'insufficient-memory'),
            'runtime drift': (lambda: self.state.update(drift=True), 'runtime-drift'),
            'image pruned': (lambda: self.state['missingImages'].add('application@sha256:' + 'a' * 64), 'image-missing'),
            'image from another source': (lambda: self.state['labels'].update({'application@sha256:' + 'a' * 64: 'e' * 40}),
                                          'image-provenance'),
            'bundle changed': (lambda: self.state.update(bundleDrift=True), 'last-known-good-unverified'),
            'Git range unavailable': (lambda: self.state.update(range=None), 'source-range-unavailable'),
            'schema behind': (lambda: self.state.update(history=FILES[:1]), 'schema-ambiguous'),
            'unfinished upgrade': (self.unfinished_upgrade, 'unfinished-operation'),
        }
        for name, (mutate, reason) in cases.items():
            with self.subTest(name):
                self.setUp()
                mutate()
                result = self.assess()
                self.assertEqual((result['verdict'], reason in result['reasons']), (rollback.REFUSE, True), result['reasons'])

    def unfinished_upgrade(self):
        directory = self.admin / upgrade.UPGRADES / OPERATOR
        directory.mkdir()
        upgrade.control.durable(directory / upgrade.JOURNAL, {'status': 'failed'})

    def test_untrusted_lineage_refuses_without_naming_a_target(self):
        def drop(path):
            return lambda: path().unlink()
        cases = {
            'last-known-good journal missing': (drop(lambda: self.admin / upgrade.UPGRADES / LKG / upgrade.JOURNAL), 'last-known-good-unverified'),
            'last-known-good plan corrupt': (lambda: (self.bundles / f'{LKG[:12]}-private' / upgrade.PLAN).write_text('{}'),
                                             'last-known-good-unverified'),
            'last-known-good configuration changed': (
                lambda: (self.bundles / f'{LKG[:12]}-private' / upgrade.TARGET_CONFIG).write_text('{"services": {}}'),
                'last-known-good-unverified'),
            'current upgrade interrupted': (lambda: upgrade.control.durable(
                self.admin / upgrade.UPGRADES / CURRENT / upgrade.JOURNAL, {**self.journal(CURRENT), 'status': 'failed'}),
                'current-release-unverified'),
            'current plan corrupt': (lambda: (self.bundles / f'{CURRENT[:12]}-private' / upgrade.PLAN).write_text('{}'),
                                     'current-release-unverified'),
            'no recorded predecessor': (lambda: upgrade.control.durable(self.admin / upgrade.UPGRADES / upgrade.ACTIVE,
                                                                       {**self.pointer(), 'previous': None}), 'no-upgrade-lineage'),
        }
        for name, (mutate, reason) in cases.items():
            with self.subTest(name):
                self.setUp()
                mutate()
                result = self.assess()
                self.assertEqual((result['verdict'], result['reasons'], result['assessmentSha256']), (rollback.REFUSE, [reason], None))
        self.setUp()
        for target in (OLDER, CURRENT):
            self.assertEqual(self.assess(target)['reasons'], ['target-not-last-known-good'], 'Only the recorded predecessor is a target.')

    def test_unknown_reasons_are_refused(self):
        with self.assertRaises(trust.Refused):
            rollback.verdict(['something-new'])
        self.assertEqual(rollback.verdict(['fence-closed', 'schema-advanced']), rollback.FORWARD)


class Execution(Fixture):
    def test_authorized_rollback_restores_the_last_known_good_release_and_keeps_lineage(self):
        before = self.journal(LKG)
        result = self.apply()
        self.assertEqual((result['mode'], result['targetRevision'], result['backupServices']), ('rolled-back-to', LKG, 'reconciled'))
        pointer = self.pointer()
        self.assertEqual((pointer['revision'], pointer['previous'], pointer['rolledBackFrom']['revision']),
                         (LKG, before['previous'], CURRENT))
        self.assertEqual(pointer['configurationSha256'], before['targetConfigurationSha256'])
        self.assertEqual(self.journal(LKG), before, 'The last-known-good receipt changed.')
        reverted = self.journal(CURRENT)
        self.assertEqual((reverted['status'], reverted['revertedBy']), ('reverted', self.rollback_journals()[0]))
        record = self.journal(self.rollback_journals()[0])
        self.assertEqual((record['status'], record['phase'], record['containment'] if 'containment' in record else None),
                         ('complete', 'complete', None))
        receipt = json.loads((self.admin / upgrade.UPGRADES / self.rollback_journals()[0] / rollback.ASSESSMENT).read_text())
        self.assertNotIn('runtime', receipt)
        self.assertEqual(receipt['verdict'], rollback.ALLOW)
        order = [e for e in self.log if e.startswith(('a:', 'b:', 'candidate:', 'backup-units'))]
        self.assertEqual(order[:5], ['candidate:up:application-candidate', 'candidate:exec application-candidate wget',
                                     'b:stop application', 'a:up:application', 'candidate:stop application-candidate'])
        self.assertIn('a:smoke', order)
        self.assertEqual(order[-1], 'backup-units')
        self.assertNotIn('operator-online', self.log, 'The claimed operator needs no GitHub.')
        self.assertEqual(self.assess()['reasons'], ['target-not-last-known-good'], 'A second rollback to the same target refuses.')
        self.assertEqual(upgrade.Upgrade.unfinished(self.tool()), [], 'A completed rollback blocks nothing.')

    def test_rollback_after_a_redeployment_is_repeatable(self):
        self.apply()
        self.release(CURRENT[:39] + 'e', LKG, '1.3.0')
        result = self.assess()
        self.assertEqual(result['verdict'], rollback.ALLOW, result['reasons'])
        self.apply(result['assessmentSha256'])
        self.assertEqual(self.pointer()['revision'], LKG)
        self.assertEqual(len(self.rollback_journals()), 2)

    def test_an_upgrade_after_a_rollback_keeps_the_derived_predecessor(self):
        self.redeploy_current(runtime={'rebuilt': True})
        self.apply()
        self.release(CURRENT[:39] + 'e', LKG, '1.3.0', runtime={'rebuilt': True})
        self.assertEqual(Path(self.pointer()['previous']['configurationFile']).name, rollback.DERIVED)
        result = self.assess()
        self.assertEqual((result['verdict'], result['reasons']), (rollback.ALLOW, []), result['reasons'])

    def test_stopped_optional_services_stay_stopped_and_unsmoked(self):
        self.state['stopped'] = {'grafana': 'stopped', 'catalogue-localizer': 'stopped'}
        self.apply()
        record = self.journal(self.rollback_journals()[0])
        self.assertEqual(record['optionalDegraded'], ['catalogue-localizer', 'grafana'])
        self.assertNotIn('a:up:grafana', self.log)

    def test_hash_and_verdict_are_rechecked_under_the_locks(self):
        sha = self.assess()['assessmentSha256']
        with self.assertRaises(trust.Refused):
            self.apply('f' * 64)
        self.state['work']['activeTranslationClaims'] = 1
        with self.assertRaises(trust.Refused):
            self.apply(sha)
        self.assertEqual(self.rollback_journals(), [])
        with self.assertRaises(trust.Refused):
            self.tool().apply(sha, '')

    def test_concurrent_operations_refuse_before_any_change(self):
        sha = self.assess()['assessmentSha256']
        for key in ('cycleBusy', 'recoveryBusy', 'maintenance'):
            with self.subTest(key):
                self.state[key] = True
                with self.assertRaises(trust.Refused):
                    self.apply(sha)
                self.state[key] = False
                self.assertEqual((self.rollback_journals(), self.pointer()['revision']), ([], CURRENT))

    def test_an_unfinished_rollback_blocks_upgrades_backups_and_another_rollback(self):
        self.state['fault'] = 'replacing'
        self.state['startFailures'].add(('b', 'application'))
        with self.assertRaises(trust.Refused):
            self.apply()
        record = self.journal(self.rollback_journals()[0])
        self.assertEqual((record['status'], record['containment']), ('failed', 'inconclusive'))
        tool = self.tool()
        self.assertEqual(upgrade.Upgrade.unfinished(tool), self.rollback_journals(), 'The upgrader and backup cycle see the journal.')
        self.assertIn('unfinished-operation', tool.assess()['reasons'])

    def test_every_handover_phase_fault_restores_the_running_release(self):
        for phase in ('candidate', 'handover', 'replacing', 'retiring', 'smoke', 'finalizing'):
            with self.subTest(phase):
                self.setUp()
                self.state['fault'] = phase
                with self.assertRaises(trust.Refused):
                    self.apply()
                record = self.journal(self.rollback_journals()[0])
                self.assertEqual((record['status'], record['failedPhase'], record['containment']), ('undone', phase, 'confirmed'))
                self.assertEqual(self.pointer()['revision'], CURRENT)
                self.assertEqual(self.journal(CURRENT)['status'], 'complete')
                self.assertIn('b:up:application', self.log, 'The running release was not restarted.')
                self.assertFalse(self.state['candidate'])
                self.assertNotIn('backup-units', self.log)
                self.assertEqual(self.assess()['verdict'], rollback.ALLOW, 'An undone attempt blocks nothing.')

    def test_unreviewed_keycloak_change_during_the_rollback_undoes_it(self):
        self.state['identityShiftAfter'] = 1  # The before snapshot reads the same state; the smoke does not.
        with self.assertRaises(trust.Refused):
            self.apply()
        self.assertEqual(self.journal(self.rollback_journals()[0])['status'], 'undone')
        self.assertEqual(self.pointer()['revision'], CURRENT)

    def test_lost_ratings_during_the_rollback_undo_it(self):
        sha = self.assess()['assessmentSha256']
        tool = self.tool()
        original = tool.smoke

        def smoke(host, record):
            self.state['data'] = {**self.state['data'], 'ratings': 4}
            original(host, record)
        tool.smoke = smoke
        with self.assertRaises(trust.Refused):
            tool.apply(sha, '#488 owner')
        self.assertEqual(self.journal(self.rollback_journals()[0])['status'], 'undone')

    def test_backup_unit_failure_keeps_the_rollback_and_is_retried_separately(self):
        self.state['unitsFail'] = True
        with self.assertRaises(trust.Refused) as raised:
            self.apply()
        self.assertIn('--reconcile-services', str(raised.exception))
        self.assertEqual(self.pointer()['revision'], LKG)
        record = self.journal(self.rollback_journals()[0])
        self.assertEqual((record['status'], record['backupServices']), ('complete', 'failed'))
        self.state['unitsFail'] = False
        self.assertEqual(self.tool().reconcile_only()['mode'], 'services-reconciled')
        self.assertEqual(self.journal(self.rollback_journals()[0])['backupServices'], 'reconciled')


class Interruption(Fixture):
    def interrupted(self, phase):
        """A controller killed after `phase`: the journal says running and nothing cleaned up."""
        tool = self.tool()
        sha = tool.assess()['assessmentSha256']
        original = tool.phase_reached

        def kill(name):
            original(name)
            if name == phase:
                raise KeyboardInterrupt
        tool.phase_reached = kill
        tool.roll_back = lambda record, error=None: None  # SIGKILL runs no cleanup.
        with self.assertRaises(KeyboardInterrupt):
            tool.apply(sha, '#488 owner')
        self.state['recoveryBusy'] = False
        return self.journal(self.rollback_journals()[0])

    def test_abandon_after_a_kill_restores_the_running_release(self):
        record = self.interrupted('replacing')
        self.assertEqual(record['status'], 'running')
        self.log.clear()
        result = self.tool().abandon()
        self.assertEqual(result['mode'], 'undone')
        self.assertIn('b:up:application', self.log)
        self.assertEqual(self.pointer()['revision'], CURRENT)
        self.assertEqual(self.journal(self.rollback_journals()[0])['status'], 'undone')

    def test_abandon_completes_a_rollback_whose_pointer_already_moved(self):
        tool = self.tool()
        sha = tool.assess()['assessmentSha256']
        original = tool.write_active

        def crash(record, reviewed):
            original(record, reviewed)
            raise KeyboardInterrupt
        tool.write_active = crash
        tool.roll_back = lambda record, error=None: None
        with self.assertRaises(KeyboardInterrupt):
            tool.apply(sha, '#488 owner')
        self.state['recoveryBusy'] = False
        self.assertEqual(self.pointer()['revision'], LKG)
        result = self.tool().abandon()
        self.assertEqual((result['mode'], result['completedBy']), ('rolled-back-to', 'abandon'))
        record = self.journal(self.rollback_journals()[0])
        self.assertEqual(record['status'], 'complete')
        self.assertEqual(self.journal(CURRENT)['status'], 'reverted')

    def test_abandon_refuses_another_target_or_no_unfinished_rollback(self):
        with self.assertRaises(trust.Refused):
            self.tool().abandon()
        self.interrupted('handover')
        with self.assertRaises(trust.Refused):
            self.tool(OLDER).abandon()

    def test_inspect_is_sanitized(self):
        self.interrupted('candidate')
        result = rollback.inspect(self.tool())
        row = result['rollbacks'][0]
        self.assertEqual((row['status'], row['advice']), ('running', rollback.ADVICE['running']))
        self.assertFalse({'dataBefore', 'readerBefore', 'identityConfigurationBefore', 'ownerReference'} & set(row))

    def test_a_different_operator_must_prove_its_publication(self):
        tool = FakeRollback(LKG, operational='e' * 40, adapter=FINGERPRINT, admin=self.admin, bundles=self.bundles).load()
        tool.verify_operator()
        self.assertIn('operator-online', self.log)
        tool = FakeRollback(LKG, operational=OPERATOR, adapter='0' * 64, admin=self.admin, bundles=self.bundles).load()
        with self.assertRaises(trust.Refused):
            tool.verify_operator()


class Registration(unittest.TestCase):
    def test_operator_inventory_and_gates_name_the_rollback(self):
        self.assertIn('scripts/production_rollback.py', install.OPERATIONAL_FILES)
        workflow = (ROOT / '.github/workflows/build-and-verify.yml').read_text()
        self.assertIn('python3 scripts/test-production-rollback.py', workflow)
        self.assertIn('scripts/production_rollback.py', (ROOT / 'scripts/detect-ci-changes.sh').read_text())
        self.assertFalse(os.stat(ROOT / 'scripts/production_rollback.py').st_mode & 0o111)

    def test_dependency_sources_cover_every_build_input(self):
        """A Dockerfile that copies a new path must extend the reviewed table, or a changed input would pass as a rebuild."""
        dockerfiles = {'keycloak': ('deploy/private-dev/keycloak/Dockerfile', ''),
                       'caddy': ('deploy/public-production/caddy/Dockerfile', 'deploy/public-production/caddy/'),
                       'catalogue-localizer': ('tools/catalogue-localization/Dockerfile', 'tools/catalogue-localization/')}
        self.assertEqual(set(dockerfiles), set(rollback.DEPENDENCY_SOURCES))
        build = (ROOT / rollback.BUILD_SCRIPT).read_text()
        for name, (dockerfile, context) in dockerfiles.items():
            covered = rollback.DEPENDENCY_SOURCES[name]
            self.assertIn(f'dockerfile="{dockerfile}"', build)
            self.assertTrue(dockerfile.startswith(covered) and rollback.BUILD_SCRIPT in covered, name)
            for line in (ROOT / dockerfile).read_text().splitlines():
                words = line.split()
                if words[:1] != ['COPY'] or any(w.startswith('--from=') for w in words):
                    continue
                for source in [w for w in words[1:-1] if not w.startswith('--')]:
                    self.assertTrue((context + source.removeprefix('./')).startswith(covered), f'{name}: {source} is not a reviewed source')


if __name__ == '__main__':
    unittest.main()
