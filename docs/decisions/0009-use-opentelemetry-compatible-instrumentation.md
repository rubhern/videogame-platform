# ADR-0009: Use OpenTelemetry-compatible instrumentation

- **Status:** Accepted
- **Date:** 2026-08-03
- **Owner:** Ruben Hernandez
- **Scope:** Private, non-commercial learning MVP

## Context

The application needs enough logs, metrics and traces to diagnose a vertical slice,
but the instrumentation contract should not depend on one hosting or visualization
backend. A full self-hosted observability stack would compete with the application on
the constrained dev VM.

## Decision

- Emit structured logs and instrument requests, database/provider work and scheduled
  synchronization with OpenTelemetry-compatible APIs and semantic conventions.
- Propagate correlation and trace context across supported boundaries.
- Keep secrets, tokens and personal data out of telemetry.
- Use bounded metric labels; user input and request/game/release/user/correlation IDs
  must not become metric dimensions.
- Configure exporters at the platform boundary; observability backends are optional
  and replaceable.
- Start with runtime health, error, latency, resource and synchronization signals
  needed to operate the current journey.

Signal names and local inspection belong to
[observability](../development/observability.md); platform retention and failure
behavior belong to the [platform design](../architecture/deployment/mvp-platform-and-delivery.md).

## Alternatives considered

- **OCI-specific instrumentation throughout:** rejected because it creates code-level
  hosting lock-in.
- **Self-hosted Prometheus/Loki/Tempo/Grafana:** deferred until measured diagnostic
  value justifies the resource and maintenance cost.
- **Logs only or defer observability:** rejected because they provide insufficient
  evidence for failures and performance.

## Consequences

Instrumentation remains portable and can support multiple backends. It still adds
code, storage and review cost, and trace/metric volume must be bounded deliberately.

## Reconsider when

Add a backend or broader signals only for a concrete diagnostic or operational need
within the zero-cost resource envelope.
