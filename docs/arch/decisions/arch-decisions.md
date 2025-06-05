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

# ADR 001 – Remove Lombok
Date: 2025-06-05

## Status
* Accepted

## Context
* The project originally adopted Lombok to generate boilerplate (getters, setters, equals/hashCode, builders) for domain objects.

* Since Java 16 introduced records and Java 21 is now our baseline, most DTOs and immutable value types can be expressed concisely without external annotation processors.

* Annotation processing in Lombok lengthens compilation, complicates reflection‑based libraries (e.g. Jackson), and breaks with every new major JDK until Lombok is updated.

* Onboarding new developers requires installing Lombok IDE plugins; missing it yields confusing compile errors.

* Static‑code‑analysis and AOT/native‑image tools struggle to see generated members, requiring extra configuration.

## Decision
* Remove the Lombok dependency (org.projectlombok:lombok) from all modules.
* Replace:

    * Immutable POJOs → Java records.

    * Mutable entities → explicit constructors plus IntelliJ “Generate” for accessors.

    * Lombok builders → standard builder patterns or MapStruct builders where needed.

    * Delete @Getter, @Setter, @Builder, @Value, @Slf4j, and related annotations from source.

## Consequences
* Pros

    * IDE‑agnostic code; no plugin prerequisite.

    * Faster incremental builds (~15‑20% measured locally).

    * Cleaner stack traces and easier debugging (no synthetic methods).

    * Future‑proof: no waiting for Lombok updates each JDK release.

* Cons

    * One‑off refactor touching ~240 files; larger diff PR.

    * Slightly more verbose getters/setters in a few mutable classes.

* Mitigations

    * Automated migration script (OpenRewrite lombok‑to‑records recipe) plus IDE refactor shortcuts keep effort to about two developer‑days.

    * Code review checklist updated to prevent re‑introducing Lombok.