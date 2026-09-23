# VideoGame Platform backend

The backend is a Java 25 / Spring Boot modular monolith. It implements every
operation of the [OpenAPI contract](../docs/architecture/api/openapi.yaml)
(release discovery, catalogue search, game details, the BFF session, and the
current user's personal ratings), the Keycloak login and rating-intent navigation
routes under `/auth`, the packaged frontend routes, Actuator health/info/metrics, and
the internal operator-triggered IGDB catalogue synchronization. Behaviour is specified
by the [use cases](../docs/architecture/application/mvp-use-cases.md) and
[API conventions](../docs/architecture/api/api-conventions.md); this README owns
only how to build, run, and exercise it.

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
bash scripts/analyze-catalogue-search.sh
bash scripts/analyze-release-browse.sh
```

The two `analyze-*` scripts write representative-scale query plans under ignored
`backend/target/query-plans/`; ADR-0015 and ADR-0016 record the accepted evidence.
Dependency and plugin versions are authoritative in the root and backend Maven POMs.

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
`SPRING_PROFILES_ACTIVE=oidc` to exercise the Keycloak BFF flow, or run the complete
packaged topology with `bash scripts/local-dependencies.sh application`.

For deterministic examples add
`SPRING_FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/dev-seed`. The seed is
development-only, excluded from the production image, and holds a small intentional
matrix (several pages, an incomplete last page, filters with many/few/no results,
every date precision, both cover-fallback reasons). It uses absolute dates, so add
`PLATFORM_CLOCK_FIXED_INSTANT=2026-08-13T10:00:00Z` to evaluate the same `recent`
and `upcoming` windows the browser gate asserts.

Configuration names, defaults, and secret classification are maintained in
[`backend/.env.example`](.env.example) and
[`application.yaml`](src/main/resources/application.yaml). Never commit
`backend/.env`.

## Modules and dependency direction

| Module      | Responsibility                                                                   |
|-------------|----------------------------------------------------------------------------------|
| `catalogue` | Games, releases, aliases, covers, local publication reads, and provider synchronization |
| `ratings`   | Release eligibility, aggregates, personal rating commands, and the `Mis puntuaciones` projection |
| `identity`  | BFF session, external identity integration, and the rating-intent return context |
| `api`       | HTTP delivery and mapping only                                                   |
| `platform`  | Cross-cutting runtime configuration and observability                            |

Domain and application code remain independent from Spring, HTTP, generated OpenAPI
types, persistence models, and provider DTOs; adapters depend inward and module
composition belongs in the owning `configuration` package. Within a layer, cohesive
use cases use capability packages such as `search`, `releases`, and `cover`. Spring
Modulith named interfaces expose only the public application contracts, and ArchUnit
prevents adapters from reaching capability internals. The rules and their rationale
are in the [solution architecture](../docs/architecture/mvp-solution-architecture.md).

## HTTP contract

Maven generates disposable interfaces and transport models from OpenAPI below
`backend/target/generated-sources/openapi`; never edit or commit them. Manual
controllers in `api.delivery` implement the generated interfaces and map to
application models. Follow the [OpenAPI workflow](../docs/development/openapi.md) for
contract changes and update the relevant [Postman collection](postman/README.md) in
the same change.

## Persistence and observability

Flyway SQL under `src/main/resources/db/migration/` is the executable schema
authority; the [migration workflow](../docs/development/database-migrations.md) owns
the policy. Actuator exposes health groups, build information, metrics, and the
synchronization command on the separate management port (`8081` by default, loopback
only), never on the product port; [observability](../docs/development/observability.md)
owns the signal catalogue.

Catalogue synchronization (`UC-009`) is one internal management command with
required inclusive ISO dates:

```bash
curl --fail -X POST -H 'Content-Type: application/json' \
  -d '{"from":"2026-01-01","to":"2026-12-31"}' \
  http://localhost:8081/actuator/cataloguesync
curl --fail http://localhost:8081/actuator/cataloguesync
```

One POST paginates internally until every IGDB Game with a release date in the
interval has been reconciled; there is no total Game limit. The GET reports the last
run or `never_run`. Apply the Flyway schema before enabling the command. Without
`IGDB_CLIENT_ID` and `IGDB_CLIENT_SECRET` it reports `SYNCHRONIZATION_DISABLED` and
changes nothing, which is how CI and a normal local run behave; automated provider
evidence uses the fixtures under `src/test/resources/provider/igdb/`, and live
credentials belong in the ignored `backend/.env` only.
[ADR-0017](../docs/decisions/0017-discover-catalogue-members-automatically-from-igdb.md)
owns the reconciliation decisions.

## Packaged application and image

```bash
bash scripts/package-application.sh
java -jar "$(bash scripts/backend-artifact.sh jar)"
bash scripts/validate-browser.sh
bash scripts/validate-identity.sh
bash scripts/validate-container-image.sh
```

The package command embeds the Vite output. The browser and identity checks exercise
the packaged JAR; the container check validates the non-root multi-architecture OCI
image, scans it, and generates SBOM evidence. Exact mechanics are owned by the
scripts, Dockerfile, Compose file, and CI workflow.
