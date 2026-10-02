#!/usr/bin/env python3
"""Complete-Game operator reconciliation via private management commands; defaults to a dry run."""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import sys
import urllib.error
import urllib.parse
import urllib.request

ZERO = "00000000-0000-0000-0000-000000000000"


def request(base, path, body=None, timeout=900):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base + path, data=data,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as response:
        return json.load(response)


def checkpoint(path, state):
    if path is None:
        return
    temporary = path.with_suffix(path.suffix + ".tmp")
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as output:
        json.dump(state, output)
        output.write("\n")
    os.replace(temporary, path)


def windows(start, end, days):
    while start <= end:
        to = min(end, start + dt.timedelta(days=days - 1))
        yield start, to
        start = to + dt.timedelta(days=1)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--management-url", default="http://127.0.0.1:8081")
    parser.add_argument("--from", dest="start", type=dt.date.fromisoformat, required=True)
    parser.add_argument("--to", dest="end", type=dt.date.fromisoformat, required=True)
    parser.add_argument("--window-days", type=int, default=7)
    parser.add_argument("--batch-size", type=int, default=10)
    parser.add_argument("--checkpoint", type=Path)
    parser.add_argument("--apply", action="store_true", help="Reconcile current provider releases and rerun synchronization")
    args = parser.parse_args(argv)
    target = urllib.parse.urlsplit(args.management_url)
    if (target.scheme != "http" or target.hostname not in {"localhost", "127.0.0.1", "::1"}
            or target.username or target.password or target.query or target.fragment
            or target.path not in {"", "/"}):
        parser.error("Use a loopback management URL (forward private-dev management locally if needed)")
    if args.end < args.start or args.end == dt.date.max or not 1 <= args.window_days <= 31 or not 1 <= args.batch_size <= 100:
        parser.error("Use an ordered date range, windows of 1–31 days and batches of 1–100")
    if args.apply and args.checkpoint is None:
        parser.error("--apply requires --checkpoint for explicit resumable operation")
    base = args.management_url.rstrip("/")
    config = dict(algorithm="complete-game-v2", target=base, start=str(args.start), end=str(args.end),
                  windowDays=args.window_days, batchSize=args.batch_size, apply=args.apply)
    state = dict(config=config, nextWindow=str(args.start), after=ZERO, gamesComplete=False)
    if args.checkpoint and args.checkpoint.exists():
        state = json.loads(args.checkpoint.read_text())
        if state["config"] != config:
            parser.error("Checkpoint belongs to different target/options; use a new checkpoint")
    print(json.dumps(dict(mode="apply" if args.apply else "dry-run", initial=request(base, "/actuator/releasestagerepair"))), flush=True)
    for start, end in windows(dt.date.fromisoformat(state["nextWindow"]), args.end, args.window_days):
        print(json.dumps(dict(windowFrom=str(start), windowTo=str(end), action="synchronize" if args.apply else "planned")), flush=True)
        if args.apply:
            report = request(
                base, "/actuator/cataloguesync", {"from": str(start), "to": str(end)})
            print(json.dumps(report), flush=True)
            if report.get("outcome") != "SUCCEEDED":
                raise RuntimeError("Window failed/partial/skipped; checkpoint retained for safe replay")
            state["nextWindow"] = str(end + dt.timedelta(days=1))
            checkpoint(args.checkpoint, state)
    while not state["gamesComplete"]:
        report = request(base, "/actuator/releasestagerepair", dict(after=state["after"], limit=args.batch_size, dryRun=not args.apply))
        print(json.dumps(report), flush=True)
        if report["reconciliation"]["outcome"] != "SUCCEEDED":
            raise RuntimeError("Repair failed/partial/skipped; checkpoint retained for safe replay")
        if not report["complete"] and report["nextAfter"] == state["after"]:
            raise RuntimeError("Repair cursor did not advance")
        state["after"] = report["nextAfter"]
        state["gamesComplete"] = report["complete"]
        checkpoint(args.checkpoint, state)
    print(json.dumps(dict(final=request(base, "/actuator/releasestagerepair"),
                          note="UNKNOWN counts describe current rows; complete Game reconciliation preserves unavailable evidence")), flush=True)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, RuntimeError, urllib.error.HTTPError) as failure:
        print(f"Repair stopped: {failure}", file=sys.stderr)
        sys.exit(1)
