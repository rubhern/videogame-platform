# 📝 Architecture Decisions

| Area | Decision                                                                   | Rationale |
|------|----------------------------------------------------------------------------|-----------|
| **Overall style** | *C4-model* micro-service architecture                                      | Clear separation of concerns; scales organisationally (independent teams) and technically (independent deployables). |
| **Runtime platform** | *Kubernetes* (managed)                                                     | Rolling updates, self-healing, built-in service discovery and HPA suit game-traffic spikes. |
| **API ingress** | *Kong Ingress / NGINX* + *Edge CDN*                                        | Centralised auth, rate-limiting and coarse caching at the edge reduce P99 latency worldwide. |
| **AuthN / AuthZ** | *Keycloak* with OIDC                                                       | Open-source, supports social log-ins; avoids bespoke auth code in every service. |
| **Service framework** | *Spring Boot* (Java 21)                                                    | Mature ecosystem, first-class observability hooks, developers already fluent. |
| **Inter-service sync** | *REST / JSON* via Gateway                                                  | Simpler than gRPC for external integration; benefits from Kong plugins (caching, tracing). |
| **Async messaging** | *Apache Kafka*                                                             | High-throughput fan-out (ratings-created, reviews-created); guarantees ordering for leaderboards. |
| **Databases** | *PostgreSQL* (users, games, ratings), *MongoDB* (reviews), *Redis* (cache) | Relational consistency for core entities; flexible schema + text search for reviews; in-memory latency for hot game data. |
| **External data** | *IGDB API* pulled by Launch Svc                                            | Avoids replicating a large third-party dataset; keeps catalogue fresh daily. |
| **Observability** | *Prometheus* + *Grafana* (metrics), *ELK* (logs), *Jaeger* (traces)        | OSS stack with vibrant community; no vendor lock-in; supports RED/SLO dashboards. |
| **CI / CD** | GitHub Actions → Docker → Helm Charts                                      | Full pipeline as-code; Helm simplifies multi-env overrides. |
| **Infrastructure as Code** | *Terraform* modules for cluster, DBs, VPC                                  | Audit-ready provisioning, reproducible environments. |
| **Secrets** | *Kubernetes Secrets* sealed by *SOPS*                                      | Encrypt-at-rest in Git, decrypt only at deploy time. |

## Trade-offs & Alternatives considered

* **Monolith vs micro-services** – a monolith would simplify deployments but hinder parallel feature work and elastic scaling of hot spots (e.g. ratings).
* **gRPC vs REST** – gRPC brings smaller payloads but HTTP/JSON is still easier for web clients and external partners.
* **Managed DB vs self-hosted** – managed Postgres wins on operational burden; MongoDB + Redis stay self-hosted for cost.
* **Hosted APM suites** – Datadog / New Relic provide out-of-the-box dashboards but incur steep licensing; OSS stack chosen for cost neutrality.

> _The team will revisit these decisions quarterly; any change will be captured here as ADR-nnn._

# ADR nnn – template
Date: 2025-04-29

## Status
* Proposed

## Context
* Describe the context of the decision.

## Decision
* Describe the decision.

## Consequences
* Describe the consequences of the decision.