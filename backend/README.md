# VideoGame Platform backend

The backend is a Java 25 / Spring Boot modular monolith. It currently implements the
PostgreSQL-backed `GET /api/v1/releases` operation, the minimal BFF session resource,
Keycloak login navigation, packaged frontend routes, and Actuator health/info/metrics.
The remaining operations in the [OpenAPI contract](../docs/architecture/api/openapi.yaml)
are approved contracts, not implemented claims.

## Build and verify

Run from the repository root:

```bash
./mvnw clean verify
```

This generates the Spring HTTP boundary from OpenAPI, checks formatting and module
rules, runs unit and PostgreSQL/Testcontainers integration tests, produces JaCoCo
reports, and packages the executable JAR. Docker is required for persistence tests.

Useful focused commands:

```bash
./mvnw -pl backend -am test
./mvnw -pl backend clean generate-sources
./mvnw spotless:apply
bash scripts/validate-migrations.sh
```

Dependency and plugin versions are authoritative in the root and backend Maven
POMs; this README intentionally does not duplicate them.

## Run locally

Start PostgreSQL and Keycloak, load the generated ignored configuration, and opt in
to Flyway:

```bash
bash scripts/local-dependencies.sh up
set -a
source backend/.env
set +a
APPLICATION_FLYWAY_ENABLED=true ./mvnw -pl backend spring-boot:run
```

The application listens on `http://localhost:8080`. Add
`SPRING_PROFILES_ACTIVE=oidc` to exercise the Keycloak BFF flow, or use the complete
packaged topology:

```bash
docker compose --profile full up --build
```

For deterministic release examples, add
`SPRING_FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/dev-seed`. The seed is
development-only and excluded from the production image profile.

Configuration names, defaults, and secret classification are maintained in
[`backend/.env.example`](.env.example) and
[`application.yaml`](src/main/resources/application.yaml). Never commit
`backend/.env`.

## Modules and dependency direction

| Module | Responsibility |
|---|---|
| `catalogue` | Games, releases, local publication reads, and future provider synchronization |
| `ratings` | Personal ratings and aggregates; currently a skeleton |
| `identity` | BFF session and external identity integration |
| `api` | HTTP delivery and mapping only |
| `platform` | Cross-cutting runtime configuration and observability |

Domain and application code remain independent from Spring, HTTP, generated OpenAPI
types, persistence models, and provider DTOs. Adapters depend inward and do not
instantiate application services. Module composition belongs in the owning
`configuration` package. Spring Modulith and ArchUnit tests enforce these rules.

The approved structure and trade-offs live in the
[solution architecture](../docs/architecture/mvp-solution-architecture.md) and
[ADR-0002](../docs/decisions/0002-use-a-modular-monolith-and-relational-data-boundary.md).

## HTTP contract

`docs/architecture/api/openapi.yaml` is the product HTTP source of truth. Maven
generates disposable interfaces and transport models below
`backend/target/generated-sources/openapi`; never edit or commit them. Manual
controllers in `api.delivery` implement generated interfaces and map to application
models.

Follow the [OpenAPI workflow](../docs/development/openapi.md) for contract changes.
Update the relevant [Postman collection](postman/README.md) in the same change.

## Persistence and observability

Flyway SQL under `src/main/resources/db/migration/` is the executable schema
authority. Hibernate schema generation is disabled, the migration role owns DDL,
and the runtime role has only required DML privileges. See the
[migration workflow](../docs/development/database-migrations.md).

Actuator exposes health groups, build information, and metrics. Correlation uses
`X-Correlation-ID`; tracing uses W3C context; OTLP export is disabled by default.
Metric labels must remain bounded and must not include user, game, request, or
correlation identifiers. See [observability](../docs/development/observability.md).

## Packaged application and image

```bash
bash scripts/package-application.sh
java -jar backend/target/videogame-platform-backend-0.7.5-SNAPSHOT.jar
bash scripts/validate-browser.sh
bash scripts/validate-identity.sh
bash scripts/validate-container-image.sh
```

The package command embeds the Vite output. The browser and identity checks exercise
the packaged JAR; the container check validates the non-root multi-architecture OCI
image, scans it, and generates SBOM evidence. Exact mechanics are owned by the
scripts, Dockerfile, Compose file, and CI workflow.
