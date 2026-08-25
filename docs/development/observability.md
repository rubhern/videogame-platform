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

Actuator runs on the separate management port. Local direct execution binds that
port to loopback; container profiles bind it only inside the private container
network and do not publish it on the product port. Routine liveness/readiness probes
do not emit application access logs; their status remains available from Actuator.
