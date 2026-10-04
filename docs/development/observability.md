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
  featured images observed, logos observed or kept because the logo lookup was
  unavailable, and Games whose summary, credits, genres and game modes were kept because
  that detail metadata was invalid. Deferral is import policy, not failure.
- Provider meters use `catalogue.synchronization.provider.request{operation,outcome}`,
  `.request.duration{operation}`, `.retry{operation}` and `.mapping.failure{reason}`.
  Operations are the closed `window`, `works`, `release_dates`, `logos`
  vocabulary. An artwork, screenshot or logo that does not satisfy ADR-0001 or states no
  usable dimensions is ignored and counted as `image_reference_invalid`. Incoherent game
  detail metadata is counted as `details_invalid` and never fails the Game.
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

The [four provisioned dashboards](../../deploy/private-dev/grafana/dashboards/)
separate **Runtime / Resources**, **Application / HTTP**, **Catalogue Synchronization**
and **Product / Business**. Their JSON owns panels, queries, units, ranges and tooltips.
The [private-dev procedure](../../deploy/private-dev/README.md#metrics-dashboards)
owns access/rollout; [local setup](local-setup.md#local-metrics-and-logs) owns
optional local startup. Provisioning removes the replaced journey dashboard.

Micrometer instruments adapters and runtime; Actuator is operator inspection, not
the scraped metrics source. The application pushes OTLP to the Collector, whose
handoff Prometheus scrapes. Prometheus remains the source for resource/HTTP/provider
time series and bounded API activity proxies. A dedicated PostgreSQL datasource uses module-owned
`observability_*` views, defined by
[the forward migration](../../backend/src/main/resources/db/migration/V20261004_150000__expose_observability_read_views.sql).
These expose retained manual-run reports, current catalogue evidence, database
resource diagnostics and active-rating aggregates. They add no stored analytics state.
Ratings rankings use its existing public game-listing projection; no dashboard joins
Catalogue tables to Ratings tables. Rating-owner identities never leave an aggregate.
Public titles/slugs can appear in bounded SQL rankings, never in Prometheus labels.

The [reader provisioning contract](../../deploy/private-dev/grafana/provision-reader.sql)
grants only explicit view reads: no base tables, writes, role inheritance, schema
creation or Keycloak access. A separate secret supplies its password. Query/lock,
connection and refresh bounds limit interference with product traffic. Aggregations
still scan current source rows in PostgreSQL; they are not constant-cost queries.
If measured query time or database contention grows, review cadence/query plans
before adding a rebuildable summary. SQL failure leaves panels unavailable and does
not affect readiness. The datasource is replaceable with the rest of telemetry.

### Interpretation and evidence limits

- `rate()` measures throughput; `increase()` estimates events within the selected
  range, handling observed resets but extrapolating boundaries. Lazy HTTP/detail
  series can miss their first event before an export baseline. Counts may be
  fractional; rounding would not make them exact.
- Explicit **observed process** panels show counter totals or weighted timer means
  since startup. SQL **current** panels ignore the time range. Selected-range SQL
  runs are exact within the retained run ledger, filtered by run start time.
  Retention pruning removes older reports; skipped commands have no durable run.
- Provider calls count logical adapter calls; durable reports count client attempts,
  including authentication/retries. Retry totals do not distinguish rate limiting
  from other transient causes. Histograms support provider p95 during activity;
  cumulative weighted means remain useful when manual calls are sparse. Neither is
  a percentile calculated from averages.
- Release quality shows persisted provider status, not date-derived effective status.
  Evidence age is time since changed synchronized evidence, not proof of a recent
  provider recheck: unchanged records retain their timestamps. Cover availability
  means approved stored references, not verified CDN availability.
- The exact GET personal-rating 404 is expected absence. Other GET failures and
  PUT/DELETE 404 responses remain visible. Revisit the route/status exclusion if the
  GET contract changes. `platform.http.errors{code,kind}` records
  a closed code allowlist and separates `expected_absence`, `client_rejection` and
  `server_failure`; unrecognized codes collapse to `UNCLASSIFIED`. It stays in the
  HTTP adapter/filter, and sink failures cannot change the response. Its process
  totals are lazy observations, not a durable error ledger.
- Successful detail, search and browsing calls are API activity proxies. Browser
  caching suppresses calls; conditional detail reads still count. Accepted rating
  commands distinguish create/update/delete by the existing HTTP contract; an
  update can repeat the same score.
- Users with active ratings are distinct current rating owners, not registered users,
  visitors or sessions. Ratings per user uses that same denominator. Rankings use
  current active votes; the five-vote floor is an exploratory sample choice, not
  statistical confidence or an approved product ranking policy.
- Unique visitors, sessions, registered users, conversion, retention, most visited
  Games and navigation cannot be derived from this instrumentation. No view-event
  ledger or identity analytics is added. The newly integrated localization pipeline
  has bounded execution metrics documented under [Catalogue localization](#catalogue-localization).

Missing data is not zero or proof of health. The application exports cumulative OTLP
metrics every minute; Collector translates timer units to milliseconds, expires
unrefreshed series after five minutes, and Prometheus scrapes every thirty seconds.
Thus a scrape can succeed while application export has stopped. Scrape-time sample
age can remain fresh until expiration; it is not original OTLP receipt age. Collector
internal accepted/refused/failed receiver point counters distinguish transport pressure
without a new service. Runtime charts never claim these are readiness checks.
Histories in Prometheus remain limited by its configured retention even when SQL
manual-run panels select a longer window. Resource gauges use known heap/pool capacity;
no universal CPU, GC or latency alert thresholds are introduced.

The executable owners are [application export](../../backend/src/main/resources/application.yaml),
[Collector](../../deploy/private-dev/otel/collector.yaml),
[Prometheus](../../deploy/private-dev/prometheus/prometheus.yaml) and
[Grafana provisioning](../../deploy/private-dev/grafana/provisioning/).
Prometheus keeps only reviewed families and closed tag vocabularies, including the
existing featured `freshness`, `month` and `lead_image` dimensions. JVM `id` denotes
fixed memory pools, never product identity. OTLP resource/instance metadata and
exemplars are not promoted into application dimensions.

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

## Catalogue localization

`catalogue.localization.content` counts bounded `kind` (`summary`, `genre`,
`game_mode`) and `outcome` (`translated`, `reused`, `skipped`, `failed`, `busy`,
`fallback`). Fallback is an additional taxonomy event, not another translated item.
`catalogue.localization.backfill` counts `progress`, `completed` and `retry` batches.
No game, provider, fingerprint or request identity is a metric tag.

Batch logs report inspected/translated/reused/skipped/failed counts and the checkpoint
cursor. The helper logs outcome and duration without source text. Adapter failures
separate timeout from unavailable/invalid runtime responses; durable pending state and
last-valid links support diagnosis and retries through the [operations runbook](operations-runbook.md#catalogue-localization).
Helper failure never participates in liveness/readiness.
