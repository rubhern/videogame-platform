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

Phase 1 is active at the technology-baseline gate. PostgreSQL and the private OCI
hosting boundary are accepted; application frameworks, persistence and migration
libraries, frontend tooling, and supported versions remain to be selected as one
coherent baseline. The executable walking skeleton and remote infrastructure start
only after that gate.

## Approved records

- [Learning MVP domain model v1.1](domain/mvp-domain-model.md)
- [Learning MVP use cases and relevant errors v1.0](application/mvp-use-cases.md)
- [Learning MVP solution architecture v1.1](mvp-solution-architecture.md)
- [Learning MVP REST API conventions v1.0](api/api-conventions.md)
- [Learning MVP OpenAPI 3.1.2 contract](api/openapi.yaml)
- [Generated OpenAPI web reference](api/reference/index.html)
- [Learning MVP platform and delivery design v1.0](deployment/mvp-platform-and-delivery.md)
- [ADR-0001: Reference IGDB cover images without copying binaries](../decisions/0001-reference-igdb-cover-images.md)
- [ADR-0002: Use a modular monolith and relational data boundary](../decisions/0002-use-a-modular-monolith-and-relational-data-boundary.md)
- [ADR-0003: Use a same-origin BFF and HTTP/JSON API](../decisions/0003-use-a-same-origin-bff-and-http-json-api.md)
- [ADR-0004: Synchronize and serve local catalogue data](../decisions/0004-synchronize-and-serve-local-catalogue-data.md)
- [ADR-0005: Host private dev on OCI Always Free](../decisions/0005-host-private-dev-on-oci-always-free.md)
- [ADR-0006: Use PostgreSQL and versioned forward migrations](../decisions/0006-use-postgresql-and-versioned-forward-migrations.md)
- [ADR-0007: Use Keycloak as the initial identity provider](../decisions/0007-use-keycloak-as-the-initial-identity-provider.md)
- [ADR-0008: Use GitHub Actions and GHCR for initial delivery](../decisions/0008-use-github-actions-and-ghcr-for-initial-delivery.md)
- [ADR-0009: Use OpenTelemetry-compatible instrumentation](../decisions/0009-use-opentelemetry-compatible-instrumentation.md)
