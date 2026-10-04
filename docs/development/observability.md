# Observability

The backend provides safe health, build/source information, correlation, structured
logs, bounded metrics, W3C trace context, and optional OpenTelemetry-compatible
export. Runtime settings are authoritative in
[`application.yaml`](../../backend/src/main/resources/application.yaml); exact library
versions remain in Maven manifests.

## Local inspection

```bash
curl --fail http://localhost:8081/actuator/health
curl --fail http://localhost:8081/actuator/health/liveness
curl --fail http://localhost:8081/actuator/health/readiness
curl --fail http://localhost:8081/actuator/info
curl --fail http://localhost:8081/actuator/metrics
curl --fail http://localhost:8081/actuator/cataloguesync
```

Liveness reports process viability. Readiness includes required local database and
catalogue-store access; IGDB and telemetry exporters are not request-serving
dependencies. The catalogue-store probe performs one constant-shape existence query
with a dedicated short statement timeout; health details remain hidden.

## Correlation, logs, metrics, and traces

- Accept a valid `X-Correlation-ID` or generate one; return the effective value and
  use it in Problem Details and diagnostic context.
- Use the `structured` Spring profile for ECS JSON console logs; [application logs](#application-logs)
  owns the event model.
- Use route templates and bounded outcome/code vocabularies in metric labels.
- Use standard `http.server.requests` for request rate, status, and latency; its
  percentile histogram supports latency analysis without defining an SLO.
- `catalogue.releases.result.count{view}` is the one release-specific meter and
  records successful page yield for the closed `recent`/`upcoming` vocabulary.
- `catalogue.featured.selection{status,freshness,month,lead_image}` counts successful
  featured reads (#151): `status` is `ranked`, `popularity_unavailable` or
  `no_qualifying_releases`; `freshness` is `fresh`, `stale` or `none`; `month` is
  `current` or `requested`; `lead_image` is the featured release's image kind,
  `artwork`, `screenshot`, `fallback` or `none`, because the hero never presents a cover. A
  rising `stale` or `popularity_unavailable` share means popularity acquisition is behind,
  and a rising `fallback` share means media acquisition is; no game, month, image or
  popularity value is a tag.
- `catalogue.game.details{eligibility,aggregate}` counts successful detail reads,
  including conditional responses. Eligibility uses the six contract reason codes;
  aggregate uses only `available`/`unavailable`. This distinguishes a degraded rating
  read from a healthy empty aggregate even when the public response remains HTTP 200.
- Synchronization run meters are `catalogue.synchronization.run{outcome}`,
  `.run.duration{outcome}` and `.run.records{kind}`. The record kinds count
  inspected release dates, created/updated/unchanged Games and
  Releases, deferred Games and failed Games, and, for committed Games, popularity
  signals observed, absent for zero/missing Hypes, and kept because featured evidence
  was unavailable or invalid,
  featured images observed, and logos observed or kept because the logo lookup was
  unavailable. Deferral is import policy, not failure.
- Provider meters use `catalogue.synchronization.provider.request{operation,outcome}`,
  `.request.duration{operation}`, `.retry{operation}` and `.mapping.failure{reason}`.
  Operations are the closed `window`, `works`, `release_dates`, `logos`
  vocabulary. An artwork, screenshot or logo that does not satisfy ADR-0001 or states no
  usable dimensions is ignored and counted as `image_reference_invalid`.
- Durable run reports hold the requested window, provider request/retry/latency
  totals and aggregate counters. The synchronization log adds lifecycle, progress and
  per-Game failure stage/reason; it never contains titles, raw payloads or provider
  identities. Cursors are not metric labels.
- No process-local publication-age gauge claims durable catalogue freshness.
  Unchanged evidence is not rewritten merely to change its timestamp.
- Never use user, game, release, request, correlation, URL, search, provider, or raw
  input values as metric tags.
- Propagate W3C trace context. OTLP trace and metric export remains disabled until an
  explicit endpoint is configured.
- Exported resources carry bounded `deployment.environment.name` and `service.version`
  attributes from `TELEMETRY_DEPLOYMENT_ENVIRONMENT` and `TELEMETRY_SERVICE_VERSION`;
  they never derive either value from visitor input.
- Telemetry failure must not break product requests or readiness.

Never log or export credentials, cookies, CSRF values, authorization codes, OAuth
tokens, personal rating ownership, raw provider payloads, database URLs with
credentials, or arbitrary exception text; technical failures name exception and
SQLState types only. Error responses expose stable codes and a correlation identifier,
never stack traces or SQL.

## Private-dev dashboards

[Version-controlled dashboards](../../deploy/private-dev/grafana/dashboards/) use only
existing bounded meters; this slice adds no application instrumentation. The
[private-dev operator procedure](../../deploy/private-dev/README.md#metrics-dashboards)
owns private-dev access and verification;
[local setup](local-setup.md#local-metrics-and-dashboards) owns the optional local
startup. Both consume the same provisioned files and use independent state.

- Runtime/HTTP: product traffic by route/method, outcome proportion, p95 from the
  existing HTTP histogram separated by method, heap used/max, JVM threads, CPU
  consumed in cores and Hikari active/max plus pending borrowers.
  Micrometer OTLP timers export milliseconds; dashboard units and translated metric
  names reflect that. The scrape panel diagnoses the Collector handoff, not readiness.
- Synchronization: exact observed process totals for completed/skipped outcomes,
  record volume (summary **sum**, not observation count), provider calls, retries
  and mapping failures; cumulative duration means and retained run-counter history.
  No new histogram is enabled. The default range is seven days; the instant totals
  are not totals for that range. Use the last-run report and logs for individual
  executions and in-flight progress.
- MVP journey: bounded API route/method activity, failure outcomes, release page yield,
  search result outcomes and game-detail eligibility/aggregate availability. These
  are operational/product-learning proxies, not user analytics. Unique visitors,
  sessions, conversion, retention, search terms and client-side navigation cannot be
  derived safely; those questions remain unsupported.

Missing data is not zero or proof of health. Counters reset on process restart.
Browser caching can suppress requests. The one-minute OTLP step limits freshness;
choose a sufficiently long rate window before interpreting HTTP rates.
Prometheus filters stored metric families and labels; JVM `id` values describe fixed
memory pools/buffers, never Game or user identity. Resource/instance metadata and
exemplars are not promoted into stored application dimensions.

### From application instrumentation to a panel

The configuration owners are [application export](../../backend/src/main/resources/application.yaml),
the [Collector](../../deploy/private-dev/otel/collector.yaml),
[Prometheus](../../deploy/private-dev/prometheus/prometheus.yaml),
the [Grafana datasource](../../deploy/private-dev/grafana/provisioning/datasources/prometheus.yaml)
and [dashboard provider](../../deploy/private-dev/grafana/provisioning/dashboards/private-dev.yaml).
The values below explain their current behaviour; those files remain authoritative.

1. **Micrometer is instrumentation inside the application.** Spring MVC observations
   automatically record `http.server.requests` timers, including HTTP outcomes, not
   just successful controller responses. Spring Boot binds JVM/processor metrics;
   Hikari's Micrometer tracker binds pool gauges. Our delivery/provider adapters
   explicitly record the bounded catalogue meters described below. An event updates
   a counter/timer/summary in memory immediately; gauges sample current state.
2. **Actuator is an operator API.** `/actuator/metrics` inspects registry measurements
   on the management port. It is not the source scraped by this topology and is not
   a prerequisite for metric export. `/actuator/cataloguesync` executes the private
   management command and reads its durable last-run report; this is separate from
   time-series storage. In particular, saying that HTTP or JVM metrics “come from
   Actuator” confuses instrumentation with an inspection endpoint.
3. **The application pushes OTLP metrics.** Its Micrometer `OtlpMeterRegistry`
   periodically serializes meters and sends them to `http://telemetry:4318/v1/metrics`
   in Compose (host/IDE execution uses loopback). The configured export step is **60 seconds**. There is no configured
   delta-temporality override; Micrometer
   exports cumulative sums/histograms by default. Counters become monotonic OTLP
   sums; timers and distribution summaries become OTLP histograms with count/sum;
   gauges become OTLP gauges. The timer base time unit is **milliseconds** here.
   Counters retain the process accumulation; time-window maximum gauges are different
   and may return to zero without erasing the cumulative timer sum/count.
4. **The existing OpenTelemetry Collector receives and translates.** Its memory
   limiter checks each second (192 MiB limit, 48 MiB spike allowance) and batch
   processor flushes at five seconds or its batch bound (512 target, 1024 maximum).
   The Prometheus exporter exposes the latest received measurements internally on
   `telemetry:9464/metrics`. Dot names become underscores, counters acquire `_total`,
   timer milliseconds appear in translated names, and histogram families expose
   `_count`, `_sum`, `_bucket{le}`. It does not attach original OTLP timestamps or
   promote resource attributes into application labels. Unrefreshed series expire
   after five minutes. Its basic debug output proves receipt without retaining
   payloads; traces still go only to that debug exporter, not a trace backend.
5. **Prometheus pulls and stores.** It scrapes that single exporter every **30 seconds**,
   with a five-second scrape timeout. Consequently two scrapes can observe the same
   application export; this does not mean two events occurred. Stored application
   series have `job="application-otlp"` and the fixed target `instance="telemetry:9464"`,
   plus the allowlisted meter tags. Prometheus applies sample/body/label bounds and
   stores scrape-time samples under the seven-day / 512 MiB TSDB retention policy;
   WAL/head/compaction still need additional disk headroom. It creates `up` itself.
6. **Grafana queries Prometheus.** The browser requests panel data from Grafana; its
   provisioned datasource proxies PromQL to `http://prometheus:9090` and Grafana
   renders the returned series. Prometheus does not send metrics to Grafana. Dashboards
   refresh every **one minute** while open; the provider checks dashboard files every **30 seconds**. Time-series
   targets use range queries across the selected range;
   explicitly instant stat targets query its end timestamp. The datasource uses POST,
   a 15-second query timeout and a **60-second minimum interval**, reflecting OTLP
   freshness even though Prometheus scrapes twice as often. Grafana expands
   `$__rate_interval` as `max($__interval + 60s, 4 * 60s)` in this configuration, hence
   a minimum **four-minute** rate window; wider views can increase it. The precise
   range-query step depends on the selected range and panel resolution.

This push/pull/query chain applies to every application-backed panel below. **Exception:** Runtime's scrape-availability
panel uses Prometheus's own `up` gauge
and bypasses Micrometer, OTLP and Collector translation. Journey's interpretation
text panel does not issue a metrics query. Grafana, storage and export failures are
not application readiness dependencies.

### Origins, meter types and tags

| Origin                                                                                                                                                                                                                                                                                                                                      | Meter created                                                                                                           | Bounded meter tags                                                           | Stored Prometheus family                                                                                                                         |
|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------|
| Spring MVC observation → Micrometer timer; our [observation convention](../../backend/src/main/java/com/videogameplatform/platform/observability/ObservabilityConfiguration.java) removes high-cardinality attributes                                                                                                                       | `http.server.requests`, **Timer**, existing percentile histogram                                                        | `uri` route template, `method`, `status`, `outcome`, `exception`, `error`    | `http_server_requests_milliseconds_{count,sum,bucket}`                                                                                           |
| Boot-bound Micrometer `JvmMemoryMetrics`, from memory-pool MXBeans                                                                                                                                                                                                                                                                          | `jvm.memory.used`, `jvm.memory.max`, **Gauge**, bytes                                                                   | `area`, `id` fixed pool name                                                 | `jvm_memory_{used,max}_bytes`                                                                                                                    |
| Boot-bound Micrometer `ProcessorMetrics`, JVM process CPU nanoseconds                                                                                                                                                                                                                                                                       | `process.cpu.time`, **FunctionCounter**                                                                                 | No custom meter tags                                                         | `process_cpu_time_nanoseconds_total`                                                                                                             |
| Boot-bound Micrometer `JvmThreadMetrics`, thread MXBean                                                                                                                                                                                                                                                                                     | `jvm.threads.live`, **Gauge**                                                                                           | No custom meter tags                                                         | `jvm_threads_live`                                                                                                                               |
| Hikari `MicrometerMetricsTracker`, enabled by Boot's datasource metrics integration                                                                                                                                                                                                                                                         | `hikaricp.connections.active`, `.max`, `.pending`, **Gauge**                                                            | `pool`                                                                       | `hikaricp_connections_{active,max,pending}`                                                                                                      |
| [Synchronization endpoint](../../backend/src/main/java/com/videogameplatform/catalogue/adapter/operator/CatalogueSynchronizationEndpoint.java) → [CatalogueSynchronizationMetrics](../../backend/src/main/java/com/videogameplatform/catalogue/adapter/observability/CatalogueSynchronizationMetrics.java) after a completed/skipped report | `catalogue.synchronization.run`, **Counter**; `.run.duration`, **Timer**; `.run.records`, **DistributionSummary**       | `outcome` on run/duration; `kind` on records                                 | `catalogue_synchronization_run_total`, `...run_duration_milliseconds_{sum,count}`, `...run_records_{sum,count}`                                  |
| [IGDB adapter](../../backend/src/main/java/com/videogameplatform/catalogue/adapter/provider/igdb/IgdbCatalogueProviderAdapter.java) → same metrics adapter around each logical provider call                                                                                                                                                | `catalogue.synchronization.provider.request`, `.retry`, `.mapping.failure`, **Counter**; `.request.duration`, **Timer** | Request: `operation,outcome`; duration/retry: `operation`; mapping: `reason` | `catalogue_synchronization_provider_request_total`, `...request_duration_milliseconds_{sum,count}`, `...retry_total`, `...mapping_failure_total` |
| [GameSearchApiMetrics](../../backend/src/main/java/com/videogameplatform/api/delivery/catalogue/search/GameSearchApiMetrics.java), successful search response                                                                                                                                                                               | `catalogue.search.result.outcome`, **Counter**, pre-registered                                                          | `outcome=results                                                             | zero_results`                                                                                                                                    | `catalogue_search_result_outcome_total` |
| [ReleaseController](../../backend/src/main/java/com/videogameplatform/api/delivery/catalogue/release/ReleaseController.java) → [ReleaseApiMetrics](../../backend/src/main/java/com/videogameplatform/api/delivery/catalogue/release/ReleaseApiMetrics.java), HTTP 200 page only                                                             | `catalogue.releases.result.count`, **DistributionSummary**, pre-registered                                              | `view=recent                                                                 | upcoming` on successful calls                                                                                                                    | `catalogue_releases_result_count_{sum,count}` |
| [FeaturedReleaseController](../../backend/src/main/java/com/videogameplatform/api/delivery/catalogue/release/FeaturedReleaseController.java) → [FeaturedReleaseApiMetrics](../../backend/src/main/java/com/videogameplatform/api/delivery/catalogue/release/FeaturedReleaseApiMetrics.java), HTTP 200 only                                 | `catalogue.featured.selection`, **Counter**, registered lazily                                                          | `status`, `freshness`, `month=current\|requested`, `lead_image`              | `catalogue_featured_selection_total`                                                                                                             |
| [GameDetailsEndpoint](../../backend/src/main/java/com/videogameplatform/api/delivery/catalogue/details/GameDetailsEndpoint.java), after catalogue/ratings context read, before conditional 304 response                                                                                                                                     | `catalogue.game.details`, **Counter**, registered lazily                                                                | Six contract `eligibility` reasons; `aggregate=available                     | unavailable`                                                                                                                                     | `catalogue_game_details_total` |

### Runtime panel queries and interpretation

The [runtime JSON](../../deploy/private-dev/grafana/dashboards/vgp-runtime.json)
owns the exact PromQL and units. Panel IDs below are stable inspection references.
Each application meter follows the complete transport/storage/query chain above.

| ID / panel                      | PromQL operation on the originating family                                  | Meaning                                                                                                                                                                                                                                                                    |
|---------------------------------|-----------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1 Requests by route/method      | `rate` of HTTP `_count`, summed by `uri,method`, product-route filter       | Estimated calls/second. Zero is no observed increment in the rate window, not no calls since startup.                                                                                                                                                                      |
| 2 Outcome proportion            | HTTP count rates summed by outcome / total product count rate               | Fraction of requests, including cache 304 and expected rating-absence 404. No traffic gives no finite ratio; inspect Journey for actionable failures.                                                                                                                      |
| 3 HTTP p95 by route/method      | `histogram_quantile(0.95, sum by (le,uri,method)(rate(..._bucket)))`        | Approximate latency below which 95% of observed requests fall, in ms. Methods remain separate; outcomes/statuses are aggregated within each route/method. Sparse traffic can be unstable/empty.                                                                            |
| 4 Heap used/reported max        | Sum heap-used bytes / sum positive heap-pool max bytes, grouped by instance | Allocation headroom against reported JVM maxima. Exclude unsupported `-1` maxima; no positive max gives no ratio. Pool maxima depend on the collector/JVM and need not equal a hard total JVM budget.                                                                      |
| 5 Process CPU consumed          | `rate(process_cpu_time_nanoseconds_total) / 1000000000`                     | CPU-seconds/second, or cores consumed. Compare against the application's actual Compose quota: currently 0.5 local and 1.5 private-dev. It is not host utilization. No quota tag is exported, so the dashboard does not pretend to calculate its percentage automatically. |
| 6 JVM live threads              | `jvm_threads_live` gauge                                                    | Sampled platform-thread population; growth is a diagnostic signal, not a universal saturation threshold or virtual-task count.                                                                                                                                             |
| 7 Hikari active/max             | Active gauge / positive max gauge, with matching pool/instance labels       | Fraction of borrowing capacity in use. Near full plus sustained pending suggests pressure; max comes from the actual configured pool, not a guessed threshold.                                                                                                             |
| 8 Collector scrape availability | Prometheus `up{job="application-otlp"}`                                     | 1 means the exporter answered a scrape; 0 means it failed. It does not prove fresh app export or product readiness.                                                                                                                                                        |
| 9 Heap used and reported max    | Separate sums of used heap bytes and positive max bytes                     | Absolute companion to occupancy. Observe trends and recovery after GC; native/non-heap/stacks also consume container memory.                                                                                                                                               |
| 10 Hikari pending borrowers     | Pending gauge per pool                                                      | Sampled waiting borrowers. Sustained `>0` indicates pressure; sampled zero can miss short contention.                                                                                                                                                                      |

No universal red/yellow thresholds are provisioned. Heap occupancy must be read with
its maximum and trend/GC recovery, not treated as total container RAM. JVM gauges
are sampled at export, so very short pressure peaks may be absent.

### Synchronization panel queries and sparse events

The [synchronization JSON](../../deploy/private-dev/grafana/dashboards/vgp-synchronization.json)
owns exact queries. Panels 1–7 are **instant** queries: the latest observed process
accumulation at the selected range end. Panel 8 is a **range** query showing retained
cumulative samples. None claims an exact selected-range event total.

| ID / panel                      | PromQL operation                                                     | Meaning                                                                                                                                                                                                         |
|---------------------------------|----------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1 Runs by outcome               | Direct run counter, sum by outcome                                   | Integer completed/skipped reports since process start, including the first exported event. Missing outcomes are unobserved, not manufactured zeros.                                                             |
| 2 Mean reported duration        | Cumulative timer `_sum` / positive `_count`, by outcome              | Mean ms across observed reports in that process/outcome. With one report it equals that run; with multiple it is not the last run. Skipped commands are timed too.                                              |
| 3 Catalogue work                | Direct records-summary `_sum`, by kind                               | Accumulated inspected/created/updated/unchanged/deferred/failed record amounts. `_count` would count reports rather than records. Registered zero is meaningful.                                                |
| 4 Provider calls                | Direct request counter, by operation/outcome                         | Logical adapter calls, including in-flight run work. A logical call may contain retries; it is not one raw HTTP attempt. Missing failure outcomes need no invented series.                                      |
| 5 Mean provider duration        | Cumulative request-duration `_sum` / positive `_count`, by operation | Mean wall time inside the API-client call, including authentication/retry waits; response mapping after the call is outside this timer. Failed calls are timed too. Not the latest call or a percentile.        |
| 6 Retries                       | Direct retry counter, by operation                                   | Extra attempts since process start. The adapter registers a counter even when a completed call contributes zero retries, so a present zero is legitimate.                                                       |
| 7 Mapping failures              | Direct mapping-failure counter, by reason                            | Lazy series appear only on a mapping failure. Absence can legitimately mean no mapping failure occurred, after verifying delivery health. A persistence failure or import deferral is not a mapping failure.    |
| 8 Retained counter observations | Direct run counters evaluated as a range query                       | Stair steps and gaps of cumulative samples within the retained selected history. A restart may reset them; identical unseen resets cannot be inferred. This is not a sum of historical runs or per-run records. |

`rate`/`increase` cannot recover an increment before the first sample of a lazily
registered meter. A manual run may finish between exports: its first observed count
is already 1 and later identical samples have zero rate, although the run did occur.
`increase` additionally extrapolates to window boundaries and can yield fractional
results for integer counters. Rounding that estimate does not make it exact. This is
why sparse run/work/provider/detail totals use cumulative instant values, rather
than rates or an extrapolated “count in the selected range”. Timer means divide
cumulative sum/count, with a positive-count guard, so they remain useful after the
event leaves a short rate window. See the primary
[Prometheus function semantics](https://prometheus.io/docs/prometheus/latest/querying/functions/#increase).

These meanings are intentionally different:

- **Retained history:** Prometheus scrape-time observations within its bounded
  retention, including previous process epochs. Samples are not a durable run ledger.
- **Process totals:** cumulative measurements reset with the application. Around a
  restart, an old lazy outcome series can survive at the Collector for up to five
  minutes before expiry. The fixed scrape target is not a process identity. Exact
  cross-restart totals and unseen resets cannot be reconstructed from these series.
- **One execution:** the durable `/actuator/cataloguesync` last-run report and its
  lifecycle logs provide that run's actual outcome, elapsed duration and record
  counters. Provider counters may advance during a run; run/record meters update
  only when the endpoint has its finished/skipped report.
  Skipped commands do not acquire/persist a run; inspect their command response and
  skip log rather than expecting the durable last-run endpoint to replace its report.

The provider request meter counts logical `window`/`works`/`release_dates` calls.
The durable report's provider-request total counts client attempts and can also
include token acquisition/retries, so those numbers need not be equal. Compare the
matching boundary instead of interpreting a difference as lost telemetry.

Do not use `or vector(0)` to conceal absent series. In a healthy run, all recorded
work kinds may include meaningful zeros; retry meters may be present with zero;
mapping-failure meters can legitimately be entirely absent. The same absence during
an exporter outage is not evidence of no failures. The run report remains the
diagnostic authority for execution-specific questions.

### Journey panel queries and expected absence

The [journey JSON](../../deploy/private-dev/grafana/dashboards/vgp-journey.json)
owns exact queries. HTTP activity uses an estimated time-series rate; error/absence
counts and catalogue totals/means are instant process observations, so sparse first
errors stay visible. No panel uses identity tags.

| ID / panel                              | Origin and PromQL operation                                                                                                  | Meaning                                                                                                                                                                                                                                                                        |
|-----------------------------------------|------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1 API activity by route/method          | Spring HTTP timer count → `rate`, summed by method/route for the five bounded journey routes                                 | Calls/second for browsing, search, details, personal-list reads and rating commands, not visitor counts.                                                                                                                                                                       |
| 2 Actionable HTTP failures              | Direct HTTP count → error-outcome selector, `unless` the exact GET rating route with status 404; sum by method/route/outcome | Integer process totals, including the first error. Retains GET authentication failures and PUT/DELETE 404 conflicts. Only the contracted routine read absence is separated, not all 404s or the entire rating route.                                                           |
| 3 Search outcomes                       | Our search counter → direct sum by outcome                                                                                   | Exact successful search-call totals with/without results since process start, not distinct searches or game counts.                                                                                                                                                            |
| 4 Mean HTTP 200 page items              | Our release summary → cumulative `_sum` / positive `_count`, by view                                                         | Returned release items per actual 200 page. The frontend's default page size is 12, so full pages legitimately average 12; smaller/last/filtered pages can lower it. 304 is excluded by code. Retained as a page-yield diagnostic, not demand/catalogue size.                  |
| 5 Detail reads by eligibility/aggregate | Our detail counter → direct sum by both tags                                                                                 | Integer successful detail reads including 304. Any valid occurred release gives global eligibility; a future release on another platform/region does not negate it. All-future evidence yields `RELEASE_NOT_OCCURRED`. Available aggregate includes a healthy empty aggregate. |
| 6 Interpretation text                   | Static provisioned text; no meter or query                                                                                   | Explains unsupported analytics and the distinction between process totals and selected-range history.                                                                                                                                                                          |
| 7 Expected unrated-game reads           | Direct Spring HTTP count → only GET personal-rating 404, sum by method/route                                                 | Integer process totals of routine absence, rather than a failed write or an actionable error count.                                                                                                                                                                            |

The [GET personal-rating contract](../architecture/api/openapi.yaml) explicitly
returns 404 `RATING_NOT_FOUND` for scoped absence.
[PersonalRatingService](../../backend/src/main/java/com/videogameplatform/ratings/application/internal/PersonalRatingService.java)
reads the authenticated owner's state;
[getMyRating](../../frontend/src/features/ratings/personal-rating-api.ts) treats that
404 as `null`, and the [rating query](../../frontend/src/features/ratings/use-personal-rating.ts)
uses it to choose create/update preconditions. The public game representation has no
personal-rating state; the paginated personal list is not a complete current lookup
for an arbitrary Game. The read therefore has a purpose and is preserved. The HTTP
meter has status/method/route, **not** the Problem code, so the dashboard's narrow
absence classification relies on this exact approved contract; a future contract
change must revisit it. Runtime's raw HTTP outcome proportion still includes these
responses rather than relabelling their HTTP outcome.

Eligibility comes from the effective release statuses computed against the trusted
Madrid evaluation date, then
[RatingEligibilityPolicy](../../backend/src/main/java/com/videogameplatform/ratings/domain/RatingEligibilityPolicy.java)
accepts any valid occurred release. The detail meter records the same ratings context
used to construct the API response. A dashboard counts reads of those categories,
not Games, releases or the selected browsing window. To diagnose a suspicious future
Game, inspect every effective release and the API eligibility, then the raw counter;
do not fix a domain error by filtering the chart.

## Private-dev log exploration

Post-MVP #159 provisions **Platform logs** in the existing authenticated Grafana.
Use Explore, or the built-in Logs Drilldown if available; no plugin installation or
custom log dashboard is required. The [operator procedure](../../deploy/private-dev/README.md#log-aggregation-and-exploration)
owns access and checks. [ADR-0021](../decisions/0021-collect-private-dev-logs-with-alloy-and-loki.md)
records the collection/security rationale; [platform design](../architecture/deployment/mvp-platform-and-delivery.md)
owns topology, retention and loss policy.

Collection is limited to complete JSON records from application stdout/stderr,
including its structured startup/runtime and journey events. Docker can split long
console lines before forwarding; Alloy drops oversized and malformed fragments rather
than reconstructing or showing truncated ECS. Pre-logger JVM/plain console output
stays outside aggregation and is inspected through Docker. Keycloak, PostgreSQL, migration actors, host
journals and arbitrary containers are outside this slice. The two indexed labels
are fixed `environment` and `service_name`; Loki may return `detected_level` as
non-indexed structured metadata for built-in exploration. Level, event, correlation/trace/request/
game/user identifiers remain ECS fields. Alloy strips the syslog envelope without
rewriting the body. Loki time uses the Docker envelope timestamp; `@timestamp` stays
available as the application's event timestamp. Do not infer request completion from
arrival time alone.

Choose a short time range first and expand within retention only when needed:

```logql
{environment="private-dev",service_name="application"}
{service_name="application"} |= "http.request.completed"
{service_name="application"} | json level="[\"log.level\"]" | level="ERROR"
{service_name="application"} | json | correlationId="<effective-correlation-id>"
{service_name="application"} | json | traceId="<effective-trace-id>"
```

Expand a log line to inspect parsed fields such as `http.route`, `http.status_code`,
`error.code` and synchronization stage/reason. Use JSON extraction for exact field
matching; a body-text match may also match the repeated message. A correlation ID
can connect a request completion and technical failure without becoming a label.
A trace ID search finds logged context; it does not imply a retained trace backend.

Empty results can mean no event, a wrong time range/field, expired data, transport
loss, line-size rejection or collector/storage failure. This is best-effort private
diagnostics, not audit evidence. Retention deletion is asynchronous; the query
lookback can hide older records before physical deletion completes. Preserve the
[application log privacy rules](#application-logs) and the exclusions above; neither
Loki nor Alloy sanitizes arbitrary secret-bearing source text. Do not enable DEBUG
or broaden collection to investigate an empty result without a reviewed need.

## Application logs

One event model serves both renderings. Each event carries bounded key-values, and its
message repeats the same values, so the default plain console (local Compose and direct
runs) shows what the ECS JSON of the `structured` profile (private dev) shows. Plain
lines also carry `[traceId-spanId] [correlationId]` through `logging.pattern.correlation`
in `application.yaml`. Key-values never use the `error` object together with an attached
throwable, because the ECS encoder then drops the event.

HTTP completion: one event per request from `CorrelationIdFilter`, except the liveness
and readiness probes. It records `http.method`, `http.route` (the registered template,
resolved even when a security filter rejected the request before MVC, otherwise
`UNMATCHED`), `http.status_code`, `http.outcome`, `duration_ms`, and `error.code` when
the answering boundary reports one: the Problem code, `AUTHENTICATION_REQUIRED`,
`CSRF_VALIDATION_FAILED`, `AUTHENTICATION_CANCELLED` or `AUTHENTICATION_FAILED`.
Expected client errors stay at `INFO`; `5xx` completions are `WARN`.

Technical failures: the API boundary writes one `ERROR` with `error.code`,
`error.type`, `error.root_cause_type` and, for a database cause, `error.sql_state`.
Exception messages and stack traces can carry SQL, connection details or data values,
so they are written only by a `DEBUG` event that is off by default. Enabling it on a
shared environment is a reviewed, temporary exception. A failed OIDC login is one
`WARN` with an allowlisted OAuth 2.0 error code or `other`.

Catalogue synchronization (`CatalogueSynchronizationLog`):

| Event                       | Level                                            | Bounded context                                                                                |
|-----------------------------|--------------------------------------------------|------------------------------------------------------------------------------------------------|
| Started                     | `INFO`                                           | run, window, provider page size                                                                |
| Progress checkpoint         | `INFO`                                           | run, phase, page, game position, elapsed, Game/release/provider counters                       |
| Game reconciled or deferred | `DEBUG`                                          | run, page, position, result                                                                    |
| Game failed                 | `WARN` for the first 20 of a run, then `DEBUG`   | run, page, position, stage, reason, failed count, identity (below)                             |
| Page lookup unavailable     | `WARN`, once per affected lookup and page        | run, page, page Games, `provider_logos` stage, provider failure code  |
| Run failure                 | `WARN`                                           | run, phase, page, stage, reason, exception class when unexpected                               |
| Finished                    | `INFO` succeeded, `WARN` partial, `ERROR` failed | outcome, stable code, window, pages, elapsed, all counters, failures tallied by `stage/reason` |
| Skipped                     | `INFO`                                           | `SYNCHRONIZATION_DISABLED` or `SYNCHRONIZATION_ALREADY_RUNNING`, window                        |
| Rejected command            | `INFO`                                           | `INVALID_SYNCHRONIZATION_WINDOW`, never the raw input                                          |
| IGDB retry                  | `DEBUG`                                          | endpoint name, retry, provider failure code, backoff                                           |

While events keep arriving, a checkpoint is written at least every 60 seconds and at
provider-page completion, but never less than 10 seconds after the previous one.
Spacing uses a monotonic source, not the product clock. Stages are `provider_page`,
`provider_game`, `provider_logos`, `validation`,
`reconciliation`, `persistence` and `run`. A `provider_logos`
failure never fails a Game: the page's Games still reconcile and keep their last valid
logo. Reasons come
from `ProviderFailureCode`, `ProviderMappingFailure`, `SynchronizationWriteException.Reason`
and the service constants (`WORK_NOT_RETURNED`, `TITLE_MISSING`,
`RELEASE_LIMIT_EXCEEDED`, `DUPLICATE_RELEASE_REFERENCE`, `RECORD_REJECTED`,
`UNEXPECTED_FAILURE`). Persistence reasons distinguish `PERSISTENCE_TIMEOUT`,
`PERSISTENCE_CONSTRAINT_VIOLATION` and `PERSISTENCE_CONNECTION_FAILED` only from
Spring's translated exception types; anything else, including lock and deadlock
failures, is `PERSISTENCE_WRITE_FAILED`.

Every Game failure states `identity`: `published` with the catalogue Game ID and slug;
`unpublished` with the ID and slug the failed attempt assigned to a new Game, which were
rolled back and change on the next run; or `none` when the failure precedes any product
identity (provider fetch, or validation of a new Game). Provider identities and titles
never appear, so a `none` failure is identified only by run, page, position, stage and
reason.

The [platform design](../architecture/deployment/mvp-platform-and-delivery.md) owns
remote telemetry topology, retention, and privacy; the private-dev Compose and
collector files linked there own executable limits. The private-dev validator checks
OTLP receipt, Prometheus queries, Grafana provisioning and retained samples after
recreation in a disposable stack, independently of application deployment. Its live
mode checks existing application metrics without injecting synthetic samples.
Product-specific meters should be added only when they answer an operational or
product decision and have a bounded cardinality review.

`POST /actuator/cataloguesync` is the internal operator command for one complete
synchronization run and `GET` reports the latest result; the
[backend README](../../backend/README.md#persistence-and-observability) owns
invocation and the [operations runbook](operations-runbook.md#catalogue-synchronization)
owns the private-dev procedure. It is never scheduled, is absent from the product
OpenAPI contract, and inherits the management-port boundary below, so no visitor
request can trigger a provider call.

Actuator runs on the separate management port with its own security boundary: the
endpoints are open on that port because it is already private, they hold no
cookie-authenticated session, and any state-changing request that a browser initiated
from another site is rejected. Local direct execution binds that port to loopback;
container profiles bind it only inside the private container network and do not
publish it on the product port. Routine liveness/readiness probes do not emit
application access logs; their status remains available from Actuator.
