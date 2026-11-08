#!/usr/bin/env python3
"""Private-dev activation contract without host changes or real credentials."""
import importlib.machinery
import importlib.util
import json
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
loader = importlib.machinery.SourceFileLoader('configure_dev_deletion', str(ROOT / 'deploy/private-dev/bin/configure-account-deletion'))
spec = importlib.util.spec_from_loader(loader.name, loader)
setup = importlib.util.module_from_spec(spec); loader.exec_module(setup)


class PrivateDevDeletionTest(unittest.TestCase):
    def test_fixed_config_needs_no_ledger_archive_or_keys(self):
        config = setup.configuration({'PRIVATE_DEV_KEYCLOAK_ORIGIN': 'https://vgpdev.example.ts.net:8443',
                                      'COMPOSE_PROJECT_NAME': 'videogame-platform-dev'},
                                     pathlib.Path('/private/runtime.env'), pathlib.Path('/private/deletion'))
        self.assertEqual(config['policy'], 'private-dev')
        self.assertEqual(set(config), {'policy', 'issuer', 'host_command', 'management_url', 'credential_file'})
        self.assertIn('--private-dev-env-file', config['host_command'])
        self.assertEqual(config['management_url'], 'http://127.0.0.1:8081/actuator/accountdeletionexecutor')

    def test_configuration_refuses_a_public_host_or_other_project(self):
        for origin, project in [('https://auth.gameometro.com', 'videogame-platform-dev'),
                                ('https://vgpdev.example.ts.net:8443', 'production')]:
            with self.assertRaises(ValueError):
                setup.configuration({'PRIVATE_DEV_KEYCLOAK_ORIGIN': origin, 'COMPOSE_PROJECT_NAME': project},
                                    pathlib.Path('/private/env'), pathlib.Path('/private/deletion'))

    def test_compose_and_supervision_keep_administration_outside_web_application(self):
        with tempfile.TemporaryDirectory() as directory:
            env = pathlib.Path(directory) / 'runtime.env'
            env.write_text((ROOT / 'deploy/private-dev/runtime.env.example').read_text().replace('example-tailnet', 'validation')
                           + '\nPRIVATE_DEV_ACCOUNT_DELETION_DIRECTORY=' + directory
                           + '\nPRIVATE_DEV_ACCOUNT_DELETION_ENABLED=true'
                           + '\nPRIVATE_DEV_SECRETS_DIR=' + directory + '/secrets\n')
            output = subprocess.run(['docker', 'compose', '--env-file', str(env),
                                     '-f', str(ROOT / 'deploy/private-dev/compose.yaml'),
                                     '-f', str(ROOT / 'deploy/private-dev/compose.account-deletion.yaml'),
                                     '--profile', 'application', '--profile', 'deployment', 'config', '--format', 'json'],
                                    capture_output=True, check=True)
            config = json.loads(output.stdout)
            app = config['services']['application']
            self.assertEqual(app['environment']['ACCOUNT_DELETION_COMPLETION_POLICY'], 'private-dev')
            self.assertIn('private-dev', app['environment']['SPRING_PROFILES_ACTIVE'].split(','))
            self.assertNotIn('KEYCLOAK_ADMIN_PASSWORD', app['environment'])
            self.assertNotIn('keycloak_admin_password', [secret['source'] for secret in app['secrets']])
            self.assertFalse(any('docker.sock' in volume['source'] for volume in app['volumes']))
            management = [port for port in app['ports'] if port['target'] == 8081]
            self.assertEqual(len(management), 1)
            self.assertEqual(management[0]['host_ip'], '127.0.0.1')
            self.assertEqual(config['secrets']['account_deletion_executor_capability']['file'], directory + '/application.key')
            unit = (ROOT / 'deploy/private-dev/systemd/gameometro-account-deletion.service').read_text()
            self.assertIn('Restart=always', unit)
            self.assertIn('ProtectSystem=strict', unit)
            self.assertNotIn('ledger', unit)
            # Exercise the actual opt-in preflight branch, never start a container.
            for definition in config['secrets'].values():
                source = pathlib.Path(definition['file'])
                self.assertTrue(source.is_relative_to(directory))
                source.parent.mkdir(parents=True, exist_ok=True)
                source.parent.chmod(0o700)
                source.write_text('a' * 64)
                source.chmod(0o600)
            subprocess.run(['bash', str(ROOT / 'scripts/validate-private-dev-runtime.sh'),
                            '--env-file', str(env)], capture_output=True, check=True)


if __name__ == '__main__':
    unittest.main()
