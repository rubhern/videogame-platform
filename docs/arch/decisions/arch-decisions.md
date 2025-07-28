# 📝 Architecture Decisions

| Area | Decision | Rationale |
|------|----------|-----------|
| **Overall style** | *C4‑model* micro‑service architecture | Clear separation of concerns; scales organisationally (independent teams) and technically (independent deployables). |
| **Runtime platform** | *MicroK8s* on Windows 11 WSL 2 | Lightweight single‑node Kubernetes ideal for a local training environment; avoids cloud fees while remaining upstream compatible. |
| **API ingress** | *Kong Ingress Controller* | Unified gateway with auth, rate‑limiting and plugins. An Edge CDN will be evaluated post‑MVP for global caching. |
| **URL scheme (MVP)** | *HTTP* only | Eliminates TLS complexity during early development; HTTPS will be enforced before public release. |
| **AuthN / AuthZ** | *Keycloak* with OIDC | Open‑source, supports social log‑ins; avoids bespoke auth code in each service. |
| **Service framework** | *Spring Boot* (Java 21) | Mature ecosystem, first‑class observability hooks, developer familiarity. |
| **Inter‑service sync** | *REST / JSON* via Gateway | Simpler than gRPC for external integration; benefits from Kong plugins (caching, tracing). |
| **Async messaging** | *Apache Kafka* | High‑throughput fan‑out (ratings‑created, reviews‑created); guarantees ordering for leaderboards. |
| **Databases** | *PostgreSQL* (users, games, ratings), *MongoDB* (reviews), *Redis* (cache) | Relational consistency for core entities; flexible schema + text search for reviews; in‑memory latency for hot game data. |
| **External data** | *IGDB API* pulled by Launch Svc | Avoids replicating a large third‑party dataset; keeps catalogue fresh daily. |
| **Observability** | *Prometheus* + *Grafana* (metrics), *ELK* (logs), *Jaeger* (traces) | OSS stack with vibrant community; no vendor lock‑in; supports RED/SLO dashboards. |
| **CI / CD** | GitHub Actions → Docker → *Argo CD* (syncs to MicroK8s) | GitOps pull‑based deployment; Argo CD reconciles manifests inside the local cluster. |
| **Infrastructure as Code** | *Helm charts* applied manually (MVP); *Terraform* TBD | Quick bootstrap for local experimentation; Terraform will be revisited once multi‑cluster or cloud infra is needed. |
| **Secrets** | *Kubernetes Secrets* sealed by *SOPS* | Encrypt‑at‑rest in Git, decrypt only at deploy time. |

## Trade‑offs & Alternatives considered

* **MicroK8s vs managed Kubernetes** – MicroK8s keeps everything local and free but lacks managed‑service SLAs; manifests remain portable should the project migrate to a cloud provider.
* **HTTP vs HTTPS in MVP** – Shipping with HTTP accelerates feedback but creates a security to‑do before go‑live (automated TLS, cert rotation).
* **Helm vs Terraform for infra tools** – Helm offers fast installs; Terraform enables stateful governance and drift detection. The team will reassess once infrastructure grows.

> _The team will revisit these decisions quarterly; any change will be captured here as ADR‑nnn._

# ADR 001 – Remove Lombok
Date: 2025‑06‑05

## Status
* Accepted

## Context
* The project originally adopted Lombok to generate boilerplate (getters, setters, equals/hashCode, builders) for domain objects.

* Since Java 16 introduced records and Java 21 is now our baseline, most DTOs and immutable value types can be expressed concisely without external annotation processors.

* Annotation processing in Lombok lengthens compilation, complicates reflection‑based libraries (e.g. Jackson), and breaks with every new major JDK until Lombok is updated.

* Onboarding new developers requires installing Lombok IDE plugins; missing it yields confusing compile errors.

* Static‑code‑analysis and AOT/native‑image tools struggle to see generated members, requiring extra configuration.

## Decision
* Remove the Lombok dependency (`org.projectlombok:lombok`) from all modules.
* Replace:

  * Immutable POJOs → Java records.

  * Mutable entities → explicit constructors plus IntelliJ “Generate” for accessors.

  * Lombok builders → standard builder patterns or MapStruct builders where needed.

  * Delete `@Getter`, `@Setter`, `@Builder`, `@Value`, `@Slf4j`, and related annotations from source.

## Consequences
* **Pros**

  * IDE‑agnostic code; no plugin prerequisite.

  * Faster incremental builds (~15‑20% measured locally).

  * Cleaner stack traces and easier debugging (no synthetic methods).

  * Future‑proof: no waiting for Lombok updates each JDK release.

* **Cons**

  * One‑off refactor touching ~240 files; larger diff PR.

  * Slightly more verbose getters/setters in a few mutable classes.

* **Mitigations**

  * Automated migration script (OpenRewrite lombok‑to‑records recipe) plus IDE refactor shortcuts keep effort to about two developer‑days.

  * Code review checklist updated to prevent re‑introducing Lombok.


# ADR 002 – Local Single‑Node GitOps Stack
Date: 2025‑07‑28

## Status
* Accepted

## Context
* The project is a **personal training playground** operating entirely on *Windows 11 + WSL 2*. Cloud costs and team size are minimal, so a lightweight, self‑contained environment is preferred.

## Decision
1. **Runtime platform** – adopt **MicroK8s** for container orchestration. It provides upstream‑compatible Kubernetes with a tiny footprint and is easy to reset or upgrade.
2. **Ingress strategy** – expose services exclusively through **Kong Ingress Controller**. Future work will explore an external *Edge CDN* once public traffic or multi‑region latency becomes a concern.
3. **Protocol (MVP)** – all endpoints are served over **HTTP**. TLS termination and certificate‑automation will be introduced when external exposure is required.
4. **Infrastructure tool installation** – deploy platform components (Kong, Keycloak, Prometheus, etc.) via **Helm charts executed manually**. When the stack grows or moves to cloud, we will reassess using **Terraform** for declarative state management.
5. **Application delivery** – business services are GitOps‑managed: **Argo CD** syncs manifests to the local **MicroK8s** cluster.

## Consequences
### Pros
* **Zero cloud bill** – entire cluster runs locally.
* **Fast feedback** – dev loop (<20s redeploy) courtesy of MicroK8s and Argo CD.
* **Low cognitive load** – HTTP avoids early‑stage cert issues; Helm CLI keeps bootstrap simple.
* **Future‑ready** – manifests, Argo CD, and Kong are cloud‑portable; adding TLS, CDN, or Terraform doesn’t invalidate earlier work.

### Cons
* **Security gap** – no in‑cluster encryption; must be addressed before any public release.
* **Manual Helm drift** – manual upgrades risk config drift until Terraform or Argo CD Chart‑bootstrapping is adopted.
* **Single node** – lacks HA; not suitable for heavy load or production.

### Follow‑ups
* Automate TLS with *cert‑manager* + *Let’s Encrypt*.
* Evaluate *Terraform* (or *Pulumi*) when additional clusters or cloud infra appear.
* Benchmark **Edge‑side caching** via Kong’s Gateway mode or a managed CDN (Cloudflare, AWS CloudFront) once public users are expected.

> _This ADR supersedes the corresponding table entries in the Architecture Decisions section; future changes to these points will be tracked as new ADRs._
