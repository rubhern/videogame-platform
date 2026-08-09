# VideoGame Platform

VideoGame Platform is a product initiative for discovering, tracking, and rating
video games. **The product alignment phase is closed.** Its approved first journey
is captured in the [learning MVP story map](docs/product/mvp-story-map.md), and its
[mobile-first clickable prototype](docs/product/clickable-prototype.md) completed an
owner-accepted [simulated five-session round](docs/research/simulated-round-synthesis.md).
The focused simulated regression resolved the blocking issue and left the journey
decision at `PASS`.

The [minimum provider-independent domain model](docs/architecture/domain/mvp-domain-model.md),
the [OpenAPI contract](docs/architecture/api/openapi.yaml), and the
[minimum platform and delivery design](docs/architecture/deployment/mvp-platform-and-delivery.md)
for the learning MVP vertical slice are defined. PostgreSQL, Keycloak, GitHub
Actions/GHCR, OpenTelemetry-compatible instrumentation, and a private zero-cost OCI
Always Free `dev` environment are approved through ADR-0005 to ADR-0009. The
[technology baseline](docs/architecture/technology/mvp-technology-baseline.md)
selects Java 25, Spring Boot 4.1, Spring Modulith 2.1, PostgreSQL 18, Flyway, React
19.2, TypeScript, Vite 8.1, Node.js 24, and the supporting quality toolset through
ADR-0010 to ADR-0012. Public production and a business model remain unapproved.

The [initial architecture diagram catalogue](docs/architecture/diagrams/README.md)
is established through ADR-0013. Structurizr provides the shared C4 model, Mermaid
provides focused code-based views, and diagrams.net provides the polished derived
deployment view without replacing approved documents or ADRs.

**Phase 1 — MVP solution definition is complete.** Application implementation is
active at the walking-skeleton gate. The initial backend skeleton proves Java 25,
Spring Boot 4.1, Spring MVC, Actuator, Spring Modulith 2.1, and the initial module
fitness functions locally. The frontend skeleton proves React 19.2, strict
TypeScript, Vite 8.1, routing, server-state infrastructure, Tailwind CSS, complete
OpenAPI type generation, tests, and a production build. The local PostgreSQL 18 and
Keycloak 26.7 dependency topology now proves isolated roles, reproducible realm and
client configuration, health, persistence, reset, and AMD64/ARM64 dependency-image
manifests. Application persistence, BFF identity integration, CI, combined packaging,
and application multi-architecture evidence remain in the walking-skeleton gate;
remote infrastructure follows only after that gate passes.

## Start here

1. Read the [Product Brief](docs/product/product-brief.md) as the closed product
   alignment record.
2. Use the [learning MVP story map](docs/product/mvp-story-map.md) for the current
   journey, release cut, acceptance checks, and deferred scope.
3. Open the [clickable prototype](docs/product/clickable-prototype.md) and use the
   [accepted simulated round](docs/research/simulated-round-synthesis.md) as the
   closed journey decision record.
4. Use the [approved domain model](docs/architecture/domain/mvp-domain-model.md) for
   the minimum provider-independent contract.
5. Review the [assumptions](docs/product/assumptions.md).
6. Review the resolved decisions and reopening conditions in
   [open questions](docs/product/open-questions.md).
7. Use the [glossary](docs/product/glossary.md) to keep terminology consistent.
8. Prepare the workstation with the [local development setup](docs/development/local-setup.md).
9. Start and verify the [local backend dependencies](docs/development/local-dependencies.md).
10. Review the [Codex workspace setup](docs/development/codex-setup.md).
11. Use the [OpenAPI validation guide](docs/development/openapi-validation.md) to
   validate the API contract locally and in CI.
12. Browse the [generated API reference](docs/architecture/api/reference/index.html)
    or follow its [regeneration tutorial](docs/development/openapi-web-documentation.md).
13. Use the [platform and delivery design](docs/architecture/deployment/mvp-platform-and-delivery.md)
    and [delivery lifecycle](docs/development/delivery-lifecycle.md) for the walking
    skeleton and private `dev` environment.
14. Review the [approved technology baseline](docs/architecture/technology/mvp-technology-baseline.md),
    [architecture diagram catalogue](docs/architecture/diagrams/README.md), and
    [ADR-0005 through ADR-0013](docs/decisions/) before implementing the walking
    skeleton, persistence, identity, delivery, hosting, or observability.
15. Use the [backend technical README](backend/README.md) and
    [backend development guide](docs/development/backend.md) to build, test, start,
    inspect, and extend the initial modular-monolith skeleton.
16. Use the [frontend technical README](frontend/README.md) and
    [frontend development guide](docs/development/frontend.md) to install, generate
    API types, test, build, and extend the client-rendered skeleton.

## Documentation

- [Product documentation](docs/product/)
- [Original product vision](docs/reference/video-game-platform-vision.pdf)
- [Research](docs/research/)
- [Development environment](docs/development/)
- [Architecture](docs/architecture/)
- [Architecture decisions](docs/decisions/)

Markdown files in this repository are the source of truth. Generated Word or PDF
documents, if needed later, are exports rather than authoritative copies.

## Validation

```bash
bash scripts/validate-prerequisites.sh
bash scripts/validate-docs.sh
npm ci
bash scripts/validate-openapi.sh
npm run frontend:verify
./mvnw clean verify
./mvnw -f tools/igdb-poc/pom.xml clean verify
```

The prerequisite gate requires the supported Ubuntu WSL2 environment, Java 25,
Node.js 24, Docker Desktop integration, and the repository Maven Wrapper. The root
Maven command compiles, tests, and packages the backend. The targeted Maven command
tests the isolated IGDB PoC only with local fixtures. Neither verification requires
provider credentials or calls IGDB.

The backend dependency topology has its own lifecycle and verification commands:

```bash
bash scripts/local-dependencies.sh up
bash scripts/local-dependencies.sh verify
bash scripts/local-dependencies.sh verify-images
bash scripts/local-dependencies.sh down
```
