#!/usr/bin/env python3
"""#452 restart transition contract: fake host and Docker, no runtime mutation."""
import ast
import copy
from datetime import datetime, timedelta, timezone
import json
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
RUN = '11111111-1111-4111-8111-111111111111'
CONFIGURATION = 'c' * 64
IMAGES = {'postgres': 'p', 'application': 'a', 'keycloak': 'k'}
NOW = datetime(2026, 11, 7, 12, tzinfo=timezone.utc)


def runtime():
    services = {name: {'image': 'image@sha256:' + '1' * 64, 'restart': 'no', 'mem_limit': '100', 'labels': {}}
                for name in (*acceptance.PERSISTENT, *acceptance.ONE_SHOT)}
    services['grafana'].update(environment={'GF_LOG_MODE': 'console'}, volumes=[])
    return {'name': prepare.PROJECT, 'services': services}


def manifest(**changes):
    return {'format': 1, 'environment': 'production', 'fence': 'roles-nologin-drained', 'backupId': 'b' * 32,
            'databases': ['videogame_platform', 'videogame_keycloak'], 'images': IMAGES,
            'recoveryPoint': (NOW - timedelta(hours=1)).isoformat(),
            'artifacts': {'videogame_platform.dump.gpg': {'bytes': 10}}, **changes}


class Delta(unittest.TestCase):
    def setUp(self):
        self.runtime = runtime()
        self.accepted = acceptance.accepted_configuration(self.runtime, Path('/operations'))

    def test_only_persistent_services_change_policy(self):
        for name in acceptance.PERSISTENT:
            self.assertEqual(self.accepted['services'][name]['restart'], 'unless-stopped')
        self.assertEqual(self.accepted['services']['migration']['restart'], 'no')
        self.assertEqual(self.runtime, runtime(), 'The installed runtime is an input, never rewritten.')

    def test_delta_is_exact_and_reviewable(self):
        self.assertEqual(acceptance.delta(self.runtime, self.accepted), {
            **{name: ['restart'] for name in acceptance.PERSISTENT},
            'catalogue-localizer': ['mem_limit', 'restart'],
            'grafana': ['environment', 'restart', 'volumes']})
        mount = self.accepted['services']['grafana']['volumes'][-1]
        self.assertTrue(mount['read_only'])
        self.assertEqual(mount['source'], '/operations/' + acceptance.DASHBOARD)

    def test_any_other_change_is_refused(self):
        mutations = {'port': lambda s: s['caddy'].__setitem__('ports', [{'target': 443}]),
                     'one-shot restart': lambda s: s['migration'].__setitem__('restart', 'unless-stopped'),
                     'always policy': lambda s: s['postgres'].__setitem__('restart', 'always'),
                     'privilege': lambda s: s['application'].__setitem__('privileged', True),
                     'host network': lambda s: s['grafana'].__setitem__('network_mode', 'host'),
                     'unbounded ceiling': lambda s: s['catalogue-localizer'].__setitem__('mem_limit', '0'),
                     'new service': lambda s: s.__setitem__('adminer', {})}
        for name, mutate in mutations.items():
            with self.subTest(name):
                config = copy.deepcopy(self.accepted)
                mutate(config['services'])
                with self.assertRaises(trust.Refused):
                    acceptance.validate_accepted(self.runtime, config, Path('/operations'))

    def test_only_an_unaccepted_runtime_can_be_the_input(self):
        changed = runtime()
        changed['services']['postgres']['restart'] = 'unless-stopped'
        extra = runtime()
        extra['services']['adminer'] = {'restart': 'no'}
        for config in (changed, extra):
            with self.assertRaises(trust.Refused):
                acceptance.accepted_configuration(config)

    def test_first_install_still_refuses_any_restart(self):
        base = {'name': prepare.PROJECT, 'networks': {},
                'services': {name: {'image': 'e@sha256:' + '1' * 64, 'environment': {}, 'volumes': []}
                             for name in ('telemetry', 'prometheus', 'grafana', 'node-exporter', 'blackbox',
                                          'application', 'migration', 'caddy')}}
        base['services']['caddy']['volumes'] = [prepare.bind('/c', '/etc/caddy/Caddyfile')]
        config = prepare.adapt(base, Path('/c'), Path('/t'))
        self.assertTrue(all(s['restart'] == 'no' for s in config['services'].values()))
        config['services']['grafana']['restart'] = 'unless-stopped'
        with self.assertRaises(trust.Refused):
            prepare.validate(base, config, Path('/c'), Path('/t'))

    def test_constants_match_the_candidate_compose_sources(self):
        compose = (ROOT / 'deploy/public-production/compose.yaml').read_text()
        localizer = compose.split('  catalogue-localizer:\n', 1)[1].split('\n  application:', 1)[0]
        self.assertEqual(int(acceptance.LOCALIZER_MEMORY), int(re.search(r'mem_limit: (\d+)m', localizer)[1]) * 1024 * 1024)
        overlay = (ROOT / 'deploy/public-production/compose.observability.yaml').read_text()
        # The accepted policy is historical; ADR-0040 re-enabled only Loki, through the upgrader.
        self.assertIn('GF_PLUGINS_DISABLE_PLUGINS: ' + upgrade.GRAFANA_DISABLED_PLUGINS + '\n', overlay)
        self.assertEqual(set(acceptance.DISABLED_PLUGINS.split(',')) - set(upgrade.GRAFANA_DISABLED_PLUGINS.split(',')), {'loki'})

    def test_grafana_keeps_exactly_the_provisioned_datasource_plugins(self):
        disabled = set(acceptance.DISABLED_PLUGINS.split(','))
        provisioned = ROOT / 'deploy/private-dev/grafana/provisioning/datasources'
        types = {m for f in provisioned.glob('*.yaml') for m in re.findall(r'type: (\S+)', f.read_text())}
        self.assertEqual(types & disabled, set())
        self.assertEqual(types, {'prometheus', 'grafana-postgresql-datasource'})
        logs = (ROOT / 'deploy/public-production/observability/loki-datasource.yaml').read_text()
        self.assertEqual(set(re.findall(r'type: (\S+)', logs)), {'loki'})
        self.assertNotIn('loki', upgrade.GRAFANA_DISABLED_PLUGINS.split(','))

    def test_reviewed_inputs_are_bound_by_the_operational_fingerprint(self):
        for name in ('scripts/private_production_acceptance.py', acceptance.DASHBOARD, acceptance.COVERAGE_VIEW):
            self.assertIn(name, install.OPERATIONAL_FILES)
            self.assertTrue((ROOT / name).is_file())

    def test_coverage_file_is_idempotent_and_view_only(self):
        sql = re.sub(r'--.*', '', (ROOT / acceptance.COVERAGE_VIEW).read_text())
        self.assertIn('CREATE OR REPLACE VIEW', sql)
        statements = [s.split()[0] for s in sql.split(';') if s.strip()]
        self.assertEqual(statements, ['CREATE', 'COMMENT', 'REVOKE'])


class Evidence(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.admin = Path(self.tmp.name)
        self.journal = self.admin / 'first-install'
        (self.journal / 'recovery').mkdir(parents=True)
        (self.admin / 'first-install-intent.json').write_text(json.dumps({'runId': RUN, 'configurationSha256': CONFIGURATION}))
        self.state({'runId': RUN, 'outcome': 'success', 'phase': 'complete', 'leaseCompletion': 'acknowledged-and-exited'})

    def state(self, value):
        (self.journal / 'installation.json').write_text(json.dumps(value))

    def test_completed_installation_is_accepted(self):
        self.assertEqual(acceptance.installation_evidence(self.admin), {'runId': RUN, 'configuration': CONFIGURATION})

    def test_unaccepted_or_recovery_pending_states_refuse(self):
        complete = {'runId': RUN, 'outcome': 'success', 'phase': 'complete', 'leaseCompletion': 'acknowledged-and-exited'}
        for name, value in {'running': {**complete, 'outcome': 'running'}, 'prepared': {**complete, 'outcome': 'prepared'},
                            'failure': {**complete, 'outcome': 'failure'}, 'phase': {**complete, 'phase': 'postgres'},
                            'no lease completion': {**complete, 'leaseCompletion': None},
                            'other attempt': {**complete, 'runId': '2' * 36}}.items():
            with self.subTest(name):
                self.state(value)
                with self.assertRaises(trust.Refused):
                    acceptance.installation_evidence(self.admin)

    def test_markers_of_an_interrupted_attempt_refuse(self):
        for name, path in {'failure sidecar': self.admin / f'first-install-failure-{RUN}.json',
                           'reconciliation': self.journal / 'reconciliation-1.json',
                           'pending write': self.journal / 'installation.json.1.pending',
                           'admin pending write': self.admin / 'first-install-intent.json.1.pending',
                           'recovery pending': self.journal / 'recovery/PENDING.json'}.items():
            with self.subTest(name):
                path.write_text('{}')
                with self.assertRaises(trust.Refused):
                    acceptance.installation_evidence(self.admin)
                path.unlink()

    def test_only_reverting_restart_tolerates_a_pending_recovery(self):
        (self.journal / 'recovery/PENDING.json').write_text('{}')
        self.assertEqual(acceptance.installation_evidence(self.admin, recovery_pending=True)['runId'], RUN)

    def test_missing_evidence_is_never_an_accepted_state(self):
        (self.journal / 'installation.json').unlink()
        with self.assertRaises(OSError):
            acceptance.installation_evidence(self.admin)

    def test_fresh_paired_backup_is_required(self):
        self.assertEqual(acceptance.verify_backup(manifest(), IMAGES, now=NOW), 'b' * 32)
        old = (NOW - acceptance.BACKUP_MAX_AGE - timedelta(seconds=1)).isoformat()
        for name, value in {'old': {'recoveryPoint': old}, 'future': {'recoveryPoint': (NOW + timedelta(hours=1)).isoformat()},
                            'unfenced': {'fence': 'none'}, 'rehearsal': {'environment': 'local'},
                            'unpaired': {'databases': ['videogame_platform']}, 'other images': {'images': {'postgres': 'x'}},
                            'no artefacts': {'artifacts': {}}, 'empty artefact': {'artifacts': {'x': {'bytes': 0}}}}.items():
            with self.subTest(name), self.assertRaises(trust.Refused):
                acceptance.verify_backup(manifest(**value), IMAGES, now=NOW)


class FakeRecovery:
    gate_volume = 'gameometro-production_recovery-state'
    images = IMAGES

    def __init__(self, log, gate=b'open', maintenance=b'none'):
        self.log, self.state, self.maintenance = log, gate, maintenance

    def lock(self):
        log = self.log

        class Lock:
            def __enter__(self):
                log.append('lock')

            def __exit__(self, *_):
                log.append('unlock')
        return Lock()

    def gate(self, action):
        return self.state if action == 'check' else self.maintenance

    def check_lease(self):
        pass

    def sql(self, database, query):
        self.log.append('sql:' + database)
        self.query = query


class Apply(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.admin = Path(self.tmp.name)
        self.log = []
        self.transition = acceptance.Transition(self.admin, ROOT, runner=self.docker)
        self.transition.evidence = {'runId': RUN, 'configuration': CONFIGURATION}
        self.transition.runtime = runtime()
        self.transition.runtime_file = self.admin / 'runtime.json'
        self.transition.source = self.admin
        self.transition.accepted = acceptance.accepted_configuration(self.transition.runtime, ROOT)
        self.transition.accepted_sha = acceptance.digest(acceptance.canonical(self.transition.accepted))
        self.backup = self.admin / 'manifest.json'
        self.backup.write_text(json.dumps(manifest()))
        self.recovery = FakeRecovery(self.log)
        self.running_failure = None
        verify_backup = acceptance.verify_backup
        self.scope = [patch.object(acceptance, 'load_recovery', side_effect=lambda *_: self.recovery),
                      # A fixed clock: the host's wall clock steps backwards, which a "now" fixture would read as future.
                      patch.object(acceptance, 'verify_backup', side_effect=lambda value, images: verify_backup(value, images, now=NOW)),
                      patch.object(acceptance.install, 'secure'),
                      patch.object(acceptance.install, 'verify_operational'),
                      patch.object(install.Host, 'compose', autospec=True, side_effect=self.compose),
                      patch.object(install.Host, 'running', autospec=True, side_effect=self.running)]
        for item in self.scope:
            item.start()
            self.addCleanup(item.stop)

    def docker(self, args, **_):
        args = [str(a) for a in args]
        if args[:3] == ['gh', 'auth', 'token']:
            return b'token'
        if args[:2] == ['docker', 'run']:
            self.log.append('gate-owner')
            self.assertIn(self.recovery.gate_volume + ':/gate:ro', args)
            return f'{RUN} released\n'.encode()
        if args[:2] == ['docker', 'update']:
            self.log.append('update:' + args[3] + ':' + args[4])
            return b''
        raise AssertionError(args)

    def compose(self, host, *args, **_):
        if args[0] == 'ps':
            name = args[-1]
            if name in acceptance.ONE_SHOT:
                return b''
            return name.encode()
        self.log.append(args[0] + ':' + ','.join(a for a in args if a in acceptance.RECREATED))
        return b''

    def running(self, host, names):
        self.log.append(('running', tuple(sorted(names)), host.config['services']['postgres']['restart']))
        if self.running_failure:
            raise self.running_failure

    def apply(self, reviewed=None, reference='#452 owner'):
        return self.transition.apply(reviewed or self.transition.accepted_sha, self.backup, reference, 'a' * 40, 'f' * 64)

    def test_order_record_and_exact_scope(self):
        record = self.apply()
        self.assertEqual(record['status'], 'applied')
        self.assertFalse(record['productionReady'])
        names = sorted(set(acceptance.PERSISTENT) - set(acceptance.RECREATED))
        self.assertEqual(self.log, [
            'lock', 'gate-owner', ('running', tuple(names), 'no'), 'sql:videogame_platform',
            'up:catalogue-localizer,grafana',
            *[f'update:unless-stopped:{name}' for name in names],
            ('running', tuple(sorted(acceptance.PERSISTENT)), 'unless-stopped'), 'unlock'])
        self.assertEqual(json.loads((self.admin / acceptance.RECORD).read_text())['status'], 'applied')
        self.assertEqual(acceptance.digest((self.admin / acceptance.ACCEPTED).read_text()), self.transition.accepted_sha)
        self.assertNotIn('migration', str(self.log))

    def test_view_runs_as_its_owner_and_grants_only_the_reader(self):
        self.apply()
        self.assertIn('SET LOCAL ROLE videogame_app_migrator', self.recovery.query)
        self.assertIn('GRANT SELECT ON catalogue.observability_localization_coverage TO videogame_grafana', self.recovery.query)
        self.assertEqual(self.recovery.query.count('GRANT'), 1)
        for setting in ('jit = off', 'max_parallel_workers_per_gather = 0'):
            self.assertIn(f'ALTER ROLE videogame_grafana SET {setting}', self.recovery.query)
            self.assertIn(f'ALTER ROLE videogame_grafana SET {setting}', (acceptance.ROOT / 'deploy/private-dev/grafana/provision-reader.sql').read_text())

    def assert_refused_without_change(self):
        with self.assertRaises(trust.Refused):
            self.apply()
        self.assertFalse(any(str(e).startswith(('update', 'up', 'sql')) for e in self.log))
        self.assertFalse((self.admin / acceptance.RECORD).exists())
        self.assertFalse((self.admin / acceptance.ACCEPTED).exists())

    def test_closed_gate_refuses(self):
        self.recovery.state = b'closed'
        self.assert_refused_without_change()

    def test_identity_maintenance_refuses(self):
        self.recovery.maintenance = RUN.encode()
        self.assert_refused_without_change()

    def test_live_drift_refuses(self):
        self.running_failure = trust.Refused('drift')
        self.assert_refused_without_change()

    def test_stale_backup_refuses(self):
        self.backup.write_text(json.dumps(manifest(recoveryPoint=(NOW - acceptance.BACKUP_MAX_AGE - timedelta(seconds=1)).isoformat())))
        self.assert_refused_without_change()

    def test_unreviewed_hash_or_missing_reference_refuses(self):
        with self.assertRaises(trust.Refused):
            self.apply(reviewed='0' * 64)
        with self.assertRaises(trust.Refused):
            self.apply(reference='')
        self.assertEqual(self.log, [])

    def test_interrupted_apply_leaves_a_record_and_can_resume(self):
        self.transition.set_policy = lambda *_: (_ for _ in ()).throw(trust.Refused('daemon'))
        with self.assertRaises(trust.Refused):
            self.apply()
        self.assertEqual(json.loads((self.admin / acceptance.RECORD).read_text())['status'], 'applying')
        del self.transition.set_policy
        self.assertEqual(self.apply()['status'], 'applied')

    def test_conflicting_previous_acceptance_refuses(self):
        (self.admin / acceptance.RECORD).write_text(json.dumps({'acceptedConfigurationSha256': '0' * 64}))
        with self.assertRaises(trust.Refused):
            self.apply()
        self.assertFalse(any(str(e).startswith(('update', 'up', 'sql')) for e in self.log))

    def test_verify_requires_a_completed_matching_record(self):
        with self.assertRaises(OSError):
            self.transition.verify()
        self.apply()
        self.assertEqual(self.transition.verify()['restartPolicy'], 'unless-stopped')
        drifted = copy.deepcopy(self.transition.accepted)
        drifted['services']['postgres']['restart'] = 'no'
        control.durable(self.admin / acceptance.ACCEPTED, drifted)
        with self.assertRaises(trust.Refused):
            self.transition.verify()

    def test_verify_reports_live_policy_drift(self):
        self.apply()
        self.running_failure = trust.Refused('Live image, health, restart or exposure mismatch.')
        with self.assertRaises(trust.Refused):
            self.transition.verify()

    def test_revert_changes_policy_only_and_blocks_verification(self):
        self.apply()
        self.log.clear()
        self.recovery.state = b'closed'
        result = self.transition.revert_restart()
        self.assertEqual(result['mode'], 'restart-reverted')
        self.assertEqual(self.log, ['lock', *[f'update:no:{n}' for n in sorted(acceptance.PERSISTENT)], 'unlock'])
        intended = json.loads((self.admin / acceptance.ACCEPTED).read_text())
        self.assertTrue(all(s['restart'] == 'no' for s in intended['services'].values()))
        self.assertEqual(intended['services']['catalogue-localizer']['mem_limit'], acceptance.LOCALIZER_MEMORY)
        with self.assertRaises(trust.Refused):
            self.transition.verify()


class HostContract(unittest.TestCase):
    def inspected(self, policy):
        return {'State': {'Running': True}, 'Config': {'Image': 'i'}, 'HostConfig': {'RestartPolicy': {'Name': policy}}}

    def refused_message(self, intended, live):
        host = install.Host('a', 'b', 'c')
        host.config = {'services': {'application': {'image': 'i', 'restart': intended}}}
        with patch.object(host, 'compose', return_value=b'id'), \
                patch.object(install, 'run', return_value=json.dumps([self.inspected(live)]).encode()):
            try:
                host.running(['application'])
            except trust.Refused as error:
                return str(error)
            except (KeyError, TypeError, AttributeError):
                return 'passed the restart comparison'
        return 'passed the restart comparison'

    def test_live_policy_must_match_the_intended_configuration(self):
        mismatch = 'Live image, health, restart or exposure mismatch.'
        self.assertEqual(self.refused_message('no', 'unless-stopped'), mismatch)
        self.assertEqual(self.refused_message('unless-stopped', 'no'), mismatch)
        self.assertNotEqual(self.refused_message('unless-stopped', 'unless-stopped'), mismatch)
        self.assertNotEqual(self.refused_message('no', 'no'), mismatch)


class Cli(unittest.TestCase):
    def test_no_force_or_install_mode_exists(self):
        script = Path(acceptance.__file__)
        for option in ('--force', '--install', '--update', '--rollback', '--reset'):
            result = subprocess.run(['python3', str(script), option], capture_output=True, timeout=30)
            self.assertEqual(result.returncode, 2)
            self.assertIn(b'unrecognized arguments', result.stderr)

    def test_non_root_invocation_refuses_without_docker_access(self):
        result = subprocess.run(['python3', str(Path(acceptance.__file__))], capture_output=True, timeout=30)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(json.loads(result.stdout)['outcome'], 'refused-or-failed')

    def test_only_reviewed_commands_are_issued(self):
        tree = ast.parse(Path(acceptance.__file__).read_text())
        literals = {n.value for n in ast.walk(tree) if isinstance(n, ast.Constant) and isinstance(n.value, str)}
        self.assertFalse(literals & {'down', 'rm', 'prune', 'kill', 'stop', '--force-recreate', '--volumes'})


if __name__ == '__main__':
    unittest.main()
