# Public read load on growth

Implemented research for [#85](https://github.com/rubhern/videogame-platform/issues/85).
This opt-in experiment measures the current application against the reviewed
[synthetic growth dataset](README.md), after the
[minimal baseline method](http-baseline.md).
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
idle, and operator commands unused. Reuse a source-identified image; the [local setup](../../development/local-setup.md)
owns the supported build/start procedure.

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



## Evidence and conclusion

The [final report](remote-harness.md) owns capacity conclusions.
The [growth-results.json](growth-results.json) retains this method’s historical results.
Clock-invalid local runs remain functional/query diagnostics only; they are not capacity evidence.
Raw artifacts remain ignored under `tools/performance/results/`.
