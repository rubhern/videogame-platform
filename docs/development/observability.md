# Observability

The backend provides safe health, build/source information, correlation, structured
logs, bounded metrics, W3C trace context, and optional OpenTelemetry-compatible
export. Runtime settings are authoritative in
[`application.yaml`](../../backend/src/main/resources/application.yaml); exact library
versions remain in Maven manifests.

## Local inspection

The unbuilt public target has a separate
[minimal observability proposal](#public-production-observability-proposal);
private-dev configuration and proven evidence remain environment-specific.

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
- `catalogue.releases.result.count{sort}` is the one release-specific meter and
  records successful page yield for the closed
  `releaseDate`/`hype`/`rating`/`externalPlayerScore` vocabulary (#285, replacing the retired `view` label).
- `catalogue.release.calendar.days{month}` records, per successful calendar read (#250), how
  many days of the served month hold a release; `month` is `current` or `later`, already in the
  Prometheus label allowlist. No month value, day, game or filter is a tag.
- `catalogue.featured.selection{status,freshness,granularity,period,lead_image}` counts
  successful featured reads (#151, #329): `status` is `ranked`, `popularity_unavailable` or
  `no_qualifying_releases`; `freshness` is `fresh`, `stale` or `none`; `granularity` is
  `week` or `month`; `period` is `current` or `requested`; `lead_image` is the featured release's image kind,
  `artwork`, `screenshot`, `fallback` or `none`, because the hero never presents a cover. A
  rising `stale` or `popularity_unavailable` share means popularity acquisition is behind,
  and a rising `fallback` share means media acquisition is; no game, period value, image or
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
  unavailable, Games whose summary, credits, genres and game modes were kept because
  that detail metadata was invalid, and external player scores observed, absent because
  the provider states none or zero ratings, and kept because the answer was invalid or
  unattributable (`player_score_*`). Deferral is import policy, not failure.
  `redundant_releases` counts stage-less provider references of committed Games that
  restated another reference's Release (`REL-014`); they are neither stored nor failures,
  and the durable report and finished log carry the same count.
  Player-score change kinds (`player_score_created|updated|removed|unchanged_games`)
  say what committed writes did to the stored usable score. The upsert's own
  `RETURNING old/new` classifies it, so there is no extra query per Game. A Game that
  neither held nor now holds a usable score, a kept score and an answer older than the
  stored check change none of them. Only manual, administration and scheduled triggers record
  these meters; repair batches appear in the retained run reports only. A failed score write
  rolls back its whole Game and is counted only as a failed Game.
- Trigger meters `catalogue.synchronization.trigger{trigger,policy,outcome}` and
  `.trigger.duration{trigger,policy,outcome}` distinguish `manual/none` (management
  command), `administration/none` (operator request, #248) and `scheduled/near_term` and
  `scheduled/upcoming`, with the closed synchronization outcome vocabulary. An
  administration trigger records only accepted runs, timed on their background worker; a
  rejected request starts nothing and is visible as its HTTP request's stable error code. Duration is monotonic wall time, including unexpected trigger
  failure. Every returned report also contributes once to the existing aggregate run
  meters/counters. A failure before a report exists contributes only to trigger meters;
  it does not invent a durable run. Dates, IDs, cron strings and exception messages are
  never metric labels.
- An operator's stop or abandonment (#342) adds no meter or label. A run stopped at a safe
  point reports the stable code `SYNCHRONIZATION_STOPPED` with the existing `partial` or
  `failed` outcome, so the run meters and the *failed or partial* alert count it like an
  interrupted run; its log line and retained report name the code. An abandonment writes no
  report: it appears as its HTTP request's route template, status and stable error code, and
  the run history holds `SYNCHRONIZATION_ABANDONED_BY_OPERATOR`. A worker that lost ownership
  returns `SYNCHRONIZATION_OWNERSHIP_LOST` to its trigger's log and meters only; the store
  refuses every record of it.
- Provider meters use `catalogue.synchronization.provider.request{operation,outcome}`,
  `.request.duration{operation}`, `.retry{operation}` and `.mapping.failure{reason}`.
  Operations are the closed `window`, `works`, `release_dates`, `logos`
  vocabulary. An artwork, screenshot or logo that does not satisfy ADR-0001 or states no
  usable dimensions is ignored and counted as `image_reference_invalid`. Incoherent game
  detail metadata is counted as `details_invalid` and an incoherent player score/count
  pair as `player_score_invalid`; neither fails the Game.
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

The [provisioned dashboards](../../deploy/private-dev/grafana/dashboards/)
separate **Runtime / Resources**, **Application / HTTP**, **Catalogue Synchronization**
and **Product / Business**, with the separate **Gameómetro · Growth** activity view
described below. Their JSON owns panels, queries, units, ranges and tooltips.
Grafana-facing text is English. Every Prometheus target declares an explicit series
name, so legends and tooltips show names such as `Registrations` or
`Ratings created`; raw PromQL remains available through query inspection and Explore.
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
The catalogue inventory also counts Games whose external player score was ever checked
and those holding a usable score; a Game never checked is not a Game without a score.
[A later migration](../../backend/src/main/resources/db/migration/V20261014_160000__observe_external_player_scores.sql)
adds the per-run player-score counters to the run view and a player-score check view
without identities.

The synchronization dashboard's **IGDB player scores** row separates stored state from
activity:
- **Stored coverage and check age** are current PostgreSQL state. Their history is not
  kept, and computing it would need a periodic snapshot that does not exist.
- **Per-run results and stored-score changes** come from retained run reports for the
  selected range, including repair batches.
- **Observed-process totals and history** come from Prometheus and cover manual and
  scheduled commands.
- **Invalid score answers** reuse the existing `player_score_invalid` mapping reason.
  They are counted per provider answer, so the figure can exceed the committed Games
  whose score was kept.
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
  Check age is time since the last committed provider check of a release, whether or not
  it changed; the last published change is shown separately. Releases outside every
  scheduled window age by design, and a check is not human verification. Cover availability
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
existing featured `freshness`, `month` and `lead_image` dimensions and the release
order's `sort` (#285, replacing the retired `view`). JVM `id` denotes
fixed memory pools, never product identity. OTLP resource/instance metadata and
exemplars are not promoted into application dimensions.

## Private-dev alerts

**Repository configuration; not yet applied to the live host.**

The [file-provisioned rule inventory](../../deploy/private-dev/grafana/provisioning/alerting/private-dev.json)
is the executable owner of PromQL, thresholds, pending periods and labels. The [notification template](../../deploy/private-dev/grafana/notifications.json.template)
and its small startup renderer own the single policy tree and optional contact points. Private-dev enables Grafana-managed evaluation; the optional workstation
stack retains alerting disabled. No Prometheus rule evaluator, exporter or service is
added. The [operator procedure](../../deploy/private-dev/README.md#private-dev-alerts)
owns rollout/rollback and validation; the [runbook](operations-runbook.md#private-dev-alerts)
owns first diagnostic actions and the boundary of proven behaviour.

These are provisional owner-attention thresholds, not SLOs or capacity guarantees.
[#85's assessment](../research/performance-baseline/remote-harness.md) prioritizes
pool acquisition and HTTP degradation; it does not establish a universal throughput,
latency or synchronization-duration limit. The five operational rules have `warning` severity: actionable degradation/risk.
`critical` is reserved for confirmed sustained unavailability or a measured failure
requiring prompt intervention; no current signal justifies that claim. Pending and
timeout Hikari symptoms share one warning, avoiding duplicate incidents. #85
supports acquisition failure as a concern, but does not establish a separately
validated critical timeout boundary. `info` belongs only to optional product activity.

| Rule | Existing source and operational intent | Evidence required before firing |
|---|---|---|
| Unexpected server errors | `platform_http_errors_total`, summed across unexpected server-error codes; excludes the approved `CATALOGUE_NOT_READY` state and client errors | At least five estimated failures across codes in five minutes, sustained for two minutes; an absolute count avoids sparse-traffic error percentages |
| Sustained product-route latency | `http_server_requests_milliseconds_bucket` and `_count`, pooled by route template/method across successful, conditional and server-error responses | Histogram p95 above one second with at least 100 estimated requests on that same route/method in five minutes, sustained for five minutes; session/auth and client-error responses are excluded |
| Hikari connection acquisition pressure | `hikaricp_connections_pending` and `hikaricp_connections_timeout_total`, combined into one application alert | Any pending request or at least two estimated acquisition timeouts in five minutes, sustained for two minutes; #85 demonstrated this failure mechanism |
| Application telemetry unavailable | Both fixed `up` targets plus changes in `process_uptime_milliseconds` over three minutes | A failed/missing scrape or non-advancing/missing application heartbeat persists for five minutes; this allows ordinary restarts and detects frozen OTLP even while the Collector still exposes cached series |
| Catalogue synchronization failed or partial | Preinitialized `catalogue_synchronization_run_total` outcomes `failed`/`partial`, retaining their distinction in alert instances | At least one estimated completed failure in fifteen minutes, sustained for two minutes; successful/skipped commands and inactivity are quiet |

Each rule links to its existing diagnostic dashboard and the runbook. Aggregation
retains only route templates/methods or the two sync outcomes; HTTP errors,
pool and telemetry each produce one instance. Error codes remain on the diagnostic
dashboard. The exclusion is the specific approved empty-catalogue business code,
not HTTP 503 generally; genuine server degradation returning 503 is included. Evaluation scans at most fifteen minutes of the
already bounded series once per minute, with no SQL, provider call, new recording
series or request-path work. HTTP histogram cost follows the existing route/method/
status/bucket cardinality, not catalogue size or user identities. No throughput,
CPU-throttling, cgroup memory-max or PostgreSQL alert is added. Database diagnostics
remain available in the runtime dashboard; the current stack has no host/PostgreSQL
exporter warranting a separate resource alert.

### Quiet traffic, missing data and recovery

HTTP error series are lazy. Missing/idle HTTP and sync series do not indicate failure;
latency below its volume guard is quiet. A newly created error series can lose its
first observed event; counters cannot reconstruct events before their first export
or between an unobserved event and a restart. `increase()` extrapolates, so threshold
counts are estimates. The existing initialized run outcomes improve sync detection,
but do not turn metrics into a durable incident ledger.

Symptom rules use Grafana `OK` on empty results and retain their last state on query
errors; the separate telemetry rule identifies loss of evidence. Its failed-query
and unexpected no-data states use its configured pending period rather than claiming
application downtime. In the pinned native API, KeepLast can retain `health=ok` and
omit `lastError` despite failed queries; inspect the separate telemetry rule and
Grafana query logs instead of treating that health field as datasource proof.
A missing individual Hikari/error/histogram family while uptime
continues is an instrumentation gap not covered by this heartbeat. No current signal
proves end-to-end application availability: confirm readiness and product reads
through the existing private operator path before diagnosing an outage. Grafana or
host failure cannot be reported by the same Grafana instance.

Fresh advancing uptime and healthy scrapes clear telemetry failure; other rules clear
on the next evaluation when their predicates clear or their range-window evidence
ages out. There is no additional recovery hold. A sync alert clearing after fifteen
minutes means its evidence expired, **not** that a retry succeeded; inspect the
retained run report. Pool gauges can miss queues shorter than the export interval;
timeout counters complement that gap. Short restarts/queues and single errors are
intentionally quieter than persistent degradation.

Completed run metrics cover both manual and opt-in scheduled commands in the current
implementation. No rule assumes a periodic job or treats a missing run as failure.
Skips can be expected mutual exclusion. Completed-duration timers cannot detect a
currently stuck run, and the fixture-paced #85 sync is no real-provider duration
baseline. Failures before a run report exists are only in lazy trigger metrics;
Prometheus currently strips their trigger/policy labels. No trigger/duration/skipped
alert or instrumentation change is justified here: inspect the durable run and logs
when a manually requested command fails to complete.

### Notification boundary

Alerts remain visible in authenticated, private Grafana **Alerting → Alert rules**.
Native Telegram delivery is **off by default**. One rendered policy tree groups
operational alerts by environment/service/category/severity, waits to collect related
instances, deduplicates identical alerts and repeats persistent incidents. Native
`DatasourceError`/`DatasourceNoData` have an explicit operational fallback route.
The current rule settings normally convert missing/error evidence into their own
Normal/Alerting/KeepLast states; native instances matter for later rules using the
native modes. Exact routing/timings belong to the executable template, not UI edits.
A separate product route needs its own opt-in and its own informational contact
point, which reuses the protected bot token and chat ID but has Grafana's native
resolved notifications disabled. The operational contact point keeps them enabled:
a recovered predicate and an expired sync failure window use distinct wording, and
the latter never claims a successful run. Large
groups render a bounded preview and direct the owner to the full group in Grafana,
keeping the plain-text message below Telegram's size limit for this fixed rule set.

The [operator procedure](../../deploy/private-dev/README.md#telegram-setup-and-delivery)
owns protected files, activation, rotation and rollback. SMTP remains disabled;
Mailpit remains the password-recovery test sink. Native Telegram uses outgoing HTTPS
only, adding no service, inbound webhook or public monitoring endpoint. Grafana
stores the token encrypted in its existing database; the chat ID is a normal contact
setting and therefore remains readable to authorized Grafana administrators. The
rendered file contains both values on bounded tmpfs (directory 0700, file 0600).
No token/chat value enters Compose environment/metadata, rule labels or annotations.
When delivery is disabled, the private-dev start wrapper routes proxy-aware outbound
HTTPS to a closed loopback port and fixes the internal bypass list. This is a native
sender kill switch, not a new proxy service: the pinned sender honors HTTPS_PROXY.
It prevents an old persisted receiver sending during Grafana's file-policy reload
interval after restart. Existing internal HTTP datasources keep working. The normal
policy subsequently becomes muted/empty; disabling does not revoke the stored token.
Notifier debug logs must remain disabled because the pinned upstream HTTP sender
can log the token-bearing URL at debug level. Transport errors redact that URL at
the configured info level; upstream API error responses are outside our control.

Delivery is best effort. The pinned native Telegram receiver reports failed requests
as non-retryable within an attempt; later notification cycles may try again. Failed
attempts remain visible in Grafana/logs. A timeout can mean a delivered message whose
acknowledgement was lost; neither deduplication nor persistence guarantees exactly-once
external delivery. Host/Grafana failure still needs independent owner inspection.
No real Telegram delivery or live-host alert rollout has been proven by this change.

## Product growth observations

**Implemented; corrected optimized-runtime rollout awaits owner review and host approval.**
This separable package adds no identity policy, durable ledger, warehouse or database
migration. The optimized private image compiles native event-metrics support;
base runtime configuration collects only `register` and `verify_email` with realm
tags, including the bounded failure dimension that Prometheus filters out. The optional
[private-dev overlay](../../deploy/private-dev/compose.product-growth.yaml) connects
the existing management endpoint to an internal Prometheus scrape. The [Keycloak event-metrics contract](https://www.keycloak.org/observability/event-metrics)
and pinned source distinguish events from accounts. `keycloak_user_events_total` has only
`realm`, `event`, `error` labels; the scrape retains the application realm, these two
events and `error=""`. In particular, a failed registration also uses `event="register"`
with a nonempty error and must be excluded. Admin/imported accounts and first login
are not successful self-registration events. No user, email, address, client or IdP
label is collected. Email verification records an action, not distinct verified
accounts or proof that every registered account finished onboarding.

The dashboard shows observed registrations/rating creations today (Europe/Madrid),
seven-day activity, verification events, update/delete activity, current active
ratings and moving 24-hour trends. The JSON owns exact queries and labels. Trend
points are overlapping rolling windows, **not** independent calendar-day totals.
Current account totals and distinct verified-account totals are explicitly
unsupported: no approved persistent identity aggregate exists, and this package
neither queries Keycloak tables nor adds privileged Admin API polling.

`ratings.personal.activity{action=created|updated|deleted}` is preinitialized with
three zero counters. The persistence adapter registers a Spring `afterCommit`
callback only after the successful command's SQL and aggregate read; rollback,
rejected writes and losing concurrent writes do not increment it. Metric failure
cannot turn an already committed write into a failed product response. Application
and domain retain no monitoring dependencies. OTLP exposes
`ratings_personal_activity_total`; action is its only application tag. Direct SQL
fixtures/imports are outside this command path. Synthetic callers using the same
commands are included, so these observations are **not organic adoption**. Current
active ratings come from the existing module-owned SQL view and include fixtures.
Deletion and recreation produce a deleted and a new created observation; they are
not a monotonic account/rating inventory.

Counter `increase` estimates can be fractional. Keycloak meters are lazy: the first
event can establish a baseline without a visible increase. Resets between sampled
points are handled, but process restarts, collection gaps and increments before the
last export/scrape can lose activity. When Keycloak is configured and up but has no
events, the dashboard displays zero; absent/down sources without evidence display
**No data**. Historical windows may retain earlier observations during an outage;
inspect target health before interpreting freshness. Rating counters export zeros
once the application exporter runs. Seven-day retention is a maximum horizon, not a
complete seven-day or lifetime ledger, especially after activation/retention eviction.

### Informational registration-window notices

The separate file-provisioned product rule detects an observed increase in successful
registrations within five minutes, without a pending delay. It is informational and
independently muted by default. Multiple registrations while firing remain one alert
instance; grouping/repeat timing, first lazy samples, restarts or failures can batch
or miss notices. The English message therefore reports registrations *observed*,
with a Growth dashboard link. An estimated increase above zero but below 1.5 is
shown as one; a larger rounded estimate is labelled as an estimate that can group or
miss registrations, never as an exact account count. An annotation change does not
create another alert identity. Product notifications retain their separate grouping
and repetition policy. The rule still returns to Normal when the window closes, but
that resolution is never delivered; a new window can notify after it. A new window
that starts within one group interval of the previous resolution can be merged into
the earlier notice. No identifying data or unsupported
account total is sent. **This does not meet one-notification-per-user semantics.**
Registration and rating writes do not depend on Telegram availability.

### Proposed durable notification follow-up

Per the owner's scope decision, exact community milestones and exact per-registration
business delivery remain a dedicated linked follow-up proposal, not an implementation
claim for #269. Existing individual achievements (#253) do not own community-wide
creation milestones. No suitable issue currently owns the complete requirement.

The follow-up must decide organic/synthetic provenance, deletion/recreation counting,
and an activation baseline (prefer prospective counting with no historical storm).
A minimal Ratings-owned PostgreSQL counter/unique milestone ledger and transactional
outbox could atomically reserve each next multiple of ten under concurrent commits;
a bounded asynchronous sender would acknowledge/retry independently of product writes.
It would need tests for rollback, races, restarts, ambiguous sends and suppression of
already acknowledged milestones. Do not reconstruct lifetime creations from current
rows or rolling metrics. Exact registration delivery would additionally need a
reviewed durable after-completion identity bridge; its maintenance/privacy boundary
must be coordinated with #274. No such bridge, ledger, worker or schema is added here.
Even a durable outbox cannot promise exactly-once Telegram delivery across an
ambiguous API acknowledgement.

### Validation boundary

The [operator procedure](../../deploy/private-dev/README.md#private-dev-alerts) owns
focused checks. The growth regression builds the actual private Dockerfile and runs
its installed wrapper with `start --optimized` against disposable PostgreSQL. It
exercises the private theme, registration/verification, failed/admin/imported-account
exclusions, password recovery/login, internal Prometheus and the native Grafana rule.
A fresh image rebuild and container recreation must retain account IDs and realm
settings while the event counters reset. The earlier `start-dev`/H2 fixture does not
prove optimized-runtime compatibility and has been replaced.

Promtool covers the original expressions/windows, including one or several observed
registrations, activity while firing, a later window, failures, missing/lazy data and
counter resets. The native Telegram TLS mock separately exercises opt-ins, English
messages and links, observed counts, grouping, silent registration resolutions,
later windows, operational recoveries, redacted errors and disabling
on a persisted Grafana database. It never uses real credentials or Telegram egress.

[Issue #269](https://github.com/rubhern/videogame-platform/issues/269) owns point-in-time
results, the prior failed rollout and remaining real-host acceptance. Disposable
proofs do not establish live identity continuity, private-host resource capacity or
real Telegram delivery. Individual registration delivery and durable milestones
remain the separate proposed follow-up above.

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
`CSRF_VALIDATION_FAILED`, `ACCESS_DENIED`, `AUTHENTICATION_CANCELLED` or
`AUTHENTICATION_FAILED`.
Expected client errors stay at `INFO`; `5xx` completions are `WARN`.

Technical failures: the API boundary writes one `ERROR` with `error.code`,
`error.type`, `error.root_cause_type` and, for a database cause, `error.sql_state`.
Exception messages and stack traces can carry SQL, connection details or data values,
so they are written only by a `DEBUG` event that is off by default. Enabling it on a
shared environment is a reviewed, temporary exception. A failed OIDC login is one
`WARN` with an allowlisted OAuth 2.0 error code or `other`.

BFF session revocation: one `INFO` per management command with
`identity.revocation.sessions` (the count) and `identity.revocation.settled`, or
`identity.revocation.code=INVALID_USER_ID` for a rejected value. The `UserId`, the
OIDC subject, session IDs and tokens never appear. No meter is added: revocation is a
rare owner command whose evidence is its answer and this event.

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
| Scheduled invocation/result | `INFO`, result `WARN` partial / `ERROR` failed | trigger, policy, window at invocation; result outcome and stable code |
| Unexpected scheduled failure | `ERROR` | trigger, policy, `SYNCHRONIZATION_TRIGGER_FAILED`, exception class; no message/payload/stack |
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
`RELEASE_LIMIT_EXCEEDED`, `DUPLICATE_RELEASE_REFERENCE`, `RELEASE_TUPLE_CONFLICT`,
`RECORD_REJECTED`, `UNEXPECTED_FAILURE`). `reconciliation/RELEASE_TUPLE_CONFLICT` means
returned references share one release tuple without one staged reference standing for it
(`REL-014`); `persistence/RELEASE_TUPLE_CONFLICT` means PostgreSQL rejected a Release that
repeats the tuple of stored evidence outside synchronization ownership, such as a curated
release. Both reject only that Game. Persistence reasons distinguish
`PERSISTENCE_TIMEOUT`, `PERSISTENCE_CONSTRAINT_VIOLATION` (any other constraint) and
`PERSISTENCE_CONNECTION_FAILED` only from Spring's translated exception types and, for the
release tuple, the unique-violation SQLState and constraint name; anything else, including
lock and deadlock failures, is `PERSISTENCE_WRITE_FAILED`.

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
synchronization run and `GET` reports the latest result. `GET /actuator/cataloguecoverage`
reads durable interval coverage under the same boundary. The
[backend README](../../backend/README.md#persistence-and-observability) owns
invocation and the [operations runbook](operations-runbook.md#catalogue-synchronization)
owns the private-dev procedure. The command remains an exceptional operator path
alongside the opt-in scheduled inbound adapter and the operator's bounded request on the
product port (#248), which answers before any provider work. The management command and the
scheduler are absent from the product OpenAPI contract; the command inherits the
management-port boundary below, and the request requires the operator authority, so no
visitor request can trigger a provider call. Scheduler state and provider outcomes
are never health dependencies.

Actuator runs on the separate management port with its own security boundary: the
endpoints are open on that port because it is already private, they hold no
cookie-authenticated session, and any state-changing request that a browser initiated
from another site is rejected. Local direct execution binds that port to loopback;
container profiles bind it only inside the private container network and do not
publish it on the product port. Production keeps it on container loopback because
`sessionrevocation` can sign any account out; the
[platform design](../architecture/deployment/mvp-platform-and-delivery.md#account-revocation-and-the-management-interface)
owns that requirement. Routine liveness/readiness probes do not emit
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

## Public production observability proposal

**Proposed, unbuilt (#252):** reuse Collector → Prometheus → private Grafana;
initially retain redacted, rotated Docker logs without a production Loki/Alloy stack
or retained traces. The [platform target](../architecture/deployment/mvp-platform-and-delivery.md#public-production-target)
owns topology/resource budgets. No private-dev collector configuration is modified.

Start with seven days / 512 MiB of Prometheus history, including bounded label sets;
WAL/head/compaction need extra disk, so this is not a filesystem quota. Diagnostic
logs are owner-limited to seven days (#274); propose at most 100 MiB per service as
well, whichever expires first, with an enforced age/rotation procedure. Ordinary byte
rotation alone does not implement age deletion. Disable detailed identity events/edge
access logging by default; retain only necessary redacted outcomes. Keycloak's default
event logging writes the username and IP of failed logins, so the
[production realm](../architecture/deployment/mvp-platform-and-delivery.md#production-realm-contract)
stores no events and registers no realm logging listener; its gate proves that synthetic
credentials, addresses and client IPs stay out of the Keycloak log. The [security signal preparation](#identity-and-overload-security-signals) uses native
aggregate metrics independently of those listeners; actual-host acceptance is pending. Do not collect
tokens, cookies, email/name, raw IP, full URLs/query strings or rating-owner IDs. If a
security investigation requires exceptional personal metadata, record purpose/access/expiry
separately and keep it out of metrics. Never send real account data to public GitHub
reports or monitoring SaaS. Backups follow their independent protected retention.

Observe HTTP rate/errors/latency, Hikari pending/timeouts, JVM/PG/identity resources,
disk/free space, sync outcomes, latest verified off-host backup age and certificate
expiry. Notifications need no new alert service: reuse the existing Grafana route
where sufficient and an existing private owner channel within budget; link relevant
work to [#269](https://github.com/rubhern/videogame-platform/issues/269).
A failed-backup and disk/error/certificate test must reach the owner before opening.
An on-host monitor cannot prove host-loss notification: run a lightweight external
reachability/backup-age check from an existing independent owner device with a
documented check cadence. No paid uptime service or status platform is assumed.

Telemetry remains outside product readiness/request dependencies. Resource pressure
stops ingestion before threatening durable data. Deletion/redaction and notification
behaviour must be tested locally, then on the actual VPS; the
[public gates](delivery-lifecycle.md#public-production-gates) require that evidence.

## Identity and overload security signals

**Prepared in #274, not deployed or accepted on a host.** Reuse native Keycloak user
event counters with `realm` as the sole optional dimension. The production
[runtime policy](../../deploy/public-production/keycloak/abuse.env) and private-dev
Compose select login, registration, verification, verification sending, password
reset sending/completion and temporary lockout events. Build with native event
metrics enabled; no extension, database event storage or logging listener is needed.
`event` and `error` are native bounded outcomes; no address, subject, IP, token,
client ID, identity provider or session dimension is collected.

The optional private-dev metrics overlay now scrapes a separate `keycloak-security`
job alongside the unchanged successful-product-event job. Its
[scrape configuration](../../deploy/private-dev/prometheus/product-growth/keycloak.yaml)
limits event/error values, samples, body and labels before storage. Unknown error
constants are dropped, not relabelled into colliding series: review the allowlist
on Keycloak upgrades. This deliberately loses unsupported error details. The
production runtime must install an equivalent realm-specific scrape in #275; do not
copy the private realm name into production.

Inspect in existing private Grafana Explore, starting with:

```promql
sum by (event, error) (increase(keycloak_user_events_total{job="keycloak-security"}[5m]))
sum(increase(platform_http_errors_total{code="SERVICE_OVERLOADED"}[5m]))
up{job="keycloak-security"}
```

Also inspect HTTP latency/5xx and Hikari active/pending/timeouts before changing
limits. A sustained rise in failed logins, duplicate registrations, reset/verification
sends or temporary lockouts warrants checking provider quota/headroom and narrowing
exposure. Compare with successful operations; a counter is evidence of an event,
not a distinct attacker, account or confirmed abuse. No per-person investigation is
possible from these aggregates. Counters reset on restart, failed scrapes are unknown
rather than zero, and no series before the first event is normal. Alert routing and
real-host notification remain the existing #269/#276 acceptance gate. SMTP success
means accepted by the relay, not inbox delivery; use the provider's private counters
for the actual daily/monthly allowance.

API capacity rejections reuse the completion event and `platform.http.errors` with
bounded code `SERVICE_OVERLOADED`; they contain no submitted data. Under attack even
one redacted event per rejection can exhaust log volume: edge admission and the
existing rotated byte ceiling bound amplification, and public runtime still needs
its enforced seven-day age ceiling. Metrics use the existing seven-day retention;
no new personal-data store or retention period is introduced. Do not enable DEBUG,
access logs or `jboss-logging` to recover missing security samples. Exceptional
personal investigation metadata needs the separately recorded purpose/access/expiry
already required by this policy. Native SMTP transport failures may still produce
technical logs; the actual relay failure/redaction test remains required before
launch, independently of the no-event-listener proof.

Public-edge [request-limit](../architecture/deployment/mvp-platform-and-delivery.md#public-edge-request-limits)
refusals (#275) are deliberately unobserved for now. The limiter's own metrics cannot be
used: every counter is also emitted per client address, and those series are never
removed, so they would export addresses and grow without bound. Its refusal log carries
the address too. Both stay disabled, and edge 429s and 503s never reach the application's
counters.

**Planned for #275 increment 7, locally checked only:** Caddy's own HTTP metrics count
requests by `server`, `handler`, `method` and `code`, so 429 refusals appear as a bounded
count, while the limiter's metrics stay disabled. A local scrape of the production
Caddyfile with those metrics enabled, after per-source refusals, held no address-like
label value and no limiter series. Increment 7 needs a metrics listener on a private
telemetry network only, never routed or published, a scrape allowlist for those labels
and a contract test that refuses any address, key or host label. Edge 503s for overload
and maintenance share their labels with upstream 503s; read them with the gate state or
add a distinguishing route there. Until then, a missing count is not evidence that no
client was refused.
