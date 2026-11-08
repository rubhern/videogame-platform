#!/usr/bin/env python3
"""Identity deny/drain adapter (#275 increment 6): refusal tests plus real Keycloak drain evidence.

The fenced runtime is simulated, except for one pinned upstream Keycloak container whose own
in-flight request metric proves the drain decision. No real host, realm or personal data.
"""
import contextlib
import importlib.machinery
import importlib.util
import io
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import unittest
import uuid
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]


def load(name, path):
    loader = importlib.machinery.SourceFileLoader(name, str(path))
    spec = importlib.util.spec_from_loader(name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


adapter = load('identity_maintenance_test', ROOT / 'deploy/public-production/bin/identity-maintenance')
ledger = load('identity_maintenance_ledger_test', ROOT / 'deploy/private-dev/bin/personal-data-ledger')
core = adapter.recovery
KEYCLOAK = re.search(r'^ARG KEYCLOAK_IMAGE=(\S+)$', (ROOT / 'deploy/private-dev/keycloak/Dockerfile').read_text(), re.M)[1]
METRICS = '''# TYPE http_server_active_requests gauge
http_server_active_requests{server_port="8080",url_scheme="http"} %s
http_server_active_requests{server_port="9000",url_scheme="http"} 1.0
'''


class FakeRuntime:
    """The recovery-state volume and the running services, in memory."""

    def __init__(self, active=(0,), running=('caddy', 'keycloak'), ready=True):
        self.ready, self.owner, self.active = ready, None, list(active)
        self.running, self.edge_view, self.calls = set(running), True, []

    def gate(self, action, run_id=None):
        self.calls.append(action)
        if action == 'check':
            return b'open' if self.ready else b'closed'
        if action == 'maintenance-check':
            return (self.owner or 'none').encode()
        if action == 'maintenance-enter':
            if self.owner not in (None, run_id):
                return b'conflict'
            self.owner = run_id
            return b'entered'
        if action == 'maintenance-exit':
            if self.owner is None:
                return b'absent'
            if self.owner != run_id:
                return b'conflict'
            self.owner = None
            return b'released'
        raise AssertionError(action)

    def compose(self, *args, **_):
        if args[0] == 'ps':
            return b'cid\n' if args[-1] in self.running else b''
        if args[:3] == ('exec', '-T', 'caddy'):
            core.require(self.edge_view and self.owner is not None, 'Operation failed; raw diagnostics withheld to protect data.')
            return b''
        if args[:3] == ('exec', '-T', 'keycloak'):
            value = self.active.pop(0) if len(self.active) > 1 else self.active[0]
            return (value if isinstance(value, str) else METRICS % f'{value}.0').encode()
        raise AssertionError(args)


class Clock:
    def __init__(self):
        self.now, self.slept = 0.0, []

    def __call__(self):
        return self.now

    def sleep(self, seconds):
        self.slept.append(seconds)
        self.now += seconds


def fenced(runtime):
    clock = Clock()
    return adapter.Maintenance(runtime, clock=clock, sleep=clock.sleep), clock


RUN, OTHER = str(uuid.uuid4()), str(uuid.uuid4())


class Adapter(unittest.TestCase):
    def test_enter_waits_beyond_the_edge_admission_bound_then_for_keycloak_to_drain(self):
        runtime = FakeRuntime(active=(2, 1, 0))
        maintenance, clock = fenced(runtime)
        self.assertEqual(maintenance.enter(RUN), 'drained')
        self.assertEqual(runtime.owner, RUN)
        self.assertGreaterEqual(clock.slept[0], 3, 'drain sampled before the edge dial bound')
        self.assertEqual(len(clock.slept), 3)

    def test_enter_refuses_during_recovery_and_writes_no_fence(self):
        runtime = FakeRuntime(ready=False)
        with self.assertRaisesRegex(core.Refused, 'Recovery authorization is closed'):
            fenced(runtime)[0].enter(RUN)
        self.assertIsNone(runtime.owner)

    def test_enter_refuses_another_runs_fence_and_repeats_its_own(self):
        runtime = FakeRuntime()
        runtime.owner = OTHER
        with self.assertRaisesRegex(core.Refused, 'Another identity-maintenance run'):
            fenced(runtime)[0].enter(RUN)
        self.assertEqual(runtime.owner, OTHER)
        runtime.owner = RUN  # An enter whose answer was lost is repeated safely.
        self.assertEqual(fenced(runtime)[0].enter(RUN), 'drained')

    def test_drain_timeout_keeps_the_fence_and_exit_refuses_until_settled(self):
        runtime = FakeRuntime(active=(1,))
        maintenance, _ = fenced(runtime)
        with self.assertRaisesRegex(core.Refused, 'did not drain'):
            maintenance.enter(RUN)
        self.assertEqual(runtime.owner, RUN)
        with self.assertRaisesRegex(core.Refused, 'did not drain'):
            fenced(runtime)[0].exit(RUN)
        self.assertEqual(runtime.owner, RUN, 'an unsettled identity mutation reopened the fence')
        runtime.active = [0]
        self.assertEqual(fenced(runtime)[0].exit(RUN), 'released')
        self.assertIsNone(runtime.owner)

    def test_missing_or_ambiguous_drain_evidence_is_a_refusal(self):
        for metrics in ('# no http metrics\n', METRICS % '1.5', METRICS % '0.0' + METRICS % '0.0'):
            runtime = FakeRuntime(active=(metrics,))
            with self.assertRaisesRegex(core.Refused, 'drain unproven|invalid in-flight'):
                fenced(runtime)[0].enter(RUN)

    def test_a_stopped_keycloak_has_nothing_in_flight(self):
        runtime = FakeRuntime(active=('not scraped',), running=('caddy',))
        self.assertEqual(fenced(runtime)[0].enter(RUN), 'drained')

    def test_the_running_edge_must_see_the_fence(self):
        runtime = FakeRuntime()
        runtime.edge_view = False
        with self.assertRaises(core.Refused):
            fenced(runtime)[0].enter(RUN)
        stopped = FakeRuntime(running=('keycloak',))
        stopped.edge_view = False  # A stopped edge serves nothing and reads the fence on start.
        self.assertEqual(fenced(stopped)[0].enter(RUN), 'drained')

    def test_check_and_exit_belong_to_the_fencing_run(self):
        runtime = FakeRuntime()
        maintenance, _ = fenced(runtime)
        maintenance.enter(RUN)
        self.assertEqual(maintenance.check(RUN), 'drained')
        with self.assertRaisesRegex(core.Refused, 'does not hold'):
            maintenance.check(OTHER)
        with self.assertRaisesRegex(core.Refused, 'release refused'):
            maintenance.exit(OTHER)
        self.assertEqual(maintenance.exit(RUN), 'released')
        self.assertEqual(maintenance.exit(RUN), 'released', 'a repeated or enter-less exit must be harmless')
        with self.assertRaisesRegex(core.Refused, 'does not hold'):
            maintenance.check(RUN)

    def test_other_runtime_actors_refuse_while_the_fence_is_held(self):
        runtime = core.Runtime.__new__(core.Runtime)
        with tempfile.TemporaryDirectory() as state:
            runtime.state = Path(state)
            fake = FakeRuntime()
            fake.owner = RUN
            with patch.object(runtime, 'engine_lock', return_value=contextlib.nullcontext()), \
                    patch.object(runtime, 'gate', side_effect=fake.gate):
                with self.assertRaisesRegex(core.Refused, 'Identity maintenance is active'):
                    with runtime.lock():
                        self.fail('a recovery, backup or deployment actor entered a held fence')
                with runtime.lock(maintenance=True):
                    pass
                fake.owner = None
                with runtime.lock():
                    pass

    def test_cli_answer_is_accepted_by_the_privacy_cleanup(self):
        fake = FakeRuntime()
        for action, state in (('enter', 'drained'), ('check', 'drained'), ('exit', 'released')):
            output = io.StringIO()
            runtime = core.Runtime.__new__(core.Runtime)
            with patch.object(core, 'Runtime', return_value=runtime), \
                    patch.object(runtime, 'lock', return_value=contextlib.nullcontext(), create=True), \
                    patch.object(adapter, 'Maintenance', return_value=adapter.Maintenance(fake, sleep=lambda _: None)), \
                    patch.object(sys, 'argv', ['identity-maintenance', '--config', 'c', '--project', 'p', '--state', 's', action, '--run-id', RUN]), \
                    contextlib.redirect_stdout(output):
                self.assertEqual(adapter.main(), 0)
            answer = json.loads(output.getvalue())
            self.assertEqual(answer['state'], state)
            with patch.object(ledger, 'run_host', return_value=answer):
                ledger.maintenance(['adapter'], action, RUN, core.ISSUER)

    def test_cli_refuses_a_malformed_run_identifier_without_touching_the_runtime(self):
        with patch.object(core, 'Runtime', side_effect=AssertionError('runtime used')), \
                patch.object(sys, 'argv', ['identity-maintenance', '--config', 'c', '--project', 'p', '--state', 's', 'enter', '--run-id', 'x; rm -rf /']), \
                contextlib.redirect_stderr(io.StringIO()) as error:
            self.assertEqual(adapter.main(), 1)
        self.assertIn('Invalid run identifier', error.getvalue())


class KeycloakDrain(unittest.TestCase):
    """Keycloak's own gauge counts a request it has received but not finished."""

    @classmethod
    def setUpClass(cls):
        cls.container = subprocess.run(
            ['docker', 'run', '--detach', '--rm', '--network', 'none', '--memory', '1g',
             '--label', 'vgp.production-test=identity-maintenance',
             '-e', 'KC_BOOTSTRAP_ADMIN_USERNAME=synthetic', '-e', 'KC_BOOTSTRAP_ADMIN_PASSWORD=synthetic',
             '-e', 'KC_METRICS_ENABLED=true', '-e', 'KC_HEALTH_ENABLED=true', KEYCLOAK, 'start-dev'],
            check=True, capture_output=True, text=True).stdout.strip()
        deadline = time.monotonic() + 180
        while subprocess.run(['docker', 'exec', cls.container, 'bash', '-c',
                              'exec 3<>/dev/tcp/127.0.0.1/9000; printf "GET /health/ready HTTP/1.1\\r\\nHost: l\\r\\n'
                              'Connection: close\\r\\n\\r\\n" >&3; grep -q "200 OK" <&3'], capture_output=True).returncode:
            if time.monotonic() > deadline:
                raise AssertionError('Keycloak did not become ready')
            time.sleep(2)

    @classmethod
    def tearDownClass(cls):
        subprocess.run(['docker', 'rm', '--force', cls.container], capture_output=True)

    def runtime(self):
        container = self.container
        fake = FakeRuntime(running=('keycloak',))

        def compose(*args, **_):
            if args[:3] == ('exec', '-T', 'keycloak'):
                return subprocess.run(['docker', 'exec', container, *args[3:]], check=True, capture_output=True).stdout
            return FakeRuntime.compose(fake, *args)
        fake.compose = compose
        return fake

    def test_an_unfinished_public_request_blocks_the_drain_until_it_ends(self):
        maintenance = adapter.Maintenance(self.runtime())
        self.assertEqual(maintenance.keycloak_active(), 0, 'the scrape counted itself or idle work')
        # A login submission whose body has not finished arriving, as a slow client sends it.
        # Keep the connection open until the assertion finishes. A fixed sleep can
        # expire while real Docker scrapes advance the simulated drain clock.
        held = subprocess.Popen(['docker', 'exec', '-i', self.container, 'bash', '-c',
                                 'exec 3<>/dev/tcp/127.0.0.1/8080; printf "POST /realms/master/login-actions/authenticate '
                                 'HTTP/1.1\\r\\nHost: l\\r\\nContent-Type: application/x-www-form-urlencoded\\r\\n'
                                 'Content-Length: 1000\\r\\n\\r\\nusername=a" >&3; read -r release'],
                                stdin=subprocess.PIPE)
        try:
            deadline = time.monotonic() + 10
            while maintenance.keycloak_active() != 1:
                self.assertLess(time.monotonic(), deadline, 'Keycloak never counted the unfinished request')
                time.sleep(0.5)
            clock = Clock()
            bounded = adapter.Maintenance(self.runtime(), clock=clock, sleep=clock.sleep)
            with self.assertRaisesRegex(core.Refused, 'did not drain'):
                bounded.drained(settle=False)
        finally:
            # The client then closes its connection without finishing the request.
            held.communicate(input=b'release\n', timeout=30)
        self.assertEqual(held.returncode, 0, 'the held client did not close cleanly')
        deadline = time.monotonic() + 30
        while maintenance.keycloak_active():
            self.assertLess(time.monotonic(), deadline, 'the ended request stayed counted')
            time.sleep(0.5)
        maintenance.drained(settle=False)


if __name__ == '__main__':
    unittest.main(verbosity=2)
