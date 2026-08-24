# Application database migrations

- **Status:** Active walking-skeleton implementation
- **Last verified:** 2026-08-19
- **Database:** PostgreSQL `18.4`
- **Migration engine:** Flyway Community `12.4.0`, managed by Spring Boot
- **Owners:** Catalogue owns `catalogue.*`; Flyway owns schema evolution
- **Decisions:** [ADR-0006](../decisions/0006-use-postgresql-and-versioned-forward-migrations.md) and [ADR-0011](../decisions/0011-use-postgresql-and-flyway-for-application-persistence.md)

This guide records the executable persistence boundary introduced for issue #22 and
extended for the first public read in issue #25. It implements the minimum catalogue
state and provider attribution reference required by that read without adding JPA
entities, catalogue synchronization, ratings, or a remote deployment procedure.

## Schema model

The application database contains a module-owned `catalogue` schema. Stable game and
release identities are separated from publication-scoped snapshots so a transaction
can install a coherent new catalogue and switch the single `is_current` pointer only
after validation. A failed synchronization can therefore leave the previous current
publication unchanged. This is snapshot publication, not a draft/hidden lifecycle
for domain games.

The standalone
[catalogue persistence model](../architecture/diagrams/mermaid/catalogue-persistence-model.mmd)
shows the physical tables, columns, keys, and cardinalities. It is a communication
view of the implemented schema: the versioned SQL under
`backend/src/main/resources/db/migration/` remains the executable authority for
column types, constraints, indexes, privileges, and schema evolution.

| Table | Responsibility | Important database guarantees |
|---|---|---|
| `catalogue.catalogue_publication` | Valid catalogue snapshot metadata and current pointer | Unique version and at most one current publication |
| `catalogue.game` | Stable provider-independent game identity | UUID primary key |
| `catalogue.game_snapshot` | Publication-scoped title, slug, and normalized displayable primary cover/source page | Game/publication foreign keys, unique slug per publication, approved allowlisted delivery state |
| `catalogue.game_external_reference` | Provider identity and optional HTTPS source-page reference for a game | Game ownership, unique provider identity, closed entity type, HTTPS-only URL |
| `catalogue.platform` | Normalized platform identity | UUID primary key and unique stable code |
| `catalogue.region` | Normalized known, worldwide, or explicit unknown region | UUID primary key and unique stable code |
| `catalogue.game_release` | Stable provider-independent release identity and owning game | Release UUID uniqueness and game foreign key |
| `catalogue.release_snapshot` | Publication-scoped release tuple, provenance, quality, freshness timestamps, and generated query period | Composite ownership foreign keys, tuple uniqueness, closed value checks, range-query boundaries |

Freshness status is intentionally not stored. The application derives it from the
recorded timestamps, the evaluation instant, and the operational threshold, as the
approved domain model requires.

The additive `V20260813_120000` migration supplies the source-page reference needed
to attribute an approved provider cover. It does not store provider payloads,
credentials, or image binaries. If the release read cannot safely resolve both the
approved image reference and its matching source page, it returns the product-owned
fallback cover.

The additive `V20260818_120000` migration constrains exact release dates to years
`1..9999`, matching the existing partial-date constraints and the API's four-digit
year representation. It is a forward correction; the earlier migration remains
immutable.

`V20260819_120000` snapshots the validated cover source URL and adds stored
`period_start`/`period_end` columns derived from the partial date. Its checks ensure
that product covers use product asset paths, provider references are approved IGDB
references, and unknown dates have no query period. `V20260819_130000` enables
`btree_gist` and adds partial GiST indexes for recent/upcoming period overlap plus a
partial B-tree index for upcoming unknown/TBA releases. These indexes follow the
measured UC-001 count/page SQL; unused speculative ordering indexes were not retained.

## Release date representation

`date_precision` is stored atomically with typed, mutually exclusive value columns.
The named `ck_release_snapshot_date_value` constraint permits only these states:

| Precision | Required value | Columns that must be null |
|---|---|---|
| `day` | `exact_date` | year, month, quarter |
| `month` | year and month `1..12` | exact date, quarter |
| `quarter` | year and quarter `1..4` | exact date, month |
| `year` | year | exact date, month, quarter |
| `unknown` | none | exact date, year, month, quarter |

PostgreSQL therefore rejects invented partial dates, years outside `1..9999`, invalid
months or quarters, and unknown dates carrying a synthetic value. The API adapter
formats these columns as the reviewed `ReleaseDate` union without increasing
precision.

The database also guarantees at most one current publication through a partial
unique index. `game_external_reference` has one row per
`game + provider + provider_entity_type` and one owner per provider identity, so a
join cannot multiply release rows. UC-001 no longer joins that live table: validated
cover attribution data is copied into the publication snapshot.

## Migration locations and immutability

```text
backend/src/main/resources/db/migration/   # every maintained environment
backend/src/main/resources/db/dev-seed/    # explicit local/test opt-in only
```

Production migrations use timestamp-based versions. Once a migration has been
applied to a shared environment, it is immutable: never edit, reorder, or delete it.
A correction always uses a new versioned migration. `flyway repair` is not a normal
way to accept changed history, and `flyway clean` remains disabled by committed
configuration. Repeatable migrations are reserved for replaceable objects such as
views or functions; none is needed yet.

The first production migration creates only the `catalogue` schema and grants the
existing `videogame_app` role usage plus table DML. The runtime principal has no
schema-creation permission. Flyway uses the separate
`videogame_app_migrator` credentials defined by the local dependency topology.

## Deterministic development and test data

`db/dev-seed` contains eight games from the accepted clickable prototype with fixed
UUIDs, timestamps, fallback covers, normalized platforms/regions, and all five date
precision variants. It is non-sensitive demonstration data. Its dates and release
states are stable fixtures, not current provider evidence or a production catalogue.

The seed location is absent from committed default Flyway locations. Include it only
for a disposable local or test database:

```bash
SPRING_FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/dev-seed
```

Changing seed data after it has been applied still requires a new dev-seed migration;
the same checksum and immutability rules apply.

## Runtime configuration

All values are read at process startup and require a restart to change. Spring fails
startup when an enabled connection cannot authenticate or Flyway validation/migration
fails.

| Environment variable | Safe default | Purpose | Secret |
|---|---|---|---|
| `APPLICATION_DB_URL` | `jdbc:postgresql://localhost:5432/videogame_platform` | Runtime JDBC target | No |
| `APPLICATION_DB_USERNAME` | `videogame_app` | DML-only runtime principal | No |
| `APPLICATION_DB_PASSWORD` | Empty; local `backend/.env` or protected source required | Runtime database credential | Yes |
| `APPLICATION_DB_CONNECTION_TIMEOUT` | `3000` ms | Maximum wait for a runtime connection from Hikari | No |
| `APPLICATION_DB_VALIDATION_TIMEOUT` | `1000` ms | Maximum Hikari connection-validation wait | No |
| `APPLICATION_DB_MAXIMUM_POOL_SIZE` | `10` | Upper bound on concurrent runtime database connections | No |
| `APPLICATION_MIGRATION_DB_URL` | Runtime JDBC target | Flyway JDBC target | No |
| `APPLICATION_MIGRATION_DB_USERNAME` | `videogame_app_migrator` | Schema-owning Flyway principal | No |
| `APPLICATION_MIGRATION_DB_PASSWORD` | Empty; local `backend/.env` or protected source required | Flyway database credential | Yes |
| `APPLICATION_FLYWAY_ENABLED` | `false` | Explicitly permits migrations during this startup | No |
| `SPRING_FLYWAY_LOCATIONS` | `classpath:db/migration` | Overrides locations; add `classpath:db/dev-seed` only for disposable local/test data | No |

Passwords must never enter committed configuration, command-line arguments, URLs,
logs, screenshots, or evidence. The local dependency wrapper generates them in the
ignored `backend/.env` file with mode `0600`.

The pool maximum explicitly preserves Hikari's current default while making the
database-concurrency ceiling visible. The private MVP has no measured evidence for a
smaller or larger pool, or for overriding minimum-idle, idle-timeout, or max-lifetime;
those settings therefore remain framework-managed. The shorter acquisition and
validation timeouts bound request waits when PostgreSQL or the pool is unavailable.

## Validation

Docker must be running. From the repository root, run:

```bash
bash scripts/validate-migrations.sh
```

The command uses Testcontainers with the exact `postgres:18.4-bookworm` image and
proves:

- migration from an empty PostgreSQL 18 database;
- Flyway validation and no-op reapplication;
- checksum failure after an applied migration is changed in a controlled probe;
- deterministic eight-game seed content and all date precision variants;
- coherent current-snapshot JDBC reads and provider attribution constraints;
- identifier, current-publication, external-reference-cardinality, cover-delivery,
  foreign-key, and date/period-coherence constraints;
- runtime read access without runtime DDL permission.

The same command runs in the dedicated migration job in
`.github/workflows/build-and-verify.yml` with Java 25. The complete backend verification
also starts the Spring application against PostgreSQL 18 and
runs the production migration before Hibernate initializes:

```bash
./mvnw clean verify
```

H2 is not a dependency and is not used as a PostgreSQL substitute.

The opt-in `scripts/analyze-release-browse.sh` tool generates 10k, 100k, or 1M
release rows, executes the production read adapter, and prints PostgreSQL 18
`EXPLAIN (ANALYZE, BUFFERS)` evidence. It is diagnostic local evidence rather than a
shared-CI latency gate.

## Local application startup

Start the local PostgreSQL and Keycloak topology first:

```bash
bash scripts/local-dependencies.sh up
```

Load the ignored local credentials into the current shell, opt into Flyway for the
first startup, and optionally include the demonstration seed:

```bash
set -a
source backend/.env
set +a
APPLICATION_FLYWAY_ENABLED=true \
SPRING_FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/dev-seed \
./mvnw -pl backend spring-boot:run
```

Normal application startup leaves Flyway disabled because persistent deployments run
one serialized migration step before application replacement. The remote command and
credentials belong to the later deployment implementation; do not improvise a shared
environment procedure from the local example.

## Forward change policy

- Prefer additive, backward-compatible changes.
- Use expand-and-contract over multiple releases for incompatible changes.
- Preserve the previous valid catalogue publication until a new one is coherent.
- Use a new forward migration for corrections; application rollback assumes schema
  compatibility.
- Treat restore as data-loss or corruption recovery, not ordinary migration undo.
- Review locks, duration, data impact, and recovery before destructive SQL.
