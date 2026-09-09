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
- Use the `structured` Spring profile for ECS JSON console logs.
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
  totals and aggregate counters. Structured logs contain outcome, stable code and
  counters, never raw payloads or provider identities. Cursors are not metric labels.
- No process-local publication-age gauge claims durable catalogue freshness.
  Unchanged evidence is not rewritten merely to change its timestamp.
- Never use user, game, release, request, correlation, URL, search, provider, or raw
  input values as metric tags.
- Propagate W3C trace context. OTLP trace and metric export remains disabled until an
  explicit endpoint is configured.
- Telemetry failure must not break product requests or readiness.

Never log or export credentials, cookies, CSRF values, authorization codes, OAuth
tokens, personal rating ownership, raw provider payloads, database URLs with
credentials, or arbitrary exception text. Error responses expose stable codes and a
correlation identifier, never stack traces or SQL.

The [platform design](../architecture/deployment/mvp-platform-and-delivery.md) owns
remote telemetry topology, retention, and privacy. Product-specific meters should be
added only when they answer an operational or product decision and have a bounded
cardinality review.

`POST /actuator/cataloguesync` is the internal operator command that starts one
complete synchronization of the required inclusive `from`/`to` interval; the
matching `GET` reports its latest recorded result. Paging is internal and does not
limit the total Games processed. [ADR-0017](../decisions/0017-discover-catalogue-members-automatically-from-igdb.md)
owns reconciliation and in-call paging semantics. Synchronization is never
scheduled and is never reachable from the product API — it is absent from the product
OpenAPI contract for the same reason — and it inherits the management-port boundary
below, so no visitor request can trigger a provider call. Without configured IGDB
credentials the command reports `SYNCHRONIZATION_DISABLED` and changes nothing.

Actuator runs on the separate management port with its own security boundary: the
endpoints are open on that port because it is already private, they hold no
cookie-authenticated session, and any state-changing request that a browser initiated
from another site is rejected. Local direct execution binds that port to loopback;
container profiles bind it only inside the private container network and do not
publish it on the product port. Routine liveness/readiness probes do not emit
application access logs; their status remains available from Actuator.
