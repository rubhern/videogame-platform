# First public HTTP baseline

This is implemented research for [#85](https://github.com/rubhern/videogame-platform/issues/85),
following the reviewed [dataset profiles](README.md). Its purpose is to validate
the measurement path at a deliberately low local load. It defines no product SLO,
latency requirement, traffic forecast or capacity commitment. Final capacity and interference conclusions belong to the [final report](remote-harness.md).
It is opt-in and has no mandatory CI integration.

## Harness and exact workload

[baseline.js](../../../tools/performance/baseline.js) uses the standard
[k6 constant-arrival-rate executor](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-arrival-rate/).
[baseline.py](../../../tools/performance/baseline.py) runs
[k6 2.3.0](https://github.com/grafana/k6/releases/tag/v2.3.0) pinned by Docker tag
and multi-platform digest. It requires only Docker and Python's standard library;
there are no remote JavaScript imports, extensions or cloud output. The tool
refuses remote Docker engines, alternative targets, load overrides and scheduled
catalogue synchronization. It verifies the supported local application's network,
loopback port, database address and readiness.

One VU, maximum one VU, starts one iteration per second for 120 configured seconds.
Each iteration makes one GET, rotating through these five requests. The request
budget is 120, with 24 calls per path; a boundary iteration cannot add a 121st call.
Missing VU capacity causes dropped iterations instead of increasing concurrency.
Requests time out after 10 seconds; the executor allows 10 seconds for completion.

| Bounded metric name | Request below `/api/v1` |
|---|---|
| `releases_recent` | `/releases?view=recent&weeks=1&page=1&pageSize=20` |
| `releases_upcoming` | `/releases?view=upcoming&weeks=1&page=1&pageSize=20` |
| `releases_featured` | `/featured-releases` |
| `catalogue_search` | `/games?q=zelda&page=1&pageSize=20` |
| `game_detail` | `/games/{gameId}` — first ranked Zelda search match, frozen in `workload.json` |

Five read-only product preflight calls, one per path, select and verify the fixture
and warm these paths before measurement. Inventory, readiness/info and before/after
observations are also outside k6. No cookies, authentication, writes, retries,
redirect following, batches, conditional GETs or asset/CDN requests are generated.
k6 reuses connections. It calls the packaged same-origin API over the local Compose
network, without browser rendering, a reverse proxy, TLS or a network between hosts.

Each request checks HTTP 200, JSON, the echoed correlation ID and a small useful
contract subset: bounded/coherent pages and unique Game identities, requested view
and evaluation date, valid featured selection, stable search/detail identity,
required detail collections and coherent rating buckets. Empty release/featured
states and the contract's explicit unavailable rating aggregate are valid states.
The fixture search must return a match. These checks complement existing API tests;
they are not a full schema validator.

Functional gates require all checks to pass, no unexpected HTTP failures, no dropped
iterations and the exact 120-request budget. Per-path p50/p95/p99 and mean latency
are observations with **no latency threshold**. Metric dimensions use only the
five fixed names and standard bounded method/status/check/scenario fields. URLs,
search terms, Game IDs and correlation IDs are excluded from k6 metric tags. The
per-request correlation ID travels only as an HTTP header and log field.

## Reproduction

Start the supported local application and optional observability using
[local setup](../../development/local-setup.md#local-metrics-and-logs), with the
existing persistent local catalogue. Keep other clients idle and do not trigger
synchronization or localization backfill during this isolated read experiment.
Run from the issue worktree:

```bash
python3 tools/performance/baseline.py
```

The printed fresh directory under ignored `tools/performance/results/` contains:

- `profile.json`: measured read-only current dataset, not synthetic data.
- `workload.json` and `manifest.json`: fixture, UTC window, monotonic elapsed time,
  wall-clock audit, exact k6 options/digest, application source/image/build/Java
  version, Docker VM and container bounds, and allowlisted runtime settings.
- `summary.json`: native k6 summary; `metrics.json`: timestamped raw metric points;
  `console.log`: per-path human-readable summary and failure diagnostics.
- `before.json` and `after.json`: existing Actuator/JVM/Hikari meters, read-only
  PostgreSQL statistics and Docker container resource snapshots. Missing meters
  are marked unavailable; no instrumentation or settings are added.

The exit code is k6's functional result. Inspect `clockAudit` even on a successful
run: functional success does not establish measurement-clock stability. After at
least 90 seconds for existing export/scrape delivery, capture the existing Grafana
datasources for the same window, supplying the local password **file**, never its
contents on the command line:

```bash
python3 tools/performance/correlate.py \
  tools/performance/results/<printed-run-directory> \
  /home/rubhern/workspace/videogame-platform/.local-secrets/grafana-admin-password
```

[correlate.py](../../../tools/performance/correlate.py) writes `server-evidence.json`
with the exact PromQL/LogQL and returned series/completions. Its window includes
two minutes before and 90 seconds after, at 30-second resolution. Surrounding
process history can include previous runs/startup; compare the manifest window and
per-run log fields, and account for counter resets. Empty series and query failure
are recorded as gaps. Neither telemetry availability nor latency is a product gate.


## Evidence and conclusion

The [final report](remote-harness.md) owns capacity conclusions.
The [http-stable-manifest.json](http-stable-manifest.json) retains this method’s historical results.
Clock-invalid local runs remain functional/query diagnostics only; they are not capacity evidence.
Raw artifacts remain ignored under `tools/performance/results/`.
