#!/usr/bin/env python3
"""Unit checks for the unattended 30-day cleanup wrapper (#539). No Docker, network or keys."""
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / 'deploy/public-production/bin/unverified-cleanup'
UNITS = ROOT / 'deploy/public-production/systemd'
SIGNER = 'A' * 40
RECIPIENT = 'B' * 40


def load():
    loader = importlib.machinery.SourceFileLoader('unverified_cleanup_under_test', str(TOOL))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


cleanup = load()


class Fixture:
    """A private admin tree shaped like the VPS one, with a fake ledger process."""

    def __init__(self, directory):
        self.admin = Path(directory).resolve() / 'admin'
        privacy = self.admin / 'privacy'
        for path in (self.admin, self.admin / 'recovery', privacy, privacy / 'ledger', privacy / 'ledger-copy',
                     privacy / 'archive', privacy / 'archive-copy', privacy / 'proof', privacy / 'holds',
                     privacy / 'gnupg', self.admin / 'observability'):
            path.mkdir(mode=0o700, parents=True, exist_ok=True)
            path.chmod(0o700)
        for name in ('admin-password', 'cloud.json'):
            (privacy / name).write_text('{}')
            (privacy / name).chmod(0o600)
        (self.admin / 'compose.accepted.json').write_text('{}')
        self.config = {
            'admin': str(self.admin), 'project': 'gameometro-production', 'state': str(self.admin / 'recovery'),
            's3Python': sys.executable, 'status': str(self.admin / 'observability/unverified-cleanup.json'),
            'adminUsername': 'privacy-operator', 'adminPasswordFile': str(privacy / 'admin-password'),
            'ledger': str(privacy / 'ledger/ledger.jsonl'), 'ledgerCopy': str(privacy / 'ledger-copy/ledger.jsonl'),
            'cloudCopies': str(privacy / 'cloud.json'), 'archiveRoot': str(privacy / 'archive'),
            'archiveCopy': str(privacy / 'archive-copy'), 'proofRoot': str(privacy / 'proof'),
            'holds': str(privacy / 'holds'), 'gnupgHome': str(privacy / 'gnupg'), 'recipient': RECIPIENT, 'signer': SIGNER}
        self.path = self.admin / 'unverified-cleanup.json'
        self.write()
        self.calls = []
        self.answers = []

    def write(self, **changes):
        self.path.write_text(json.dumps({**self.config, **changes}))
        self.path.chmod(0o600)

    def ledger(self, arguments):
        self.calls.append(list(arguments))
        return self.answers.pop(0)

    @property
    def status(self):
        return json.loads(Path(self.config['status']).read_text())

    def subcommands(self):
        # The ledger subcommand follows --host-command and its value.
        return [call[call.index('--host-command') + 2:] for call in self.calls]


class ConfigurationTests(unittest.TestCase):
    def test_reviewed_configuration_loads(self):
        with tempfile.TemporaryDirectory() as name:
            fixture = Fixture(name)
            self.assertEqual(cleanup.load_configuration(fixture.path)['signer'], SIGNER)

    def test_unsafe_configurations_refuse(self):
        with tempfile.TemporaryDirectory() as name:
            fixture = Fixture(name)
            privacy = fixture.admin / 'privacy'
            for change in ({'signer': 'a' * 40}, {'recipient': 'B' * 16}, {'ledgerCopy': fixture.config['ledger']},
                           {'archiveCopy': fixture.config['archiveRoot']}, {'proofRoot': str(privacy / 'archive')},
                           {'ledger': '/tmp/ledger.jsonl'}, {'gnupgHome': '/root/.gnupg'}, {'project': 'Bad Name'},
                           {'adminUsername': 'x y'}, {'status': '/etc/passwd'}):
                fixture.write(**change)
                with self.subTest(change=change), self.assertRaises(cleanup.Refused):
                    cleanup.load_configuration(fixture.path)
            fixture.path.write_text(json.dumps({**fixture.config, 'extra': 'x'}))
            with self.assertRaises(cleanup.Refused):
                cleanup.load_configuration(fixture.path)
            fixture.write()
            fixture.path.chmod(0o644)
            with self.assertRaises(cleanup.Refused):
                cleanup.load_configuration(fixture.path)
            fixture.path.chmod(0o600)
            (privacy / 'gnupg').chmod(0o755)
            with self.assertRaises(cleanup.Refused):
                cleanup.load_configuration(fixture.path)

    def test_commands_use_the_cloud_copies_the_fence_and_the_live_cleanup_transport(self):
        with tempfile.TemporaryDirectory() as name:
            fixture = Fixture(name)
            ledger, fence = cleanup.commands(cleanup.load_configuration(fixture.path))
            for option, value in (('--cloud-copies', fixture.config['cloudCopies']), ('--signer', SIGNER),
                                  ('--recipient', RECIPIENT), ('--ledger-copy', fixture.config['ledgerCopy'])):
                self.assertEqual(ledger[ledger.index(option) + 1], value)
            host = shlex.split(ledger[ledger.index('--host-command') + 1])
            self.assertEqual(host[2], str(cleanup.TRANSPORT))
            self.assertIn('--identity-cleanup', host)
            self.assertEqual(host[host.index('--config') + 1], str(fixture.admin / 'compose.accepted.json'))
            self.assertEqual(shlex.split(fence)[2], str(cleanup.MAINTENANCE))
            self.assertNotIn('--identity-cleanup', shlex.split(fence))

    def test_active_upgrade_configuration_is_digest_checked(self):
        with tempfile.TemporaryDirectory() as name:
            fixture = Fixture(name)
            active = fixture.admin / 'upgrades/candidate.json'
            active.parent.mkdir()
            active.write_text('{"services": {}}')
            pointer = {'configurationFile': str(active), 'configurationSha256': cleanup.core.digest(active)}
            (fixture.admin / 'upgrades/ACTIVE.json').write_text(json.dumps(pointer))
            config = cleanup.load_configuration(fixture.path)
            self.assertEqual(cleanup.runtime_configuration(config), active)
            active.write_text('{"services": {"changed": {}}}')
            with self.assertRaises(cleanup.Refused):
                cleanup.runtime_configuration(config)

    def test_built_arguments_are_accepted_by_the_real_ledger(self):
        # An argparse refusal exits 2 with usage; reaching the cloud configuration proves every option parses.
        with tempfile.TemporaryDirectory() as name:
            fixture = Fixture(name)
            ledger, _ = cleanup.commands(cleanup.load_configuration(fixture.path))
            result = subprocess.run([*ledger, 'status'], capture_output=True, text=True, timeout=60)
            self.assertEqual(result.returncode, 1, result.stderr)
            self.assertIn('Cloud configuration', result.stderr)


class RunTests(unittest.TestCase):
    def run_with(self, fixture, answers):
        fixture.answers = list(answers)
        with patch.object(cleanup, 'ledger_run', side_effect=fixture.ledger):
            return cleanup.run(fixture.path, now=cleanup.core.timestamp('2026-12-08T06:15:00Z'))

    def test_quiet_day_records_success_without_retry(self):
        with tempfile.TemporaryDirectory() as name:
            fixture = Fixture(name)
            code = self.run_with(fixture, [(0, 'CLEAN-1\tuser\tcomplete\tsteps\n'),
                                           (0, '{"candidates": 0, "maintenance": false}\n')])
            self.assertEqual(code, 0)
            self.assertEqual([c[0] for c in fixture.subcommands()], ['status', 'cleanup-unverified'])
            self.assertIn('--execute', fixture.calls[1])
            status = fixture.status
            self.assertEqual((status['outcome'], status['attention'], status['erased']), ('success', False, 0))
            self.assertEqual(status['lastSuccessAt'], '2026-12-08T06:15:00Z')
            self.assertEqual(Path(fixture.config['status']).stat().st_mode & 0o777, 0o644)

    def test_incomplete_erasure_is_retried_before_any_new_cleanup(self):
        with tempfile.TemporaryDirectory() as name:
            fixture = Fixture(name)
            code = self.run_with(fixture, [(0, 'CLEAN-1\tuser\tINCOMPLETE\trevoke=done\n'), (0, 'Request CLEAN-1 completed.\n'),
                                           (0, '{"erased": 1, "skipped": 0, "backlog": false}\n')])
            self.assertEqual(code, 0)
            self.assertEqual([c[0] for c in fixture.subcommands()], ['status', 'retry', 'cleanup-unverified'])
            retry = fixture.calls[1]
            self.assertEqual(shlex.split(retry[retry.index('--maintenance-command') + 1])[2], str(cleanup.MAINTENANCE))
            self.assertEqual((fixture.status['retried'], fixture.status['erased']), (1, 1))

    def test_failed_retry_never_starts_a_cleanup_and_keeps_the_last_success(self):
        with tempfile.TemporaryDirectory() as name:
            fixture = Fixture(name)
            self.run_with(fixture, [(0, ''), (0, '{"candidates": 0}\n')])
            fixture.calls.clear()
            code = self.run_with(fixture, [(0, 'CLEAN-1\tuser\tINCOMPLETE\t-\n'), (1, '')])
            self.assertEqual(code, 1)
            self.assertEqual([c[0] for c in fixture.subcommands()], ['status', 'retry'])
            status = fixture.status
            self.assertEqual((status['outcome'], status['attention'], status['reason']), ('failure', True, 'retry-failed'))
            self.assertEqual(status['lastSuccessAt'], '2026-12-08T06:15:00Z')

    def test_backlog_repeats_bounded_rounds_and_stops_when_nothing_is_erased(self):
        with tempfile.TemporaryDirectory() as name:
            fixture = Fixture(name)
            full = (0, '{"erased": 100, "skipped": 0, "backlog": true}\n')
            self.run_with(fixture, [(0, ''), *[full] * cleanup.MAX_ROUNDS])
            self.assertEqual(len(fixture.calls), 1 + cleanup.MAX_ROUNDS)
            self.assertEqual((fixture.status['erased'], fixture.status['backlog']), (100 * cleanup.MAX_ROUNDS, True))
            fixture.calls.clear()
            self.run_with(fixture, [(0, ''), (0, '{"erased": 0, "skipped": 3, "backlog": true}\n')])
            self.assertEqual(len(fixture.calls), 2)
            self.assertEqual((fixture.status['outcome'], fixture.status['skipped']), ('success', 3))

    def test_failures_and_timeouts_are_recorded_with_a_bounded_reason(self):
        for answers, reason in (([(1, '')], 'ledger-unavailable'), ([(None, '')], 'timeout'),
                                ([(0, ''), (1, '')], 'cleanup-failed'), ([(0, ''), (None, '')], 'timeout'),
                                ([(0, ''), (0, 'not json\n')], 'unexpected-answer')):
            with self.subTest(reason=reason), tempfile.TemporaryDirectory() as name:
                fixture = Fixture(name)
                self.assertEqual(self.run_with(fixture, answers), 1)
                self.assertEqual((fixture.status['reason'], fixture.status['attention']), (reason, True))

    def test_refused_configuration_runs_nothing(self):
        with tempfile.TemporaryDirectory() as name:
            fixture = Fixture(name)
            fixture.write(signer='short')
            self.assertEqual(self.run_with(fixture, []), 1)
            self.assertEqual(fixture.calls, [])

    def test_check_is_read_only(self):
        with tempfile.TemporaryDirectory() as name:
            fixture = Fixture(name)
            fixture.answers = [(0, 'CLEAN-1\tuser\tcomplete\t-\n'), (0, '{"candidatesPresent": true, "dryRun": true}\n')]
            with patch.object(cleanup, 'ledger_run', side_effect=fixture.ledger), patch('builtins.print') as output:
                self.assertEqual(cleanup.check(fixture.path), 0)
            self.assertEqual([c[0] for c in fixture.subcommands()], ['status', 'cleanup-unverified'])
            self.assertTrue(all('--execute' not in c and '--maintenance-command' not in c for c in fixture.calls))
            self.assertEqual(json.loads(output.call_args.args[0])['candidatesPresent'], True)
            self.assertFalse(Path(fixture.config['status']).exists())


class UnitTests(unittest.TestCase):
    def test_service_and_timer_are_reviewed(self):
        service = (UNITS / 'gameometro-unverified-cleanup.service').read_text()
        self.assertEqual(service.count('ExecStart='), 1)
        self.assertIn('ExecStart=/usr/bin/python3 /opt/gameometro/runtime/deploy/public-production/bin/unverified-cleanup run'
                      ' --config /etc/gameometro/production/unverified-cleanup.json\n', service)
        for line in ('ProtectSystem=strict', 'NoNewPrivileges=true', 'ProtectHome=true', 'Type=oneshot',
                     'ReadWritePaths=/var/lib/gameometro/production /etc/gameometro/production/observability'):
            self.assertIn(line + '\n', service)
        timer = (UNITS / 'gameometro-unverified-cleanup.timer').read_text()
        self.assertIn('OnCalendar=*-*-* 06:15:00 UTC\n', timer)
        self.assertIn('Persistent=true\n', timer)
        # Clear of the backup slots (02:30 and 14:30 UTC) even with the randomized delay.
        backup = (UNITS / 'gameometro-backup.timer').read_text()
        self.assertIn('02:30:00 UTC', backup)
        self.assertIn('14:30:00 UTC', backup)

    def test_tool_is_executable(self):
        self.assertTrue(os.access(TOOL, os.X_OK))


if __name__ == '__main__':
    unittest.main()
