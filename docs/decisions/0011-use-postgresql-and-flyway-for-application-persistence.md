# ADR-0011: Use Flyway and persistence adapters with PostgreSQL

- **Status:** Accepted
- **Date:** 2026-08-04
- **Decision owner:** Ruben Hernandez
- **Scope:** Private, non-commercial learning MVP
- **Technology baseline:** [Learning MVP technology baseline](../architecture/technology/mvp-technology-baseline.md)
- **Solution architecture:** [Learning MVP solution architecture](../architecture/mvp-solution-architecture.md)
- **Platform design:** [Learning MVP platform and delivery design](../architecture/deployment/mvp-platform-and-delivery.md)

## 1. Context

The MVP requires one application-owned relational consistency boundary for games,
releases, normalized provider state, product users, personal ratings, aggregate
results, and synchronization metadata. Uniqueness, transactions, deterministic
queries, migrations, backup, restore, and future schema evolution are material from
the first vertical slice.

[ADR-0006](0006-use-postgresql-and-versioned-forward-migrations.md) already selects
PostgreSQL and immutable forward migrations. This ADR does not reconsider that
decision. It selects the migration tool and persistence-adapter strategy that the
accepted platform ADR deliberately left open.

## 2. Decision drivers

- Strong relational integrity and transaction support.
- Mature indexing, query planning, JSON support, and operational tooling.
- One database engine for local, CI, `dev`, and future production-like environments.
- SQL visible in code review.
- Simple migration model for one application and one engine.
- Compatibility with Spring Boot, JPA, Testcontainers, backups, and common hosting
  platforms.
- Forward-compatible rollout and rollback of application versions.
- Low conceptual and licensing overhead for a personal MVP.

## 3. Considered migration options

### Option A — Flyway Community with SQL migrations

**Benefits**

- Simple versioned and repeatable migration model.
- SQL-first: reviewers see the exact PostgreSQL operations.
- `migrate`, `validate`, `info`, `repair`, and baseline capabilities cover the MVP.
- Direct Spring Boot and PostgreSQL integration.
- Fits forward-only and expand-and-contract delivery.

**Costs**

- Less declarative portability across database engines.
- Rich environment contexts, labels, preconditions, and rollback modelling are more
  limited than Liquibase.
- Safe rollback remains an application and schema design responsibility.

### Option B — Liquibase

**Benefits**

- Changelogs in SQL, XML, YAML, or JSON.
- Rich changesets, contexts, labels, preconditions, and rollback capabilities.
- Stronger abstraction when supporting several database engines or complex governed
  deployment combinations.

**Costs**

- Larger conceptual surface than required for one PostgreSQL application.
- Declarative changes may hide engine-specific SQL details that reviewers need to
  evaluate.
- Context and label flexibility can create environment-dependent schema paths if not
  tightly governed.

### Option C — Framework-generated schema

Examples include Hibernate `ddl-auto` updates.

**Benefits**

- Minimal initial setup.

**Costs**

- Inadequate review, sequencing, repeatability, and deployment control.
- Couples schema lifecycle to application startup.
- Unsafe for persistent shared environments.

## 4. Decision

Use:

```text
PostgreSQL 18.x
Flyway Community
SQL versioned migrations
Spring Data JPA / Hibernate in persistence adapters
Explicit SQL or JdbcClient projections where clearer
Testcontainers PostgreSQL for integration tests
```

PostgreSQL 18 is the approved initial supported line inherited from the technology
baseline. The project accepts PostgreSQL-specific SQL. Database portability is not
an MVP requirement.

Flyway is selected over Liquibase because both provide the required core schema
versioning capabilities, while Flyway's SQL-first model is simpler and better aligned
with one PostgreSQL database, Git-based review, and forward migration.

## 5. Migration policy

### 5.1 File model

Use timestamp-based versioned migrations:

```text
V20260803_180000__create_catalogue_schema.sql
V20260803_181000__create_ratings_schema.sql
V20260805_090000__add_rating_version.sql
```

Repeatable migrations are limited to replaceable objects:

```text
R__catalogue_search_view.sql
```

### 5.2 Immutability

- An applied migration MUST NOT be edited.
- A correction uses a new migration.
- Checksums are validated in CI and deployment.
- `repair` requires explicit diagnosis and review; it is not a normal way to hide
  changed history.

### 5.3 Deployment

For persistent remote environments:

```text
backup or recovery point where required
    -> flyway validate
        -> flyway migrate with migration credentials
            -> deploy application image
                -> readiness
                    -> smoke tests
```

Application instances do not race to perform remote migrations at startup.

### 5.4 Forward compatibility

Potentially incompatible changes use expand-and-contract:

1. add compatible schema;
2. deploy code that supports old and new representations;
3. migrate or backfill data;
4. switch reads and writes;
5. verify;
6. remove obsolete schema in a later release.

Application rollback returns to an earlier image only while the schema remains
backward compatible. A new corrective migration is preferred to an automated
reverse migration.

### 5.5 Safety

- `flyway clean` is forbidden outside disposable local or CI databases.
- Destructive SQL requires explicit data-impact review and recovery evidence.
- Large table changes require lock and duration assessment.
- Database backups are not considered valid until restoration is tested.
- Runtime credentials SHOULD not have DDL privileges.

## 6. Persistence mapping policy

- JPA entities live only in outbound persistence adapters.
- Domain entities and value objects do not become JPA entities by default.
- API DTOs do not become persistence models.
- Module-owned schemas or tables are not queried directly by another module.
- Critical invariants use database constraints as well as application rules.
- Catalogue queries MAY use explicit SQL projections for clarity and performance.
- Integration tests use PostgreSQL, not H2 as a behavioural substitute.
- Hibernate schema auto-update is disabled for maintained environments.

## 7. Consequences

### Positive

- One mature database for every environment.
- Strong transaction and constraint support for rating consistency.
- Exact SQL is visible in pull-request review.
- Simple migration history and operational model.
- Real PostgreSQL integration tests are straightforward with Testcontainers.
- Broad hosting and backup options remain available.

### Negative

- SQL and migrations are PostgreSQL-specific.
- Developers must understand locks, indexes, execution plans, and migration safety.
- Automated rollback is intentionally limited; changes require compatibility design.
- JPA can produce inefficient queries if adapter behaviour is not reviewed and tested.

### Accepted

- Liquibase's richer contexts, labels, preconditions, and rollback model are not used
  because current scope does not justify their complexity.
- A future multi-database or heavily governed enterprise deployment may reconsider the
  migration tool.

## 8. Implementation verification

Acceptance authorizes implementation. The walking skeleton and later delivery work
must provide the following evidence:

- PostgreSQL 18 starts locally and in Testcontainers;
- Flyway creates an empty database from zero;
- `flyway validate` detects an intentionally modified applied migration in a test;
- Catalogue and Ratings ownership can be expressed without cross-module table access;
- rating uniqueness and optimistic concurrency are protected under concurrent tests;
- the deployment pipeline can run migrations separately from application startup;
- a backup and restoration exercise succeeds for the persistent `dev` environment.

The Java build must include Flyway's open-source PostgreSQL database module. A
blocking PostgreSQL 18, Flyway, JDBC, or `linux/arm64` incompatibility reopens this
ADR.

## 9. Reconsideration triggers

- A bounded context develops storage needs incompatible with PostgreSQL.
- Multiple database engines become an explicit supported product requirement.
- Regulatory governance requires richer change controls or generated rollback plans.
- Flyway Community no longer supplies required foundational migration capabilities.
- Database size or availability requirements justify a materially different data
  platform.

## 10. Official references

- [PostgreSQL 18 documentation](https://www.postgresql.org/docs/18/)
- [PostgreSQL release notes](https://www.postgresql.org/docs/release/)
- [Flyway commands](https://documentation.red-gate.com/flyway/reference/commands)
- [Flyway PostgreSQL support](https://documentation.red-gate.com/fd/postgresql-database-277579325.html)
- [Liquibase changelog concepts](https://docs.liquibase.com/secure/user-guide-5-2-1/what-is-a-changelog)
- [Liquibase contexts](https://docs.liquibase.com/secure/reference-guide-5-2-1/changelog-attributes/what-are-contexts)
- [Liquibase labels](https://docs.liquibase.com/secure/reference-guide-5-2-1/changelog-attributes/what-are-labels)

## 11. Change history

| Date | Status | Change |
|---|---|---|
| 2026-08-03 | Proposed | Initial decision selecting PostgreSQL 18, Flyway Community, SQL-first migrations, JPA adapters, and Testcontainers. |
| 2026-08-04 | Accepted | Narrowed the ADR to Flyway and persistence adapters, inherited PostgreSQL from ADR-0006, and moved executable evidence to implementation gates. |
