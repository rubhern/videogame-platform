"""Bounded localizer soak: memory trend, CPU, PIDs and failures. No database or catalogue access.

Runs beside the loaded model, normally `docker exec -i <localizer> python -c "$(cat soak.py)"`,
with a JSON array of source texts on stdin. It posts only to the helper's own loopback
endpoint, so nothing is persisted and no backfill starts. Output never contains source text.
Exit status: 0 stable, 1 blocking observation (see `blocking`), 2 invalid invocation.
"""
import argparse
import json
from pathlib import Path
import statistics
import sys
import time
import urllib.error
import urllib.request

MAX_SAMPLES = 5000
MAX_REQUESTS = 20000
MAX_SECONDS = 4 * 3600
CGROUP = Path("/sys/fs/cgroup")


def fields(text):
    return {key: int(value) for key, value in
            (line.split() for line in text.splitlines()) if value.lstrip("-").isdigit()}


def observe(root=CGROUP):
    """One cgroup v2 reading. A missing file is absent evidence, never zero."""
    def number(name):
        try:
            value = (root / name).read_text().strip()
        except OSError:
            return None
        return int(value) if value.isdigit() else value

    def table(name):
        try:
            return fields((root / name).read_text())
        except (OSError, ValueError):
            return {}

    memory, events, cpu, pids = table("memory.stat"), table("memory.events"), table("cpu.stat"), table("pids.events")
    return {"current": number("memory.current"), "peak": number("memory.peak"), "limit": number("memory.max"),
            "anon": memory.get("anon"), "file": memory.get("file"), "oomKill": events.get("oom_kill"),
            "memoryMaxEvents": events.get("max"), "pidsCurrent": number("pids.current"), "pidsPeak": number("pids.peak"),
            "pidsLimit": number("pids.max"), "pidsMaxEvents": pids.get("max"), "cpuUsec": cpu.get("usage_usec"),
            "throttledUsec": cpu.get("throttled_usec")}


def trend(values, warmup):
    """Compare early and late medians after warmup; a single peak cannot look like growth."""
    settled = [v for v in values[warmup:] if v is not None]
    if len(settled) < 6:
        return {"verdict": "insufficient", "samples": len(settled)}
    third = len(settled) // 3
    early, late = statistics.median(settled[:third]), statistics.median(settled[-third:])
    return {"verdict": "measured", "samples": len(settled), "earlyMedianBytes": int(early),
            "lateMedianBytes": int(late), "growthBytes": int(late - early)}


def assess(report, *, max_growth_bytes, min_headroom_bytes):
    """Reasons that block a larger run. Unknown memory evidence blocks: it is not healthy."""
    blocking = []
    if report["failures"]:
        blocking.append("translation-failures")
    if report["oomKillsDuringRun"] is None:
        blocking.append("oom-evidence-unavailable")
    elif report["oomKillsDuringRun"]:
        blocking.append("oom-kill")
    if report["memoryMaxEventsDuringRun"]:
        blocking.append("memory-ceiling-reached")
    if report["pidsMaxEventsDuringRun"]:
        blocking.append("pid-ceiling-reached")
    anon = report["anonTrend"]
    if anon["verdict"] != "measured":
        blocking.append("anonymous-memory-trend-unavailable")
    elif anon["growthBytes"] > max_growth_bytes:
        blocking.append("anonymous-memory-growth")
    limit, peak = report["end"]["limit"], report["end"]["peak"]
    if isinstance(limit, int) and isinstance(peak, int) and limit - peak < min_headroom_bytes:
        blocking.append("insufficient-ceiling-headroom")
    return blocking


def post(url, text, timeout):
    request = urllib.request.Request(url, json.dumps({"text": text}).encode(),
                                     {"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        response.read(1024 * 1024)
        return response.status


def delta(start, end):
    return None if start is None or end is None else end - start


def run(samples, *, max_requests, max_seconds, warmup, url, timeout, sample_every,
        translate=post, read=observe, clock=time.monotonic):
    begin, started = read(), clock()
    readings, slowest, failures, requests = [], 0.0, {}, 0
    while requests < max_requests and clock() - started < max_seconds:
        text = samples[requests % len(samples)]
        before = clock()
        try:
            translate(url, text, timeout)
        except urllib.error.HTTPError as error:
            failures[str(error.code)] = failures.get(str(error.code), 0) + 1
        except (OSError, ValueError) as error:
            name = type(error).__name__
            failures[name] = failures.get(name, 0) + 1
        slowest = max(slowest, clock() - before)
        requests += 1
        if requests % sample_every == 0:
            readings.append(read())
    end, elapsed = read(), clock() - started
    cpu = delta(begin["cpuUsec"], end["cpuUsec"])
    return {"requests": requests, "elapsedSeconds": round(elapsed, 3),
            "requestsPerSecond": round(requests / elapsed, 3) if elapsed else None,
            "slowestRequestSeconds": round(slowest, 3), "failures": failures,
            "cpuCoresAverage": round(cpu / 1e6 / elapsed, 3) if cpu is not None and elapsed else None,
            "oomKillsDuringRun": delta(begin["oomKill"], end["oomKill"]),
            "memoryMaxEventsDuringRun": delta(begin["memoryMaxEvents"], end["memoryMaxEvents"]),
            "pidsMaxEventsDuringRun": delta(begin["pidsMaxEvents"], end["pidsMaxEvents"]),
            "anonTrend": trend([r["anon"] for r in readings], warmup),
            "currentTrend": trend([r["current"] for r in readings], warmup),
            "begin": begin, "end": end}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://127.0.0.1:8092/translate")
    parser.add_argument("--max-requests", type=int, default=500)
    parser.add_argument("--max-seconds", type=int, default=900)
    parser.add_argument("--warmup-readings", type=int, default=5)
    parser.add_argument("--sample-every", type=int, default=5)
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("--max-anon-growth-mib", type=int, default=64)
    parser.add_argument("--min-headroom-mib", type=int, default=128)
    args = parser.parse_args()
    try:
        samples = json.load(sys.stdin)
    except ValueError:
        parser.error("stdin must be a JSON array of source texts")
    if (not isinstance(samples, list) or not 1 <= len(samples) <= MAX_SAMPLES
            or not all(isinstance(s, str) and s.strip() for s in samples)):
        parser.error(f"stdin must hold 1..{MAX_SAMPLES} non-empty source texts")
    if not 1 <= args.max_requests <= MAX_REQUESTS or not 1 <= args.max_seconds <= MAX_SECONDS \
            or args.sample_every < 1 or args.warmup_readings < 0:
        parser.error(f"Bounds: requests 1..{MAX_REQUESTS}, seconds 1..{MAX_SECONDS}")
    report = run(samples, max_requests=args.max_requests, max_seconds=args.max_seconds,
                 warmup=args.warmup_readings, url=args.url, timeout=args.timeout, sample_every=args.sample_every)
    report["distinctSamples"] = len(set(samples))
    report["blocking"] = assess(report, max_growth_bytes=args.max_anon_growth_mib * 1024 * 1024,
                                min_headroom_bytes=args.min_headroom_mib * 1024 * 1024)
    print(json.dumps(report, indent=2))
    return 1 if report["blocking"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
