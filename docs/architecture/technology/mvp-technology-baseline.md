# Learning MVP technology baseline

- **Status:** Approved
- **Owner:** Ruben Hernandez

This document owns approved technology families, version lines, and selection
policies. Exact patch/plugin/dependency/image versions live in Maven/npm manifests,
the lock file, Dockerfile, and Compose file.

| Area | Approved baseline |
|---|---|
| Backend | Java 25, preview disabled; Spring Boot 4.1; Spring MVC; Spring Modulith 2.1; Maven Wrapper |
| Persistence | PostgreSQL 18; Flyway Community SQL-first immutable migrations; JPA/Hibernate only in adapters; explicit JDBC/SQL read models where clearer; Testcontainers PostgreSQL |
| API | OpenAPI 3.1.2; Redocly validation/reference; Maven OpenAPI Generator interfaces/models; manual delivery mapping |
| Frontend | Node.js 24/npm 11; React 19.2; strict TypeScript; Vite 8.1; React Router; TanStack Query; Tailwind CSS 4; openapi-typescript/openapi-fetch |
| Identity | Keycloak 26.7 via same-origin server-side OIDC BFF |
| Testing/quality | JUnit/AssertJ, Spring tests, Modulith/ArchUnit, Vitest/RTL, Playwright/axe, Spotless, JaCoCo evidence, Sonar, CodeQL, dependency/secret/image checks |
| Observability | Actuator, Micrometer, W3C tracing, OpenTelemetry-compatible optional export |
| Delivery | GitHub Actions/GHCR; non-root multi-architecture OCI image; local Docker Compose |

## Policies

- Domain/application remain framework-independent; generated, persistence, provider,
  and transport models stay in adapters.
- OpenAPI is contract-first for product HTTP. Backend/frontend generated artefacts
  are disposable and reproducible.
- PostgreSQL-specific SQL is accepted. Hibernate schema generation is disabled;
  runtime credentials have no DDL privilege.
- TanStack Query owns server state; component/URL state use the smallest correct
  owner. OAuth/session secrets are not browser-stored.
- Coverage is evidence, not an arbitrary global percentage target. Prefer behaviour,
  architecture, real PostgreSQL, contract, identity, browser, and failure tests at
  meaningful seams.
- Pin executable versions and lock dependency graphs. Review release notes,
  compatibility, security, licence, runtime architecture support, and rollback before
  upgrades. Do not copy patch versions into prose.
- Supported images and application artefacts must work on `linux/amd64` and
  `linux/arm64` before OCI Ampere provisioning.

## Deferred by default

Java preview, WebFlux, Lombok, H2 as PostgreSQL proof, Cucumber, Next.js/SSR/RSC,
another global state/component library, microservices, brokers, distributed caches,
search engines, extra databases, event sourcing, physical CQRS, Kubernetes, service
mesh, API Management, and gRPC require an approved need or bounded experiment.

Durable choices are recorded in ADR-0010 through ADR-0014 and inherited platform
ADRs. Individual patch versions and routine quality libraries do not need ADRs.
