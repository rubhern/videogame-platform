# Architecture

The initial product problem, journey, MVP boundary, and provider constraints are
explicit. The [prototype journey gate](../research/simulated-round-synthesis.md) is
`PASS`, the [minimum domain model](domain/mvp-domain-model.md) is `Approved`, and the
[minimum application use cases](application/mvp-use-cases.md) and
[MVP solution architecture](mvp-solution-architecture.md), and the
[REST API conventions](api/api-conventions.md) are `Approved`. The
[OpenAPI 3.1.2 contract](api/openapi.yaml) implements the eight approved operations
for the first vertical slice. The
[platform and delivery design](deployment/mvp-platform-and-delivery.md) defines its
minimum local, CI, and private zero-cost OCI `dev` platform.

The approved integration constraints are provider independence, local synchronized
metadata reads, separate release and subscription-availability concepts, no direct
browser calls to the authenticated IGDB API, and attributed direct IGDB CDN cover
references without copied provider image binaries. The initial solution uses one
same-origin web application deployment with a server-side BFF, modular monolith, and
relational data boundary. API Management remains deferred until an adoption trigger
or bounded learning experiment justifies it.

Phase 1 solution definition is complete. The
[approved technology baseline](technology/mvp-technology-baseline.md) selects the
application frameworks, persistence and migration libraries, frontend tooling,
quality controls, and supported version lines. The executable walking skeleton is
the current implementation gate; remote infrastructure starts only after local, CI,
`linux/amd64`, and `linux/arm64` compatibility are proven.

The initial diagram baseline is established and governed by
[ADR-0013](../decisions/0013-use-model-backed-and-purpose-specific-architecture-diagrams.md).
The diagrams communicate the approved architecture while their owning documents,
contracts, and ADRs remain authoritative.

## Approved records

- [Architecture diagram catalogue](diagrams/README.md)
- [Learning MVP domain model v1.1](domain/mvp-domain-model.md)
- [Learning MVP use cases and relevant errors v1.0](application/mvp-use-cases.md)
- [Learning MVP solution architecture v1.2](mvp-solution-architecture.md)
- [Learning MVP REST API conventions v1.0](api/api-conventions.md)
- [Learning MVP OpenAPI 3.1.2 contract](api/openapi.yaml)
- [Generated OpenAPI web reference](api/reference/index.html)
- [Learning MVP platform and delivery design v1.1](deployment/mvp-platform-and-delivery.md)
- [Learning MVP technology baseline v1.0](technology/mvp-technology-baseline.md)
- [ADR-0001: Reference IGDB cover images without copying binaries](../decisions/0001-reference-igdb-cover-images.md)
- [ADR-0002: Use a modular monolith and relational data boundary](../decisions/0002-use-a-modular-monolith-and-relational-data-boundary.md)
- [ADR-0003: Use a same-origin BFF and HTTP/JSON API](../decisions/0003-use-a-same-origin-bff-and-http-json-api.md)
- [ADR-0004: Synchronize and serve local catalogue data](../decisions/0004-synchronize-and-serve-local-catalogue-data.md)
- [ADR-0005: Host private dev on OCI Always Free](../decisions/0005-host-private-dev-on-oci-always-free.md)
- [ADR-0006: Use PostgreSQL and versioned forward migrations](../decisions/0006-use-postgresql-and-versioned-forward-migrations.md)
- [ADR-0007: Use Keycloak as the initial identity provider](../decisions/0007-use-keycloak-as-the-initial-identity-provider.md)
- [ADR-0008: Use GitHub Actions and GHCR for initial delivery](../decisions/0008-use-github-actions-and-ghcr-for-initial-delivery.md)
- [ADR-0009: Use OpenTelemetry-compatible instrumentation](../decisions/0009-use-opentelemetry-compatible-instrumentation.md)
- [ADR-0010: Use Java 25, Spring Boot 4, and Spring Modulith](../decisions/0010-use-java-25-spring-boot-4-and-spring-modulith.md)
- [ADR-0011: Use Flyway and persistence adapters with PostgreSQL](../decisions/0011-use-postgresql-and-flyway-for-application-persistence.md)
- [ADR-0012: Use React, TypeScript, and Vite](../decisions/0012-use-react-typescript-and-vite-for-the-web-frontend.md)
- [ADR-0013: Use model-backed and purpose-specific architecture diagrams](../decisions/0013-use-model-backed-and-purpose-specific-architecture-diagrams.md)
