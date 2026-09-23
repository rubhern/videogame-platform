# MVP close-out observability review

- **Type:** Point-in-time observability review (evidence, not an approved decision)
- **Reviewer:** AI-assisted review pass; the owner retains approval authority
- **Revision reviewed:** `d10bc44` on `main`, clean working tree
- **Scope:** logs, metrics, traces, health, diagnostics and operational signals of
  the backend and its runtime; the signals used to declare a deployment healthy;
  privacy and cardinality of everything emitted; alerting and dashboards proportionate
  to a private single-owner environment.
- **Out of scope:** CI, build, release and testing strategy (`OUT_OF_SCOPE: DEVOPS`,
  see the [DevOps review](devops-review.md)); host, network, storage and Docker
  topology (`OUT_OF_SCOPE: INFRASTRUCTURE`, see the
  [infrastructure review](infrastructure-review.md)); code quality and architecture
  (see the [code review](code-review.md) and the [architecture review](architecture-review.md)).
- **Sources contrasted:** `docs/development/observability.md`,
  `docs/architecture/deployment/mvp-platform-and-delivery.md`, the solution
  architecture, the technology baseline, the Product Brief ("Signals to observe"),
  the use-case record, `backend/src/main/resources/application.yaml` (management,
  metrics, tracing, logging and the `structured` profile), `application-oidc.yaml`,
  every instrumentation class (`CorrelationIdFilter`, `ObservabilityConfiguration`,
  `CatalogueStoreHealthIndicator`, `ReadinessHealthProperties`,
  `ApiExceptionHandler`, `ReleaseApiMetrics`, `GameSearchApiMetrics`,
  `GameDetailsEndpoint`, `CatalogueSynchronizationMetrics`,
  `CatalogueSynchronizationEndpoint`, the identity handlers), `backend/pom.xml`
  telemetry dependencies, `deploy/private-dev/compose.yaml` and
  `deploy/private-dev/otel/collector.yaml`, the shared Keycloak realm, and the
  logging assertions in `BackendStartupTest`, `ReleaseApiFailureIntegrationTest`
  and the deployment smoke.
- **Live evidence used:** one CI job log (read-only) in which the structured log
  encoder reports a failure while writing a technical-failure event.

Nothing here changes code, configuration or canonical documentation.

Implementation state, so that instrumentation is not confused with a backend:

| State | What |
|---|---|
| **Instrumentation existing** | One access log per request with correlation, route template, status, outcome and duration, plus `traceId`/`spanId` from MDC; one `ERROR` log per technical failure with cause; one `INFO` log per synchronization run; `http.server.requests` with percentile histogram and a high-cardinality-free observation convention; `catalogue.releases.result.count{view}`, `catalogue.search.result.outcome{outcome}`, `catalogue.game.details{eligibility,aggregate}`, synchronization run/provider/mapping meters; JVM, process, Hikari and Logback meters from Boot; liveness and readiness groups; `/actuator/info` with build and source revision; `POST/GET /actuator/cataloguesync` |
| **Export configured** | Local: OTLP disabled, plain console logs. Private dev: ECS JSON console logs into Docker's `local` driver (10 MiB × 3 per container); OTLP metrics every 60 s and traces at 10 % sampling to an internal OpenTelemetry Collector whose only exporter is `debug` with basic verbosity, so metrics and traces are counted and discarded |
| **Approved, pending** | A durable telemetry backend, dashboards, alerting and remote export are deferred by the platform design "until measured value justifies their host cost" |
| **Future** | Nothing beyond that is designed |

---

## 1. Executive summary

The observability design is disciplined and privacy-safe: it emits little, names
things with bounded vocabularies, keeps identifiers and input out of telemetry, and
proves its own boundaries (correlation and trace in logs, collector receipt) in
every deployment. Health semantics are right for the product: IGDB, the CDN and
telemetry never affect readiness, an empty catalogue is a product state rather than
an outage, and a degraded aggregate is a tagged success rather than an error. The
Product Brief's "signals to observe" are largely derivable from what exists.

Three things, however, currently prevent the system from answering the questions
an incident asks:

1. **In the `structured` profile every technical-failure log line is dropped**
   (`OB-01`, HIGH). The ECS encoder writes the throwable as a nested `error`
   object and the handler adds a key-value named `error.code`; the JSON writer
   refuses the second `error` member, the appender fails, and the event is lost.
   A CI log shows exactly this. Private dev runs the `structured` profile, so a
   `500`, a `CATALOGUE_READ_FAILED` or a `RATING_WRITE_FAILED` leaves an access-log
   line and a metric, but no cause and no stack trace. The tests that assert the
   failure log do so at the appender level, before encoding, and therefore pass.
2. **A partial synchronization cannot be diagnosed** (`OB-02`, HIGH). The run
   record and the log say `PARTIAL` and count failed Games; nothing anywhere says
   which Games failed or why, a provider failure collapses to
   `SYNCHRONIZATION_FAILED` without its code, and a run that takes half an hour is
   silent until it ends.
3. **Identity failures are invisible and health cannot name its failing
   component** (`OB-03`, `OB-04`). The BFF discards the authentication exception
   after redirecting, so a wrong client secret, an issuer mismatch and a user
   pressing "cancel" look identical; readiness returns `DOWN` with no component and
   no log line.

Everything emitted today goes to a sink that keeps nothing (`OB-06`): logs live in
30 MiB Docker files and metrics and traces are counted by the collector and
discarded. That is the approved state, and this review does not argue for a
platform; it argues for the smallest retention step that makes post-incident
investigation possible, and records the trigger for more.

Findings: **2 HIGH, 5 MEDIUM, 5 LOW**, plus six revisit triggers. Five GitHub
issues are suggested.

---

## 2. Overall observability assessment

**Detecting problems.** Request-level failures are detectable from
`http.server.requests{status,uri}` and from the access log; synchronization
outcomes are detectable from meters, the durable run record and one log line;
readiness reflects the database and the schema. Identity failures are not
detectable (`OB-03`), and nothing is retained long enough to detect a trend
(`OB-06`).

**Diagnosing failures.** This is where the gaps concentrate: the failure log with
the cause is lost in structured mode (`OB-01`), synchronization failures have no
per-Game trail (`OB-02`), readiness hides the failing component (`OB-04`), and
stack traces are truncated from the end, which removes the root cause first
(`OB-01`).

**Understanding requests and internal operations.** The access log answers what
was called, how long it took and how it ended; the correlation identifier is
returned to the client and shown to the user on error pages, so a report can be
matched to a line. Traces add nothing today beyond the identifier already in the
logs (section 7).

**Differentiating application, database, identity and provider failures.**
Database: yes, through stable codes and readiness (once `OB-01` and `OB-04` are
fixed, also through cause and component). Provider: yes, through
`provider.request{outcome}` and the run code (better with `OB-02`). Identity: no
(`OB-03`). Application: yes, through `INTERNAL_ERROR` and the (currently lost)
cause.

**Verifying the environment.** Liveness, readiness, `/actuator/info` version and
revision, and the deployment smoke's evidence record give a complete answer.

**Investigating after the fact.** Limited to what the Docker log files still hold;
no metric or trace history exists (`OB-06`).

**Knowing product behaviour.** Zero-result search, aggregate availability and
eligibility reasons, synchronization outcome and page yield are observable; rating
activation and resume outcomes, journey errors by stable code, and catalogue
freshness age are not (`OB-05`, `OB-07`).

**Cost.** Volume is tiny and bounded; cardinality is under control everywhere;
nothing sensitive was found in any emitted signal (section 10).

---

## 3. Current strengths

- **Privacy by construction.** The observation convention returns no
  high-cardinality key-values, so spans carry no URL, query or client detail; the
  access log uses the route template and never the path or query; metric tags are
  closed enums (`view`, `outcome`, `eligibility`, `aggregate`, `operation`, `kind`,
  `reason`); the correlation identifier is accepted from the client only if it
  matches `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`; Problem Details carry a code and a
  correlation identifier and never SQL or a stack trace, and integration tests
  assert it; `management.info.env.enabled=false`; the IGDB client never lets a
  body, URL or credential reach a log, a metric or an exception message.
- **Correct health semantics.** Liveness is process viability; readiness is the
  database plus a constant-shape catalogue schema probe with its own bounded
  timeout; IGDB, the cover CDN, Keycloak and the collector are excluded on purpose;
  probes do not generate access logs.
- **Bounded synchronization telemetry.** Run outcome and duration by outcome;
  record counts by kind; provider requests, latency and retries by the closed
  operation vocabulary; mapping failures by reason; a durable run record with
  counters; cursors and provider identities deliberately absent from metric tags.
- **A deployment is declared healthy by evidence, not by a green container.**
  Readiness, exact version and revision, metric catalogue presence, releases API
  through the rendered shell, a real Keycloak session with `HttpOnly`/`Secure`
  cookie and CSRF-protected logout, correlation and trace propagation in the
  structured log, and collector receipt of the smoke trace.
- **Correlation that reaches the user.** The `X-Correlation-ID` echoed on every
  response and the "Referencia para soporte" line on every error state let a person
  report exactly the identifier an operator can grep.
- **Telemetry cannot hurt the product.** OTLP connect and export timeouts are 1 s
  and 5 s, export is off unless configured, the collector has a memory limiter and
  bounded logs, and none of it is a readiness dependency.

---

## 4. Findings

Priority meaning: `HIGH` = seriously hinders detecting failures, investigating
relevant errors, distinguishing availability from degradation, protecting secrets
or personal data, or knowing the outcome of an important operation; `MEDIUM` = a
real diagnostic gap; `LOW` = worth doing opportunistically. Type: `CURRENT_PROBLEM`
exists today; `IMPROVEMENT` is a better fit; `REVISIT_TRIGGER` is not current work.

### HIGH

#### `OB-01` — Technical-failure logs are dropped by the ECS encoder in the `structured` profile, and truncated stack traces lose the root cause

- **Priority:** HIGH · **Type:** CURRENT_PROBLEM · **Area:** logs · **Cardinality risk:** none
- **Evidence:** `ApiExceptionHandler.java:584-590`
  (`LOGGER.atError().addKeyValue("error.code", …).setCause(exception)`);
  `application.yaml` `structured` profile (`logging.structured.format.console: ecs`,
  `stacktrace.max-length: 2048`, no `root-first`); `deploy/private-dev/compose.yaml`
  (`SPRING_PROFILES_ACTIVE: oidc,structured`); CI log of the identity job at the
  reviewed revision: `ch.qos.logback.core.ConsoleAppender[CONSOLE] - Appender
  [CONSOLE] failed to append. java.lang.IllegalStateException: The name 'error' has
  already been written … at PersonalRatingApiIntegrationTest.collectionDatabaseFailure…`;
  Spring Boot 4.1's Logback `ElasticCommonSchemaStructuredLogFormatter` writes the
  throwable as a nested `error` object (`type`, `message`, `stack_trace`) and
  expands dotted key-value pairs into nested objects, so `error.code` becomes a
  second `error` member. `ReleaseApiFailureIntegrationTest` asserts the event on a
  captured `ILoggingEvent` (before encoding) and `BackendStartupTest` asserts only
  access-log lines under `structured`, so no test observes the loss.
- **Operational question that is hard to answer today:** "Why did that `500` /
  `CATALOGUE_READ_FAILED` / `RATING_WRITE_FAILED` happen?" — on private dev the
  answer is not written anywhere.
- **Problem observed:** every call to `logTechnicalFailure` (internal error,
  catalogue read failure, rating write failure, personal ratings read failure,
  personal rating read failure) is lost in the profile the private environment
  runs. Separately, `max-length: 2048` truncates the *end* of the printed stack
  trace, and Java prints the outermost wrapper first and the root cause
  (`Caused by: … PSQLException: connection refused`) last, so even when the line is
  written the most useful part is the first to go.
- **Impact:** the only diagnostic trail for backend failures on dev is an
  access-log line with `SERVER_ERROR` and a metric with `status=500`; the cause,
  the failing SQL grammar or connection error and the stack are gone.
- **Proposal:** (1) rename the key-value to something that does not collide with
  the ECS `error` object, for example `problem.code` (ECS then writes
  `problem: {code}`), and use the same key in every failure log; (2) set
  `logging.structured.json.stacktrace.root-first: true` so truncation drops the
  wrappers rather than the root cause, and raise `max-length` to 4096; (3) add one
  test under `@ActiveProfiles("structured")` with `CapturedOutput` that provokes a
  technical failure and asserts a JSON line containing the code, `error.type` and
  `error.stack_trace`, so the encoder path itself is covered.
- **Simpler alternative considered:** drop the key-value and put the code in the
  message. Rejected: the code should stay queryable as a field.
- **Trade-offs:** none; the rename touches one constant, one test assertion and
  one line in the observability guide.
- **Cost:** small · **Issue:** yes

#### `OB-02` — A partial or failed synchronization leaves no trail of which Games failed, why, or how far the run got

- **Priority:** HIGH · **Type:** CURRENT_PROBLEM · **Area:** logs / operations · **Cardinality risk:** none (no new tags; identifiers go to a log line and the durable report, never to a metric)
- **Evidence:** `CatalogueSynchronizationService.java` (`synchronizeGame` catches
  `ProviderRequestException`, `SynchronizationWriteException` and
  `IllegalArgumentException` and only increments `counts.failed`; the outer
  `catch (RuntimeException)` records `SYNCHRONIZATION_FAILED` without the provider
  failure code; the page loop emits nothing); `CatalogueSynchronizationEndpoint.java:59-63`
  (one `INFO` line at the end with outcome, code and counters);
  `CatalogueSynchronizationReport` (counters only); `JdbcCatalogueSynchronizationStore.lastRun`
  (a `running` row reports zero counters); `docs/development/observability.md`
  ("Structured logs contain outcome, stable code and counters, never raw payloads
  or provider identities").
- **Operational question that is hard to answer today:** "The run says
  `PARTIAL`, 3 Games failed. Which ones, and was it a provider mapping problem, a
  unique-tuple collision or a write failure? And is the run that started twenty
  minutes ago still alive?"
- **Problem observed:** the design isolates the affected Game (ADR-0017) and then
  forgets it. Mapping-failure metrics say *how many* records failed by reason, not
  *which Game* carried them; write failures and duplicate-reference rejections are
  counted without a reason; a provider page failure ends the run with the generic
  code while the specific `ProviderFailureCode` exists only as a metric tag; during
  the run the only heartbeat is a database column.
- **Impact:** the one important internal operation cannot be investigated after
  the fact; the operator's only recourse is to re-run the whole interval and hope
  the same Games fail while watching.
- **Proposal:** (1) one `WARN` line per failed Game with the provider *reference*
  (the numeric IGDB Game identifier, which is public catalogue metadata, not a
  payload and not personal) and a bounded reason (`ProviderMappingFailure` name,
  `ProviderFailureCode` name, `write_conflict`, `write_failed`,
  `duplicate_release_reference`), never the title or payload; (2) the same list,
  capped (for example the first 50), inside the durable run report so `GET
  /actuator/cataloguesync` shows it; (3) one `INFO` line per provider page with
  page index, Games in page and cumulative counters so a long run is visibly
  alive; (4) the provider failure code in the terminal log line and report when a
  page failure ends the run. If the owner prefers to keep "no provider identities
  in logs", keep only the durable report list and the page-progress lines; the
  report is operational history the design already stores.
- **Simpler alternative considered:** rely on the mapping-failure metrics.
  Rejected: counts cannot point at a Game, and metrics are discarded today.
- **Trade-offs:** a few dozen log lines per large run; the observability guide's
  sentence about provider identities needs one clarification (identifier versus
  payload).
- **Cost:** small–medium · **Issue:** yes

### MEDIUM

#### `OB-03` — Identity and BFF failures are indistinguishable from a user cancelling, and Keycloak availability is invisible to the application

- **Priority:** MEDIUM · **Type:** CURRENT_PROBLEM · **Area:** logs / metrics / health · **Cardinality risk:** low (OAuth2 error codes are a small closed set; guard with an allowlist and `other`)
- **Evidence:** `RatingIntentAuthenticationFailureHandler.java:34-53` (redirects with
  the `cancelled` marker and never inspects the `AuthenticationException`); no
  logger and no meter in `identity/*`; Spring Security logs OAuth2 login failures
  at `DEBUG`; `application.yaml` (`root: INFO`); readiness excludes Keycloak by
  design; the shared realm has no event configuration, so Keycloak's own login
  events go only to its container log through the default `jboss-logging`
  listener.
- **Operational question that is hard to answer today:** "Nobody can log in since
  the last deployment — is it the client secret, the issuer, the redirect URI,
  Keycloak being down, or did the user just cancel?"
- **Problem observed:** the BFF holds the exact error (`invalid_client`,
  `invalid_grant`, `invalid_state_parameter`, `access_denied`, a JWT validation
  failure) and discards it. The only trace is an access-log line for
  `/login/oauth2/code/keycloak` with `302`, identical for success and failure.
- **Impact:** identity is the one failure domain the system cannot name.
- **Proposal:** (1) in the failure handler, one `WARN` line with the bounded error
  code (`OAuth2AuthenticationException.getError().getErrorCode()` when present,
  otherwise the exception's simple class name) and whether a rating return context
  was pending; no state, code, token or redirect URI; (2) a counter
  `identity.authentication{outcome=success|failure,error=<allowlisted code|other>}`
  incremented from the success and failure handlers — first check whether Boot's
  Spring Security observations already publish `spring.security.authentications`
  on `/actuator/metrics`; if they do and their tags are bounded, use them and skip
  the counter; (3) optionally a `HealthContributor` named `identityProvider` that
  fetches the JWKS URI with a 2 s timeout and a 60 s cache, included in
  `/actuator/health` but **not** in the readiness group, so the operator can see
  "login is currently impossible" without making Keycloak a readiness dependency.
- **Simpler alternative considered:** raise `org.springframework.security` to
  `DEBUG` on dev. Rejected: verbose, and DEBUG lines from Security include request
  details the policy excludes.
- **Trade-offs:** one log line per failed login; one small meter.
- **Cost:** small · **Issue:** yes (grouped with `OB-04`)

#### `OB-04` — Readiness reports `DOWN` without naming the component and without a log line

- **Priority:** MEDIUM · **Type:** CURRENT_PROBLEM · **Area:** health / logs · **Cardinality risk:** none
- **Evidence:** `application.yaml` (`show-details: never`, no `show-components`);
  `CatalogueStoreHealthIndicator.java:31-36` (`catch (DataAccessException) →
  Health.down().build()`, no log); Boot's `db` indicator likewise; the management
  port is private and unauthenticated by design (`ManagementEndpointSecurityConfiguration`);
  earlier review `OBS-01` (still open).
- **Operational question that is hard to answer today:** "Readiness is `DOWN` —
  is PostgreSQL unreachable, or is the schema/role wrong after a migration?"
- **Problem observed:** `/actuator/health/readiness` returns `{"status":"DOWN"}`
  and nothing else; neither indicator logs the transition; the deployment script
  reports "candidate readiness failed" with no component.
- **Impact:** the operator must guess and read container logs of two services to
  distinguish a database outage from a schema problem.
- **Proposal:** `management.endpoint.health.show-components: always` (component
  names and statuses without details; `db` and `catalogueStore` are not topology
  secrets and the port is private), and one `WARN` in `CatalogueStoreHealthIndicator`
  on failure with the bounded exception class name and the configured timeout,
  rate-limited to state transitions (remember the last status) so a sustained
  outage does not log every 10 s.
- **Simpler alternative considered:** `show-details: always`. Rejected: the `db`
  details include the validation query and database product; unnecessary.
- **Trade-offs:** none.
- **Cost:** small · **Issue:** yes (grouped with `OB-03`)

#### `OB-05` — Stable Problem codes are not a metric, so journey errors and the `503` family are indistinguishable

- **Priority:** MEDIUM · **Type:** IMPROVEMENT · **Area:** metrics / product-signals · **Cardinality risk:** low (`ProblemCode` is a generated closed enum of about 25 values; `status` is bounded)
- **Evidence:** `ApiExceptionHandler.problem(…)` builds every Problem and records
  nothing; `http.server.requests` distinguishes `503` on `/api/v1/releases` but not
  `CATALOGUE_NOT_READY` (a product state) from `CATALOGUE_READ_FAILED` (an
  outage); the Product Brief lists "journey errors" and "zero-result/catalogue-
  boundary failures" as signals to observe; `observability.md` asks that product
  meters answer a decision and pass a cardinality review.
- **Operational question that is hard to answer today:** "How often do users hit
  `RATING_NOT_ELIGIBLE`, `RATING_WRITE_CONFLICT` or `SEARCH_QUERY_INVALID`, and is
  that `503` an empty catalogue or a database problem?"
- **Problem observed:** the stable code is the product's own error vocabulary and
  the one dimension that separates "expected product outcome" from "technical
  failure" inside the same HTTP status.
- **Impact:** journey-error learning and outage triage both fall back to log
  grepping.
- **Proposal:** one counter `api.problem{code,status}` incremented in `problem(…)`
  (and in the two identity Problem writers with their two codes). It answers the
  question above directly and doubles as the alert source for
  `CATALOGUE_READ_FAILED`/`RATING_WRITE_FAILED` rates once a backend exists.
- **Simpler alternative considered:** grep the access log. Rejected: the access log
  does not carry the code and logs are not retained.
- **Trade-offs:** about 25 × 8 possible series at most; negligible.
- **Cost:** small · **Issue:** yes (grouped with `OB-07`)

#### `OB-06` — Emitted telemetry is retained nowhere: the collector discards metrics and traces, and logs live only in 30 MiB Docker files

- **Priority:** MEDIUM · **Type:** CURRENT_PROBLEM (accepted by design; the gap is investigation after the fact) · **Area:** operations / dashboards · **Cardinality risk:** none
- **Evidence:** `deploy/private-dev/otel/collector.yaml` (`exporters: debug` with
  `verbosity: basic` as the only exporter for both pipelines);
  `deploy/private-dev/compose.yaml` (`x-logging: local, max-size 10m, max-file 3`;
  `TELEMETRY_OTLP_METRICS_ENABLED: true`, `…STEP: 60s`, traces at `0.1`);
  platform design ("A durable telemetry backend, dashboards, alerting and remote
  export remain deferred until measured value justifies their host cost").
- **Operational question that is hard to answer today:** "What did latency,
  errors, heap or the connection pool look like yesterday at 22:10 when the user
  saw an error page?"
- **Problem observed:** the application pushes about 300 metric series every
  minute and 10 % of traces to a process that counts them and forgets them; the
  application log rotates after 30 MiB, which at private traffic is weeks, but the
  metric and trace history is zero. This is exactly the approved state, so the
  finding is not "add a platform" but "the cheapest retention that makes the
  instrumentation investigable".
- **Impact:** every post-incident question about resources or trends is
  unanswerable; every question about a request is answerable only while the log
  file survives.
- **Proposal:** (1) replace the collector's `debug` exporter with the `file`
  exporter writing rotated JSON lines to a bounded volume (for example
  `max_megabytes: 100`, `max_backups: 3`) for both pipelines, keeping `debug` only
  if the deployment smoke's receipt check needs it (it counts debug lines today;
  the check can count file lines instead) — the core `otelcol` image already used
  ships this exporter; verify with `otelcol components` before relying on it;
  (2) raise the application container's log retention to hold roughly a month of
  private traffic (the mechanism is `OUT_OF_SCOPE: INFRASTRUCTURE`; the requirement
  is "about 30 days of application log"); (3) record the revisit trigger for a
  real store (`RT-1`): a single-binary, zero-cost time-series store such as
  VictoriaMetrics or Prometheus plus Grafana on the same host, when the first
  incident needs a graph rather than a grep.
- **Simpler alternative considered:** disable metric and trace export entirely
  until a backend exists. Rejected: the deployment smoke uses collector receipt
  as evidence of the telemetry boundary, and the file exporter is cheaper than a
  backend while giving history.
- **Trade-offs:** a few MiB per day on disk; JSON files are grep-able, not
  queryable.
- **Cost:** small · **Issue:** yes

#### `OB-07` — Rating activation, resume outcome and catalogue freshness age are not observable

- **Priority:** MEDIUM · **Type:** IMPROVEMENT · **Area:** product-signals / metrics · **Cardinality risk:** none (closed outcome enums)
- **Evidence:** Product Brief ("Signals to observe include release-to-game
  navigation, rating activation, later rating retrieval, repeat release use,
  zero-result/catalogue-boundary failures, catalogue freshness, synchronization
  outcome, and journey errors"); `RatingIntentController.start` (three outcomes —
  redirect to login, resumed, invalid — all `302` and therefore identical in
  `http.server.requests`); the success and failure handlers (`resumed`,
  `expired`, `cancelled` markers, no meter); `observability.md` ("No
  process-local publication-age gauge claims durable catalogue freshness");
  `GET /actuator/cataloguesync` (last run, manual only).
- **Operational question that is hard to answer today:** "How often does pressing
  a star lead to a login, and how often does the return expire or get cancelled?
  How old is the catalogue?"
- **Problem observed:** the Brief's rating-activation signal is the one MVP
  hypothesis the system cannot count; freshness is knowable only by asking the
  endpoint by hand. Release-to-game navigation, later rating retrieval and
  zero-result behaviour are already derivable from route counts and the search
  meter and need nothing.
- **Impact:** the learning the Brief asks for is not collected; a stale catalogue
  is noticed by a user, not by the operator.
- **Proposal:** (1) `identity.rating_intent{outcome=login|resumed|invalid}` in the
  start endpoint and `identity.rating_resume{outcome=resumed|expired|cancelled}`
  in the handlers — six series, no identifiers; (2) a gauge
  `catalogue.synchronization.last_success.age_seconds` computed from
  `synchronization_run` at scrape time with a 60 s cache — database-backed, so it
  is correct across instances and does not fall under the guide's process-local
  objection; useful only once `OB-06` gives metrics a home, so it may wait for
  `RT-1`.
- **Simpler alternative considered:** infer activation from `/auth/rating-intent/start`
  request counts. Rejected: it cannot separate invalid input from real starts.
- **Trade-offs:** two tiny meters and one bounded query per scrape.
- **Cost:** small · **Issue:** yes (grouped with `OB-05`)

### LOW

#### `OB-08` — Log lines are not uniformly structured and not self-describing across deployments

- **Priority:** LOW · **Type:** IMPROVEMENT · **Area:** logs · **Cardinality risk:** none
- **Evidence:** `CatalogueSynchronizationEndpoint.java:59-63` (`LOGGER.info("… outcome={} code={} counters={}", …)`,
  a formatted message whose `counters` is a record `toString`, while the access
  and failure logs use key-values); `application.yaml` (`structured` profile sets
  no `logging.structured.ecs.service.version`/`service.environment`, so ECS lines
  carry no version although OTLP resources do); `backend/.env.example` exposes only
  `LOGGING_LEVEL_COM_VIDEOGAMEPLATFORM`, and no document names the two or three
  framework loggers worth raising temporarily (`org.springframework.security.oauth2`,
  `org.flywaydb`, `com.zaxxer.hikari`).
- **Problem observed:** the synchronization outcome is unqueryable as fields;
  after a rollback, a log line cannot say which version wrote it; troubleshooting
  guidance is tribal.
- **Proposal:** key-values for outcome, code and each counter; set the two ECS
  service fields from the existing `TELEMETRY_*` variables; a five-line
  "temporary diagnostics" note in the observability guide.
- **Cost:** small · **Issue:** no (fold into `OB-01`)

#### `OB-09` — Silent degradations: the aggregate read failure and invalid cover data leave no log

- **Priority:** LOW · **Type:** IMPROVEMENT · **Area:** logs · **Cardinality risk:** none
- **Evidence:** `JdbcRatingStatisticsReadAdapter.java:20` (swallows
  `DataAccessException | IllegalArgumentException | ArithmeticException` and
  returns `Unavailable`; the only signal is `aggregate=unavailable` on
  `catalogue.game.details`); `IgdbCoverReferenceResolver` throws
  `CatalogueDataInvalidException`, which reaches the catch-all and is logged as
  `INTERNAL_ERROR` (lost under `OB-01`).
- **Problem observed:** "why is the community score unavailable?" has a metric
  but no cause; "why does this game page return `500`?" has a cause that is
  currently dropped and, once written, is labelled as a generic internal error.
- **Proposal:** one `WARN` with the bounded exception class name in the
  statistics adapter; a dedicated handler mapping `CatalogueDataInvalidException`
  to its own code so the log and the (`OB-05`) counter say "persisted data
  invalid" rather than "internal error".
- **Cost:** small · **Issue:** no (code review `CR-19` and `CR-03` already carry it)

#### `OB-10` — The observability guide forbids what the failure log deliberately does

- **Priority:** LOW · **Type:** CURRENT_PROBLEM (documentation contradiction) · **Area:** privacy / logs · **Cardinality risk:** none
- **Evidence:** `observability.md` ("Never log or export … arbitrary exception
  text"); `ApiExceptionHandler.logTechnicalFailure` (`setCause(exception)`, which
  under ECS writes `error.message` and `error.stack_trace` with every cause's
  message); earlier review `DOC-02` (still open).
- **Problem observed:** the guide is canonical for policy and the implementation
  contradicts it; but removing the cause would destroy diagnosis. The real risk
  the rule protects against is exception text that carries request input or
  personal values (a PostgreSQL constraint `Detail:` line can include key values;
  the rating store avoids that path with `ON CONFLICT DO NOTHING` and conditional
  updates).
- **Proposal:** reword the rule to "never log exception text that carries request
  input, personal values or credentials; wrapped causes and stack traces are
  allowed", and add one test on the rating write path asserting the logged cause
  chain contains neither the user identifier nor the value.
- **Cost:** small · **Issue:** no (fold into `OB-01`)

#### `OB-11` — PostgreSQL emits no slow-query signal

- **Priority:** LOW · **Type:** IMPROVEMENT · **Area:** operations · **Cardinality risk:** none
- **Evidence:** `deploy/private-dev/compose.yaml` (`postgres` service with no
  `command` overrides; PostgreSQL defaults: `log_min_duration_statement = -1`);
  the application's query timeouts (`5s`) turn a slow query into a
  `CATALOGUE_READ_FAILED` with no statement identity on the database side.
- **Problem observed:** "which query was slow?" cannot be answered from either
  side; the ADR-0015/0016 `EXPLAIN` evidence is opt-in and local.
- **Proposal:** `log_min_duration_statement = 1000` (and `log_lock_waits = on`)
  on the private-dev PostgreSQL, whose logs are already bounded; parameters are
  not logged by default. The mechanism lives in Compose
  (`OUT_OF_SCOPE: INFRASTRUCTURE`); the requirement is stated here.
- **Cost:** small · **Issue:** no (fold into `OB-06`)

#### `OB-12` — Trace export on dev costs a little and is used only as a boundary proof

- **Priority:** LOW · **Type:** REVISIT_TRIGGER · **Area:** traces · **Cardinality risk:** none
- **Evidence:** `application.yaml` (`spring-boot-starter-opentelemetry`, W3C
  propagation, one server span per request through the observation convention,
  no JDBC or HTTP-client spans); `compose.yaml` (`TELEMETRY_TRACING_SAMPLING_PROBABILITY: "0.1"`);
  the deployment smoke asserts collector receipt of one trace.
- **Assessment:** in a modular monolith with one span per request and no
  backend, distributed tracing adds nothing beyond the `traceId` already in every
  log line, which the correlation identifier already provides. Keep propagation
  and the log fields; keep export only because the smoke uses it. Do not add JDBC
  or client spans until `RT-1`; then `datasource-micrometer` is the first
  worthwhile span because "DB time versus application time" is the question a
  monolith actually has.
- **Cost:** none now · **Issue:** no

---

## 5. Logging assessment

**What exists.** Three application log statements: the access line
(`INFO`, `http.method`, `http.route`, `http.status_code`, `http.outcome`,
`duration_ms`, plus `correlationId`, `traceId`, `spanId` from MDC), the technical
failure line (`ERROR`, `error.code`, cause), and the synchronization outcome line
(`INFO`, formatted message). Framework logs at `INFO` (startup, Hikari, Tomcat,
Modulith verification, Flyway in the migration actor). The `structured` profile
switches the console to ECS JSON with a 2048-character stack-trace cap.

**Levels.** Correct where they exist: failures at `ERROR`, requests at `INFO`,
nothing at `WARN` — which is itself the gap: the system has no "something is
wrong but the request succeeded" tier (login failed, statistics unavailable,
Game skipped, readiness component down). `OB-02`, `OB-03`, `OB-04`, `OB-09` all
add exactly that tier.

**Correlation.** Excellent: accepted safely from the client, generated otherwise,
echoed in the header and the Problem body, present in every log line through MDC,
restored correctly after the request, excluded for probes.

**Context and consistency.** The access line is the model; the sync line is not
(`OB-08`); the failure line is broken under ECS (`OB-01`). Field names mix ECS
style (`http.route`) with camel case (`correlationId`); acceptable, but the
ECS-nested behaviour that broke `error.code` argues for choosing non-ECS-reserved
prefixes for custom fields (`problem.*`, `vgp.*`).

**Exceptions.** Logged with cause at the API boundary only; the identity layer
never logs; adapters that swallow (`OB-09`) never log. Stack traces would be
truncated from the wrong end (`OB-01`).

**Noise and volume.** None. No success logs beyond the access line, no per-Game
lines during sync (the opposite problem), no probe lines. Volume is a few hundred
bytes per request.

**Reconstructing what happened.** For a request: possible from the access line and
the correlation identifier the user reports, provided the log file still exists.
For a failure: not possible on dev today (`OB-01`). For a sync: the outcome and
counts, never the detail (`OB-02`).

---

## 6. Metrics assessment

| Meter | Tags | Cardinality | Question it answers | Assessment |
|---|---|---|---|---|
| `http.server.requests` (+ percentile histogram) | `method`, `uri` (route template), `status`, `outcome`, `exception` (class name) | bounded | rate, latency percentiles, error ratio per route | the workhorse; correct |
| `catalogue.releases.result.count` | `view` (2) | bounded | page yield per view; empty-page share | marginal but cheap; keep |
| `catalogue.search.result.outcome` | `outcome` (2) | bounded | zero-result share (Brief signal) | good |
| `catalogue.game.details` | `eligibility` (6), `aggregate` (2) | bounded | degraded aggregate versus healthy empty; eligibility mix | good; the only product-state meter |
| `catalogue.synchronization.run{outcome}`, `.run.duration{outcome}`, `.run.records{kind}` | outcome (5), kind (9) | bounded | run outcomes, duration, volume by kind | good |
| `catalogue.synchronization.provider.request{operation,outcome}`, `.request.duration{operation}`, `.retry{operation}`, `.mapping.failure{reason}` | operation (3), outcome (success + 4 codes), reason (6) | bounded | provider health and rejection reasons | good |
| JVM, process, system, `hikaricp.*`, `logback.events`, `disk.*` | Boot defaults | bounded | heap, GC, threads, CPU, pool saturation, log error count | present; unexported (`OB-06`) |

**Missing and justified:** `api.problem{code,status}` (`OB-05`), the two rating
boundary counters and the freshness-age gauge (`OB-07`), an identity outcome
counter unless Security's own observation already provides it (`OB-03`).

**Not missing:** a "ratings created" counter (PUT `201` on the ratings route already
counts it), a "details viewed" counter (route count), a per-user anything.

**Naming.** Micrometer dot names, consistent `catalogue.` prefix, `outcome`/`kind`
/`reason` used the same way everywhere. The proposed `api.` and `identity.`
prefixes fit.

**Duplication.** `http.server.requests` and the access log overlap by design
(aggregate versus per-request); acceptable.

---

## 7. Tracing assessment

W3C propagation is on; the `traceparent` from the smoke reaches the access log and
the collector. Spans today: the server span per request (Boot's observation),
with high-cardinality key-values removed by `ObservabilityConfiguration`. No
JDBC, no HTTP-client (the IGDB client is `java.net.http`; Security's token client
is not observed), no internal spans. Sampling: 100 % locally, 10 % on dev; export
disabled unless configured.

**Value in a modular monolith today:** log correlation, which the correlation
identifier already gives; plus the boundary proof the deployment uses. That is
honest and enough. The first span that would earn its place is JDBC timing
(`RT-1`); the second is none — internal operations are synchronous and the sync
loop is better served by progress logs (`OB-02`) than by spans.

**Correlating logs and traces:** both `correlationId` and `traceId` are in every
line; a sampled trace and its log lines share the identifier; unsampled requests
still carry identifiers. Correct.

---

## 8. Health and availability assessment

| Group | Includes | Excludes | Assessment |
|---|---|---|---|
| liveness | `livenessState` | everything else | correct |
| readiness | `readinessState`, `db`, `catalogueStore` | Keycloak, IGDB, CDN, collector | correct for a public catalogue that must serve without identity or provider |

`show-details: never` hides components (`OB-04`); `probes.enabled` gives the
Kubernetes-style paths the deployment and Compose health checks use; `info` shows
build version and source revision without environment. The management port is
private and unauthenticated, and its only write operation rejects cross-site
browser requests.

**Degraded versus unavailable.** The product expresses degradation in responses
(`aggregate: unavailable`, `freshnessStatus: stale`, fallback cover) and in the
details meter, never in health — correct; health has no `DEGRADED` and needs
none. An empty catalogue is `CATALOGUE_NOT_READY` at the API and `UP` in
readiness — correct. What the health endpoint cannot do is tell the operator
*which* dependency is down (`OB-04`) or that identity is unavailable (`OB-03`,
outside readiness by design).

**Signals used to declare a deployment healthy** (DevOps owns the mechanism):
readiness, exact version/revision, metric catalogue presence, releases API and
rendered shell, real Keycloak session, correlation and trace in the structured
log, collector receipt. Adequate; `OB-06` would let the same smoke leave a
durable metric trace behind.

---

## 9. Operational and product signals

Could an incident answer the six questions?

| Question | Requests | Synchronization | Identity | Database |
|---|---|---|---|---|
| What failed | access log status + Problem code in the response; cause lost (`OB-01`) | outcome code, counters; not which Game (`OB-02`) | no (`OB-03`) | stable code; cause lost (`OB-01`); component hidden (`OB-04`) |
| When | access log timestamp | run record `startedAt`/`completedAt` | access log only | readiness flip time unknown (no log) |
| How much | `http.server.requests` by status; not retained (`OB-06`) | counters | unknown | affected routes by status |
| What was running | route template | interval; no progress (`OB-02`) | route | query unknown (`OB-11`) |
| Partial state | n/a | `PARTIAL` with counts; last valid Game state preserved (ADR-0017) | pending return context discarded | n/a |
| Needs intervention | judgement from status | `FAILED` needs a re-run; `PARTIAL` cannot say which Games (`OB-02`) | unknown | readiness `DOWN` |

**Product signals versus operational signals.** The Brief's list maps as follows:
release-to-game navigation → details route count (adequate proxy); rating
activation → not observable (`OB-07`); later rating retrieval → `GET /me/ratings*`
route counts; repeat release use → not observable and should stay so (per-user);
zero-result/catalogue-boundary → search outcome meter plus `OB-05` codes;
catalogue freshness → manual endpoint, gauge deferred (`OB-07`); synchronization
outcome → complete; journey errors → `OB-05`. No analytics platform, no client
telemetry and no user dimension are proposed; every product signal is an aggregate
counter with a closed vocabulary, which keeps operational and product observability
in one Micrometer registry without mixing concerns.

---

## 10. Privacy and cardinality assessment

**Sensitive data check** (verified in code and configuration; nothing found):
no OAuth/OIDC token, cookie, CSRF value or authorization code is logged, exported
or placed in a metric (the session controller returns the CSRF token in the
response body only, which is the contract); no credential or database URL with a
password appears in logs (`info.env` disabled; the wrappers export values only to
the process environment, and the validator checks container metadata); no raw
provider payload, URL or body leaves the IGDB client or the adapter; no rating is
associated with a person anywhere in telemetry (the details meter counts
eligibility and aggregate availability, not users); no user identifier is a tag
or a log field (the access log has no principal); no search string reaches logs or
tags (the search meter is a two-value outcome); no URL or raw input is logged (route
templates only) and spans carry no high-cardinality key-values; Problem responses
carry no stack trace or SQL and tests assert it.

**Indirect leaks considered:** exception messages (`OB-10`: bounded risk, policy
wording to align); the correlation identifier echoed from the client (safe pattern,
no injection into JSON or headers); the deployment evidence and smoke (fixed
identifiers, no credentials); Keycloak's own login-event logging (server log only,
default listener); PostgreSQL logs (statement text without parameters if `OB-11`
is adopted).

**Cardinality of every existing and proposed tag:**

| Tag | Values | Verdict |
|---|---|---|
| `uri` on `http.server.requests` | route templates plus Boot's bounded fallbacks | bounded |
| `exception` on `http.server.requests` | exception simple class names reachable from code | bounded |
| `view`, `outcome`, `eligibility`, `aggregate`, `operation`, `kind`, `reason` | closed enums (2–9 values) | bounded |
| proposed `code`, `status` on `api.problem` | `ProblemCode` enum (~25), HTTP statuses used (~8) | bounded |
| proposed `outcome`, `error` on `identity.authentication` | closed set with allowlist and `other` | bounded if the allowlist is enforced; **do not** tag with the raw error string |
| proposed `outcome` on `identity.rating_intent` / `identity.rating_resume` | 3 / 3 | bounded |
| proposed `catalogue.synchronization.last_success.age_seconds` | no tags | bounded |

No metric uses a user, game, release, request, correlation, provider, search or URL
value, and no proposal introduces one. The one place an identifier is proposed
(`OB-02`) is a log line and the durable run report, where a numeric provider Game
reference is bounded in length and is public catalogue metadata.

---

## 11. Alerting/dashboard recommendations

**Now (no backend, single owner):** no alerting infrastructure. One "morning
check" command on the host, run by hand or by a daily `systemd` timer that appends
to a log, printing: readiness with components (`OB-04`), the last synchronization
run and its age, the count of `ERROR` lines in the application log for the last
24 hours, and the collector file size (`OB-06`). This is the dashboard the
environment needs; it costs a script.

**When `RT-1` happens (a store and one dashboard page):** the only three alerts
worth an interruption, each with its action:

| Condition | Detects | Human action | Why interrupt |
|---|---|---|---|
| Readiness `DOWN` for more than 5 minutes | database or schema failure; the app is up but cannot serve | check the PostgreSQL container and the last migration; restore if needed | the product is unavailable and will not recover by itself |
| `api.problem{code=CATALOGUE_READ_FAILED|RATING_WRITE_FAILED|INTERNAL_ERROR}` rate above a handful per 15 minutes | a technical fault users are hitting while readiness is still `UP` | read the failure log for the correlation identifiers in that window | silent for users otherwise; degradation without outage |
| `catalogue.synchronization.last_success.age_seconds` above the agreed freshness window (for example 14 days) | the catalogue is going stale for everyone | run a synchronization; check the last run's failure list | product-visible and only the operator can fix it |

Everything else (login failures, zero-result share, page yield, provider retries,
heap) is for inspection, not interruption. One dashboard page: request rate,
p95 latency and 5xx by route; problem codes; heap and Hikari usage; sync last
outcome and age; identity outcomes. Nothing more.

---

## 12. Revisit triggers

| ID | Revisit … | When … | First move with current components |
|---|---|---|---|
| `RT-1` | A durable metrics/traces store and one dashboard | The first incident that needed history rather than a grep, or `OB-06`'s file exporter proves insufficient | VictoriaMetrics or Prometheus plus Grafana on the same host, zero cost, scraping the collector's `prometheus` exporter or `/actuator/prometheus`; the collector stays the only application-facing endpoint |
| `RT-2` | JDBC spans (`datasource-micrometer`) | `RT-1` exists and a latency question needs DB-versus-app split | add the dependency; spans stay bounded to statement templates |
| `RT-3` | Application log shipping (Loki or the collector `filelog` receiver) | Grepping Docker files becomes the bottleneck, or a second host exists | collector `filelog` receiver into the same `file` exporter first |
| `RT-4` | Keycloak event persistence (`eventsEnabled`, bounded expiration) | A second user, or a security question about logins | realm setting through the documented realm-change procedure; no personal data beyond what Keycloak already stores |
| `RT-5` | Trace sampling and export on dev | `RT-1` exists (raise sampling) or the smoke stops needing receipt (disable) | one environment variable |
| `RT-6` | A synchronization progress endpoint | Runs regularly exceed the operator's patience for `docker compose exec` sessions | `GET /actuator/cataloguesync` returning live counters from the running row instead of zeros |

---

## 13. Things reviewed but intentionally not recommended

- **A hosted observability SaaS or a full stack (Loki, Tempo, Prometheus,
  Grafana, Elastic) now.** Zero recurring budget, one owner, and the approved
  deferral; `RT-1` names the moment and the zero-cost shape.
- **Per-method or per-adapter spans, `@Observed` everywhere.** Nothing in a
  monolith with one request span and synchronous internals would be answered by
  them.
- **Request-body, query-string or principal logging "for diagnosis".** Prohibited
  by policy and unnecessary: the correlation identifier plus the stable code
  identify the request without its content.
- **A `DEGRADED` health status.** Degradation is a product response, already
  modelled and metered.
- **Including Keycloak or the collector in readiness.** Correct as excluded;
  `OB-03`'s optional contributor is deliberately outside the readiness group.
- **Removing `catalogue.releases.result.count`.** Marginal but harmless and
  documented; not worth a change.
- **Client-side (browser) telemetry or analytics.** Not in the MVP; the
  server-side aggregates cover the Brief's signals.
- **Tagging metrics with the OAuth2 error string, the Problem `detail`, or the
  provider Game reference.** Unbounded or unnecessary; the proposals above use
  allowlists and logs instead.
- **Alerting on login failures, zero results or page yield.** Inspection signals;
  interrupting the owner for them would train alert fatigue in a one-person team.
- **Enabling Keycloak user events now.** `RT-4`.
- **A separate access-log format (Tomcat access log).** The filter's one line is
  already the access log and carries the correlation fields Tomcat's would not.

---

## 14. Suggested GitHub issues

Only work that justifies tracking; related findings are grouped.

| # | Proposed title | Findings | Priority | Cost |
|---|---|---|---|---|
| 1 | Make technical-failure logs survive the ECS encoder and keep the root cause | `OB-01`, `OB-08`, `OB-10` | HIGH | small |
| 2 | Record which Games failed and why in synchronization, with page progress | `OB-02` | HIGH | small–medium |
| 3 | Name the failing component in readiness and make identity failures visible | `OB-03`, `OB-04` | MEDIUM | small |
| 4 | Count stable Problem codes and the rating-boundary outcomes | `OB-05`, `OB-07` | MEDIUM | small |
| 5 | Retain emitted telemetry on the host with the collector file exporter and a bounded log window | `OB-06`, `OB-11` | MEDIUM | small |

`OB-09` is already carried by the code review (`CR-19`, `CR-03`); `OB-12` and every
`RT-*` remain triggers.

Suggested order: 1 before the next deployment (it is the difference between having
and not having a cause for any `500` on dev); 2 before the first real
synchronization interval on dev; 3 and 5 before the MVP is declared closed; 4 when
the first product-signal review is planned.

---

## 15. Final assessment

The observability of VideoGame Platform is small on purpose and mostly right: it
protects secrets and people without exception, it keeps every tag bounded, it
models degradation as product state and reserves health for real availability,
and it proves its own correlation and export boundaries on every deployment. The
MVP can be closed on this design once one defect is fixed and one gap is filled:
the structured encoder must actually write the failure log it is asked to write
(`OB-01`), and a synchronization run must leave enough behind to be investigated
(`OB-02`). The remaining recommendations add the `WARN` tier the system lacks
(identity, readiness component, silent degradations), give the product's own
error vocabulary a metric, count the one Brief signal that is currently invisible,
and give the telemetry that already flows a place to survive the night — with no
new platform, no new host cost and no new dimension that could ever identify a
person.

What was verified: every configuration key, instrumentation class, meter and log
statement cited, at `d10bc44`; the ECS collision through the CI log line and the
Spring Boot 4.1 formatter's nested `error` and pair-expansion behaviour; the
absence of any identifier or input in tags, spans and logs; the collector's
exporter configuration. What was assumed: that the collector image's core
distribution ships the `file` exporter (to be verified with `otelcol components`
before `OB-06` is implemented); that Boot's Spring Security observations may or
may not be active (`OB-03` asks to check `/actuator/metrics` first); and that
private-dev traffic stays at single-owner levels, which is what makes 30 MiB of
log "weeks" rather than "hours".
