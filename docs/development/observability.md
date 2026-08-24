# Backend observability

- **Status:** Active walking-skeleton implementation
- **Last verified:** 2026-08-23
- **Backend version:** `0.7.2-SNAPSHOT`
- **Decision:** [ADR-0009](../decisions/0009-use-opentelemetry-compatible-instrumentation.md)
- **Architecture:** [Learning MVP solution architecture](../architecture/mvp-solution-architecture.md)

This guide records the operational inspection boundary implemented for issue #23 and
the bounded release-read telemetry added by issue #25.
It covers safe health probes, build metadata, request correlation, structured logs,
baseline metrics, W3C trace context, and optional OTLP export. It does not provision a
collector, OCI integration, dashboard, alert, or remote environment.

## Implemented boundary

The backend uses Spring Boot Actuator, Micrometer Observation, Micrometer Tracing,
OpenTelemetry, and OTLP exporters managed by Spring Boot 4.1.0. Product code remains
independent of the telemetry backend. The application starts and serves requests when
no collector exists because trace and metric export are disabled by default and no
telemetry component participates in readiness.

| Signal | Implemented evidence |
|---|---|
| Health | Aggregate health plus explicit liveness and readiness groups with hidden component details |
| Version | Maven build name, version, time, and safe source revision at `/actuator/info` |
| Logs | Human-readable local access logs; ECS JSON through the `structured` profile |
| Correlation | Validated or generated `X-Correlation-ID`, returned to the caller and placed in log context |
| Traces | W3C `traceparent` consumption/production and Micrometer-to-OpenTelemetry tracing |
| Metrics | HTTP server, release-query, JVM/runtime, process, system, JDBC, and Hikari pool meters |
| Export | Independently enabled OTLP/HTTP trace and metric export to replaceable endpoints |

Issue #23 originally incremented the backend from `0.1.0-SNAPSHOT` to
`0.2.0-SNAPSHOT`. Issue #25 subsequently adds a compatible product capability and
increments the same Maven reactor to `0.3.0-SNAPSHOT` under the pre-1.0 Semantic
Versioning policy. The later composition-root correction increments the backend
patch to `0.3.1-SNAPSHOT`. The frontend and isolated IGDB PoC do not change.
The PostgreSQL JDBC security remediation then increments the backend patch to
`0.3.2-SNAPSHOT`. The later release-query builder refactor increments the backend
patch to `0.3.3-SNAPSHOT`; neither correction changes the observability contract.
Issue #26 advances the backend reactor to `0.4.0-SNAPSHOT` for compatible combined
frontend packaging without changing these observability semantics.
Issue #40 advances it to `0.5.0-SNAPSHOT` for compatible OIDC BFF session behaviour,
again without changing the observability contract.
Issue #27 advances it to `0.6.0-SNAPSHOT` for compatible production-image delivery,
and the optional complete local Compose topology advances it to `0.7.0-SNAPSHOT` for
compatible containerized runtime configuration. Neither changes the observability
contract.
Issue #79 then increments the backend patch to `0.7.2-SNAPSHOT` for compatible,
single-boundary technical-failure logging and correlation hardening; the public error
contract remains unchanged.

## Health semantics

| Endpoint | Included contributors | Meaning |
|---|---|---|
| `/actuator/health/liveness` | `livenessState` only | The process is viable and should not be restarted |
| `/actuator/health/readiness` | `readinessState`, `db`, `catalogueStore` | The application can connect with the runtime database role and query the migrated catalogue schema |
| `/actuator/health` | All registered contributors | Aggregate diagnostic status with group names but no component details |

`catalogueStore` executes a bounded `EXISTS` query against
`catalogue.catalogue_publication`. An empty table is `UP`: no current publication is a
valid business state that a later catalogue API reports as `CATALOGUE_NOT_READY`.
Missing migrations, insufficient runtime privileges, or a database failure make
readiness `DOWN`. Startup with Flyway enabled still fails before serving traffic when
migration validation or execution fails.

IGDB, Keycloak, and telemetry exporters are deliberately absent from readiness. IGDB
is not a request-path dependency, Keycloak is required for new login rather than
public-read readiness, and a telemetry outage must not block product traffic.
Liveness must never gain database, provider, identity, or collector checks because
that would turn dependency failures into restart loops.

Every health response uses `management.endpoint.health.show-details=never`. Database
addresses, exceptions, credentials, schema names, and internal component details are
therefore absent from the HTTP response.

## Version and source revision

The Spring Boot Maven Plugin generates `META-INF/build-info.properties`. Actuator
returns its safe fields under `build`:

```json
{
  "build": {
    "name": "VideoGame Platform Backend",
    "version": "0.7.2-SNAPSHOT",
    "sourceRevision": "local-development"
  }
}
```

Build time is also present and is omitted from the example because it changes on each
build. Local builds use the explicit `local-development` fallback. A CI or immutable
image build must identify its source commit without embedding credentials:

```bash
./mvnw -Dsource.revision="$GITHUB_SHA" clean verify
```

`management.info.env.enabled=false` prevents arbitrary `info.*` environment values
from being exposed. Only generated build information is enabled.

## Request correlation and safe access logs

The request filter accepts `X-Correlation-ID` only when it is 1–64 characters from
the allowlisted alphanumeric, dot, underscore, and hyphen set; otherwise it generates
a UUID. The selected value is returned in the response header and stored in MDC for
the duration of the request. A previous MDC value is restored, or the request value
is removed, from a nested `finally` block even when processing throws.

Each request produces one access log containing only:

- correlation, trace, and span identifiers;
- HTTP method, matched route template, status, and bounded outcome;
- duration in milliseconds.

If processing escapes with an exception, the filter rethrows the original exception
unchanged and records the access event as `500` / `SERVER_ERROR`. It does not include
the exception type or message. Handled application errors use the final HTTP response
status produced by Spring MVC.

It never records query strings, concrete unmatched paths, request or response bodies,
headers, cookies, tokens, credentials, provider payloads, user IDs, or exception
messages. The HTTP observation retains the framework's bounded low-cardinality tags
and removes its concrete high-cardinality URL. Metrics therefore use route templates
such as `/actuator/health/**`, not caller-provided paths.

### Human-readable local logging

Plain console output is the default for interactive local development. Spring Boot's
standard Logback pattern remains in control; `logging.pattern.level=%5p %kvp` makes
the same SLF4J key-value pairs visible without duplicating them in the message. A
simplified access line is:

```text
INFO [correlationId,traceId,spanId] GET /actuator/metrics/{requiredMetricName} -> 200 SUCCESS in 2 ms [cid=4b282dd5-e5d6-4e17-bd2f-f9d0c446ff2b]
```

The committed level policy is deliberately quiet outside application code:

| Logger | Default | Temporary override example |
|---|---|---|
| `root` | `INFO` | `LOGGING_LEVEL_ROOT=WARN` |
| `com.videogameplatform` | `INFO` | `LOGGING_LEVEL_COM_VIDEOGAMEPLATFORM=DEBUG` |

For example, enable application DEBUG logs for one local run without enabling noisy
Spring, Tomcat, Hikari, Hibernate, or Flyway DEBUG output:

```bash
LOGGING_LEVEL_COM_VIDEOGAMEPLATFORM=DEBUG ./mvnw -pl backend spring-boot:run
```

Do not use `root=DEBUG` as the normal development configuration. Spring Boot
configuration, command-line properties, and environment variables can still override
either level when a focused diagnosis requires it.

### ECS structured logging

Maintained or machine-ingested runs activate ECS JSON. Spring Boot's structured
encoder includes both MDC and SLF4J fluent key-value pairs, so the same event contains
`correlationId`, `traceId`, `spanId`, the nested `http` fields, and `duration_ms` as
queryable JSON members rather than interpolated message text:

```bash
SPRING_PROFILES_ACTIVE=structured ./mvnw -pl backend spring-boot:run
```

The structured profile inherits `root=INFO` and
`com.videogameplatform=INFO`. Standard Spring configuration may override them, but
INFO remains the maintained-environment default.

Structured stack traces are bounded to 2048 characters and 20 throwable levels. This
limits ingestion volume but is not a substitute for avoiding sensitive exception
messages. Application logging must continue to use allowlisted operational values and
stable error codes.

`correlationId` is the validated application/request identifier returned to the
caller. `traceId` identifies the distributed trace, and `spanId` identifies the
current operation within that trace. They are related in each access event but serve
different purposes; none is used as a metric label.

## Tracing and sampling

The application consumes and propagates W3C Trace Context only. Micrometer creates an
HTTP server observation and makes `traceId` and `spanId` available to the logging
context. The current local/private-`dev` baseline samples 100% of root traces
(`management.tracing.sampling.probability=1.0`) because traffic and export cost are
minimal while complete diagnostic evidence is valuable. The probability remains
configurable through `TELEMETRY_TRACING_SAMPLING_PROBABILITY` and can be reduced
without changing code when measured volume, cost, or storage justifies it.

This is probabilistic head sampling: the decision is made when a trace starts. At a
probability below `1.0`, a request that later becomes slow or fails may already have
been excluded. Tail sampling can decide after the outcome is known and retain all
errors or slow traces, but it requires additional Collector state, buffering, memory,
and routing. It is deliberately deferred to issue
[#50](https://github.com/rubhern/videogame-platform/issues/50), after the telemetry
pipeline in #43 and private-`dev` validation in #36 provide real evidence.

Trace sampling does not sample metrics. Every instrumented request continues to
contribute to the configured HTTP metrics independently of whether its trace is
retained or exported.

## Metrics

Spring Boot Actuator and Micrometer auto-configuration provide most baseline meters;
the application does not manually recreate HTTP, JVM, process, system, JDBC, or
HikariCP instrumentation. `/actuator/metrics` exposes the instance's in-memory
diagnostic registry. The initial baseline includes:

- `http.server.requests` with method, route template, status, outcome, and exception
  class tags;
- `jvm.*`, `process.*`, and `system.*` runtime meters;
- `jdbc.connections.*` and `hikaricp.connections.*` database-pool meters.
- `catalogue.releases.requests` and `catalogue.releases.latency`, tagged only with
  the closed release view/outcome values;
- `catalogue.releases.result.count`, tagged only with the release view and recording
  returned items for `200` responses; conditional `304` responses record no sample;
- `catalogue.releases.failures`, tagged only with a stable reviewed error code.

Requests are not probabilistically sampled out of these metrics: every relevant
instrumented request updates the HTTP meter. The local registry lives in the process
and resets when that application instance restarts. Historical retention, aggregation
across instances, and durable querying belong to the external metrics backend once a
telemetry pipeline exists.

Do not add correlation IDs, trace IDs, user IDs, game IDs, provider entity IDs, raw
paths, error messages, or unbounded text as metric tags. Future business observations
must use a small closed set of operation, outcome, and stable error-code values.

## OTLP configuration

All values are read at startup and require a restart to change.

| Environment variable | Safe default | Purpose | Secret |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | Empty; plain local logs | Set to `structured` for ECS JSON console logs | No |
| `TELEMETRY_OTLP_TRACES_ENABLED` | `false` | Enable OTLP/HTTP trace export | No |
| `TELEMETRY_OTLP_TRACES_ENDPOINT` | `http://localhost:4318/v1/traces` | Replaceable collector trace endpoint | No |
| `TELEMETRY_OTLP_METRICS_ENABLED` | `false` | Enable OTLP/HTTP metric export | No |
| `TELEMETRY_OTLP_METRICS_ENDPOINT` | `http://localhost:4318/v1/metrics` | Replaceable collector metric endpoint | No |
| `TELEMETRY_OTLP_METRICS_STEP` | `60s` | Metric export interval | No |
| `TELEMETRY_TRACING_SAMPLING_PROBABILITY` | `1.0` | Root-trace sampling probability from `0.0` to `1.0` | No |

For a separately managed local collector that accepts OTLP/HTTP:

```bash
SPRING_PROFILES_ACTIVE=structured \
TELEMETRY_OTLP_TRACES_ENABLED=true \
TELEMETRY_OTLP_METRICS_ENABLED=true \
./mvnw -pl backend spring-boot:run
```

Change either endpoint independently to use another compatible backend. Collector
authentication headers are deliberately not part of the committed walking-skeleton
configuration. A maintained environment must add them through a reviewed protected
secret mechanism rather than command-line arguments, source files, logs, or URLs.

`http://localhost:4318/v1/traces` and
`http://localhost:4318/v1/metrics` are configurable export destinations. They are not
endpoints served by this Spring Boot application. Nothing needs to listen on port
4318 while both exporters are disabled; enabling an exporter requires a separately
managed compatible Collector or backend at its configured destination.

## Validation

Docker must be running. The stable backend gate is:

```bash
./mvnw clean verify
```

`BackendStartupTest` starts the application against PostgreSQL 18 and proves:

- liveness and readiness composition with hidden details;
- build version and safe source-revision output;
- HTTP, JVM, and database-pool metric availability;
- route-template metric labels;
- the effective 100% root-trace sampling baseline;
- incoming W3C trace propagation into ECS logs;
- response correlation and absence of representative tokens, cookies, query secrets,
  user-like IDs, correlation IDs, and trace IDs from metrics.

`CorrelationIdFilterTest` proves valid and invalid correlation-ID handling, response
headers, MDC restoration and cleanup, route-template-only access fields, a single
event on exceptional completion, and safe `500` / `SERVER_ERROR` classification.
`CatalogueStoreHealthIndicatorTest` proves an unmigrated database produces `DOWN`
without publishing exception details. The tracked Postman collection covers the same
read-only Actuator surface for manual local inspection.

### Manual console verification

With the local dependencies and ignored `backend/.env` loaded, run the application once in
each console mode and issue the same request:

```bash
# Human-readable local console
APPLICATION_FLYWAY_ENABLED=true ./mvnw -pl backend spring-boot:run

# ECS JSON console
APPLICATION_FLYWAY_ENABLED=true \
SPRING_PROFILES_ACTIVE=structured \
./mvnw -pl backend spring-boot:run
```

```bash
curl --include \
  --header 'X-Correlation-ID: manual-observability-check' \
  --header 'traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01' \
  http://localhost:8080/actuator/health
```

The first run must show readable HTTP key-value pairs and correlation context. The
second must produce one valid ECS JSON event with the same safe fields. Both must
return `X-Correlation-ID: manual-observability-check`, retain the incoming trace ID,
and avoid the raw request URI, query, bodies, credentials, cookies, or arbitrary
headers.

This procedure was executed on 2026-08-10 against the supported local PostgreSQL 18
topology. The text run showed one route-template access line with `GET`, `200`,
`SUCCESS`, duration, correlation ID, trace ID, and span ID. The structured run emitted
one valid ECS JSON object with the same values under `correlationId`, `traceId`,
`spanId`, `http`, and `duration_ms`; both responses returned the effective correlation
header. Exporters remained disabled and no service on port 4318 was required.

## Dependency and recovery assessment

Spring Boot manages `spring-boot-starter-opentelemetry` 4.1.0, Micrometer Tracing
1.7.0, OpenTelemetry Java 1.62.0, and `micrometer-registry-otlp` 1.17.0 as one
compatible family. Micrometer and OpenTelemetry use Apache License 2.0. The additions
introduce no provider SDK, paid service, database schema, credential, or mandatory
network dependency.

The main risks are telemetry leakage, cardinality growth, exporter queue/network
overhead, and evolving semantic conventions. Full tracing is appropriate at current
traffic but would increase export and storage volume once a pipeline and workload
exist. Allowlisting, route templates, configurable head sampling,
disabled-by-default exporters, bounded timeouts, automated negative assertions, and
Spring-managed versions mitigate current risks. The walking-skeleton CI gate now
adds Spotless, JaCoCo/SonarQube Cloud, Gitleaks, dependency review, and CodeQL without
granting telemetry credentials.

Rollback requires only the previous backend artefact and configuration because this
change has no migration or persistent state. A forward fix can independently disable
either exporter. A collector failure is diagnosed from exporter logs and must not
change health readiness or product responses.

## Current limitations

- No collector or OCI telemetry integration is provisioned.
- Application logs go to stdout; OTLP log export is not enabled.
- No dashboard, alert, SLO, retention policy, or remote sampling policy exists yet;
  head-versus-tail evaluation is deferred to #50.
- No product or business-operation metric exists before its owning use case is
  implemented.
- Database statement tracing is not enabled; the baseline exposes pool metrics and
  health without adding raw SQL to telemetry.
- A deployed collector, remote telemetry retention, dashboards and private-`dev`
  smoke evidence remain later platform work; issue #34 already proves the initial
  topology budget and hosted walking-skeleton compatibility.
