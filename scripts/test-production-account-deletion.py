#!/usr/bin/env python3
"""Unit checks for the production self-service deletion runner (#543). No Docker, network or keys."""
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / 'deploy/public-production/bin/account-deletion'
TOKEN = 'c' * 64


def load(name, path):
    loader = importlib.machinery.SourceFileLoader(name, str(path))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


tool = load('account_deletion_under_test', TOOL)
fixtures = load('unverified_cleanup_fixtures', ROOT / 'scripts/test-production-unverified-cleanup.py')


class Fixture(fixtures.Fixture):
    def __init__(self, directory):
        super().__init__(directory)
        self.capability = self.admin / 'capability'
        self.capability.write_text(TOKEN + '\n')
        self.capability.chmod(0o600)
        self.own = self.admin / 'account-deletion.json'
        self.own.write_text(json.dumps({'cleanupConfig': str(self.path), 'credentialFile': str(self.capability)}))
        self.own.chmod(0o600)

    def runner(self):
        with patch.object(tool, 'APPLICATION_UID', os.getuid()):
            return tool.ProductionRunner(self.own)


def completed(stdout=b'', code=0):
    return subprocess.CompletedProcess([], code, stdout, b'')


class ConfigurationTests(unittest.TestCase):
    def test_the_erasure_reuses_the_cleanup_custody_and_transport_without_the_fence(self):
        with tempfile.TemporaryDirectory() as name:
            fixture = Fixture(name)
            actor = fixture.runner()
            ledger, fence = tool.cleanup.commands(fixture.config)
            config = actor.config
            self.assertEqual(config['policy'], 'independent')
            self.assertEqual(config['issuer'], 'https://auth.gameometro.com/realms/gameometro')
            # #564: the cleanup transport in its self-service mode; no global identity fence.
            self.assertNotIn('maintenance_command', config)
            cleanup_host = shlex.split(ledger[ledger.index('--host-command') + 1])
            self.assertEqual(config['host_command'],
                             [('--self-service' if word == '--identity-cleanup' else word) for word in cleanup_host])
            self.assertNotIn('--identity-cleanup', config['host_command'])
            self.assertNotIn(fence, json.dumps(config))
            self.assertEqual(config['cloud_copies'], fixture.config['cloudCopies'])
            for own, cleanup in (('ledger', 'ledger'), ('ledger_copy', 'ledgerCopy'), ('archive_root', 'archiveRoot'),
                                 ('archive_copy', 'archiveCopy'), ('proof_root', 'proofRoot'), ('holds', 'holds'),
                                 ('gnupg_home', 'gnupgHome'), ('recipient', 'recipient'), ('signer', 'signer')):
                self.assertEqual(config[own], fixture.config[cleanup])
            # Each claim rebuilds the bridge file privately, and the S3 interpreter runs it.
            child = actor.child()
            self.assertEqual(child[:3], [fixture.config['s3Python'], '-I', str(tool.runner.BRIDGE)])
            self.assertEqual(json.loads(actor.config_path.read_text()), config)
            self.assertEqual(actor.config_path.stat().st_mode & 0o777, 0o600)

    def test_configuration_and_capability_refusals(self):
        with tempfile.TemporaryDirectory() as name:
            fixture = Fixture(name)
            fixture.own.write_text(json.dumps({'cleanupConfig': str(fixture.path)}))
            self.assertRaises(tool.core.Refused, fixture.runner)
            fixture.own.write_text(json.dumps({'cleanupConfig': str(fixture.path), 'credentialFile': str(fixture.capability)}))
            for mode, value in ((0o640, TOKEN), (0o600, 'C' * 64), (0o600, 'c' * 63)):
                with self.subTest(mode=oct(mode), value=value[:2]):
                    fixture.capability.write_text(value)
                    fixture.capability.chmod(mode)
                    self.assertRaises(tool.core.Refused, fixture.runner)
            fixture.capability.write_text(TOKEN)
            fixture.capability.chmod(0o600)
            # Owned by another UID than the application's.
            self.assertRaises(tool.core.Refused, tool.ProductionRunner, fixture.own)


class TransportTests(unittest.TestCase):
    def test_exchange_sends_the_capability_on_stdin_to_the_loopback_port(self):
        with tempfile.TemporaryDirectory() as name:
            actor = Fixture(name).runner()
            calls = []
            answer = b'HTTP/1.1 200 \r\nContent-Type: application/json\r\nContent-Length: 15\r\n\r\n{"idle": true }'
            def run(argv, **options):
                calls.append((argv, options.get('input')))
                return completed(b'abc123\n' if argv[:2] == ['docker', 'ps'] else answer)
            with patch.object(tool.subprocess, 'run', side_effect=run):
                self.assertEqual(actor.exchange({'action': 'claim'}), {'idle': True})
            (ps, _), (exec_, request) = calls
            self.assertIn('label=com.docker.compose.project=gameometro-production', ps)
            self.assertIn('label=com.docker.compose.service=application', ps)
            self.assertEqual(exec_[:4], ['docker', 'exec', '-i', 'abc123'])
            self.assertEqual(exec_[-3:], ['nc', '127.0.0.1', '8081'])
            self.assertNotIn(TOKEN, ' '.join(exec_ + ps))
            self.assertIn(b'Authorization: Bearer ' + TOKEN.encode(), request)
            self.assertTrue(request.startswith(b'POST /actuator/accountdeletionexecutor HTTP/1.1\r\n'))
            self.assertTrue(request.endswith(b'\r\n\r\n{"action": "claim"}'))

    def test_exactly_one_running_application_is_required(self):
        with tempfile.TemporaryDirectory() as name:
            actor = Fixture(name).runner()
            for ids in (b'', b'a\nb\n'):
                with self.subTest(ids=ids), patch.object(tool.subprocess, 'run', return_value=completed(ids)):
                    self.assertRaises(tool.core.Refused, actor.exchange, {'action': 'heartbeat'})

    def test_responses_are_bounded_and_only_200_answers(self):
        self.assertEqual(tool.http_body(b'HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\n{"a":\r\n2\r\n1}\r\n0\r\n\r\n'), {'a': 1})
        self.assertEqual(tool.http_body(b'HTTP/1.1 200 \r\nContent-Length: 8\r\n\r\n{"a": 2}trailing'), {'a': 2})
        for raw in (b'HTTP/1.1 401 \r\nContent-Length: 2\r\n\r\n{}', b'HTTP/1.1 503 \r\n\r\n{}', b'garbage',
                    b'HTTP/1.1 200 \r\n\r\n' + b' ' * tool.MAX_RESPONSE):
            with self.subTest(raw=raw[:16]):
                self.assertRaises(Exception, tool.http_body, raw)


class CoordinationTests(unittest.TestCase):
    def test_an_active_upgrade_pauses_heartbeat_and_claims(self):
        with tempfile.TemporaryDirectory() as name:
            fixture = Fixture(name)
            actor = fixture.runner()
            self.assertFalse(actor.paused())
            journal = fixture.admin / 'upgrades/run-1/upgrade.json'
            journal.parent.mkdir(parents=True)
            for status, paused in (('running', True), ('failed', True), ('succeeded', False), ('rolled-back', False)):
                with self.subTest(status=status):
                    journal.write_text(json.dumps({'status': status}))
                    self.assertEqual(actor.paused(), paused)
            journal.write_text('{broken')
            self.assertTrue(actor.paused(), 'An unreadable journal pauses rather than races.')
            journal.write_text(json.dumps({'status': 'running'}))
            with patch.object(actor, 'exchange') as exchange:
                self.assertFalse(actor.tick())
                exchange.assert_not_called()


class UnitTests(unittest.TestCase):
    def test_the_unit_runs_the_reviewed_command_as_root_and_restarts(self):
        text = (ROOT / 'deploy/public-production/systemd/gameometro-account-deletion.service').read_text()
        self.assertIn('ExecStart=/usr/bin/python3 /opt/gameometro/runtime/deploy/public-production/bin/account-deletion run'
                      ' --config /etc/gameometro/production/account-deletion.json\n', text)
        self.assertIn('Restart=always\n', text)
        self.assertNotIn('User=', text)
        self.assertTrue(os.access(TOOL, os.X_OK))


class BridgeTests(unittest.TestCase):
    """#564: production self-service needs the cloud copies, not the global identity fence."""
    SUBJECT, USER, REQUEST = ('11111111-1111-4111-8111-111111111111', '22222222-2222-4222-8222-222222222222',
                              '33333333-3333-4333-8333-333333333333')

    def bridge(self):
        return load('self_service_bridge_under_test', tool.runner.BRIDGE)

    def config(self, **extra):
        paths = {key: '/protected/' + key for key in ('ledger', 'ledger_copy', 'proof_root', 'archive_root', 'archive_copy',
                                                      'gnupg_home', 'holds')}
        return {'policy': 'independent', 'issuer': tool.core.ISSUER, 'recipient': 'r', 'signer': 's',
                'host_command': ['transport', '--self-service'], **paths, **extra}

    def envelope(self):
        return {'version': 1, 'issuer': tool.core.ISSUER, 'subject': self.SUBJECT, 'userId': self.USER,
                'requestId': self.REQUEST}

    def test_production_refuses_without_cloud_copies(self):
        bridge = self.bridge()
        with patch.object(bridge.ledger, 'command_erase') as erase, self.assertRaisesRegex(ValueError, 'cloud copies'):
            bridge.execute(self.config(), self.envelope())
        erase.assert_not_called()

    def test_production_erases_without_a_maintenance_command(self):
        bridge = self.bridge()
        stop = RuntimeError('erasure reached')
        with patch.object(bridge.ledger.cloud_module, 'CloudCopies') as cloud, \
                patch.object(bridge.ledger, 'command_erase', side_effect=stop) as erase, \
                self.assertRaises(RuntimeError):
            bridge.execute(self.config(cloud_copies='/protected/cloud.json'), self.envelope())
        cloud.assert_called_once_with(Path('/protected/cloud.json'))
        arguments = erase.call_args.args[0]
        self.assertIsNone(arguments.maintenance_command)
        self.assertTrue(arguments.self_service)
        self.assertEqual(erase.call_args.args[1], ['transport', '--self-service'])


if __name__ == '__main__':
    unittest.main()
