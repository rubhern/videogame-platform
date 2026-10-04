"""Bounded comparison on an operator-provided JSON array of real IGDB summaries."""
import argparse
import json
from pathlib import Path
import resource
import time
from helper import Translator

parser = argparse.ArgumentParser()
parser.add_argument("--model", required=True)
parser.add_argument("--samples", required=True)
parser.add_argument("--output", required=True)
args = parser.parse_args()
rows = json.loads(Path(args.samples).read_text())
if not isinstance(rows, list) or not 1 <= len(rows) <= 20:
    raise ValueError("Require 1..20 representative samples")
started = time.perf_counter()
translator = Translator(args.model)
load_seconds = time.perf_counter() - started
translator.translate("A warrior explores the world.")
results = []
wall = time.perf_counter()
cpu = time.process_time()
for row in rows:
    started = time.perf_counter()
    result = translator.translate(row["summary"])
    results.append({"name": row["name"], "source_chars": len(row["summary"]),
                    "seconds": time.perf_counter() - started, "translation": result["text"]})
wall = time.perf_counter() - wall
cpu = time.process_time() - cpu
usage = resource.getrusage(resource.RUSAGE_SELF)
steady_rss = next(int(line.split()[1]) for line in Path("/proc/self/status").read_text().splitlines() if line.startswith("VmRSS:"))
evidence = {"revision": translator.revision, "load_seconds": load_seconds,
            "model_bytes": sum(p.stat().st_size for p in Path(args.model).iterdir() if p.is_file()),
            "peak_rss_kib": usage.ru_maxrss, "steady_rss_kib": steady_rss, "wall_seconds": wall, "cpu_seconds": cpu,
            "summaries_per_second": len(results) / wall, "results": results}
Path(args.output).write_text(json.dumps(evidence, ensure_ascii=False, indent=2))
print(json.dumps({k: v for k, v in evidence.items() if k != "results"}))
