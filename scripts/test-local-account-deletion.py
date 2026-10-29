#!/usr/bin/env python3
"""Local launcher qualification without touching any workstation account or database."""
import argparse
import contextlib
import importlib.machinery
import importlib.util
import io
import json
import os
import pathlib
import shutil
import shlex
import sys
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[1]
loader = importlib.machinery.SourceFileLoader('local_deletion', str(ROOT / 'scripts/local-account-deletion.py'))
spec = importlib.util.spec_from_loader(loader.name, loader)
local = importlib.util.module_from_spec(spec)
loader.exec_module(local)


class LocalDeletionTest(unittest.TestCase):
    def test_provisions_real_gpg_and_recovers_the_same_local_evidence_and_capability(self):
        with tempfile.TemporaryDirectory() as directory:
            state = pathlib.Path(directory) / 'protected/deletion'
            args = argparse.Namespace(origin='http://localhost:8180', admin_username='local-admin')
            with patch.object(local, 'STATE', state):
                for _ in range(2):
                    with patch('sys.stdin', io.StringIO('synthetic-admin-password')):
                        local.prepare(args)
                    config = json.loads((state / 'runner.json').read_text())
                    credential = (state / 'executor.key').read_text()
                    if _ == 0:
                        original = config.copy()
                        original_credential = credential
                        # Real existing engine preflight checks encryption/signature/archive/ledger.
                        bridge_loader = importlib.machinery.SourceFileLoader('local_runner_test', str(ROOT / 'deploy/private-dev/bin/personal-data-self-service-runner'))
                        bridge_spec = importlib.util.spec_from_loader(bridge_loader.name, bridge_loader)
                        runner = importlib.util.module_from_spec(bridge_spec)
                        bridge_loader.exec_module(runner)
                        runner.Runner(state / 'runner.json').preflight()
                        ledger = pathlib.Path(config['ledger']).read_bytes()
                    else:
                        self.assertEqual(config, original)
                        self.assertEqual(credential, original_credential)
                        self.assertEqual(pathlib.Path(config['ledger']).read_bytes(), ledger)
                    self.assertEqual((state / 'executor.key').stat().st_mode & 0o777, 0o600)
                    self.assertNotEqual(config['ledger'], config['ledger_copy'])
                    self.assertNotIn('synthetic-admin-password', json.dumps(config))
                subprocess.run(['gpgconf', '--homedir', str(state / 'keys'), '--kill', 'gpg-agent'], check=True)

    def test_launcher_prepares_before_starting_application_and_runs_executor_last(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = pathlib.Path(directory)
            (fixture / 'scripts').mkdir()
            (fixture / 'backend').mkdir()
            (fixture / 'bin').mkdir()
            for name in ('local-dependencies.sh', 'backend-artifact.sh'):
                shutil.copy(ROOT / 'scripts' / name, fixture / 'scripts' / name)
            shutil.copy(ROOT / 'pom.xml', fixture / 'pom.xml')
            log = fixture / 'commands'
            (fixture / 'bin/docker').write_text('#!/bin/sh\nprintf "docker %s\\n" "$*" >> "$LOCAL_DELETION_TEST_LOG"\n')
            (fixture / 'bin/python3').write_text(
                '#!/bin/sh\nif [ "$1" = "-" ]; then exec ' + shlex.quote(sys.executable) + ' "$@"; fi\n'
                'printf "python %s\\n" "$*" >> "$LOCAL_DELETION_TEST_LOG"\n'
                'case "$*" in *prepare*|*configure-keycloak-identity*) cat >/dev/null ;; esac\n')
            for name in ('docker', 'python3'):
                (fixture / 'bin' / name).chmod(0o700)
            environment = dict(os.environ, PATH=str(fixture / 'bin') + ':' + os.environ['PATH'],
                               LOCAL_DELETION_TEST_LOG=str(log), XDG_STATE_HOME=str(fixture / 'state'))
            command = ['bash', str(fixture / 'scripts/local-dependencies.sh')]
            subprocess.run(command + ['application', '--account-deletion'], env=environment,
                           capture_output=True, check=True)
            recorded = log.read_text()
            self.assertIn('compose.account-deletion.yaml', recorded)
            self.assertLess(recorded.index('local-account-deletion.py prepare'),
                            recorded.index('--profile full up --build --detach --wait'))
            self.assertLess(recorded.index('--profile full up --build --detach --wait'),
                            recorded.index('local-account-deletion.py run'))
            for arguments in (['up', '--account-deletion'],
                              ['application', '--account-deletion', '--account-deletion']):
                rejected = subprocess.run(command + arguments, env=environment, capture_output=True)
                self.assertNotEqual(rejected.returncode, 0)

    def test_compose_mounts_only_the_dedicated_private_capability(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = pathlib.Path(directory)
            for name in ('compose.yaml', 'compose.account-deletion.yaml'):
                shutil.copy(ROOT / name, fixture / name)
            (fixture / 'backend').mkdir()
            (fixture / 'backend/.env').write_text('OIDC_ISSUER_URI=http://localhost:8180/realms/videogame-platform\n')
            environment = dict(os.environ, APPLICATION_VERSION='synthetic',
                               LOCAL_ACCOUNT_DELETION_UID=str(os.getuid()),
                               LOCAL_ACCOUNT_DELETION_GID=str(os.getgid()))
            for name in ('POSTGRES_ADMIN_PASSWORD', 'APPLICATION_DB_PASSWORD',
                         'APPLICATION_MIGRATION_DB_PASSWORD', 'KEYCLOAK_DB_PASSWORD',
                         'KEYCLOAK_ADMIN_PASSWORD', 'KEYCLOAK_BFF_CLIENT_SECRET',
                         'LOCAL_TEST_USER_PASSWORD'):
                environment[name] = 'synthetic-placeholder'
            # Mailpit inheritance is resolved locally; no service is started.
            (fixture / 'deploy/private-dev').mkdir(parents=True)
            shutil.copy(ROOT / 'deploy/private-dev/compose.mail.yaml', fixture / 'deploy/private-dev/compose.mail.yaml')
            result = subprocess.run(['docker', 'compose', '--project-directory', str(fixture),
                                     '-f', str(fixture / 'compose.yaml'),
                                     '-f', str(fixture / 'compose.account-deletion.yaml'),
                                     '--profile', 'full', 'config', '--format', 'json'],
                                    env=environment, capture_output=True, check=True)
            service = json.loads(result.stdout)['services']['application']
            self.assertEqual(service['user'], f'{os.getuid()}:{os.getgid()}')
            self.assertEqual(service['environment']['ACCOUNT_DELETION_EXECUTOR_ENABLED'], 'true')
            self.assertNotIn('KEYCLOAK_ADMIN_PASSWORD', service['environment'])
            self.assertEqual(len(service['volumes']), 1)
            mount = service['volumes'][0]
            self.assertEqual(mount['target'], '/run/local-account-deletion/executor.key')
            self.assertTrue(mount['read_only'])
            self.assertTrue(all(port['host_ip'] == '127.0.0.1' for port in service['ports']))

    def test_refuses_remote_runtime_before_creating_state(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(local, 'STATE', pathlib.Path(directory) / 'absent'):
            for origin in ('https://vgpdev', 'http://example.com', 'http://localhost:8180@remote'):
                with self.assertRaises(ValueError):
                    local.prepare(argparse.Namespace(origin=origin, admin_username='local-admin'))
            self.assertFalse(local.STATE.exists())

    def test_refuses_symlink_state(self):
        with tempfile.TemporaryDirectory() as directory:
            target = pathlib.Path(directory) / 'target'
            target.mkdir()
            link = pathlib.Path(directory) / 'link'
            link.symlink_to(target, target_is_directory=True)
            with self.assertRaises(ValueError):
                local.private_directory(link)

    def test_refuses_foreign_checkout_before_starting_runner(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(local, 'STATE', pathlib.Path(directory)):
            (local.STATE / 'runner.json').write_text('{}')
            with patch.object(local, 'run', return_value=json.dumps([{'Config': {'Labels': {'com.docker.compose.project': 'videogame-platform-dev'}}}])):
                with self.assertRaisesRegex(ValueError, 'outside this local checkout'):
                    local.serve()


if __name__ == '__main__':
    unittest.main()
