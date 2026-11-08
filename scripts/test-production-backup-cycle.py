#!/usr/bin/env python3
"""The scheduled cycle's failure paths on the real recovery core with a fake runtime and S3.

No host, Docker daemon, provider or account is used. These prove the cycle's decisions; only
a run on the real host can prove how long the fence lasts or that its services come back.
"""
import contextlib
import datetime as dt
import hashlib
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import time
import unittest
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.dont_write_bytecode = True


def module(name, path):
    loader = importlib.machinery.SourceFileLoader(name, str(path))
    spec = importlib.util.spec_from_loader(name, loader)
    loaded = importlib.util.module_from_spec(spec)
    sys.modules[name] = loaded
    loader.exec_module(loaded)
    return loaded


cycle = module('backup_cycle_under_test', ROOT / 'deploy/public-production/bin/backup-cycle')
watch = module('backup_watch_under_test', ROOT / 'deploy/public-production/bin/backup-watch')
core = cycle.load_core()
NOW = dt.datetime(2026, 11, 7, 2, 30, tzinfo=dt.timezone.utc)
GiB = 1024 ** 3


class FakeRuntime:
    """Records what would reach Docker; the journal and gate behave as the real actor's do."""

    def __init__(self, state, test):
        self.state, self.test = state, test
        self.calls, self.fail = [], {}
        self.gate_state, self.maintenance = b'open', b'none'
        self.running = {'keycloak', 'caddy', 'application', 'grafana'}
        self.sync, self.sizes = b'0', (b'%d|%d' % (2 * GiB, GiB))
        self.lease_free, self.monitoring, self.cycle_run = True, True, None

    @contextlib.contextmanager
    def lock(self):
        core.require(self.lease_free, 'Another recovery/backup actor is active.')
        self.calls.append('lock')
        yield

    def trip(self, name):
        if name in self.fail:
            failure = self.fail[name]
            if failure is not None:
                self.fail[name] = failure - 1 if failure > 1 else None
                if failure == 1:
                    self.fail.pop(name)
                raise core.Refused(f'{name} failed')

    def gate(self, action, run_id=None):
        return self.maintenance if action == 'maintenance-check' else self.gate_state

    def compose(self, *arguments, **_):
        if arguments[0] == 'ps':
            return b'id\n' if arguments[-1] in self.running else b''
        if arguments[0] == 'up':
            self.trip('start-' + arguments[-1])
            self.running.add(arguments[-1])
            self.calls.append('start-' + arguments[-1])
            return b''
        self.trip('smoke')
        self.calls.append('smoke')
        return b''

    def sql(self, _database, query):
        return self.sync if 'synchronization_run' in query else self.sizes

    def write_pending(self, phase):
        core.write(self.state / 'PENDING.json', {'phase': phase, 'backupId': None, 'cycleRun': self.cycle_run})

    def backup(self, destination, _keyring, _recipient):
        self.calls.append('backup')
        self.gate_state = b'closed'
        self.running -= {'keycloak', 'caddy', 'application', 'grafana'}
        self.write_pending('quiescing')
        self.trip('backup')
        if 'hang' in self.fail:
            time.sleep(5)
        self.test.pair(destination)
        self.write_pending('backup-complete-writers-still-fenced')

    def thaw(self):
        self.trip('thaw')
        self.calls.append('thaw')
        self.gate_state = b'open'
        (self.state / 'PENDING.json').unlink()

    def reopen_capture(self, run_id):
        self.trip('reopen')
        self.calls.append('reopen-capture')
        pending = self.state / 'PENDING.json'
        if pending.exists():
            value = json.loads(pending.read_text())
            core.require(value.get('cycleRun') == run_id and value.get('phase') in core.CAPTURE_PHASES, 'Foreign fence.')
        self.gate_state = b'open'
        pending.unlink(missing_ok=True)


class CycleTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.root.chmod(0o700)
        self.now = NOW
        clock = patch.object(core, 'utc', side_effect=lambda: self.now)
        clock.start()
        self.addCleanup(clock.stop)
        key = patch.object(core, 'public_key')
        key.start()
        self.addCleanup(key.stop)
        self.state = self.root / 'state'
        self.work = self.root / 'work'
        for directory in (self.state, self.work):
            directory.mkdir(mode=0o700)
        self.runtime = FakeRuntime(self.state, self)
        self.uploader = self.credential('uploader.json', 'upload-key')
        self.reader = self.credential('admission.json', 'reader-key')
        self.config = {'authorized': True, 'admin': str(self.root / 'admin'), 'project': 'vgp-production-test-unit', 'state': str(self.state),
                       'keyring': str(self.root / 'keyring'), 'recipient': 'A' * 40, 'work': str(self.work),
                       'uploaderConfig': str(self.uploader), 'admissionConfig': str(self.reader), 's3Python': '/usr/bin/python3',
                       'status': str(self.root / 'status.json'), 'maxFenceSeconds': 1}
        self.s3_calls, self.s3_fail = [], set()
        self.memory = SimpleNamespace(total=100 * GiB, free=50 * GiB)
        self.disk = SimpleNamespace(total=100 * GiB, free=50 * GiB)
        self.cycle = self.make()

    def make(self):
        return cycle.Cycle(self.config, core, runtime=lambda: self.runtime, invoke=self.invoke, sleep=lambda _: None,
                           usage=lambda path: self.memory if path == '/dev/shm' else self.disk).load()

    def credential(self, name, key):
        path = self.root / name
        path.write_text(json.dumps({'accessKey': key}))
        path.chmod(0o600)
        return path

    def invoke(self, command, *arguments, **_):
        self.s3_calls.append((command, arguments))
        if command in self.s3_fail:
            raise core.Refused(f'S3 {command} failed; see its protected receipt.')
        receipt = self.root / f'{command}-{len(self.s3_calls)}.json'
        receipt.write_text(json.dumps({'alerts': []}))
        return receipt

    def pair(self, destination):
        destination.mkdir(mode=0o700)
        artifacts = {}
        for name in core.FILES[:2]:
            data = b'synthetic ciphertext'
            (destination / name).write_bytes(data)
            artifacts[name] = {'bytes': len(data), 'ciphertextSha256': hashlib.sha256(data).hexdigest(), 'plaintextSha256': 'a' * 64}
        core.write(destination / 'manifest.json', {'format': 1, 'environment': 'production', 'issuer': core.ISSUER, 'databases': list(core.DATABASES),
                                                   'fence': 'roles-nologin-drained', 'backupId': 'a' * 32, 'recoveryPoint': self.now.isoformat(), 'artifacts': artifacts})
        (destination / 'SHA256SUMS').write_text(''.join(f'{core.digest(destination / n)}  {n}\n' for n in core.FILES[:-1]))

    def published(self):
        return [c for c, _ in self.s3_calls if c == 'publish']

    def status(self):
        return json.loads(Path(self.config['status']).read_text())

    def assert_open(self):
        self.assertEqual(self.runtime.gate_state, b'open')
        self.assertFalse((self.state / 'PENDING.json').exists())
        self.assertLessEqual({'keycloak', 'caddy', 'application'}, self.runtime.running)

    # --- the normal cycle ---
    def test_captures_reopens_in_dependency_order_then_uploads(self):
        self.assertEqual(self.cycle.run(), 0)
        self.assertEqual(self.runtime.calls, ['lock', 'backup', 'thaw', 'start-keycloak', 'start-caddy', 'start-application', 'start-grafana', 'smoke'])
        self.assert_open()
        self.assertEqual([c for c, _ in self.s3_calls], ['admission', 'admission', 'publish'])
        status = self.status()
        self.assertEqual((status['kind'], status['outcome'], status['status'], status['attention']), ('backup-cycle', 'success', 'idle', False))
        self.assertEqual(status['lastUploadRecoveryPoint'], NOW.isoformat())

    def test_only_the_last_uploaded_pair_stays_on_the_host(self):
        self.assertEqual(self.cycle.run(), 0)
        first = self.cycle.journal['pair']
        self.now += dt.timedelta(hours=12)
        self.assertEqual(self.cycle.run(), 0)
        self.assertEqual([p.name for p in self.work.joinpath('pairs').iterdir()], [Path(self.cycle.journal['pair']).name])
        self.assertNotEqual(first, self.cycle.journal['pair'])

    def test_a_service_stopped_on_purpose_stays_stopped(self):
        self.runtime.running.discard('grafana')
        self.assertEqual(self.cycle.run(), 0)
        self.assertNotIn('start-grafana', self.runtime.calls)

    def test_an_optional_service_never_fails_the_product(self):
        self.runtime.fail['start-grafana'] = 1
        self.assertEqual(self.cycle.run(), 0)
        self.assert_open()
        self.assertEqual(self.status()['optionalDegraded'], ['grafana'])

    # --- predictable failures cost no downtime ---
    def test_preflight_refusals_never_fence(self):
        def gate(): self.runtime.gate_state = b'closed'
        def maintenance(): self.runtime.maintenance = b'11111111-1111-1111-1111-111111111111'
        def pending(): core.write(self.state / 'PENDING.json', {'phase': 'restoring'})
        def service(): self.runtime.running.discard('caddy')
        def sync(): self.runtime.sync = b'1'
        def disk(): self.disk.free = 16 * GiB
        def memory(): self.memory.free = GiB - 1
        def admission(): self.s3_fail.add('admission')
        def key(): core.public_key.side_effect = core.Refused('Private decryption keys are forbidden on the runtime.')
        def shared(): self.credential('admission.json', 'upload-key')
        def upgrade():
            journal = self.root / 'admin/upgrades/abc/upgrade.json'
            journal.parent.mkdir(parents=True)
            journal.write_text(json.dumps({'status': 'failed'}))
        for fault in (gate, maintenance, pending, service, sync, disk, memory, admission, key, shared, upgrade):
            with self.subTest(fault.__name__):
                self.setUp()
                fault()
                self.assertEqual(self.make().run(), 1)
                self.assertNotIn('backup', self.runtime.calls)
                self.assertFalse(self.published())
                self.assertEqual(self.status()['lastFailure'], 'preflight-refused')
                self.assertFalse(self.status()['attention'])
                core.public_key.side_effect = None

    def test_refuses_without_the_owner_authorization(self):
        self.config['authorized'] = False
        with self.assertRaises(core.Refused):
            self.make().run()
        self.assertFalse(self.runtime.calls)

    def test_overlap_refuses_and_reconcile_leaves_it_alone(self):
        with self.cycle.exclusive():
            other = self.make()
            with self.assertRaises(core.Refused):
                other.run()
            self.assertEqual(other.reconcile(), 0)
        self.assertFalse(self.runtime.calls)

    def test_lease_lost_before_the_fence_fences_nothing(self):
        self.runtime.lease_free = False
        self.assertEqual(self.cycle.run(), 1)
        self.assertNotIn('backup', self.runtime.calls)
        self.assertEqual(self.cycle.journal['status'], 'idle')

    # --- failed captures reopen ---
    def test_failed_capture_reopens_and_claims_no_upload(self):
        self.runtime.fail['backup'] = 1
        self.assertEqual(self.cycle.run(), 1)
        self.assert_open()
        self.assertIn('reopen-capture', self.runtime.calls)
        self.assertFalse(self.published())
        self.assertEqual(self.status()['lastFailure'], 'capture-failed')
        self.assertIsNone(self.status()['lastUploadAt'])

    def test_capture_over_its_fence_budget_reopens(self):
        self.runtime.fail['hang'] = None
        started = time.monotonic()
        self.assertEqual(self.cycle.run(), 1)
        self.assertLess(time.monotonic() - started, 4)
        self.assert_open()
        self.assertEqual(self.status()['lastFailure'], 'capture-failed')

    def test_failed_thaw_reopens_and_still_uploads_the_valid_pair(self):
        self.runtime.fail['thaw'] = 1
        self.assertEqual(self.cycle.run(), 0)
        self.assert_open()
        self.assertEqual(len(self.published()), 1)

    def test_reopening_retries_until_the_owner_is_needed(self):
        self.runtime.fail['backup'] = 1
        self.runtime.fail['reopen'] = 9
        self.assertEqual(self.cycle.run(), 1)
        self.assertEqual(self.cycle.journal['status'], 'reopening')
        self.assertTrue(self.status()['attention'])
        self.assertEqual(self.cycle.reconcile(), 0)
        self.assertEqual(self.cycle.reconcile(), 1)
        self.assertEqual(self.cycle.journal['status'], 'owner-required')
        calls = len(self.runtime.calls)
        self.assertEqual(self.cycle.reconcile(), 1)
        self.assertEqual(self.cycle.run(), 1)
        self.assertEqual(len(self.runtime.calls), calls, 'No further automatic action once the owner is needed.')

    def test_transient_reopen_failure_recovers_on_the_next_tick(self):
        self.runtime.fail['backup'] = 1
        self.runtime.fail['reopen'] = 1
        self.cycle.run()
        self.assertEqual(self.cycle.reconcile(), 0)
        self.assert_open()
        self.assertEqual(self.cycle.journal['status'], 'idle')

    def test_failed_required_service_stays_visible_and_retries(self):
        self.runtime.fail['start-application'] = 1
        self.assertEqual(self.cycle.run(), 1)
        self.assertEqual(self.cycle.journal['status'], 'reopening')
        self.assertFalse(self.published())
        self.assertEqual(self.cycle.reconcile(), 0)
        self.assert_open()
        self.assertEqual(len(self.published()), 1, 'The valid pair uploads once the product is back.')

    def test_smoke_failure_keeps_retrying_inside_its_budget(self):
        self.runtime.fail['smoke'] = 2
        self.assertEqual(self.cycle.run(), 0)
        self.assert_open()

    # --- crashes, reboots and fences that are not ours ---
    def crash_mid_capture(self, phase='quiescing', pair=False):
        run = 'b' * 32
        self.cycle.journal.update(status='capturing', runId=run, pair=str(self.work / f'pairs/20261107T023000Z-{run}'), fenceEntered=True)
        self.cycle.save()
        self.runtime.cycle_run = run
        self.runtime.gate_state = b'closed'
        self.runtime.running -= {'keycloak', 'caddy', 'application'}
        if phase:
            self.runtime.write_pending(phase)
        if pair:
            self.pair(Path(self.cycle.journal['pair']))

    def test_reboot_mid_capture_reopens_without_upload(self):
        self.crash_mid_capture()
        self.assertEqual(self.make().reconcile(), 0)
        self.assert_open()
        self.assertFalse(self.published())

    def test_crash_before_the_journal_was_written_reopens(self):
        self.crash_mid_capture(phase=None)
        self.assertEqual(self.make().reconcile(), 0)
        self.assert_open()

    def test_reboot_after_the_pair_was_published_uploads_it(self):
        self.crash_mid_capture('backup-complete-writers-still-fenced', pair=True)
        self.assertEqual(self.make().reconcile(), 0)
        self.assert_open()
        self.assertEqual(len(self.published()), 1)

    def test_fence_replaced_after_a_crash_is_left_for_its_owner(self):
        self.crash_mid_capture()
        for phase in ('upgrade-claimed', 'restoring', 'deletion-replay-required'):
            with self.subTest(phase):
                core.write(self.state / 'PENDING.json', {'phase': phase, 'backupId': None})
                fresh = self.make()
                fresh.journal.update(status='capturing')
                self.assertEqual(fresh.reconcile(), 1)
                self.assertEqual(fresh.journal['status'], 'owner-required')
                self.assertEqual(self.runtime.gate_state, b'closed')
                self.assertNotIn('reopen-capture', self.runtime.calls)

    def test_manual_backup_fence_is_left_for_its_owner(self):
        self.crash_mid_capture()
        core.write(self.state / 'PENDING.json', {'phase': 'backup-complete-writers-still-fenced', 'backupId': 'x'})
        fresh = self.make()
        self.assertEqual(fresh.reconcile(), 1)
        self.assertEqual(self.runtime.gate_state, b'closed')

    def test_closed_gate_this_run_never_entered_is_not_ours(self):
        self.crash_mid_capture()
        self.cycle.journal['fenceEntered'] = False
        self.cycle.save()
        fresh = self.make()
        fresh.reconcile()
        self.assertEqual(self.runtime.gate_state, b'closed')
        self.assertEqual(fresh.journal['status'], 'idle')

    def test_acknowledge_requires_a_resolved_fence(self):
        self.crash_mid_capture()
        core.write(self.state / 'PENDING.json', {'phase': 'restoring'})
        fresh = self.make()
        fresh.reconcile()
        with self.assertRaises(core.Refused):
            fresh.acknowledge()
        (self.state / 'PENDING.json').unlink()
        self.runtime.gate_state = b'open'
        self.assertEqual(fresh.acknowledge(), 0)
        self.assertEqual(fresh.journal['status'], 'idle')

    def test_reconcile_with_nothing_to_do_touches_nothing(self):
        self.assertEqual(self.cycle.reconcile(), 0)
        self.assertFalse(self.runtime.calls)
        self.assertFalse(self.s3_calls)

    # --- storage trouble never costs availability ---
    def test_failed_upload_leaves_the_product_open_and_retries_with_backoff(self):
        self.s3_fail.add('publish')
        self.assertEqual(self.cycle.run(), 1)
        self.assert_open()
        self.assertEqual(self.cycle.journal['status'], 'upload-pending')
        self.assertEqual(self.status()['lastFailure'], 'upload-failed')
        self.assertFalse(self.status()['attention'])
        attempts = len(self.published())
        self.cycle.reconcile()  # Too early.
        self.assertEqual(len(self.published()), attempts)
        self.s3_fail.clear()
        self.now += dt.timedelta(minutes=15)
        self.assertEqual(self.cycle.reconcile(), 0)
        self.assertEqual(self.cycle.journal['status'], 'idle')
        self.assertIsNone(self.status()['lastFailure'])

    def test_upload_after_the_admission_window_is_refused_for_good(self):
        self.s3_fail.add('publish')
        self.cycle.run()
        attempts = len(self.published())
        self.now += dt.timedelta(hours=6)
        self.s3_fail.clear()
        self.cycle.reconcile()
        self.assertEqual(len(self.published()), attempts)
        self.assertEqual(self.cycle.journal['status'], 'idle')
        self.assertEqual(self.status()['lastFailure'], 'upload-window-closed')

    def test_abandoned_pair_is_replaced_by_the_next_cycle(self):
        self.s3_fail.add('publish')
        self.cycle.run()
        abandoned = self.cycle.journal['pair']
        self.now += dt.timedelta(hours=12)
        self.s3_fail.clear()
        self.assertEqual(self.cycle.run(), 0)
        self.assertFalse(Path(abandoned).exists())

    def test_capacity_warning_is_reported(self):
        original = self.invoke
        def warned(command, *arguments, **kw):
            receipt = original(command, *arguments, **kw)
            receipt.write_text(json.dumps({'alerts': ['capacity-warning']}))
            return receipt
        self.cycle.invoke = warned
        self.assertEqual(self.cycle.run(), 0)
        self.assertTrue(self.status()['capacityWarning'])

    def test_monitoring_fault_never_changes_the_backup(self):
        self.config['status'] = str(self.root / 'absent/status.json')
        self.assertEqual(self.make().run(), 0)

    def test_check_reads_and_never_fences(self):
        self.assertEqual(self.cycle.check(), 0)
        self.assertFalse(self.runtime.calls)
        self.assertEqual({c for c, _ in self.s3_calls}, {'admission'})
        self.runtime.gate_state = b'closed'
        self.assertEqual(self.cycle.check(), 1)


class RealRecoveryFence(unittest.TestCase):
    """The ownership rule on the real actor, whose gate and SQL alone are replaced."""

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.state = Path(temporary.name).resolve()
        self.runtime = core.Runtime.__new__(core.Runtime)
        self.runtime.state = self.state
        self.runtime.gate = lambda *_: b'closed'
        self.runtime.role_login = lambda enabled: self.logins.append(enabled)
        self.logins = []

    def test_cycle_fence_records_its_owner_and_reopens(self):
        self.runtime.cycle_run = 'c' * 32
        self.runtime.closed('quiescing')
        self.assertEqual(json.loads((self.state / 'PENDING.json').read_text())['cycleRun'], 'c' * 32)
        self.runtime.reopen_capture('c' * 32)
        self.assertEqual(self.logins, [True])
        self.assertTrue((self.state / 'READY').exists())
        self.assertFalse((self.state / 'PENDING.json').exists())

    def test_every_other_fence_refuses(self):
        cases = [{'phase': 'quiescing'}, {'phase': 'quiescing', 'cycleRun': 'd' * 32}, {'phase': 'restoring', 'cycleRun': 'c' * 32},
                 {'phase': 'deletion-replay-required', 'cycleRun': 'c' * 32}, {'phase': 'upgrade-claimed', 'cycleRun': 'c' * 32}]
        for pending in cases:
            with self.subTest(pending):
                core.write(self.state / 'PENDING.json', pending)
                with self.assertRaises(core.Refused):
                    self.runtime.reopen_capture('c' * 32)
                self.assertEqual(self.logins, [])
                self.assertFalse((self.state / 'READY').exists())

    def test_manual_backup_journal_is_unchanged(self):
        self.runtime.closed('quiescing')
        self.assertEqual(json.loads((self.state / 'PENDING.json').read_text()), {'phase': 'quiescing', 'backupId': None})


class Watch(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name).resolve()
        self.sent = []

    def run_watch(self, code, evidence, hour=12):
        now = dt.datetime(2026, 11, 7, hour, tzinfo=dt.timezone.utc)
        real = dt.datetime
        class Clock(real):
            @classmethod
            def now(cls, tz=None): return now
        arguments = ['backup-watch', 'observer', '--state-directory', str(self.directory)]
        with patch.object(sys, 'argv', arguments), patch.object(watch.dt, 'datetime', Clock), \
                patch.object(watch, 'observe', return_value=(code, evidence)), patch.object(watch, 'notify', side_effect=self.sent.append):
            return watch.main()

    def test_summary_carries_fixed_vocabulary_and_no_identifiers(self):
        evidence = {'alerts': ['restore-missing'], 'bucket': 'private-bucket', 'pairs': [{'backupId': 'secret-id'}]}
        self.assertEqual(self.run_watch(2, evidence), 0)
        self.assertEqual(self.sent, ['Backup observer findings: restore-missing. Run the owner reconstruction: '
                                     'bash ~/gameometro-276-release/reconstruct.sh'])
        self.assertNotIn('private-bucket', self.sent[0])

    def test_critical_or_failed_observation_fails_the_job(self):
        self.assertEqual(self.run_watch(2, {'alerts': ['integrity-critical']}), 1)
        self.assertEqual(self.run_watch(1, {'reason': 'S3 lock configuration drift.'}), 1)
        self.assertIn('S3 lock configuration drift.', self.sent[-1])

    def test_repeats_stay_quiet_until_the_daily_heartbeat(self):
        self.run_watch(0, {'alerts': []})
        self.run_watch(0, {'alerts': []})
        self.assertEqual(len(self.sent), 1)
        self.run_watch(0, {'alerts': []}, hour=watch.HEARTBEAT_HOUR)
        self.assertEqual(self.sent, ['Backup observer healthy.'] * 2)

    def test_failed_channel_fails_the_job(self):
        def broken(_):
            raise OSError('channel down')
        arguments = ['backup-watch', 'observer', '--state-directory', str(self.directory)]
        with patch.object(sys, 'argv', arguments), patch.object(watch, 'observe', return_value=(0, {'alerts': []})), patch.object(watch, 'notify', side_effect=broken):
            with self.assertRaises(OSError):
                watch.main()


class Prepared(unittest.TestCase):
    """What ships must stay inert until the owner activates it."""
    UNITS = ROOT / 'deploy/public-production/systemd'

    def test_capture_slots_are_every_twelve_hours_and_nothing_is_enabled(self):
        timer = (self.UNITS / 'gameometro-backup.timer').read_text()
        self.assertEqual(re.findall(r'^OnCalendar=(.+)$', timer, re.M), ['*-*-* 02:30:00 UTC', '*-*-* 14:30:00 UTC'])
        self.assertIn('Persistent=true', timer)
        reconcile = (self.UNITS / 'gameometro-backup-reconcile.timer').read_text()
        self.assertIn('OnBootSec=', reconcile)
        for path in self.UNITS.glob('*.service'):
            self.assertIn('prepared, not installed', path.read_text())
            self.assertIn('--config /etc/gameometro/production/backup-cycle.json', path.read_text())

    def test_watch_workflow_is_inert_public_safe_and_pinned(self):
        text = (ROOT / '.github/workflows/backup-watch.yml').read_text()
        self.assertEqual(len(re.findall(r"if: github\.ref == 'refs/heads/main' && vars\.BACKUP_(?:OBSERVER|EXPIRY)_ENABLED == 'true'", text)), 2)
        self.assertNotIn('upload-artifact', re.sub(r'^#.*$', '', text, flags=re.M))
        self.assertNotIn('pull_request', text)
        self.assertEqual(set(re.findall(r'environment: (\S+)', text)), {'backup-observer', 'backup-expiry'})
        for action in re.findall(r'uses: (\S+)', text):
            self.assertRegex(action, r'@[0-9a-f]{40}$')

    def test_expiry_credentials_and_observer_credentials_are_separate_environments(self):
        text = (ROOT / '.github/workflows/backup-watch.yml').read_text()
        observer, expiry = text.split('\n  expiry:\n')
        self.assertIn('environment: backup-observer', observer)
        self.assertNotIn('environment: backup-expiry', observer)
        self.assertIn('environment: backup-expiry', expiry)
        self.assertNotIn('RESTORE_EVIDENCE', expiry)


if __name__ == '__main__':
    os.umask(0o077)
    unittest.main()
