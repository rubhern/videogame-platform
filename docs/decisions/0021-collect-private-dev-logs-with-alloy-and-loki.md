# ADR-0021: Collect private-dev logs with Alloy and Loki

- **Status:** Proposed
- **Date:** 2026-10-04
- **Owner:** Ruben Hernandez
- **Scope:** Post-MVP private dev; implementation prepared for owner review in #159

## Context

The owner needs searchable application diagnostics alongside the existing Grafana
metrics on one private 8 GB Linux host, without recurring cost. ECS console logs
already carry the required event and correlation evidence. Docker's current local
logging driver stores rotated binary files; a file tailer cannot read them as JSON.
ADR-0009 keeps storage replaceable and permits a backend for a concrete diagnostic
need. The owner has requested this bounded logging slice; host rollout and acceptance
remain separate from repository implementation.

## Decision proposed for review

Use one Grafana Alloy receiver and one monolithic Loki process with local filesystem
storage, queried through the existing authenticated Grafana Explore/Logs Drilldown.
Do not replace the OpenTelemetry Collector or Prometheus metrics path.

Forward only application stdout/stderr using Docker's per-container RFC5424 syslog
driver over host-loopback UDP. Preserve each admitted ECS message byte for byte.
Admit complete JSON within the line bound; Docker can split long console lines, so
Alloy drops oversized/malformed fragments instead of reconstructing partial events.
Plain pre-logger JVM output remains available only through Docker inspection. Keep non-blocking
bounded delivery and Docker's bounded dual-logging cache. Alloy accepts only the
fixed application tag and indexes only fixed environment/service labels. Identifiers
stay in the body and are parsed at query time. No host, database, identity, migration
or arbitrary container discovery is enabled.

Avoid Docker daemon access. A read-only Unix socket mount does not make API requests
read-only: access can create privileged containers and is effectively host-root
control. A socket proxy would add a privileged component and still disclose metadata
and logs; broad Docker storage mounts would grant access beyond the chosen service.
Neither is necessary for this slice.

Keep the receiver on IPv4 loopback only and Loki on a dedicated internal network
shared with Alloy and Grafana. Loki is single tenant without application-level auth;
network membership and the host owner are its trust boundary. Local processes can
inject or spoof allowed syslog tags; these logs are diagnostic evidence, not a trusted
audit trail. No receiver UI/API, Loki port, public route or cloud integration is added.

Bound retention, ingestion, lines, streams, query work, retries and container
resources in executable configuration. Log history is disposable and excluded from
PostgreSQL backups. Retention is asynchronous, not a disk quota; operator capacity
checks and stop/recovery procedures are required.

## Alternatives considered

- **Docker discovery with a socket:** convenient automatic recreation tracking, but
  unnecessary host privilege and collection scope for one application.
- **Read-only Docker JSON files:** requires changing the driver, broad host reads or
  a host discovery helper, and coupling to Docker-managed file layout.
- **TCP syslog:** stronger delivery while connected, but initialization depends on a
  reachable receiver; that conflicts with independent application startup.
- **OTLP application logs:** requires application exporter/configuration changes and
  does not collect its existing console stream; unnecessary scope here.
- **CLI logs only:** retained as a bounded inspection fallback, but lacks central
  search and visualization.
- **Distributed Loki/cloud logging/custom dashboard:** no demonstrated need or
  resource/cost justification.

## Consequences and reconsideration

UDP and bounded queues can lose events during outage, overload, large messages or
restart. No backfill/replay from Docker history is promised. Docker cache writes can
also fail when the remote driver fails. Source privacy rules remain essential;
aggregation cannot reliably scrub arbitrary unsafe exception text or secrets.

The pipeline adds a small resource envelope and two components to maintain. Prove
real host capacity, private reachability, recreation and eventual retention deletion
before acceptance. Reconsider transport only for observed losses that prevent
private-dev diagnosis; reconsider quotas/retention when measured disk use approaches
the operator budget. Durable audit logging, HA and broader collection require a new
owner decision.

The [platform design](../architecture/deployment/mvp-platform-and-delivery.md) owns
current topology and storage policy; [observability](../development/observability.md#private-dev-log-exploration)
owns interpretation; the [operator procedure](../../deploy/private-dev/README.md#log-aggregation-and-exploration)
owns rollout and recovery. No deployment is implied by this proposed ADR.

## Technical references

- [Alloy syslog receiver](https://grafana.com/docs/alloy/latest/reference/components/loki/loki.source.syslog/)
- [Docker daemon attack surface](https://docs.docker.com/engine/security/#docker-daemon-attack-surface)
- [Docker syslog driver](https://docs.docker.com/engine/logging/drivers/syslog/)
- [Docker dual-logging limitations](https://docs.docker.com/engine/logging/dual-logging/)
- [Loki filesystem storage and disk-full boundary](https://grafana.com/docs/loki/latest/operations/storage/filesystem/)
- [Loki retention](https://grafana.com/docs/loki/latest/operations/storage/retention/)
