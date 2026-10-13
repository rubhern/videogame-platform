# Public read load on growth

Implemented research for [#85](https://github.com/rubhern/videogame-platform/issues/85).
This opt-in experiment measures the current application against the reviewed
[synthetic growth dataset](README.md), after the
[owner-accepted stable minimal baseline](http-baseline.md#accepted-stable-repetition-after-wsl-restart).
It defines no product thresholds, SLOs, traffic forecast or CI gate. Application
code, SQL, indexes, pools and architecture are unchanged.

## Experimental method

The existing [k6 workload](../../../tools/performance/baseline.js) and
[local runner](../../../tools/performance/baseline.py) support a fixed `growth` mode.
Four sequential constant-arrival-rate levels target 5, 10, 25 and 50 requests/second
for 300 seconds each. Each iteration sends one GET; there are no retries, writes,
batches, cookies, authentication, asset requests or conditional GETs. Connections
are reused. The 20 cases rotate deterministically with equal 5% weight: scheduled
budgets are 1,500/3,000/7,500/15,000 requests, or 75/150/375/750 per case.
Dropped iterations can change the delivered mix; the retained results report
actual counts. These are experimental inputs, not user-demand predictions.

All paged requests use page size 20. Requests time out after 10 seconds and have
10 seconds graceful completion. Per-level preallocated/max VUs are respectively
10/60, 20/120, 50/300 and 100/600. The k6 container is bounded to 1 CPU and 1 GiB;
its observed resources are retained to distinguish generator limitations. A
30-second idle interval after each level captures pool/heap recovery and readiness.
This is a bounded read-load experiment, not a full stress or soak test.

Only functional checks are thresholds: HTTP 200, valid JSON, echoed correlation,
coherent pages and filters, frozen search totals/first identities, detail fan-out
and available/coherent rating buckets. Latencies, throughput, timeouts and dropped
iterations remain observations. Metric tags use a fixed case vocabulary; raw URLs,
search terms and identifiers are excluded. Correlation IDs are log/header fields.

The local application container is reused with the
[experiment Compose override](../../../tools/performance/compose.growth.yaml).
The existing discarded-after-use dataset container serves PostgreSQL. The normal
persistent catalogue is neither reset nor loaded with synthetic data. Flyway and
scheduled synchronization are disabled, provider credentials are empty, the
localization helper is stopped, and no synchronization/localization commands are
invoked. Public reads use normalized local state. The fixture reference date and
application evaluation date must match, and the runner verifies counts, disposable
boundary and migration checksum before load.

Existing Actuator/JVM/Hikari meters, PostgreSQL database/activity/lock statistics,
Docker stats and cgroup CPU/memory counters are sampled between requests. The
sampling loop waits 10 seconds between captures; capture time adds to the interval.
Before/after and idle snapshots supplement the existing 60-second OTLP export and
30-second Prometheus scrapes. Management and read-only observer queries are outside
the k6 workload and introduce small measurement overhead. PostgreSQL rollback
counters include deliberate read-only observer rollbacks.

Clock evidence includes timestamped raw HTTP samples and a host wall/monotonic pair
every 250 ms. Concurrent k6 completions can be serialized out of timestamp order;
raw reversals up to 100 ms are recorded separately as possible concurrent reorderings.
Larger reversals or a wall/monotonic backwards discrepancy over 100 ms stop escalation
and invalidate capacity interpretation. This is an audit resolution, not a product
threshold or a proof against smaller/unsampled clock changes.

## Reproduction

Use the supported local Docker engine and existing application image/observability
stack. The [local setup](../../development/local-setup.md) owns dependency startup
and credentials. The following uses the existing primary checkout's configuration;
substitute its location if different. It recreates only the application container,
with the same application image and resource/pool limits.

```bash
bash tools/performance/dataset.sh generate growth 2026-10-07 > /tmp/growth-profile.json
docker network connect videogame-platform_default videogame-platform-performance-postgres
local_runtime=/home/rubhern/workspace/videogame-platform
export APPLICATION_VERSION="$(bash scripts/backend-artifact.sh version)"
performance_runtime_override="$(mktemp --suffix=.yaml)"
cat > "$performance_runtime_override" <<YAML
services:
  application:
    env_file:
      - path: $local_runtime/backend/.env
        required: true
YAML
docker compose --env-file "$local_runtime/.env" \
  -f compose.yaml -f compose.observability.yaml -f "$performance_runtime_override" \
  -f tools/performance/compose.growth.yaml --profile full \
  up --no-deps --detach application
# Wait for /actuator/health/readiness on loopback port 8081 to report UP.
python3 tools/performance/baseline.py growth
# Wait at least 90 seconds after the final level for export/scrape delivery.
python3 tools/performance/correlate.py \
  tools/performance/results/<printed-growth-directory> \
  "$local_runtime/.local-secrets/grafana-admin-password"
# Restore the persistent local catalogue using the same base files without the growth override.
docker compose --env-file "$local_runtime/.env" \
  -f compose.yaml -f compose.observability.yaml -f "$performance_runtime_override" \
  --profile full up --no-deps --detach application
rm -f -- "$performance_runtime_override"
bash tools/performance/dataset.sh stop
```

For later dates regenerate with the application's Europe/Madrid date. If the dataset
container already has the network attached, omit `docker network connect`; Docker
rejects duplicate attachment. Keep the localization helper stopped, other clients
idle, and operator commands unused. Reuse a source-identified image; build with the
existing procedure in the [HTTP baseline report](http-baseline.md#application-build-used-for-this-observation)
when necessary. No new image build was needed for this measurement.

The ignored run directory retains `profile.json`, `preflight.json`, `experiment.json`,
and per-level workload/manifest/native summary/raw metrics/console/snapshots/paired
clock samples. [correlate.py](../../../tools/performance/correlate.py) reads the same
existing Grafana datasources and produces per-level `server-evidence.json` plus
`growth-results.json`. Bounded 10-second Loki completion windows avoid truncating higher-volume
completion queries. Empty series or missing logs are evidence gaps.

## Exact workload and recorded observation

[`growth_requests`](../../../tools/performance/baseline.py) owns two selectable
profiles; both rotate the listed order deterministically, one call per iteration:

- **`adversarial`** (default, and the only profile of this local run): all 20 rows,
  each 5% of scheduled requests. `search_broad`, `search_deep_10k` and
  `search_deep_48k` match every Game and page to offsets 10,000/48,000, so 15% of
  this mix is deliberately extreme contract-reachable search.
- **`normal`**: the other 17 rows, each 1/17 (≈5.9%); a budget of 300 schedules 18
  calls for the first eleven rows and 17 for the last six. It is an experimental
  approximation of habitual public reads, **not observed traffic**.

Filters use the frozen synthetic identities:
PC `7a26a1da-d414-0b7a-c3cb-4e28c51d3a07`, PS5
`2be57ffd-b64b-2428-5be4-8cacc033f8da`, Worldwide
`c75704d9-d54e-1529-6e7f-4e449a0872fd` and North America
`2d4da997-25a3-cd72-1e45-b99ae8a084a1`. The exact encoded paths and expected
identities are retained in the machine-readable experiment manifest.

| Case | Request inputs | Preflight Games / detail evidence |
|---|---|---:|
| `releases_recent` | recent, 1 week, page 1 | 417 |
| `releases_upcoming` | upcoming, 1 week, page 1 | 1,023 |
| `releases_featured` | current month | 6 featured Games |
| `recent_pc` | recent, 4 weeks, PC, page 1 | 1,049 |
| `recent_multi_filters` | recent, 4 weeks, PC OR PS5, Worldwide OR North America, page 1 | 1,232 |
| `upcoming_north_america` | upcoming, 4 weeks, North America, page 1 | 10 |
| `upcoming_approximate` | upcoming, 4 weeks, approximate dates enabled, page 1 | 8,649 |
| `recent_deep` | recent, 4 weeks, page 51, offset 1,000 | 2,165 |
| `upcoming_deep` | upcoming, 4 weeks, page 51, offset 1,000 | 3,518 |
| `search_selective` | `Needle`, page 1 | 50 |
| `search_broad` | `Adventure`, page 1 | 50,000 |
| `search_deep_10k` | `Adventure`, page 501, offset 10,000 | 50,000 |
| `search_deep_48k` | `Adventure`, page 2,401, offset 48,000 | 50,000 |
| `search_alias` | `Aliasquest 000001`, page 1 | 1, approved alias match |
| `search_accent` | `Adventure Élite`, page 1 | 5,000 |
| `search_normalized` | `  adventure ELITE  `, page 1 | 5,000, same first identity as accented query |
| `detail_fanout_1` | synthetic Game 4 | 1 release, 84 Ratings |
| `detail_fanout_2` | synthetic Game 1 | 2 releases, 84 Ratings |
| `detail_fanout_3` | synthetic Game 2 | 3 releases, 84 Ratings |
| `detail_fanout_10` | synthetic Game 3 | 10 releases, 84 Ratings |

Search preflight freezes totals and first identity, while release preflight freezes
totals for the measured date. Every detail exercises a populated rating aggregate.
Only four fixed Games are sampled for detail; this does not represent varied rating
hotspots, the entire popularity distribution or random cold-cache access. All paths
are warmed by preflight; no cache is flushed between levels.


## Recorded result: clock-invalid diagnostic

The experiment started on **2026-10-07 Europe/Madrid**, run directory
`growth-20261007T094633Z-gi6fpx2u`. Retained
[growth-results.json](growth-results.json) contains the exact paths/distribution,
per-case native percentiles, per-level options and source/image identity, clock
reversals, server series, resource counters and control data. Complete raw metrics,
paired clock samples, completion logs and periodic captures remain in the ignored
run directory. The measurement covered **only 5 req/s**: the clock prerequisite
failed and the harness stopped before the other three levels.

The application reused image ID
`sha256:d3e6f20250bf60f8e925fb4d096d3047ac00b94baee2eae2462162ca3fc95ded`,
`0.29.0-SNAPSHOT`, source `9eb305c3fb8c13398f2b64be69fc3d850479d806`,
Temurin Java 25, 0.5 CPU / 1 GiB container limit, actual maximum heap 247.5 MiB and
Hikari maximum 10. The synthetic PostgreSQL kept dataset.sh's existing 2 CPU /
1 GiB bounds, rather than the normal local PostgreSQL's 0.5 CPU / 512 MiB.
This resource difference, dataset, workload and clock prohibit treating this as a
controlled capacity comparison with the minimal baseline. Docker Desktop/WSL2
exposed 32 CPUs and about 15.2 GiB VM memory. Metrics export remained enabled,
traces disabled, and no observability service/configuration was changed.

### Results by configured load

These durations are retained observations, not acceptance thresholds. The native
throughput is explicitly unusable as capacity evidence because of clock steps.

| Input req/s | Requests | Native throughput req/s | p50 ms | p95 ms | p99 ms | HTTP errors | Timeouts | Dropped |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 5 | 1,500 | 5.156 — clock-invalid | 27.16 | 365.30 | 393.72 | 0 (0%) | 0 | 0 |
| 10 | Not executed: clock prerequisite failed | — | — | — | — | — | — | — |
| 25 | Not executed: clock prerequisite failed | — | — | — | — | — | — | — |
| 50 | Not executed: clock prerequisite failed | — | — | — | — | — | — | — |

All 6,000 functional checks passed, k6 exit code was 0, every request returned
HTTP 200, and all 20 cases received exactly 75 calls (5%). Combined mean was
65.27 ms, maximum 453.77 ms. The exact 1,500-request budget is valid count evidence;
functional success does not establish a stable measurement clock.

| Case | Calls | p50 ms | p95 ms | p99 ms |
| `releases_recent` | 75 | 24.19 | 27.26 | 29.54 |
| `releases_upcoming` | 75 | 23.94 | 28.08 | 43.47 |
| `releases_featured` | 75 | 15.89 | 19.82 | 21.54 |
| `recent_pc` | 75 | 31.89 | 35.93 | 67.26 |
| `recent_multi_filters` | 75 | 42.41 | 49.10 | 62.46 |
| `upcoming_north_america` | 75 | 14.36 | 17.48 | 18.72 |
| `upcoming_approximate` | 75 | 67.85 | 72.40 | 74.57 |
| `recent_deep` | 75 | 34.04 | 38.33 | 41.23 |
| `upcoming_deep` | 75 | 41.03 | 44.39 | 45.83 |
| `search_selective` | 75 | 5.69 | 8.36 | 9.71 |
| `search_broad` | 75 | 188.45 | 200.13 | 215.05 |
| `search_deep_10k` | 75 | 343.72 | 376.22 | 389.92 |
| `search_deep_48k` | 75 | 372.40 | 414.26 | 434.88 |
| `search_alias` | 75 | 4.93 | 6.89 | 7.92 |
| `search_accent` | 75 | 29.69 | 36.54 | 82.91 |
| `search_normalized` | 75 | 31.19 | 38.88 | 40.79 |
| `detail_fanout_1` | 75 | 5.00 | 7.14 | 21.05 |
| `detail_fanout_2` | 75 | 5.00 | 7.09 | 20.33 |
| `detail_fanout_3` | 75 | 5.17 | 6.87 | 7.68 |
| `detail_fanout_10` | 75 | 5.32 | 7.82 | 9.53 |

There is **no measured evolution across increasing rates** because 10/25/50 req/s
were not run. Search and detail durations are kept for diagnosis, without inferring
capacity or declaring an application/database bottleneck from this invalid window.
Per-case p99 has only 75 samples, adding substantial tail uncertainty.

### Runtime observations and correlation

Twenty-three periodic captures plus before/after snapshots were retained. Gauge
ranges can miss peaks and are not averages. Temporal CPU/rate calculations are
withheld from the condensed evidence when the clock audit fails.

- **JVM:** heap samples 101.40–154.47 MiB against a 247.5 MiB maximum; 56 live threads.
  GC counters increased by 11 pauses / 55 ms. Actuator's existing process CPU gauge
  ranged from 0.043 to 0.307; it is an instrument observation with its own sampling
  and CPU normalization, not a container-capacity claim.
- **Hikari:** sampled active connections 0–2 of 10, pending 0, timeout counter delta 0.
  Acquisition counters increased by 1,890 / 162.08 ms total; the 0.086 ms mean is
  counter-based, without a time-window denominator. At the later idle capture,
  active and pending were both zero and readiness was `UP`. No claim about unobserved
  pool peaks or stress recovery follows.
- **PostgreSQL:** 11 connections (10 pooled application connections plus the observer),
  no new deadlocks or sampled ungranted locks. Block reads increased by 132 and
  block hits by 52,943,256. Temporary counters increased by **375 files /
  2,061,312,000 bytes (about 1.92 GiB)**. Commits increased by 6,606; all 24 additional
  rollbacks correspond to the snapshot observer's deliberate read-only rollback.
  The temporary-work total is a diagnostic observation; there is no per-query
  attribution and it does not establish saturation.
- **Container resources:** cgroup CPU usage increased by 16.63 CPU-seconds for the
  application and 90.82 for PostgreSQL. Throttling counters increased by 42 periods /
  1.918 seconds and 1 period / 174 microseconds respectively. k6 had no sampled
  throttling. Maximum sampled `memory.current` was about 560 MiB application,
  1,000 MiB PostgreSQL and 33.8 MiB k6. These include file cache; Docker's displayed
  working-set memory excludes some cache. No new `oom`, `oom_kill` or memory-limit
  events occurred during the measured level. Existing PostgreSQL startup/generation
  memory events are excluded by before/after deltas.
- **HTTP:** raw k6, direct Actuator delta and Loki each reconcile to 1,500 HTTP 200s.
  Server cumulative processing time increased by 96,901.02 ms. Correlated routes
  were 600 releases, 75 featured, 525 search and 300 detail requests. All requested
  existing Prometheus series were returned; none was silently treated as zero.
- **Control:** the post-run dataset still contained 50,000 Games, 200,000 Releases
  and 500,000 Ratings, with zero synchronization runs, content translations or
  summary translation links. Localization meters were absent, explicitly marked
  unavailable; disabled provider credentials/scheduling, stopped helper and empty
  control tables corroborate the absence of background work.

After the idle capture and evidence collection, the application was restored to
its ordinary persistent local catalogue. Restoration readiness and read-only
inventory were verified separately. The verified disposable PostgreSQL container
and anonymous volume were then removed; all measurement files were preserved.

### Clock audit and interpretation boundary

The load window was `09:46:39.156850Z`–`09:51:30.609794Z` in stepped wall time:
291.453 seconds versus **300.513 monotonic seconds**. The HTTP auditor found
**10 backwards steps**, 0.682–0.796 seconds between successive completion samples,
roughly one per 30 seconds. There were no small concurrent-reordering candidates.
The independent 250 ms wall/monotonic sampling retained 1,198 pairs and detected
11 backwards-discrepancy intervals, including one correction split across adjacent
samples. This corroborates guest-clock movement rather than k6 completion ordering.
Native throughput is based on distorted elapsed time; it is preserved unchanged
and not corrected into a capacity estimate.

The earlier accepted minimal repetition remains valid evidence with
`backsteps: []`. It proves stability for that earlier two-minute execution, not
this later five-minute growth execution. Neither run overwrites the other.

**Decision: no capacity conclusion and no optimization.** This result cannot
establish a saturation point, bottleneck or increasing-load behavior. Clock
stability must be re-established across the intended sustained window before
interpreting the four-level experiment. No follow-up optimization issue is justified
by this run. No clock/host configuration was changed as part of the measurement.

### Observability gaps and focused validation

- Clock instability prevents reliable capacity rates and alignment of completion,
  OTLP and Prometheus windows. The audit diagnoses a time reversal but does not
  identify the Windows/WSL/Docker clock correction source.
- PostgreSQL statistics lack per-query CPU/latency/temp attribution, query plans and
  retained wait/lock histories. Short connection/lock peaks can be missed; the
  observer itself appears active. No extension, exporter or query tuning was added.
- Existing 60-second OTLP export, batching and 30-second scrapes blur intervals and
  can retain stale values from the previous application process. Direct cumulative
  deltas and run-specific completion logs provide the count cross-check.
- No physical Windows-host pressure, disk latency, IO wait or retained database trace
  backend is available. Synthetic data, a warm fixed mix, one local VM and one
  completed level do not support a public-production capacity promise.

Focused validation passed Python syntax, pinned k6 option inspection, fixed-argument
and remote-engine rejection, four clock-audit cases (monotonic, concurrent reorder,
large backstep and missing raw evidence), live preflight, 6,000 functional checks,
raw/native/server count reconciliation, documentation validation and diff whitespace.
The original 120-request mode's options were inspected without rerunning its accepted
load. No backend/frontend/identity/migration suites were needed: product code and
contracts did not change. No mandatory CI integration or version increment applies
because this experiment tooling/evidence is outside releasable artefacts.

#85 remains open for the pending load levels, authenticated/mixed workloads,
synchronization interference and stress/soak evidence. This record preserves the
observed diagnostic result; the subsequent interference harness is documented
separately in [interference-harness.md](interference-harness.md).
