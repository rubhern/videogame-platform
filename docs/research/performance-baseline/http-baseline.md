# First public HTTP baseline

This is implemented research for [#85](https://github.com/rubhern/videogame-platform/issues/85),
following the reviewed [dataset profiles](README.md). Its purpose is to validate
the measurement path at a deliberately low local load. It defines no product SLO,
latency requirement, traffic forecast or capacity commitment. Stress, authenticated
traffic, synchronization interference and production optimizations remain deferred.
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

### Application build used for this observation

The configured primary checkout holds the existing local secrets and data, while
the issue worktree has the reviewed source. The cached `0.26.0-SNAPSHOT` image lacked
a source revision, so it was used only for an initial harness check. The retained
observation uses a new build of this worktree's `0.29.0-SNAPSHOT`, revision
`702600188044fa51aa0834da6e030f872a50a916`. No product source was edited.
The commands below reproduce the build without regenerating credentials or
recreating PostgreSQL. They assume those existing local dependency containers:

```bash
local_runtime=/home/rubhern/workspace/videogame-platform
docker start videogame-platform-postgres-1 videogame-platform-keycloak-1
bash "$local_runtime/scripts/local-dependencies.sh" observability --recreate
export APPLICATION_VERSION="$(bash scripts/backend-artifact.sh version)"
export SOURCE_REVISION="$(git rev-parse HEAD)"
performance_runtime_override="$(mktemp --suffix=.yaml)"
trap 'rm -f -- "$performance_runtime_override"' EXIT
cat > "$performance_runtime_override" <<YAML
services:
  application:
    env_file:
      - path: $local_runtime/backend/.env
        required: true
YAML
docker compose --env-file "$local_runtime/.env" \
  -f compose.yaml -f compose.observability.yaml -f "$performance_runtime_override" \
  --profile full build application
docker compose --env-file "$local_runtime/.env" \
  -f compose.yaml -f compose.observability.yaml -f "$performance_runtime_override" \
  --profile full up --no-deps --detach application
```

Wait for `http://127.0.0.1:8081/actuator/health/readiness` to report `UP` before
running the baseline. `--recreate` repaired stale Docker Desktop observability
bind mounts in this session and preserved its volumes. It is not required for
every experiment. Application startup uses the existing approved Flyway behavior;
no performance migration or seed is installed. The baseline itself issues only reads.

## Recorded result and interpretation

The retained observation ran on **2026-10-07 Europe/Madrid**, from
`2026-10-06T22:52:16.358204Z` to `22:54:09.209160Z` in the VM's stepped UTC wall
clock. Run ID: `perf-current-20261006T225212Z-92_pvii8`. Evidence is retained as the
[manifest and measured dataset](http-manifest.json), [native k6 summary](http-summary.json),
[120 raw latency samples](http-samples.json), and [server observations](http-server-evidence.json).
The remaining raw runtime outputs stay in the ignored run directory.

Environment:

- Application `0.29.0-SNAPSHOT`, exact revision stated above, Temurin Java
  `25.0.4+7`, `oidc,structured`, 1 GiB memory / 0.5 CPU, Hikari maximum 10;
  actual maximum heap 247.5 MiB. OTLP metrics enabled at 60 seconds, traces disabled,
  scheduled synchronization disabled; translation helper stopped.
- PostgreSQL `18.4-bookworm`, persistent supported local database, 512 MiB / 0.5 CPU.
  `current` remained **20,241 Games, 36,090 Releases, 7 Ratings**; counts matched the
  reviewed inventory. Docker Engine `29.7.2` on Docker Desktop/WSL2,
  kernel `6.18.40.1-microsoft-standard-WSL2`, x86_64, 32 VM CPUs,
  16,328,323,072 bytes of VM memory. k6 `2.3.0`, 256 MiB / 1 CPU, same VM/network.
- Existing Collector `0.160.0`, Prometheus `v3.13.3`, Grafana `13.2.2`,
  Alloy `v1.20.1`, Loki `3.7.8`; existing provisioning/settings. Collector and
  Prometheus configurations matched this worktree. Their pinned images and limits
  remain owned by the [shared overlay](../../../deploy/private-dev/compose.observability.yaml)
  and [log services](../../../deploy/private-dev/compose.logs.yaml).

Preflight found 146 recent and 183 upcoming Games in the default one-week windows,
six ranked featured Games with fresh popularity evidence, and three Zelda search
matches. The detail fixture was `98cd7020-a79a-4310-a6e8-8c624769a1db`, with one
stored release and an available empty rating aggregate. This is a selective search
and an unrated detail fixture; the seven actual ratings do not exercise a dense
aggregate. The pages are shallow, the selected month is current and paths/caches
are warm. This run does not sample the synthetic profiles or claim representative
future traffic distributions.

**120 requests, 24 per path; all HTTP 200; 480/480 checks; zero HTTP failures;
zero dropped iterations; k6 exit code 0.** k6 received 2,054,261 bytes including
response overhead. Its observed durations in milliseconds were:

| Path | Mean | p50 | p95 | p99 |
|---|---:|---:|---:|---:|
| Recent releases | 17.31 | 17.18 | 18.09 | 20.97 |
| Upcoming releases | 16.61 | 16.76 | 17.30 | 18.68 |
| Featured releases | 10.30 | 10.24 | 10.81 | 11.05 |
| Catalogue search | 7.70 | 7.53 | 8.59 | 9.89 |
| Game detail | 7.07 | 7.05 | 7.57 | 7.60 |
| Combined mix | 11.80 | 10.27 | 17.61 | 18.91 |

The native k6 summary reports **1.068 requests/second** and approximately
112.32 seconds of elapsed time. Four backwards wall-clock transitions occurred
between successive request samples, while the wrapper measured **120.53 seconds
with a monotonic clock**, including Docker startup/exit overhead. This corresponds
to about 0.996 requests/second over the whole runner invocation, consistent with
the configured low rate, but it is not a replacement k6 statistic. Retain the raw
reported rate with this qualification; it cannot establish throughput capacity.
Per-request timers were cross-checked against server completions; clock stability
still needs verification before later temporal/rate conclusions.

Successfully correlated signals:

- **HTTP:** Actuator completion count rose from 130 to 250; cumulative server time
  increased by 1,297.06 ms, a 10.81 ms mean for the run. Loki contained all 120
  matching numeric correlation IDs, all HTTP 200: 48 `/api/v1/releases` and 24
  each featured/search/detail. Prometheus exported the same four route templates,
  cumulative counts/times and runtime series; the settled process total was 250,
  including previous warm-up/read traffic, rather than an isolated per-run counter.
- **JVM/pool:** direct GC counters increased by one pause / 7 ms; threads were
  55 then 56. Direct heap snapshots were about 116.0 then 111.5 MiB; coarser
  in-window exported samples showed 123.1–147.9 MiB. Process/system CPU, heap
  limits, GC, threads, pool acquisition and pool maximum reached Prometheus.
  Sampled pool active/pending values were zero, maximum 10, and timeout counters
  stayed zero. These samples do not measure concurrency peaks.
- **Database/container:** application-database connections were 11 before/after,
  with no new block reads, temporary files/bytes or deadlocks; block-cache hits
  increased by 226,272. The one extra rollback was the observer's deliberate
  read-only transaction rollback. Docker snapshots captured application memory
  of 392.5 then 397 MiB, PostgreSQL around 127.5 MiB, CPU and cumulative I/O.
  They describe observed containers, not physical-host pressure or interval peaks.

## Correlation and observability limits

The canonical [observability guide](../../development/observability.md) owns meter
meaning, security and export limits. This harness reuses its Runtime / Resources
and Application / HTTP dashboards, PostgreSQL statistics and Platform logs.

Actuator `http.server.requests` count/time deltas provide a direct process-level
cross-check. Prometheus uses `http_server_requests_milliseconds_{count,sum,bucket}`
with route templates; both release views share `/api/v1/releases`. The raw k6 fixed
names and correlated completions distinguish them without new server metric tags.
Existing histograms can support server latency estimates via dashboard queries;
they are different measurements from k6's send/wait/receive duration, which excludes
DNS/connect/TLS (captured separately). Raw points retain individual timings.

Loki completions are matched to the exact run's numeric correlation suffixes,
excluding preflight. These IDs remain log fields, with only the existing bounded
`environment`/`service_name` Loki labels. No trace backend is retained: trace IDs
can connect logged context but do not provide a stored database-query trace.

One-minute cumulative OTLP export, Collector batching and 30-second Prometheus
scrapes blur short intervals and can miss initial lazy counters. Gauges at this
resolution miss short CPU/GC/pool spikes; zero sampled active/pending connections
does not prove no connections were busy. Use direct before/after counters and
correlated completion logs for exact counts, rather than interpreting extrapolated
`increase()` as an exact per-run ledger. An application restart resets meters; the
shared local series can briefly show stale values from the preceding process.

PostgreSQL statistics cover all application-database clients, including inventory,
observation queries and Grafana. They expose connections, cache reads/hits, temporary
work, deadlocks and transaction totals, without query attribution or latency/wait
histories. Docker snapshots show container bounds and resource observations, not
peaks or full Windows-host pressure. There is no retained physical-host CPU, disk
latency, I/O wait or memory-pressure history in the current stack. These gaps limit
later bottleneck/capacity diagnosis; no exporter, extension or instrumentation is
added in this slice.

The run also observed backwards Docker/WSL wall-clock steps. The manifest audit and
independent monotonic elapsed time preserve that limitation. Clock stability must
be resolved/rechecked before interpreting later arrival rates, saturation or
aligned time series. The same four reversals appeared in the application completion
timestamps, independently confirming the guest-clock behavior. This harness does
not reconfigure the host clock.

## Focused validation and remaining scope

Validation passed Python syntax, pinned k6 option inspection, remote-engine and
load-override rejection, live preflight and all 480 functional checks, exact 120-call
budget, telemetry queries, independent raw-sample/summary/log-count reconciliation,
documentation links and diff whitespace checks. The first source-identified run
also passed, and the final repeat validated monotonic timing/clock audit after the
observed clock steps. All runtime captures are read-only with respect to the catalogue. The image
build packages the current source with its existing skip-tests image profile;
unaffected Java/frontend/browser suites are not rerun for this tooling-only change.

Later #85 work still needs representative varied/selective/non-selective/deep-page
traffic, synthetic-profile HTTP runs, authenticated ratings, controlled sync
interference, stress/soak/recovery criteria and measurement gaps addressed as needed.
None is inferred from this initial run. This engineering harness/documentation is
outside releasable application/API artefacts, so no version increment is required.
Owner review and publication remain separate; nothing was committed.
