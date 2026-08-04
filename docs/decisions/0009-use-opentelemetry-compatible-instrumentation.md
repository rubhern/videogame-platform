# ADR-0009: Use OpenTelemetry-compatible instrumentation

- **Status:** Accepted
- **Date:** 2026-08-03
- **Owner:** Ruben Hernandez
- **Scope:** Private, non-commercial learning MVP
- **Related architecture:** [Learning MVP solution architecture](../architecture/mvp-solution-architecture.md)
- **Related platform:** [Learning MVP platform and delivery design](../architecture/deployment/mvp-platform-and-delivery.md)

## Context

The approved architecture requires structured logs, metrics, trace correlation,
health, and business-operation outcomes from the first slice. The zero-cost dev
platform provides OCI Logging, Monitoring, and a bounded APM allowance, but application
code should not depend on one hosting vendor.

[OpenTelemetry](https://opentelemetry.io/docs/) is a vendor-neutral observability
framework for traces, metrics, and logs. OCI documents an
[OpenTelemetry integration with OCI APM and Logging](https://docs.oracle.com/en/learn/oci-apm-with-opentelemetry/).

## Decision

Instrument the application with OpenTelemetry-compatible APIs, semantic conventions,
and W3C trace context where supported by the selected implementation stack.

Initially:

- emit structured application logs with correlation, trace, and span identifiers;
- measure bounded request, dependency, rating-command, and catalogue-synchronization
  signals;
- propagate trace context through HTTP and meaningful internal boundaries;
- expose liveness, readiness, version, and migration/synchronization diagnostics;
- export local telemetry to console or a lightweight local backend;
- export `dev` telemetry through an OpenTelemetry collector/exporter to OCI Logging,
  Monitoring, and the Always Free APM allowance where useful.

Keep the domain independent from telemetry products. Domain/application code may
express meaningful operation outcomes through internal abstractions, while adapters
perform vendor export. Do not add high-cardinality identifiers or sensitive data to
metric labels, traces, or logs.

OCI is the initial backend, not the application contract. Retention and ingestion are
bounded to the zero-cost allowance; a missing telemetry backend MUST NOT make product
reads unavailable.

## Alternatives considered

### OCI-specific SDK instrumentation throughout the application

It provides direct feature access but creates hosting lock-in and leaks infrastructure
into application/domain code.

### Self-hosted Prometheus, Loki, Tempo, and Grafana

This offers rich hands-on tooling but adds several always-on components to a
resource-constrained single VM. It may be used later as a bounded experiment, not the
initial operational dependency.

### Logs only

This is simpler but does not satisfy the approved trace correlation, health, and
business-operation visibility needed to diagnose the complete journey.

### Defer observability until production

This would make early failure modes and deployment acceptance opaque and contradicts
the approved engineering gate.

## Consequences

### Positive

- Instrumentation is portable across OCI and future observability backends.
- Standard context makes application, identity, database, and synchronization
  diagnosis more coherent.
- The project learns current enterprise observability without self-hosting a large
  stack initially.

### Negative

- OpenTelemetry SDK/collector configuration adds implementation and operational work.
- OCI's free APM allowance is small and requires sampling/retention discipline.
- Semantic conventions and exporters evolve and require version management.

## Risks and mitigations

- **Sensitive telemetry:** allowlist fields, redact secrets/tokens/personal data, and
  test representative failures.
- **Cardinality/cost growth:** use route templates and bounded outcome labels; set
  sampling and retention limits.
- **Backend outage:** buffer only within safe bounds, fail open for product traffic,
  and expose exporter health separately from readiness.
- **Instrumentation coupling:** isolate exporters and avoid OCI SDKs in domain and
  application rules.
- **Noise:** start with signals needed to answer current operational questions and add
  more only from evidence.

## Follow-up actions

- Select supported SDK and collector versions in the technology baseline.
- Define the initial attribute allowlist, sampling, redaction, and retention policy.
- Add tests for correlation propagation and absence of secrets/personal data.
- Create a minimal dashboard for version, request failures/latency, rating outcomes,
  catalogue freshness/synchronization, and dependency health.
- Revisit the backend if OCI limits or a hosting migration justify another exporter.
