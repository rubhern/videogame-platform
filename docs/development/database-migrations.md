# Application database migrations

- **Status:** Active walking-skeleton implementation
- **Last verified:** 2026-08-09
- **Database:** PostgreSQL `18.4`
- **Migration engine:** Flyway Community `12.4.0`, managed by Spring Boot
- **Owners:** Catalogue owns `catalogue.*`; Flyway owns schema evolution
- **Decisions:** [ADR-0006](../decisions/0006-use-postgresql-and-versioned-forward-migrations.md) and [ADR-0011](../decisions/0011-use-postgresql-and-flyway-for-application-persistence.md)

This guide records the executable persistence boundary introduced for issue #22. It
implements the minimum catalogue state required by the first public read without
implementing that endpoint, JPA entities, catalogue synchronization, ratings, or a
remote deployment procedure.

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
| `catalogue.game_snapshot` | Publication-scoped title, slug, and displayable primary cover | Game/publication foreign keys, unique slug per publication, approved cover only |
| `catalogue.platform` | Normalized platform identity | UUID primary key and unique stable code |
| `catalogue.region` | Normalized known, worldwide, or explicit unknown region | UUID primary key and unique stable code |
| `catalogue.game_release` | Stable provider-independent release identity and owning game | Release UUID uniqueness and game foreign key |
| `catalogue.release_snapshot` | Publication-scoped release tuple, provenance, quality, and freshness timestamps | Composite ownership foreign keys, tuple uniqueness, closed value checks |

Freshness status is intentionally not stored. The application derives it from the
recorded timestamps, the evaluation instant, and the operational threshold, as the
approved domain model requires.

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

PostgreSQL therefore rejects invented partial dates, invalid months or quarters, and
unknown dates carrying a synthetic value. The API adapter added later will format
these columns as the reviewed `ReleaseDate` union without increasing precision.

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
| `APPLICATION_DB_PASSWORD` | Empty; local `.env` or protected source required | Runtime database credential | Yes |
| `APPLICATION_MIGRATION_DB_URL` | Runtime JDBC target | Flyway JDBC target | No |
| `APPLICATION_MIGRATION_DB_USERNAME` | `videogame_app_migrator` | Schema-owning Flyway principal | No |
| `APPLICATION_MIGRATION_DB_PASSWORD` | Empty; local `.env` or protected source required | Flyway database credential | Yes |
| `APPLICATION_FLYWAY_ENABLED` | `false` | Explicitly permits migrations during this startup | No |
| `SPRING_FLYWAY_LOCATIONS` | `classpath:db/migration` | Overrides locations; add `classpath:db/dev-seed` only for disposable local/test data | No |

Passwords must never enter committed configuration, command-line arguments, URLs,
logs, screenshots, or evidence. The local dependency wrapper generates them in the
ignored `.env` file with mode `0600`.

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
- identifier, uniqueness, foreign-key, and date-coherence constraints;
- runtime read access without runtime DDL permission.

The same command runs in `.github/workflows/migrations.yml` with Java 25. The complete
backend verification also starts the Spring application against PostgreSQL 18 and
runs the production migration before Hibernate initializes:

```bash
./mvnw clean verify
```

H2 is not a dependency and is not used as a PostgreSQL substitute.

## Local application startup

Start the local PostgreSQL and Keycloak topology first:

```bash
bash scripts/local-dependencies.sh up
```

Load the ignored local credentials into the current shell, opt into Flyway for the
first startup, and optionally include the demonstration seed:

```bash
set -a
source .env
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
