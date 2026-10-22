#!/usr/bin/env python3
"""Deterministic tests of the historical-sync-pilot harness against a synthetic Docker runtime.

No container, provider, PostgreSQL or private-dev access is used, and no value produced here is
a throughput measurement. `--write-example <directory>` regenerates the labelled synthetic
example evidence instead of running the tests.
"""

from __future__ import annotations

import contextlib
import datetime as dt
import importlib.machinery
import importlib.util
import json
import os
import pathlib
import shutil
import signal
import socket
import subprocess
import sys
import tarfile
import tempfile
import time
import unittest

sys.dont_write_bytecode = True

repository_root = pathlib.Path(__file__).resolve().parents[1]
harness_path = repository_root / "deploy/private-dev/bin/historical-sync-pilot"
fake_directory = repository_root / "scripts/fixtures/historical-sync-pilot"
_loader = importlib.machinery.SourceFileLoader("historical_sync_pilot", str(harness_path))
_spec = importlib.util.spec_from_loader(_loader.name, _loader)
assert _spec is not None
pilot = importlib.util.module_from_spec(_spec)
_loader.exec_module(pilot)

HOST = socket.gethostname().split(".")[0]
LEAK = "SHOULD_NOT_LEAK_7f3a"


class Sandbox:
    """One temporary evidence root, runtime.env and fake Docker state."""

    def __init__(self, **overrides: object) -> None:
        self.directory = pathlib.Path(tempfile.mkdtemp(prefix="hsp-test-"))
        self.root = self.directory / "evidence"
        self.root.mkdir()
        self.env_file = self.directory / "runtime.env"
        # A protected-looking value on an ignored key proves only allow-listed keys are read.
        self.env_file.write_text(f"COMPOSE_PROJECT_NAME=videogame-platform-dev\nAPPLICATION_VERSION=0.0.0-synthetic\n"
                                 f"PRIVATE_DEV_OPAQUE_VALUE={LEAK}\n")
        self.state_path = self.directory / "docker-state.json"
        started = "2026-10-22T08:00:00.000000000Z"
        state = {
            "containers": {"application": "app1", "postgres": "pg1", "catalogue-localizer": "loc1"},
            "running": {"application": True, "postgres": True, "catalogue-localizer": True},
            "startedAt": {"application": started, "postgres": started, "catalogue-localizer": started},
            "health": {"catalogue-localizer": "healthy"},
            "applicationEnvironment": {"CATALOGUE_SYNC_SCHEDULING_ENABLED": "false", "IGDB_CLIENT_SECRET_FILE": LEAK},
            "dockerRoot": str(self.directory), "readiness": "UP", "credentials": True,
            "coverage": [{"from": "2017-03-01", "to": "2017-03-31", "status": "never_covered"}],
            "scenario": {"duration": 2.5}, "run": None, "otherRuns": [], "logs": [],
            "meters": {"providerRequests": 0, "providerUnsuccessful": 0, "providerRetries": 0,
                       "localization": {"translated": 10, "reused": 0}},
            "games": 1000, "databaseBytes": 50 * 1024**2, "commits": 100,
        }
        for key, value in overrides.items():
            state[key] = value
        self.write(state)

    def write(self, state: dict) -> None:
        self.state_path.write_text(json.dumps(state))

    def update(self, **changes: object) -> None:
        lock = self.state_path.with_suffix(".lock")
        with lock.open("a") as handle:
            import fcntl
            fcntl.flock(handle, fcntl.LOCK_EX)
            state = json.loads(self.state_path.read_text())
            for key, value in changes.items():
                if callable(value):
                    value(state)
                else:
                    state[key] = value
            self.write(state)

    def state(self) -> dict:
        return json.loads(self.state_path.read_text())

    def environment(self) -> dict:
        return {**os.environ, "PATH": f"{fake_directory}{os.pathsep}{os.environ['PATH']}",
                "FAKE_DOCKER_STATE": str(self.state_path)}

    def command(self, *arguments: str, timeout: float = 60) -> subprocess.CompletedProcess:
        return subprocess.run(
            [sys.executable, str(harness_path), "--env-file", str(self.env_file), "--evidence-root", str(self.root),
             "--expected-host", HOST, *arguments],
            capture_output=True, text=True, env=self.environment(), timeout=timeout, check=False)

    def execution(self) -> pathlib.Path:
        found = sorted(self.root.glob("*/hsp-*"))
        assert found, "no execution directory"
        return found[-1]

    def wait_finished(self, directory: pathlib.Path, timeout: float = 60) -> dict:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            metadata = json.loads((directory / "metadata.json").read_text())
            if metadata.get("monitorOutcome") and not pilot.monitor_alive(directory):
                return metadata
            time.sleep(0.2)
        raise AssertionError("monitor did not finish")

    def wait_for(self, predicate, timeout: float = 30) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if predicate():
                return
            time.sleep(0.1)
        raise AssertionError("condition not reached")

    def cleanup(self) -> None:
        shutil.rmtree(self.directory, ignore_errors=True)


class UnitTest(unittest.TestCase):
    def test_month_windows_are_whole_inclusive_months_inside_the_approved_scope(self):
        today = dt.date(2026, 10, 22)
        self.assertEqual(pilot.month_window("2017-03", today), (dt.date(2017, 3, 1), dt.date(2017, 3, 31)))
        self.assertEqual(pilot.month_window("2020-02", today), (dt.date(2020, 2, 1), dt.date(2020, 2, 29)))
        self.assertEqual(pilot.month_window("2025-12", today), (dt.date(2025, 12, 1), dt.date(2025, 12, 31)))
        for invalid in ("2017-3", "2017-13", "2017-00", "17-03", "2017-03-01", "", "2017-02", "2026-11"):
            with self.assertRaises(pilot.PilotError, msg=invalid):
                pilot.month_window(invalid, today)

    def test_redaction_removes_credentials_tokens_and_addresses(self):
        text = pilot.redact("Authorization: Bearer abcdefghijkl client_secret=s3cr3t password=x owner@example.org "
                            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.c2lnbmF0dXJl")
        for secret in ("abcdefghijkl", "s3cr3t", "owner@example.org", "eyJhbGci"):
            self.assertNotIn(secret, text)
        self.assertEqual(pilot.redact("failures=reconcile/WRITE_FAILED=2"), "failures=reconcile/WRITE_FAILED=2")

    def test_export_scan_flags_secrets_but_accepts_redacted_text(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "errors.log"
            path.write_text("ok password=[redacted]\nlater access_token=abcdef123\n")
            self.assertEqual(pilot.scan_for_secrets(path), ["errors.log:2"])

    def test_log_lines_parse_from_ecs_json_and_plain_console(self):
        run = "11111111-1111-4111-8111-111111111111"
        message = (f"Catalogue synchronization progress: run={run} phase=reconciling page=3 game=120/500 elapsed_s=61"
                   " games[created=10 updated=2 unchanged=100 deferred=1 failed=0] release_dates_inspected=400"
                   " provider[requests=12 retries=1]")
        ecs = "2026-10-22T10:00:00.123456789Z " + json.dumps({"log.level": "INFO", "message": message})
        plain = f"2026-10-22T10:00:01Z 2026-10-22 INFO 1 --- [main] c.v.CatalogueSynchronizationLog : {message}"
        for line in (ecs, plain):
            moment, _, _, text = pilot.log_entry(line)
            self.assertIsNotNone(moment)
            fields = pilot.progress_fields(pilot.PROGRESS.search(text))
            self.assertEqual((fields["page"], fields["gamesUnchanged"], fields["providerRetries"]), (3, 100, 1))

    def test_log_follower_keeps_failure_vocabulary_without_identities_and_skips_other_runs(self):
        run, other = "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222"
        with tempfile.TemporaryDirectory() as directory:
            state: dict = {}
            follower = pilot.LogFollower(pathlib.Path(directory), state)
            lines = [
                "2026-10-22T10:00:00Z " + json.dumps({"log.level": "WARN", "message":
                    f"Catalogue synchronization game failed: run={run} stage=reconcile reason=WRITE_FAILED page=1 game=1/3 identity=published abc secret-slug failed_games=1"}),
                "2026-10-22T10:00:01Z " + json.dumps({"log.level": "WARN", "message":
                    f"Catalogue synchronization game failed: run={other} stage=provider reason=X page=1 game=1/3 identity=none failed_games=1"}),
            ]
            follower.consume(lines, run)
            follower.consume(lines, run)  # the cursor prevents counting a line twice
            self.assertEqual(state["failureTally"], {"game:reconcile/WRITE_FAILED": 1})
            errors = (pathlib.Path(directory) / "errors.log").read_text()
            self.assertNotIn("secret-slug", errors)

    def test_bounded_files_stop_growing(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "errors.log"
            for index in range(20):
                pilot.append_bounded(path, f"line {index}", 5)
            lines = path.read_text().splitlines()
            self.assertEqual(len(lines), 6)
            self.assertIn("suppressed", lines[-1])

    def test_byte_and_percent_parsing(self):
        self.assertEqual(pilot.byte_size("512MiB"), 512 * 1024**2)
        self.assertEqual(pilot.byte_size(" 1.5GB "), 1_500_000_000)
        self.assertIsNone(pilot.byte_size("--"))
        self.assertEqual(pilot.percent("42.50%"), 42.5)


class HarnessTest(unittest.TestCase):
    def setUp(self) -> None:
        self.sandboxes: list[Sandbox] = []

    def tearDown(self) -> None:
        for sandbox in self.sandboxes:
            for directory in sandbox.root.glob("*/hsp-*"):
                state = json.loads((directory / "state.json").read_text()) if (directory / "state.json").exists() else {}
                if pilot.monitor_alive(directory) and isinstance(state.get("pid"), int):
                    with contextlib.suppress(ProcessLookupError):
                        os.kill(state["pid"], signal.SIGKILL)
            sandbox.cleanup()

    def sandbox(self, **overrides: object) -> Sandbox:
        sandbox = Sandbox(**overrides)
        self.sandboxes.append(sandbox)
        return sandbox

    def start(self, sandbox: Sandbox, *extra: str) -> pathlib.Path:
        result = sandbox.command("start", "--month", "2017-03", "--interval", "1", "--label", "synthetic-test", *extra)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return sandbox.execution()

    def test_preflight_passes_and_changes_nothing(self):
        sandbox = self.sandbox()
        before = sandbox.state()
        result = sandbox.command("preflight", "--month", "2017-03", "--json")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        answer = json.loads(result.stdout)
        self.assertFalse(answer["blocked"])
        self.assertEqual(answer["window"], {"from": "2017-03-01", "to": "2017-03-31"})
        self.assertEqual(sandbox.state(), before)
        self.assertEqual(list(sandbox.root.iterdir()), [])
        self.assertNotIn(LEAK, result.stdout)

    def test_preflight_and_start_refuse_a_run_in_flight_wrong_host_and_missing_credentials(self):
        busy = self.sandbox(otherRuns=[{"runId": "33333333-3333-4333-8333-333333333333", "from": "2026-10-01",
                                        "to": "2026-11-30", "status": "running", "startedAt": "2026-10-22T04:00:00Z",
                                        "heartbeatAt": "2026-10-22T04:00:00Z"}])
        result = busy.command("start", "--month", "2017-03", "--interval", "1")
        self.assertEqual(result.returncode, 1)
        self.assertIn("a run is already in flight", result.stdout)
        self.assertEqual(list(busy.root.iterdir()), [])
        self.assertIsNone(busy.state()["run"])

        no_credentials = self.sandbox(credentials=False)
        result = no_credentials.command("preflight", "--month", "2017-03")
        self.assertEqual(result.returncode, 1)
        self.assertIn("FAIL  provider credentials", result.stdout)

        sandbox = self.sandbox()
        result = subprocess.run([sys.executable, str(harness_path), "--env-file", str(sandbox.env_file),
                                 "--evidence-root", str(sandbox.root), "preflight", "--month", "2017-03"],
                                capture_output=True, text=True, env=sandbox.environment(), check=False)
        if HOST != "vgpdev":
            self.assertEqual(result.returncode, 1)
            self.assertIn("runs only on vgpdev", result.stdout)

    def test_covered_month_and_scheduling_warn_and_start_needs_explicit_intent(self):
        sandbox = self.sandbox(
            coverage=[{"from": "2017-03-01", "to": "2017-03-31", "status": "covered", "scanCompletedAt": "2026-10-01T00:00:00Z"}],
            applicationEnvironment={"CATALOGUE_SYNC_SCHEDULING_ENABLED": "true"})
        result = sandbox.command("preflight", "--month", "2017-03")
        self.assertEqual(result.returncode, 0)
        self.assertIn("WARN  coverage", result.stdout)
        self.assertIn("WARN  recurring scheduling", result.stdout)
        result = sandbox.command("start", "--month", "2017-03", "--interval", "1")
        self.assertEqual(result.returncode, 2)
        self.assertIn("--allow-covered", result.stderr)
        self.assertFalse((sandbox.root / "ACTIVE").exists())
        self.assertIsNone(sandbox.state()["run"])

    def test_successful_run_is_detached_monitored_reported_and_exported(self):
        sandbox = self.sandbox()
        directory = self.start(sandbox)
        sandbox.wait_for(lambda: json.loads((directory / "state.json").read_text()).get("pid"))
        monitor = json.loads((directory / "state.json").read_text())["pid"]
        # The monitor leads its own session, so a closed terminal's hang-up never reaches it.
        self.assertEqual(os.getsid(monitor), monitor)
        self.assertNotEqual(os.getsid(monitor), os.getsid(0))
        duplicate = sandbox.command("start", "--month", "2020-11", "--interval", "1")
        self.assertEqual(duplicate.returncode, 1)
        self.assertIn("still being monitored", duplicate.stdout)
        status = sandbox.command("status")
        self.assertIn("Monitor (this harness): running", status.stdout)
        metadata = sandbox.wait_finished(directory)
        self.assertEqual(metadata["monitorOutcome"], "run_finished")
        self.assertFalse((sandbox.root / "ACTIVE").exists())
        summary = json.loads((directory / "summary.json").read_text())
        self.assertTrue(summary["synthetic"])
        self.assertEqual(summary["applicationOutcome"]["status"], "partial")
        self.assertEqual(summary["measured"]["runDurationSeconds"]["status"], "measured")
        self.assertEqual(summary["estimated"]["gamesPerHour"]["status"], "estimated")
        self.assertIn("perPhaseTiming (provider vs reconciliation vs localization)", summary["unavailable"])
        self.assertEqual(summary["failureTally"].get("game:reconcile/WRITE_FAILED", 0) >= 1, True)
        samples = pilot.read_samples(directory)
        self.assertGreaterEqual(len(samples), 2)
        self.assertFalse(any(row["probe_failures"] for row in samples))
        self.assertEqual(summary["measured"]["pages"]["status"], "measured")
        report = json.loads((directory / "synchronization.json").read_text())["report"]
        self.assertEqual(summary["failureTally"]["game:reconcile/WRITE_FAILED"], report["counters"]["failedGames"])
        self.assertEqual(tuple(samples[0].keys()), pilot.SAMPLE_COLUMNS)
        self.assertTrue(any(row["progress_page"] for row in samples))
        self.assertTrue(all(row["application_cpu_percent"] == "42.5" for row in samples))
        localization = json.loads((directory / "localization.json").read_text())
        self.assertEqual(localization["contentByOutcome"]["translated"]["status"], "measured")
        self.assertGreater(localization["contentByOutcome"]["translated"]["value"], 0)
        markdown = (directory / "summary.md").read_text()
        self.assertTrue(markdown.startswith("> **SYNTHETIC EVIDENCE.**"))
        done = sandbox.command("status")
        self.assertIn("Monitor (this harness): not running", done.stdout)
        self.assertIn("(PostgreSQL, authoritative)", done.stdout)
        archive = sandbox.directory / "evidence.tar.gz"
        exported = sandbox.command("export", "--output", str(archive))
        self.assertEqual(exported.returncode, 0, exported.stderr)
        with tarfile.open(archive) as handle:
            names = handle.getnames()
            self.assertIn(f"historical-sync-evidence/2017-03/{directory.name}/summary.md", names)
            self.assertFalse(any(name.endswith(("state.json", "monitor.lock", "monitor.log")) for name in names))
            for member in handle.getmembers():
                self.assertNotIn(LEAK, handle.extractfile(member).read().decode())
        self.assertEqual(sandbox.command("export", "--output", str(archive)).returncode, 2)
        # A finished pilot frees the root: the next month is accepted only when explicitly started.
        sandbox.update(run=None, logs=[])
        self.assertEqual(sandbox.command("preflight", "--month", "2020-11").returncode, 0)

    def test_safe_stop_is_the_application_action_and_its_evidence_is_kept(self):
        sandbox = self.sandbox(scenario={"duration": 60})
        directory = self.start(sandbox)
        sandbox.wait_for(lambda: json.loads((directory / "state.json").read_text()).get("runId"))
        guidance = sandbox.command("stop")
        self.assertEqual(guidance.returncode, 0)
        self.assertIn("Detener sincronización", guidance.stdout)
        self.assertIn("no coverage is recorded", guidance.stdout)
        self.assertIsNone(sandbox.state()["run"]["stopRequestedAt"])
        # The operator's UC-013 request, as the administration page would record it.
        sandbox.update(run=lambda state: state["run"].update(stopRequestedAt="2026-10-22T10:00:00Z"))
        metadata = sandbox.wait_finished(directory)
        self.assertEqual(metadata["monitorOutcome"], "run_finished")
        summary = json.loads((directory / "summary.json").read_text())
        self.assertEqual(summary["applicationOutcome"], {"status": "partial", "code": "SYNCHRONIZATION_STOPPED"})
        self.assertTrue(summary["stopRequested"])
        events = (directory / "events.jsonl").read_text()
        self.assertIn("stop request observed", events)

    def test_monitor_only_stop_leaves_the_application_run_alone(self):
        sandbox = self.sandbox(scenario={"duration": 60})
        directory = self.start(sandbox)
        sandbox.wait_for(lambda: json.loads((directory / "state.json").read_text()).get("runId"))
        result = sandbox.command("stop", "--monitor-only")
        self.assertIn("does NOT stop application run", result.stdout)
        metadata = sandbox.wait_finished(directory)
        self.assertEqual(metadata["monitorOutcome"], "monitor_stopped")
        self.assertEqual(sandbox.state()["run"]["status"], "running")
        status = sandbox.command("status")
        self.assertIn("running", status.stdout)
        self.assertIn("nothing is monitoring it", status.stdout)
        sandbox.update(run=lambda state: state["run"].update(stopRequestedAt="2026-10-22T10:00:00Z"))

    def test_lost_monitor_keeps_partial_evidence_and_report_closes_it(self):
        sandbox = self.sandbox(scenario={"duration": 4})
        directory = self.start(sandbox)
        sandbox.wait_for(lambda: len(pilot.read_samples(directory)) >= 2)
        os.kill(json.loads((directory / "state.json").read_text())["pid"], signal.SIGKILL)
        sandbox.wait_for(lambda: not pilot.monitor_alive(directory))
        blocked = sandbox.command("preflight", "--month", "2020-11")
        self.assertEqual(blocked.returncode, 1)
        self.assertIn("lost monitor", blocked.stdout)
        sandbox.wait_for(lambda: sandbox.state()["run"]["status"] != "running")
        report = sandbox.command("report")
        self.assertEqual(report.returncode, 0, report.stderr)
        self.assertIn("Cleared the active marker", report.stdout)
        summary = json.loads((directory / "summary.json").read_text())
        self.assertEqual(summary["monitor"]["outcome"], "monitor_lost")
        self.assertEqual(summary["applicationOutcome"]["status"], "partial")
        self.assertGreaterEqual(summary["monitor"]["samples"], 2)
        # Without a final snapshot, growth is reported as unavailable rather than invented.
        resources = json.loads((directory / "resources.json").read_text())
        self.assertEqual(resources["databaseSizeGrowthBytes"]["status"], "unavailable")
        self.assertEqual(sandbox.command("report").returncode, 0)

    def test_application_restart_ends_monitoring_without_claiming_an_outcome(self):
        sandbox = self.sandbox(scenario={"duration": 60})
        directory = self.start(sandbox)
        sandbox.wait_for(lambda: json.loads((directory / "state.json").read_text()).get("runId"))
        sandbox.update(containers=lambda state: state["containers"].update(application="app2"),
                       startedAt=lambda state: state["startedAt"].update(application="2026-10-22T09:00:00Z"))
        metadata = sandbox.wait_finished(directory)
        self.assertEqual(metadata["monitorOutcome"], "application_restarted")
        summary = json.loads((directory / "summary.json").read_text())
        self.assertEqual(summary["applicationOutcome"]["status"], "running")
        self.assertTrue(summary["applicationRestarted"])
        self.assertEqual(json.loads((directory / "synchronization.json").read_text())["providerMeters"]["requests"]["status"],
                         "unavailable")

    def test_skipped_run_is_reported_without_a_durable_run(self):
        sandbox = self.sandbox(scenario={"duration": 1, "skip": True})
        directory = self.start(sandbox)
        metadata = sandbox.wait_finished(directory)
        self.assertEqual(metadata["monitorOutcome"], "run_not_started")
        summary = json.loads((directory / "summary.json").read_text())
        self.assertEqual(summary["applicationOutcome"], {"status": "skipped", "code": "SYNCHRONIZATION_ALREADY_RUNNING"})
        self.assertEqual(summary["measured"]["runDurationSeconds"]["status"], "unavailable")

    def test_secrets_in_application_logs_never_reach_evidence(self):
        sandbox = self.sandbox(scenario={"duration": 1.5, "leak": True})
        directory = self.start(sandbox)
        sandbox.wait_finished(directory)
        for path in directory.iterdir():
            if path.is_file():
                text = path.read_text(errors="replace")
                for secret in ("abcdefghijklmnop", "owner@example.org", LEAK):
                    self.assertNotIn(secret, text, path.name)
        self.assertIn("[redacted]", (directory / "errors.log").read_text())
        self.assertEqual(sandbox.command("export", "--output", str(sandbox.directory / "out.tar.gz")).returncode, 0)


def write_example(target: pathlib.Path) -> None:
    sandbox = Sandbox(scenario={"duration": 4})
    try:
        result = sandbox.command("start", "--month", "2017-03", "--interval", "1", "--label", "synthetic-example")
        if result.returncode != 0:
            raise SystemExit(result.stdout + result.stderr)
        directory = sandbox.execution()
        sandbox.wait_finished(directory)
        if target.exists():
            shutil.rmtree(target)
        target.mkdir(parents=True)
        # The workstation's name and temporary paths are not evidence; give them neutral values.
        replacements = {HOST: "synthetic-host", str(sandbox.directory): "/synthetic"}
        for name in pilot.EXPORT_FILES:
            if (directory / name).is_file():
                text = (directory / name).read_text()
                for old, new in replacements.items():
                    text = text.replace(old, new)
                (target / name).write_text(text)
        print(f"Wrote synthetic example evidence to {target}")
    finally:
        sandbox.cleanup()


if __name__ == "__main__":
    if sys.argv[1:2] == ["--write-example"]:
        write_example(pathlib.Path(sys.argv[2]).resolve())
    else:
        unittest.main(verbosity=1)
