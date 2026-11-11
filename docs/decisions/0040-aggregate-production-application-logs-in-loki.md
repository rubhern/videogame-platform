# ADR-0040: Aggregate production application logs in Loki

- **Status:** Accepted
- **Date:** 2026-11-11
- **Owner:** Ruben Hernandez
- **Scope:** Public production runtime; private-dev log collection is unchanged
- **Related:** [ADR-0021](0021-collect-private-dev-logs-with-alloy-and-loki.md), [ADR-0025](0025-host-public-production-on-a-deferred-single-vps.md), [ADR-0037](0037-align-local-and-private-dev-with-production.md)

## Context

Production Grafana held metrics only. A failed visitor-feedback send showed the cost: the
metrics confirmed `FEEDBACK_UNAVAILABLE`, but telling a disabled channel from a relay
refusal needed the application's own warning, which only existed in Docker's local log on
the VPS. The owner decided that production diagnostics belong in the existing,
private Grafana.

Production rules differ from private-dev. Only the edge may publish ports. Every service
is read-only and unprivileged, and the in-place upgrader refuses any runtime difference it
has no reviewed rule for. The private-dev path of ADR-0021 (Docker syslog over host-loopback
UDP into Alloy) would need a published receiver port, a different logging driver on the
application, and one more component. Each of those is a production refusal by design.

## Decision

The application exports its own log events over OTLP to the existing OpenTelemetry
Collector. The Collector forwards them to one monolithic Loki process, and Grafana
queries Loki through a provisioned datasource. In detail:

- **Application:** the OpenTelemetry Logback appender sends the same events the console
  prints, plus the bounded correlation identifier. Export is off by default and enabled
  only in production. The console remains the primary stream. A bounded queue drops
  records when the Collector is away and never holds a request.
- **Collector:** a logs pipeline with a bounded queue and short retry. A refusing or
  absent Loki loses log records; it never affects the metrics pipeline.
- **Loki:** single tenant, filesystem storage on its own named volume, with six days of
  retention, so asynchronous deletion completes within the seven-day limit for routine logs
  in [data protection](../development/data-protection.md). It joins only the internal telemetry network, publishes no
  port, has no Docker access and reads no host log. Executable configuration bounds
  ingestion, streams, lines, queries and memory.
- **Grafana:** it loads the Loki datasource plugin, the only plugin newly enabled, and its
  process ceiling rises to absorb the plugin's threads.
- **Upgrader:** it adds the service and its volume as reviewed additions and starts Loki
  as an optional service, so a Loki failure never fences the product.

Log history is disposable diagnostics. It is excluded from backups and restores and is
not an audit trail.

## Alternatives considered

- **ADR-0021's syslog → Alloy path:** keeps private-dev parity, but needs a published UDP
  port, a logging-driver change on the application and another component, all of which
  the production guards refuse.
- **Docker socket or log-file discovery:** grants host-level control or broad host reads.
- **Logs on the VPS only:** the status quo; it keeps diagnosis outside Grafana.
- **A bounded feedback-outcome metric only:** smaller, but answers one question, not
  general diagnosis.
- **Hosted logging:** a new contracted service with personal-data export.

## Consequences

Production and private-dev now reach Loki by different transports. Private-dev keeps
ADR-0021 until a separate decision aligns it. Log records can be lost during Collector or
Loki outages, overload or restarts, and events emitted before Logback starts stay only in
Docker's local log. Source privacy rules remain essential: aggregation cannot scrub unsafe
exception text, so the existing "no addresses, secrets or message content in logs" rule
still governs every log statement.

The monitoring envelope grows by 384 MiB of memory and Loki's disk use. Compose and the
[Loki configuration](../../deploy/public-production/observability/loki.yaml) own the exact
bounds. The [platform design](../architecture/deployment/mvp-platform-and-delivery.md)
owns topology and capacity. [Observability](../development/observability.md#production-log-exploration)
owns how to query, and the [production procedure](../../deploy/public-production/README.md#application-logs)
owns rollout and disk checks.
