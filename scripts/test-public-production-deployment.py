#!/usr/bin/env python3
"""Deployment trust, serialization, refusal and recovery tests; no registry or host."""
import contextlib
import fcntl
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import public_production_deployment as deploy

REV = 'a' * 40
DIGEST = 'sha256:' + 'b' * 64
IMAGE = deploy.trust.IMAGE_REPOSITORY + '@' + DIGEST
CONFIG = 'c' * 64
SCHEMA = 'd' * 64
HISTORY = 'e' * 32


class Runtime:
    def __init__(self, directory):
        self.directory, self.evidence = directory, directory / 'evidence'
        self.evidence.mkdir()
        self.project = 'vgp-production-test-123456abcdef'
        self.file = directory / 'compose.json'
        self.selected = {'source_revision': REV, 'image_digest': DIGEST, 'images': {'application': IMAGE},
                         'applicationVersion': '0.30.0-SNAPSHOT', 'source_run_id': '123',
                         'source_run_attempt': '1', 'securityRunId': 456}
        self.config = {'services': {n: {'image': IMAGE if n in ('application', 'migration') else n + '@sha256:' + 'f' * 64}
                                   for n in ('application', 'migration', 'postgres', 'keycloak', 'caddy')},
                       'networks': {'data': {'name': self.project + '-data', 'internal': True}},
                       'volumes': {'data': {'name': self.project + '-data'}}, 'secrets': {}}
        secrets = directory / 'secrets'
        secrets.mkdir(mode=0o750)
        path = secrets / 'application-db-password'
        path.write_text('never-export-this-secret')
        path.chmod(0o640)
        self.config['secrets']['db'] = {'file': str(path)}
        self.save_config()
        self.calls = []
        self.app_image = None
        self.running = False
        self.migration_exit = 0
        self.history = HISTORY
        self.unhealthy = False
        self.smoke_failure = False

    def save_config(self):
        self.file.write_text(json.dumps(self.config))
        self.receipt = {'configurationSha256': hashlib.sha256(self.file.read_bytes()).hexdigest()}

    def cid(self, service):
        return '' if service == 'application' and self.app_image is None else service

    def inspect_image(self, image):
        return subprocess.CompletedProcess([], 0, json.dumps([{'Os': 'linux', 'Architecture': 'amd64',
                  'Config': {'Labels': {'org.opencontainers.image.revision': self.selected['source_revision'],
                                        'org.opencontainers.image.version': self.selected['applicationVersion']}}}]), '')

    def inspect(self, cid):
        image = self.app_image if cid == 'application' else self.config['services'][cid]['image']
        running = self.running if cid == 'application' else True
        health = 'unhealthy' if cid == 'application' and self.unhealthy else 'healthy'
        return subprocess.CompletedProcess([], 0, json.dumps([{'Config': {'Image': image},
                                                             'State': {'Running': running, 'Health': {'Status': health}}}]), '')

    def sql(self, query):
        return '20261020.120000' if 'LIMIT 1' in query else self.history

    def compose(self, *args, **kwargs):
        self.calls.append(args)
        if args[0] == 'stop':
            self.running = False
        if args[0] == 'exec':
            return subprocess.CompletedProcess(args, 0, json.dumps({'build': {'version': self.selected['applicationVersion'], 'sourceRevision': self.selected['source_revision']}}), '')
        code = self.migration_exit if args[0] == 'run' else 0
        return subprocess.CompletedProcess(args, code, 'private actor output', 'never-export-this-secret')

    def stop_application(self):
        return self.compose('stop', '--timeout', '30', 'application')

    def up(self, service, *, no_deps=False):
        assert no_deps, "Deployment must never recreate dependencies"
        self.calls.append(('activate', service))
        self.running, self.app_image = True, self.selected['images']['application']

    def probe(self, *args, **kwargs):
        self.calls.append(('smoke',))
        if self.smoke_failure:
            raise RuntimeError('never-export-this-secret')


class Deployments(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.runtime = Runtime(Path(self.tmp.name))
        self.api = unittest.mock.Mock()
        self.api.request.return_value = {'status': 'ahead'}
        self.deployer = deploy.Deployment(self.runtime, self.api, CONFIG, SCHEMA)
        self.selection = patch.object(deploy, 'verify_selection').start()
        self.configuration = patch.object(deploy, 'verify_configuration', return_value=SCHEMA).start()
        self.addCleanup(patch.stopall)

    def receipts(self):
        return [json.loads(p.read_text()) for p in self.runtime.evidence.glob('deployment-*.json')]

    def test_success_and_repeat_use_identical_immutable_actor_before_activation(self):
        first = self.deployer.run()
        second = self.deployer.run()
        self.assertEqual(first['outcome'], 'success')
        self.assertEqual(second['previousImage'], IMAGE)
        self.assertEqual(second['migrationVersion'], '20261020.120000')
        self.assertEqual(self.runtime.calls, [('stop', '--timeout', '30', 'application'),
                         ('run', '--rm', '--no-deps', 'migration'), ('activate', 'application'),
                         ('exec', '-T', 'application', 'wget', '-qO-', '-T', '15', 'http://127.0.0.1:8081/actuator/info'), ('smoke',)] * 2)
        self.assertEqual(len(self.receipts()), 2)
        self.selection.assert_called_with(self.api, self.runtime.selected)
        self.assertNotIn('never-export-this-secret', ''.join(p.read_text() for p in self.runtime.evidence.iterdir()))

    def test_first_release_and_retry_do_not_require_a_preexisting_flyway_table(self):
        history_available = False
        original = self.runtime.compose
        original_sql = self.runtime.sql
        def sql(query):
            if not history_available:
                raise AssertionError('Flyway history was queried before the migration actor created it')
            return original_sql(query)
        def compose(*args, **kwargs):
            nonlocal history_available
            result = original(*args, **kwargs)
            if args[0] == 'run' and result.returncode == 0:
                history_available = True
            return result
        with patch.object(self.runtime, 'sql', side_effect=sql), patch.object(self.runtime, 'compose', side_effect=compose):
            self.runtime.migration_exit = 1
            with self.assertRaises(deploy.trust.Refused):
                self.deployer.run()
            self.runtime.migration_exit = 0
            self.assertEqual(self.deployer.run()['outcome'], 'success')

    def test_invalid_provenance_and_configuration_prevent_all_mutation(self):
        for verifier in (self.selection, self.configuration):
            with self.subTest(verifier=verifier):
                verifier.side_effect = deploy.trust.Refused('private evidence body')
                with self.assertRaises(deploy.trust.Refused):
                    self.deployer.run()
                verifier.side_effect = None
                self.assertFalse(self.runtime.calls)
                self.assertFalse(self.deployer.state.exists())
        self.assertTrue(all(r['failureReason'] == 'preflight-refused' for r in self.receipts()))

    def test_concurrent_attempt_has_own_receipt_and_cannot_overwrite_state(self):
        self.deployer.run()
        before = self.deployer.state.read_bytes()
        self.runtime.calls.clear()
        with self.deployer.lock.open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with self.assertRaisesRegex(deploy.trust.Refused, 'Concurrent'):
                self.deployer.run()
        self.assertEqual(self.deployer.state.read_bytes(), before)
        self.assertEqual(self.runtime.calls, [])
        self.assertIn('concurrent-or-lock-unavailable', [r['failureReason'] for r in self.receipts()])

    def test_failed_migration_never_activates_and_forward_retry_is_possible(self):
        self.deployer.run()
        self.runtime.selected = {**self.runtime.selected, 'image_digest': DIGEST.replace('b', '1'), 'images': {'application': IMAGE.replace('b' * 64, '1' * 64)}}
        self.runtime.config['services']['application']['image'] = self.runtime.selected['images']['application']
        self.runtime.config['services']['migration']['image'] = self.runtime.selected['images']['application']
        self.runtime.save_config()
        self.runtime.calls.clear()
        self.runtime.migration_exit = 1
        with self.assertRaisesRegex(deploy.trust.Refused, 'Migration failed'):
            self.deployer.run()
        self.assertFalse(any(c[0] == 'activate' for c in self.runtime.calls))
        self.assertFalse(self.runtime.running)
        self.assertEqual(self.runtime.app_image, IMAGE)
        self.runtime.migration_exit = 0
        self.assertEqual(self.deployer.run()['outcome'], 'success')

    def test_readiness_and_smoke_failure_stop_candidate_and_preserve_failure(self):
        for flag, phase in (('unhealthy', 'readiness'), ('smoke_failure', 'smoke')):
            setattr(self.runtime, flag, True)
            with self.subTest(phase=phase), self.assertRaises(Exception):
                self.deployer.run()
            self.assertFalse(self.runtime.running)
            state = json.loads(self.deployer.state.read_text())
            self.assertEqual(state['outcome'], 'failure')
            self.assertEqual(state['phase'], phase)
            self.assertNotIn('never-export-this-secret', self.deployer.state.read_text())
            setattr(self.runtime, flag, False)
            self.deployer.run()

    def older_release(self):
        self.deployer.run()
        self.runtime.calls.clear()
        self.runtime.selected['source_revision'] = 'f' * 40
        self.api.request.return_value = {'status': 'behind'}

    def test_rollback_requires_explicit_request_and_identical_schema_and_history(self):
        self.older_release()
        with self.assertRaisesRegex(deploy.trust.Refused, 'explicit'):
            self.deployer.run()
        self.deployer.schema = '1' * 64
        self.configuration.return_value = self.deployer.schema
        with self.assertRaisesRegex(deploy.trust.Refused, 'compatibility'):
            self.deployer.run(rollback=True)
        self.deployer.schema = SCHEMA
        self.configuration.return_value = SCHEMA
        self.runtime.history = '2' * 32
        with self.assertRaisesRegex(deploy.trust.Refused, 'compatibility'):
            self.deployer.run(rollback=True)
        self.assertFalse(self.runtime.calls)
        self.runtime.history = HISTORY
        self.assertEqual(self.deployer.run(rollback=True)['outcome'], 'success')

    def test_applied_migrations_are_never_reverted(self):
        self.older_release()
        self.runtime.history = '0' * 32
        with self.assertRaises(deploy.trust.Refused):
            self.deployer.run(rollback=True)
        self.assertFalse(self.runtime.calls)

    def test_render_drift_exposure_missing_secret_and_actor_mismatch_refused(self):
        for mutate in (lambda: self.runtime.file.write_text('{}'),
                       lambda: self.runtime.config['services']['application'].update(ports=['443:443']),
                       lambda: self.runtime.config['networks']['data'].update(internal=False),
                       lambda: self.runtime.config['services']['migration'].update(image='latest'),
                       lambda: (self.runtime.directory / 'secrets/application-db-password').unlink()):
            # A fresh runtime for each independent violation.
            with tempfile.TemporaryDirectory() as directory:
                self.runtime = Runtime(Path(directory))
                self.deployer = deploy.Deployment(self.runtime, self.api, CONFIG, SCHEMA)
                mutate()
                if self.runtime.file.read_text() != '{}':
                    self.runtime.save_config()
                with self.assertRaises(deploy.trust.Refused):
                    self.deployer.run()
                self.assertFalse(self.runtime.calls)

    def test_wrong_candidate_labels_or_digest_are_refused_before_migration(self):
        with patch.object(self.runtime, 'inspect_image', return_value=subprocess.CompletedProcess([], 0,
                          json.dumps([{'Os': 'linux', 'Architecture': 'arm64', 'Config': {'Labels': {}}}]), '')):
            with self.assertRaisesRegex(deploy.trust.Refused, 'platform'):
                self.deployer.run()
        self.runtime.selected['image_digest'] = 'sha256:' + '0' * 64
        with self.assertRaisesRegex(deploy.trust.Refused, 'digest'):
            self.deployer.run()
        self.assertFalse(self.runtime.calls)

    def test_failure_to_stop_is_reported_without_raw_output(self):
        original = self.runtime.compose
        def compose(*args, **kwargs):
            if args[0] == 'stop':
                raise RuntimeError('never-export-this-secret')
            return original(*args, **kwargs)
        with patch.object(self.runtime, 'compose', side_effect=compose), self.assertRaises(RuntimeError):
            self.deployer.run()
        state = json.loads(self.deployer.state.read_text())
        self.assertEqual(state['stopResult'], 'failed-private-operator-action-required')
        self.assertNotIn('never-export-this-secret', self.deployer.state.read_text())
        self.assertFalse(any(c[0] == 'activate' for c in self.runtime.calls))

    def test_runtime_info_mismatch_fails_smoke_and_stops_candidate(self):
        original = self.runtime.compose
        def compose(*args, **kwargs):
            if args[0] == 'exec':
                return subprocess.CompletedProcess(args, 0, '{"build":{"sourceRevision":"wrong"}}', '')
            return original(*args, **kwargs)
        with patch.object(self.runtime, 'compose', side_effect=compose), self.assertRaisesRegex(deploy.trust.Refused, 'metadata'):
            self.deployer.run()
        self.assertFalse(self.runtime.running)
        self.assertEqual(json.loads(self.deployer.state.read_text())['failureReason'], 'smoke-failed')

    def test_dependency_missing_and_unrecorded_or_interrupted_state_refused(self):
        with patch.object(self.runtime, 'cid', return_value=''):
            with self.assertRaises(deploy.trust.Refused):
                self.deployer.run()
        self.runtime.app_image = IMAGE
        with self.assertRaises(deploy.trust.Refused):
            self.deployer.run()
        self.runtime.app_image = None
        deploy.atomic_json(self.deployer.state, {'outcome': 'running'})
        with self.assertRaises((KeyError, deploy.trust.Refused)):
            self.deployer.run()
        self.assertFalse(self.runtime.calls)

    def test_interruption_is_sanitized_and_stops_application(self):
        with patch.object(self.runtime, 'up', side_effect=KeyboardInterrupt):
            with self.assertRaises(KeyboardInterrupt):
                self.deployer.run()
        self.assertEqual(json.loads(self.deployer.state.read_text())['outcome'], 'failure')
        self.assertFalse(self.runtime.running)


class Inputs(unittest.TestCase):
    def test_real_host_is_blocked_without_any_docker_call(self):
        with patch.object(deploy.sys, 'argv', ['deploy']), self.assertRaisesRegex(deploy.trust.Refused, 'Real-host'):
            deploy.main()

    def test_manual_rehearsal_requires_all_reviewed_inputs(self):
        with patch.object(deploy.sys, 'argv', ['deploy', '--rehearsal']), self.assertRaisesRegex(deploy.trust.Refused, 'required'):
            deploy.main()

    def test_manual_command_dispatches_exact_reviewed_inputs_to_isolated_harness(self):
        import public_production_integration as integration
        args = ['deploy', '--rehearsal', '--revision', REV, '--digest', DIGEST,
                '--configuration-fingerprint', CONFIG, '--evidence', '/unused-evidence']
        with patch.object(deploy.sys, 'argv', args), patch.object(integration, 'main', return_value=0) as main:
            self.assertEqual(deploy.main(), 0)
        self.assertEqual(main.call_args.args[0], ['--revision', REV, '--digest', DIGEST,
                         '--configuration-fingerprint', CONFIG, '--evidence', '/unused-evidence', '--stage', 'E'])

    def test_configuration_checks_actual_bytes_against_exact_source(self):
        fingerprint = deploy.local_configuration()
        self.assertRegex(fingerprint, r'^[0-9a-f]{64}$')
        with patch.object(deploy, 'inventory', return_value='0' * 64), self.assertRaisesRegex(deploy.trust.Refused, 'fingerprint'):
            deploy.verify_configuration(unittest.mock.Mock(), REV, fingerprint)

    def test_fingerprint_includes_source_mode_and_inventory_rejects_unsafe_or_missing(self):
        entries = {'truncated': False, 'tree': [{'path': 'deploy/public-production/compose.yaml', 'type': 'blob',
                                               'mode': '100644', 'sha': 'a' * 40}]}
        first = deploy.inventory(entries, deploy.runtime_path)
        entries['tree'][0]['mode'] = '100755'
        self.assertNotEqual(first, deploy.inventory(entries, deploy.runtime_path))
        entries['tree'][0]['mode'] = '120000'
        with self.assertRaises(deploy.trust.Refused):
            deploy.inventory(entries, deploy.runtime_path)
        with self.assertRaises(deploy.trust.Refused):
            deploy.inventory({'truncated': False, 'tree': []}, deploy.runtime_path)


if __name__ == '__main__':
    unittest.main()
