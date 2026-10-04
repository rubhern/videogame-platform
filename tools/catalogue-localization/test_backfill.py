"""Checkpoint recovery at the private management boundary, without model downloads."""
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "backfill", Path(__file__).resolve().parents[2] / "scripts/localize-catalogue.py")
backfill = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(backfill)


class CheckpointTests(unittest.TestCase):
    def result(self, cursor, completed=False, failed=0):
        return {"afterKind": "SUMMARY", "afterId": cursor, "completed": completed,
                "inspected": 1, "report": {"translated": 1, "reused": 0, "skipped": 0, "failed": failed}}

    def run_command(self, checkpoint, response):
        with patch("sys.argv", ["backfill", "--checkpoint", str(checkpoint), "--max-batches", "1"]), \
                patch.object(backfill.urllib.request, "urlopen", response), patch("sys.stdout", io.StringIO()):
            backfill.main()

    def test_acknowledged_batch_resumes_and_completed_checkpoint_makes_no_request(self):
        with tempfile.TemporaryDirectory() as directory:
            checkpoint = Path(directory) / "progress.json"
            cursor = "10000000-0000-0000-0000-000000000001"
            self.run_command(checkpoint, lambda *_args, **_kwargs: io.BytesIO(json.dumps(self.result(cursor)).encode()))
            requests = []

            def finish(request, **_kwargs):
                requests.append(json.loads(request.data))
                return io.BytesIO(json.dumps(self.result(cursor, completed=True)).encode())

            self.run_command(checkpoint, finish)
            self.assertEqual(requests[0]["afterId"], cursor)
            self.assertTrue(json.loads(checkpoint.read_text())["completed"])
            self.run_command(checkpoint, lambda *_args, **_kwargs: self.fail("Completed sweep must not restart"))

    def test_interrupted_request_preserves_acknowledged_cursor_and_retry_batch_retains_it(self):
        with tempfile.TemporaryDirectory() as directory:
            checkpoint = Path(directory) / "progress.json"
            cursor = "10000000-0000-0000-0000-000000000001"
            previous = {"version": 1, "target": "http://127.0.0.1:8081/actuator/cataloguelocalize",
                        "afterKind": "SUMMARY", "afterId": cursor, "completed": False}
            checkpoint.write_text(json.dumps(previous))

            def interrupted(*_args, **_kwargs):
                raise TimeoutError("Interrupted response")

            with self.assertRaises(TimeoutError):
                self.run_command(checkpoint, interrupted)
            self.assertEqual(json.loads(checkpoint.read_text()), previous)
            with self.assertRaises(SystemExit):
                self.run_command(checkpoint, lambda *_args, **_kwargs: io.BytesIO(json.dumps(self.result(cursor, failed=1)).encode()))
            self.assertEqual(json.loads(checkpoint.read_text()), previous)

    def test_checkpoint_from_other_target_or_format_is_rejected_before_work(self):
        with tempfile.TemporaryDirectory() as directory:
            checkpoint = Path(directory) / "progress.json"
            for state in ({"version": 0}, {"version": 1, "target": "http://other-host/actuator/cataloguelocalize"}):
                checkpoint.write_text(json.dumps(state))
                with self.assertRaises(SystemExit), patch("sys.stderr", io.StringIO()):
                    self.run_command(checkpoint, lambda *_args, **_kwargs: self.fail("Wrong checkpoint must not send work"))


if __name__ == "__main__":
    unittest.main()
