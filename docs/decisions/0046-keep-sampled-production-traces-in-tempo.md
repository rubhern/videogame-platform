# ADR-0046: Keep sampled production traces in Tempo

- **Status:** Proposed
- **Date:** 2026-11-14
- **Owner:** Ruben Hernandez
- **Scope:** Public production runtime; private-dev trace persistence stays with
  [#230](https://github.com/rubhern/videogame-platform/issues/230)
- **Issue:** [#552](https://github.com/rubhern/videogame-platform/issues/552)
- **Related:** [ADR-0037](0037-align-local-and-private-dev-with-production.md),
  [ADR-0040](0040-aggregate-production-application-logs-in-loki.md)

## Context

Production Grafana holds metrics (Prometheus) and application logs (Loki, ADR-0040), but no
traces. The application already propagates W3C trace context and can export OTLP traces, but
production kept export off, and its Collector had no traces pipeline. Diagnosing a slow route
(for example [#547](https://github.com/rubhern/videogame-platform/issues/547)) therefore stops
at "this route is slow". It cannot show whether the time goes to a SQL statement or to the
application around it.

Keeping traces off was the default, not a decision. ADR-0037 allows the observability topology
to differ between environments.

## Decision

Production keeps a sampled share of request traces in one monolithic Grafana Tempo, queried
from the existing private Grafana:

- **Sampling:** the application traces 10% of requests. Every other request still propagates
  W3C context and keeps its trace ID in logs, but has no stored trace.
- **Spans:**
  - **HTTP:** server spans keep only the bounded route template, method, status and outcome.
    The full URL, which carries query strings such as search terms, is never an attribute.
    Outbound HTTP spans follow the same rule.
  - **JDBC:** one span per statement records the parameterized SQL text and never bound values.
    Result-set operations and connection spans are excluded, and statement observations add
    no metric series. Visitor input must keep reaching SQL as a bound parameter. A test proves
    a search term reaches no exported span.
- **Collector:** a traces pipeline with the same bounded queue and short retry as the logs. An
  absent or refusing Tempo loses spans and never holds the application, metrics or logs.
- **Tempo:**
  - Single tenant, with local storage on its own named volume and 72 hours of retention, well
    inside the seven-day limit for routine logs in
    [data protection](../development/data-protection.md).
  - It joins only the internal telemetry network, publishes no port, and listens for gRPC on
    loopback only.
  - Executable configuration bounds ingestion, trace size and queries.
- **Grafana:** loads the Tempo datasource plugin. A log line that carries a trace ID links to
  its trace, and a span links back to its log lines. Grafana's memory and process ceilings rise
  for the plugin.
- **Upgrader:** adds Tempo and its volume as reviewed additions and starts Tempo as an optional
  service, so a Tempo failure never fences the product. Turning trace export on is a reviewed,
  forward-only setting transition.

Traces are disposable diagnostics. They are excluded from backups and restores, and are not an
audit trail.

## Alternatives considered

- **No traces (status quo):** keeps the smallest footprint, but slow-route diagnosis stays
  guesswork from metrics and logs.
- **Every request traced:** complete, but disk, CPU and Collector load grow with traffic for
  little extra diagnostic value at this scale.
- **Tail sampling in the Collector:** keeps every slow or failed trace, but it needs the
  contrib Collector distribution and buffers whole traces in memory. It is a later step if 10%
  proves too thin.
- **Jaeger or Zipkin:** each would be another query UI and datasource beside Grafana's
  existing Loki and Prometheus correlation.
- **Hosted tracing:** a new contracted service with personal-data export.

## Consequences

- **Envelope:** the monitoring envelope grows by 320 MiB for Tempo plus 32 MiB for Grafana, and
  by Tempo's disk use. [Compose](../../deploy/public-production/compose.observability.yaml) and
  the [Tempo configuration](../../deploy/public-production/observability/tempo.yaml) own the
  exact bounds.
- **Coverage:** a slow request has about a one-in-ten chance of a stored trace, so diagnosis may
  need several occurrences. Spans can be lost during Collector or Tempo outages, overload or
  restarts.
- **SQL text:** statement text becomes visible to whoever can open production Grafana, which is
  the owner only. A statement that ever concatenated visitor input into SQL would leak it, so
  binding stays mandatory.

## Revisit when

- 10% sampling repeatedly misses the slow requests being diagnosed: consider tail sampling.
- Tempo's disk or memory use crowds the VPS reserve.
- Private-dev adopts persistent traces (#230), which may align the two topologies.
