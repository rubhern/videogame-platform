# Development environment

- [Local development setup](local-setup.md): supported WSL2 boundary, mandatory
  prerequisites, non-secret local configuration, validation, and troubleshooting.
- [Backend development](backend.md): initial module boundaries, supported build,
  verification, start, health, and packaging commands.
- [Frontend development](frontend.md): React/TypeScript/Vite skeleton, OpenAPI type
  generation, verification, local development, and current limitations.
- [Local backend dependencies](local-dependencies.md): PostgreSQL and Keycloak
  topology, generated local credentials, verification, shutdown, and safe reset.
- [Application database migrations](database-migrations.md): module-owned catalogue
  schema, Flyway immutability, deterministic seed data, Testcontainers evidence, and
  local migration configuration.
- [Backend observability](observability.md): health semantics, build metadata,
  correlation, structured logs, metrics, W3C tracing, OTLP configuration, telemetry
  safety, and automated evidence.
- [Codex workspace setup](codex-setup.md): verified tools, configuration,
  rationale, risks, and deferred capabilities.
- [OpenAPI contract validation](openapi-validation.md): syntax, lint, references,
  schemas, examples, local execution, and CI integration.
- [OpenAPI web documentation tutorial](openapi-web-documentation.md): regenerate,
  inspect, and update the static Redoc API reference.
- [Learning MVP delivery lifecycle](delivery-lifecycle.md): readiness, Git and pull
  requests, quality gates, acceptance, releases, and Definition of Done.
- [Work management](work-management.md): GitHub Issues and Projects workflow,
  planning fields, work-in-progress limit, and pull-request traceability.
- [IGDB provider PoC](../../tools/igdb-poc/README.md): isolated Java CLI,
  local-fixture validation, and authenticated execution instructions.

This section documents reproducible development and delivery setup. Technical
environment and deployment mechanics belong in the
[platform design](../architecture/deployment/mvp-platform-and-delivery.md). It must
not contain credentials, tokens, personal data, or machine-specific secrets.

Phase 1 solution definition is complete. The current implementation gate is the
smallest local walking skeleton, including CI and explicit `linux/amd64` and
`linux/arm64` compatibility evidence before feature expansion.
