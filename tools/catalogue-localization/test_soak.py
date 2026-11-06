"""Soak decisions from deterministic cgroup readings, without a model or a running helper."""
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.error

import soak

MIB = 1024 * 1024


def reading(anon=400 * MIB, peak=500 * MIB, limit=1792 * MIB, oom=0, memory_max=0, pids_max=0, cpu=0):
    return {"current": anon, "peak": peak, "limit": limit, "anon": anon, "file": 0, "oomKill": oom,
            "memoryMaxEvents": memory_max, "pidsCurrent": 5, "pidsPeak": 9, "pidsLimit": 64,
            "pidsMaxEvents": pids_max, "cpuUsec": cpu, "throttledUsec": 0}


class Clock:
    def __init__(self):
        self.now = 0.0

    def __call__(self):
        self.now += 1
        return self.now


def execute(readings, translate=lambda *_: 200, requests=None, **limits):
    series = iter(readings)
    options = dict(max_requests=requests or len(readings) - 2, max_seconds=10000, warmup=0, url="u",
                   timeout=1, sample_every=1)
    options.update(limits)
    return soak.run(["text"], translate=translate, read=lambda: next(series), clock=Clock(), **options)


def blocking(report):
    return soak.assess(report, max_growth_bytes=64 * MIB, min_headroom_bytes=128 * MIB)


class SoakTests(unittest.TestCase):
    def test_flat_memory_with_a_transient_peak_is_stable(self):
        values = [400 * MIB] * 9 + [900 * MIB] + [400 * MIB] * 10
        self.assertEqual(blocking(execute([reading(anon=v) for v in values])), [])

    def test_progressive_growth_blocks_even_without_an_oom(self):
        report = execute([reading(anon=(400 + 10 * i) * MIB) for i in range(30)])
        self.assertIn("anonymous-memory-growth", blocking(report))

    def test_each_failure_signal_blocks(self):
        steady = [reading() for _ in range(20)]
        for name, last in {"oom-kill": reading(oom=1), "memory-ceiling-reached": reading(memory_max=3),
                           "pid-ceiling-reached": reading(pids_max=1),
                           "insufficient-ceiling-headroom": reading(peak=1700 * MIB)}.items():
            with self.subTest(name):
                self.assertIn(name, blocking(execute([*steady, last])))

    def test_translation_failures_are_counted_by_cause_and_block(self):
        outcomes = iter([urllib.error.HTTPError("u", 503, "x", {}, io.BytesIO()), TimeoutError("late")])

        def translate(*_):
            try:
                raise next(outcomes)
            except StopIteration:
                return 200
        report = execute([reading() for _ in range(20)], translate)
        self.assertEqual(report["failures"], {"503": 1, "TimeoutError": 1})
        self.assertIn("translation-failures", blocking(report))

    def test_unavailable_evidence_blocks_instead_of_looking_healthy(self):
        report = execute([reading(anon=None, oom=None) for _ in range(20)])
        self.assertEqual(report["anonTrend"]["verdict"], "insufficient")
        self.assertEqual(report["oomKillsDuringRun"], None)
        result = blocking(report)
        self.assertIn("oom-evidence-unavailable", result)
        self.assertIn("anonymous-memory-trend-unavailable", result)

    def test_request_and_time_budgets_bound_the_run(self):
        calls = []
        execute([reading() for _ in range(10)], lambda *_: calls.append(1), requests=5)
        self.assertEqual(len(calls), 5)
        calls.clear()
        execute([reading() for _ in range(10)], lambda *_: calls.append(1), requests=1000, max_seconds=4)
        self.assertLess(len(calls), 5)

    def test_cgroup_reading_keeps_a_missing_file_absent(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "memory.current").write_text("10\n")
            (root / "memory.max").write_text("max\n")
            (root / "memory.stat").write_text("anon 7\nfile 3\n")
            result = soak.observe(root)
        self.assertEqual((result["current"], result["limit"], result["anon"]), (10, "max", 7))
        self.assertIsNone(result["peak"])
        self.assertIsNone(result["oomKill"])

    def test_output_never_contains_source_text(self):
        report = execute([reading() for _ in range(20)])
        self.assertNotIn("text", str({k: v for k, v in report.items() if k != "begin" and k != "end"}))

    def test_invalid_input_is_refused(self):
        for value in ("not-json", "[]", '["ok", ""]', '{"a": 1}'):
            with self.subTest(value), patch("sys.argv", ["soak"]), patch("sys.stdin", io.StringIO(value)), \
                    patch("sys.stderr", io.StringIO()), self.assertRaises(SystemExit) as refused:
                soak.main()
            self.assertEqual(refused.exception.code, 2)


if __name__ == "__main__":
    unittest.main()
