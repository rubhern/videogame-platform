#!/usr/bin/env python3
"""Operator-only bounded PostgreSQL backfill using the existing private management boundary."""
import argparse
import json
from pathlib import Path
import urllib.request


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:8081/actuator/cataloguelocalize")
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--batch-size", type=int, choices=range(1, 101), default=10)
    parser.add_argument("--max-batches", type=int, default=100)
    args = parser.parse_args()
    if not 1 <= args.max_batches <= 10000:
        parser.error("max-batches must be 1..10000")
    state = json.loads(args.checkpoint.read_text()) if args.checkpoint.exists() else {
        "version": 1, "target": args.url,
        "afterKind": "", "afterId": "00000000-0000-0000-0000-000000000000", "completed": False}
    if state.get("version") != 1 or state.get("target") != args.url:
        parser.error("Checkpoint belongs to a different target or format; use a fresh checkpoint")
    if state.get("completed"):
        print("Completed checkpoint; use a fresh checkpoint for a later catalogue sweep.")
        return
    for _ in range(args.max_batches):
        body = {"afterKind": state["afterKind"], "afterId": state["afterId"], "limit": args.batch_size}
        request = urllib.request.Request(args.url, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=args.batch_size * 60 + 30) as response:
            result = json.load(response)
        print(json.dumps(result), flush=True)
        # Only persist an acknowledged batch result. An interrupted request safely replays it.
        state = {"version": 1, "target": args.url,
                 **{key: result[key] for key in ("afterKind", "afterId", "completed")}}
        args.checkpoint.parent.mkdir(parents=True, exist_ok=True)
        temporary = args.checkpoint.with_suffix(args.checkpoint.suffix + ".tmp")
        temporary.write_text(json.dumps(state, indent=2) + "\n")
        temporary.replace(args.checkpoint)
        if result["report"]["failed"]:
            raise SystemExit("Batch requires retry; source and last valid content remain persisted.")
        if result["completed"]:
            return
    print("Batch budget reached; resume with the same checkpoint.")


if __name__ == "__main__":
    main()
