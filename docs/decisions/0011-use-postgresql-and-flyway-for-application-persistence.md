# ADR-0011: Use Flyway and persistence adapters with PostgreSQL

- **Status:** Accepted
- **Date:** 2026-08-04
- **Owner:** Ruben Hernandez
- **Scope:** Application persistence

## Context

The approved PostgreSQL boundary needs reviewable schema evolution and persistence
mapping that does not turn domain objects into framework-owned records. PostgreSQL
semantics, constraints and query plans are part of the design, not an implementation
detail to emulate with H2.

## Decision

- Use Flyway Community with immutable, versioned SQL migrations; reserve repeatable
  migrations for replaceable objects.
- Accept PostgreSQL-specific SQL and test database behavior against PostgreSQL with
  Testcontainers.
- Keep JPA/Hibernate entities and repositories inside outbound persistence adapters.
  Domain, application and API models remain separate.
- Use explicit JDBC/SQL projections where they express bounded reads more clearly or
  efficiently than aggregate persistence.
- Disable Hibernate schema update and deny DDL to runtime application credentials.
- Enforce integrity with keys, checks, uniqueness and transactions; inspect real SQL
  and plans for changed hot queries.
- Apply migrations through the deployment sequence defined by
  [ADR-0006](0006-use-postgresql-and-versioned-forward-migrations.md).

Migration naming, authoring and validation belong to
[database migrations](../development/database-migrations.md).

## Alternatives considered

- **Liquibase:** capable but its additional model and controls are not required for
  the current single-PostgreSQL scope.
- **Framework-generated schema:** rejected because it is not an immutable,
  reviewable deployment record.
- **H2 as integration proof:** rejected because it does not reproduce PostgreSQL
  behavior.

## Consequences

SQL and schema history are explicit, real database behavior is testable and domain
models remain persistence-independent. The team must understand PostgreSQL locks,
indexes, plans and forward compatibility; JPA behavior still needs query review.

## Reconsider when

Revisit only for a supported multi-engine requirement, governance that genuinely
needs richer change controls, a bounded context whose storage needs do not fit
PostgreSQL, or loss of required Flyway Community capability.
