#!/usr/bin/env python3
"""Focused refusal/isolation checks; no registry, running services or real credentials."""
import copy
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import subprocess
import sys
import unittest
from unittest.mock import patch
import zipfile

import public_production_integration as integration

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('runtime_contract', ROOT / 'scripts/test-public-production-runtime.py')
contract = importlib.util.module_from_spec(spec)
spec.loader.exec_module(contract)
REVISION = 'a' * 40
RUN = {'id': 123, 'run_started_at': '2026-10-20T00:00:00Z'}
REPO = integration.REPOSITORIES['keycloak']
IMAGE = REPO + '@sha256:' + 'b' * 64


class Publications(unittest.TestCase):
    def publication(self, *, lines=None, change=None, files=None):
        artifact = {'id': 77, 'name': f'dependency-image-publication-keycloak-{REVISION}',
                    'expired': False, 'created_at': '2026-10-20T00:01:00Z'}
        listing = {'total_count': 1, 'artifacts': [artifact]}
        if change:
            change(listing)
        record = lines if lines is not None else f'tag={REPO}:{REVISION}\ndigest={IMAGE}\nrevision={REVISION}\n'
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, 'w') as bundle:
            for name in files or ['published-image.txt']:
                bundle.writestr(name, record)
        class API:
            def request(self, _path, archive=False):
                return globals_archive if archive else listing
        globals_archive = archive.getvalue()
        return integration.dependency_publication(API(), RUN, REVISION, 'keycloak')

    def test_exact_digest(self):
        self.assertEqual(self.publication(), IMAGE)

    def test_absent_expired_ambiguous_or_previous_attempt(self):
        for change in [lambda d: d.update(artifacts=[]),
                       lambda d: d['artifacts'][0].update(expired=True),
                       lambda d: d['artifacts'].append(dict(d['artifacts'][0])),
                       lambda d: d['artifacts'][0].update(created_at='2026-10-19T00:00:00Z'),
                       lambda d: d.update(total_count=101)]:
            with self.subTest(change=change), self.assertRaises(integration.trust.Refused):
                self.publication(change=change)

    def test_mutable_wrong_repository_source_and_duplicate_fields(self):
        good = f'tag={REPO}:{REVISION}\ndigest={IMAGE}\nrevision={REVISION}\n'
        for record in [good.replace(IMAGE, REPO + ':latest'), good.replace(IMAGE, IMAGE.replace(REPO, 'ghcr.io/other/image')),
                       good.replace('revision=' + REVISION, 'revision=' + 'c' * 40),
                       good + f'digest={IMAGE}\n', good.replace(f'tag={REPO}:{REVISION}', f'tag={REPO}:main')]:
            with self.subTest(record=record), self.assertRaises(integration.trust.Refused):
                self.publication(lines=record)

    def test_unexpected_archive(self):
        with self.assertRaises(integration.trust.Refused):
            self.publication(files=['published-image.txt', 'extra.txt'])


class Isolation(unittest.TestCase):
    def test_production_config_is_preserved_with_only_test_substitutions(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            env, _, _ = contract.synthetic_inputs(directory)
            original = contract.render(env)
            config = integration.isolated_config(copy.deepcopy(original), 'vgp-production-test-unit', directory)
        for service in original['services']:
            before, after = original['services'][service], config['services'][service]
            for invariant in ['image', 'mem_limit', 'cpus', 'pids_limit', 'cap_drop', 'security_opt', 'read_only', 'entrypoint', 'secrets']:
                self.assertEqual(before.get(invariant), after.get(invariant), (service, invariant))
            self.assertFalse(after.get('ports'))
            self.assertNotIn('build', after)
        self.assertTrue(all(n['internal'] for n in config['networks'].values()))
        self.assertTrue(all(v['name'].startswith('vgp-production-test-unit-') for v in config['volumes'].values()))
        self.assertEqual(config['services']['application']['environment']['OIDC_ISSUER_URI'], 'https://auth.gameometro.com/realms/gameometro')
        self.assertEqual(config['services']['caddy']['networks']['application-edge']['ipv4_address'], '10.203.1.2')
        self.assertEqual(set(config['services']['postgres']['networks']), {'application-data', 'identity-data'})
        self.assertNotIn('test-client', config['services']['keycloak']['networks'])

    def test_model_missing_wrong_or_corrupt_is_refused_without_download(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            with self.assertRaises(FileNotFoundError):
                integration.verify_model(directory)
            approved = json.loads((ROOT / 'tools/catalogue-localization/models.json').read_text())['tcbig']
            for manifest in [{'model': {'sha256': 'wrong'}, 'files': {}}, {'model': approved, 'files': {}}]:
                (directory / 'manifest.json').write_text(json.dumps(manifest))
                with self.assertRaises(integration.trust.Refused):
                    integration.verify_model(directory)

    def test_production_bootstrap_reads_before_gosu_and_refuses_empty(self):
        # A real shell refusal is meaningful even without starting PostgreSQL.
        import os
        import subprocess
        with tempfile.TemporaryDirectory() as temporary:
            empty = Path(temporary) / 'empty'
            empty.touch()
            env = {**os.environ, 'APPLICATION_DB_PASSWORD_FILE': str(empty)}
            result = subprocess.run(['bash', ROOT / 'deploy/public-production/bin/run-postgres'], env=env, text=True, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('empty', result.stderr)
            self.assertNotIn('docker-entrypoint', result.stderr)


class FailureLifecycle(unittest.TestCase):
    def test_unavailable_image_refuses_before_any_compose_start(self):
        with tempfile.TemporaryDirectory() as temporary:
            evidence = Path(temporary) / 'evidence'
            calls = []
            def command(args, **_kwargs):
                calls.append(args)
                if args == ['gh', 'auth', 'token']:
                    return subprocess.CompletedProcess(args, 0, 'never-export-this-token', '')
                if args[:2] == ['docker', 'pull']:
                    return subprocess.CompletedProcess(args, 1, '', 'private registry')
                self.fail('Unexpected command after unavailable immutable image')
            with patch.object(sys, 'argv', ['exercise', '--revision', REVISION, '--evidence', str(evidence)]), \
                 patch.object(integration, 'resolve', return_value={'images': {'application': IMAGE}}), \
                 patch.object(integration, 'command', side_effect=command), \
                 contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(integration.main(), 1)
            text = (evidence / 'receipt.json').read_text()
            self.assertIn('unavailable', text)
            self.assertNotIn('never-export-this-token', text)
            self.assertFalse(any('compose' in call for call in calls))

    def test_cleanup_includes_every_profile(self):
        exercise = integration.Exercise(Path('/unused'), Path('/unused-evidence'), {}, None)
        with patch.object(integration, 'command') as command:
            exercise.compose('down', '--volumes', '--remove-orphans')
            self.assertIn('--profile', command.call_args.args[0])
            self.assertEqual(command.call_args.args[0][command.call_args.args[0].index('--profile') + 1], '*')

    def test_oom_is_failure_after_resources_are_removed(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            exercise = integration.Exercise(directory, directory, {}, None)
            exercise.file.touch()
            event = {'Action': 'oom', 'Actor': {'Attributes': {'com.docker.compose.service': 'keycloak'}}}
            def command(args, **_kwargs):
                return subprocess.CompletedProcess(args, 0, json.dumps(event) if args[:2] == ['docker', 'events'] else '', '')
            with patch.object(integration, 'command', side_effect=command), patch.object(exercise, 'compose') as compose:
                with self.assertRaisesRegex(integration.trust.Refused, 'OOM'):
                    exercise.cleanup()
                compose.assert_called_once_with('down', '--volumes', '--remove-orphans', '--timeout', '15')
            self.assertEqual(exercise.receipt['teardown'], 'passed')


if __name__ == '__main__':
    unittest.main()
