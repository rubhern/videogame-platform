# VideoGame Platform backend

The backend is a Java 25 / Spring Boot modular monolith. It currently implements the
PostgreSQL-backed `GET /api/v1/releases`, `GET /api/v1/games` and
`GET /api/v1/games/{gameId}` operations, authenticated current-user rating read/write/delete,
the minimal BFF session resource, Keycloak login navigation, the rating authentication
boundary (`/auth/rating-intent` start and single-use, expiring return context that
resumes the same game and selected value without persisting a rating), packaged
frontend routes, Actuator health/info/metrics, and the internal operator-triggered
IGDB catalogue synchronization.
The remaining operations in the [OpenAPI contract](../docs/architecture/api/openapi.yaml)
are approved contracts, not implemented claims.

## Public game details

Catalogue reads one publication through the existing read-only transaction policy.
The application read port bounds complete aliases/releases and fails closed if a game
exceeds either bound; it never returns a truncated eligibility context. Missing
editorial content uses an explicit product-owned “not yet curated” message, while
sourced summaries retain their language and provenance.

Ratings consumes Catalogue application context and reads only its own active-rating
table. PostgreSQL computes count, mean and ten distribution buckets in one statement;
a missing contribution set is empty, and a read failure becomes unavailable without
blocking the game. No personal record or user identity is included in public delivery.

The public representation revalidates on every reuse because Madrid evaluation dates
and aggregate/freshness state affect its ETag. Degraded aggregates are not stored.
The [observability policy](../docs/development/observability.md) defines the shared
HTTP metrics and the bounded detail-read meter for eligibility and aggregate state.
No provider request is made during a game read.

The forward migrations add defaulted catalogue summary columns and extend the
module-owned rating table with timestamps and an opaque version token. The previous
application remains compatible with the expanded schema. Apply them before activating
this version. Roll back the application while retaining the additive data; do not
reverse the migration or drop ratings to recover an application deployment.

## Personal rating commands

`GET`, conditional `PUT`, and conditional `DELETE` on
`/api/v1/me/ratings/{gameId}` implement `UC-005`, `UC-006`, and `UC-007`. The server
derives the product `UserId` exclusively from the validated OIDC issuer and subject.
Personal absence is scoped by that identifier, so another user's row is never exposed.

Create requires `If-None-Match: *`; update and delete require the current strong,
opaque `ETag` in `If-Match`. PostgreSQL enforces one active row per user and game,
and conditional DML resolves concurrent races without lost updates. Create and update
re-evaluate release eligibility with the trusted application clock; delete deliberately
does not. Each successful write and its database-computed aggregate are read in one
bounded transaction. A database failure rolls back the personal state before a safe
`RATING_WRITE_FAILED` response. These authenticated responses use `no-store`, and
state changes require the session CSRF token plus same-origin browser metadata.

The collection endpoint `/api/v1/me/ratings` remains unimplemented and is owned by
issue #32; no rating UI is included here.

For the local browser login, cookie synchronization and automatic CSRF bootstrap used
to exercise these commands, follow the
[authenticated Postman instructions](postman/README.md#authenticated-personal-rating-run).

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
bash scripts/local-dependencies.sh application
```

For deterministic release examples, add
`SPRING_FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/dev-seed`. The seed is
development-only and excluded from the production image profile. It holds a small
intentional matrix — twelve games over twenty releases across four platforms and five
regions — that produces several real pages, an incomplete last page, filters with
many, few and no results, every date precision, and both cover-fallback reasons.

The seed uses absolute dates, so add `PLATFORM_CLOCK_FIXED_INSTANT=2026-08-13T10:00:00Z`
to evaluate the same `recent` and `upcoming` windows the browser gate asserts.

Configuration names, defaults, and secret classification are maintained in
[`backend/.env.example`](.env.example) and
[`application.yaml`](src/main/resources/application.yaml). Never commit
`backend/.env`.

## Modules and dependency direction

| Module      | Responsibility                                                                              |
|-------------|---------------------------------------------------------------------------------------------|
| `catalogue` | Games, releases, local publication reads, and bounded provider synchronization              |
| `ratings`   | Release eligibility, aggregates, and transactional current-user rating commands             |
| `identity`  | BFF session and external identity integration                                               |
| `api`       | HTTP delivery and mapping only                                                              |
| `platform`  | Cross-cutting runtime configuration and observability                                       |

Domain and application code remain independent from Spring, HTTP, generated OpenAPI
types, persistence models, and provider DTOs. Adapters depend inward and do not
instantiate application services. Module composition belongs in the owning
`configuration` package. Within a layer, cohesive use cases use capability packages
such as `search`, `releases`, and `cover`; genuinely shared mechanisms stay at the
layer root. Spring Modulith named interfaces expose only the public application
contracts, while ArchUnit prevents adapters from reaching capability internals.

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

For catalogue-search plan evidence, run `bash scripts/analyze-catalogue-search.sh`.
The supported fixture sizes live in the script; full production count/page plans are
written under ignored `backend/target/query-plans/`. See
[ADR-0016](../docs/decisions/0016-search-the-bounded-catalogue-with-postgresql-text-search.md)
for the indexing decision and its limits.

Catalogue synchronization (`UC-009`) is one internal management command:

```bash
curl --fail -X POST -H 'Content-Type: application/json' \
  -d '{"from":"2026-01-01","to":"2026-12-31"}' \
  http://localhost:8081/actuator/cataloguesync
curl --fail http://localhost:8081/actuator/cataloguesync
```

`from` and `to` are required ISO dates and both are inclusive. One POST paginates
internally until every IGDB Game represented by a `release_dates` row in that
interval has been reconciled. There is no total
Game limit. `providerPageSize` is an internal memory/transport bound only.
The GET reports the last run or `never_run`.
The reconciliation, date-window, retry and per-Game atomicity decisions live in
[ADR-0017](../docs/decisions/0017-discover-catalogue-members-automatically-from-igdb.md).
The endpoint is not scheduled and is absent from the public OpenAPI.

Apply the Flyway schema before enabling this command, even if GET is the first
operation. Application startup normally leaves Flyway disabled; see the
[migration workflow](../docs/development/database-migrations.md).
An earlier local #33 schema is not compatible with the rewritten, unpublished
migration: preserve its data and arrange an explicit reviewed conversion before
starting this version. Do not use checksum repair as a schema upgrade or reset a
persistent database.

Without `IGDB_CLIENT_ID` and `IGDB_CLIENT_SECRET` the command reports
`SYNCHRONIZATION_DISABLED` and changes nothing, which is how CI and a normal local run
behave; automated provider evidence uses the fixtures under
`src/test/resources/provider/igdb/`. Live provider evidence needs a confidential Twitch
developer application and belongs in the ignored `backend/.env` only.

Actuator exposes health groups, build information, metrics, and that command on the
separate local management port (`8081` by default), not on the product port.
Correlation uses `X-Correlation-ID`; tracing uses W3C context; OTLP export is
disabled by default.
Metric labels must remain bounded and must not include user, game, request, or
correlation identifiers. See [observability](../docs/development/observability.md).

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
