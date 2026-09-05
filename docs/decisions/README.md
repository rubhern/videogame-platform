# Architecture decision records

ADRs preserve the reason for significant, durable choices: context, decision,
alternatives, consequences and reconsideration triggers. They do not own current
operating procedures, exact dependency versions or implementation status; those
belong to the linked architecture/development sources and executable manifests.

Create an ADR only when reversing a choice would have meaningful architectural or
product consequences. Supersede accepted records rather than rewriting their
historical decision.

## Accepted

- [ADR-0001: Reference IGDB cover images without copying binaries](0001-reference-igdb-cover-images.md)
- [ADR-0002: Use a modular monolith and relational data boundary](0002-use-a-modular-monolith-and-relational-data-boundary.md)
- [ADR-0003: Use a same-origin BFF and HTTP/JSON API](0003-use-a-same-origin-bff-and-http-json-api.md)
- [ADR-0004: Synchronize and serve local catalogue data](0004-synchronize-and-serve-local-catalogue-data.md)
- [ADR-0005: Host private dev on OCI Always Free](0005-host-private-dev-on-oci-always-free.md)
- [ADR-0006: Use PostgreSQL and versioned forward migrations](0006-use-postgresql-and-versioned-forward-migrations.md)
- [ADR-0007: Use Keycloak as the initial identity provider](0007-use-keycloak-as-the-initial-identity-provider.md)
- [ADR-0008: Use GitHub Actions and GHCR for initial delivery](0008-use-github-actions-and-ghcr-for-initial-delivery.md)
- [ADR-0009: Use OpenTelemetry-compatible instrumentation](0009-use-opentelemetry-compatible-instrumentation.md)
- [ADR-0010: Use Java 25, Spring Boot 4 and Spring Modulith](0010-use-java-25-spring-boot-4-and-spring-modulith.md)
- [ADR-0011: Use Flyway and persistence adapters with PostgreSQL](0011-use-postgresql-and-flyway-for-application-persistence.md)
- [ADR-0012: Use React, TypeScript and Vite for the web frontend](0012-use-react-typescript-and-vite-for-the-web-frontend.md)
- [ADR-0013: Use model-backed and purpose-specific architecture diagrams](0013-use-model-backed-and-purpose-specific-architecture-diagrams.md)
- [ADR-0014: Generate backend HTTP contracts from OpenAPI](0014-generate-backend-http-contracts-from-openapi.md)
- [ADR-0015: Query published release pages with a bounded PostgreSQL read model](0015-query-published-release-pages-with-postgresql.md)
- [ADR-0016: Search the bounded catalogue with PostgreSQL text search](0016-search-the-bounded-catalogue-with-postgresql-text-search.md)
