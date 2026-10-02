#!/usr/bin/env python3
"""Deterministic operator-tool tests; no network or persistent catalogue access."""
import contextlib
import datetime as dt
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("repair_stages", Path(__file__).with_name("repair-release-stages.py"))
repair = importlib.util.module_from_spec(spec)
spec.loader.exec_module(repair)


class RepairStagesTest(unittest.TestCase):
    def test_windows_are_bounded_inclusive_and_nonoverlapping(self):
        self.assertEqual(list(repair.windows(dt.date(2026, 9, 29), dt.date(2026, 10, 7), 7)),
                         [(dt.date(2026, 9, 29), dt.date(2026, 10, 5)), (dt.date(2026, 10, 6), dt.date(2026, 10, 7))])

    def test_default_dry_run_never_posts_sync_or_requests_writes(self):
        calls = []
        def fake(base, path, body=None):
            calls.append((path, body))
            if body is None:
                return {"totalUnknown": 2, "repairableUnknown": 1, "withoutSupportedReference": 1}
            return dict(nextAfter="a", complete=True, inspected=1, reconciliation={"outcome":"SUCCEEDED"})
        with patch.object(repair, "request", fake), contextlib.redirect_stdout(io.StringIO()):
            repair.main(["--from", "2026-09-29", "--to", "2026-10-07"])
        self.assertFalse(any(path.endswith("cataloguesync") for path, _ in calls))
        self.assertTrue(all(body["dryRun"] for _, body in calls if body))

    def test_checkpoint_replays_failed_window_then_resumes_reference_cursor(self):
        with tempfile.TemporaryDirectory() as directory:
            checkpoint = Path(directory) / "repair.json"
            args = ["--from", "2026-09-29", "--to", "2026-10-07", "--apply", "--checkpoint", str(checkpoint)]
            calls = []
            def fake(base, path, body=None):
                calls.append((path, body))
                if body is None:
                    return {"totalUnknown": 0}
                if path.endswith("cataloguesync"):
                    if body["from"] == "2026-10-06":
                        raise RuntimeError("fixture provider failure")
                    return {"outcome": "SUCCEEDED"}
                raise AssertionError("Game repair must wait for windows")
            with patch.object(repair, "request", fake), contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaisesRegex(RuntimeError, "provider failure"):
                    repair.main(args)
            state = json.loads(checkpoint.read_text())
            self.assertEqual(state["nextWindow"], "2026-10-06")
            self.assertEqual(state["after"], repair.ZERO)
            calls.clear()
            def resumed(base, path, body=None):
                calls.append((path, body))
                if body is None:
                    return {"totalUnknown": 1}
                if path.endswith("cataloguesync"):
                    return {"outcome": "SUCCEEDED"}
                if body["after"] == repair.ZERO:
                    return dict(nextAfter="next", complete=False, reconciliation={"outcome":"SUCCEEDED"})
                return dict(nextAfter="last", complete=True, reconciliation={"outcome":"SUCCEEDED"})
            with patch.object(repair, "request", resumed), contextlib.redirect_stdout(io.StringIO()):
                repair.main(args)
            self.assertEqual([body["from"] for path, body in calls if path.endswith("cataloguesync")], ["2026-10-06"])
            self.assertTrue(json.loads(checkpoint.read_text())["gamesComplete"])
            calls.clear()
            with patch.object(repair, "request", resumed), contextlib.redirect_stdout(io.StringIO()):
                repair.main(args)
            self.assertTrue(all(body is None for _, body in calls))

    def test_partial_game_batch_preserves_checkpoint_and_replays(self):
        with tempfile.TemporaryDirectory() as directory:
            checkpoint = Path(directory) / "games.json"
            args = ["--from", "2026-10-01", "--to", "2026-10-01", "--apply", "--checkpoint", str(checkpoint)]
            def failed(base, path, body=None):
                if body is None:
                    return {"totalUnknown": 1}
                if path.endswith("cataloguesync"):
                    return {"outcome": "SUCCEEDED"}
                return dict(nextAfter=repair.ZERO, complete=False, reconciliation={"outcome": "PARTIAL"})
            with patch.object(repair, "request", failed), contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaisesRegex(RuntimeError, "Repair failed/partial"):
                    repair.main(args)
            self.assertEqual(json.loads(checkpoint.read_text())["after"], repair.ZERO)
            self.assertFalse(json.loads(checkpoint.read_text())["gamesComplete"])

    def test_partial_window_does_not_advance_and_bad_target_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            checkpoint = Path(directory) / "partial.json"
            with patch.object(repair, "request", lambda *a: {"outcome": "PARTIAL"}), contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaisesRegex(RuntimeError, "partial"):
                    repair.main(["--from", "2026-10-01", "--to", "2026-10-02", "--apply", "--checkpoint", str(checkpoint)])
            self.assertFalse(checkpoint.exists())
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                repair.main(["--from", "2026-10-01", "--to", "2026-10-02", "--management-url", "https://example.com"])


if __name__ == "__main__":
    unittest.main()
