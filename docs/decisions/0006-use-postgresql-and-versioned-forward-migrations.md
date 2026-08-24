# ADR-0006: Use PostgreSQL and versioned forward migrations

- **Status:** Accepted
- **Date:** 2026-08-03
- **Owner:** Ruben Hernandez
- **Scope:** Private, non-commercial learning MVP

## Context

Catalogue releases, identity links and ratings need relational integrity,
transactions and queryable history. The schema must evolve reproducibly across local
and remote environments without startup-time inference or a paid managed service.

## Decision

- Use PostgreSQL as the application database and as Keycloak's database service,
  with separate databases/roles and no application access to Keycloak tables.
- Evolve the application schema with immutable, ordered, forward-only SQL migrations.
- Apply migrations once per deployment before starting the new application version;
  application instances do not race to migrate.
- Use expand-and-contract changes when versions may overlap; restore data and deploy
  forward rather than editing an applied migration.
- Protect critical invariants with database constraints and explicit transaction
  boundaries.

[Database migrations](../development/database-migrations.md) owns the authoring and
verification procedure.

## Alternatives considered

- **Managed/alternative database:** rejected because it conflicts with the free,
  portable baseline without supplying needed capability.
- **Embedded/file database:** rejected because it would not validate production-like
  PostgreSQL semantics.
- **ORM schema generation:** rejected because it lacks reviewable, reproducible
  deployment history.

## Consequences

Schema changes are explicit, auditable and portable, and relational invariants can be
enforced close to the data. Developers must design forward compatibility, migration
ordering, backups and recovery deliberately.

## Reconsider when

Revisit the physical database service only when measured availability, scale or
operational evidence justifies a compatible managed or separated topology.
