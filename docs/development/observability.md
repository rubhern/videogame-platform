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
- `catalogue.game.details{eligibility,aggregate}` counts successful detail reads,
  including conditional responses. Eligibility uses the six contract reason codes;
  aggregate uses only `available`/`unavailable`. This distinguishes a degraded rating
  read from a healthy empty aggregate even when the public response remains HTTP 200.
- Synchronization run meters are `catalogue.synchronization.run{outcome}`,
  `.run.duration{outcome}` and `.run.records{kind}`. The record kinds count
  inspected release dates, created/updated/unchanged Games and
  Releases, deferred Games and failed Games. Deferral is import policy, not failure.
- Provider meters use `catalogue.synchronization.provider.request{operation,outcome}`,
  `.request.duration{operation}`, `.retry{operation}` and `.mapping.failure{reason}`.
  Operations are the closed `window`, `works`, `release_dates` vocabulary.
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

| Event | Level | Bounded context |
|---|---|---|
| Started | `INFO` | run, window, provider page size |
| Progress checkpoint | `INFO` | run, phase, page, game position, elapsed, Game/release/provider counters |
| Game reconciled or deferred | `DEBUG` | run, page, position, result |
| Game failed | `WARN` for the first 20 of a run, then `DEBUG` | run, page, position, stage, reason, failed count, identity (below) |
| Run failure | `WARN` | run, phase, page, stage, reason, exception class when unexpected |
| Finished | `INFO` succeeded, `WARN` partial, `ERROR` failed | outcome, stable code, window, pages, elapsed, all counters, failures tallied by `stage/reason` |
| Skipped | `INFO` | `SYNCHRONIZATION_DISABLED` or `SYNCHRONIZATION_ALREADY_RUNNING`, window |
| Rejected command | `INFO` | `INVALID_SYNCHRONIZATION_WINDOW`, never the raw input |
| IGDB retry | `DEBUG` | endpoint name, retry, provider failure code, backoff |

While events keep arriving, a checkpoint is written at least every 60 seconds and at
provider-page completion, but never less than 10 seconds after the previous one.
Spacing uses a monotonic source, not the product clock. Stages are `provider_page`,
`provider_game`, `validation`, `reconciliation`, `persistence` and `run`. Reasons come
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
collector files linked there own executable limits. Its bounded synthetic OTLP check
owns repository/host receipt validation independent of application deployment.
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
