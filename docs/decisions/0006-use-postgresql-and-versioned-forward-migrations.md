# ADR-0006: Use PostgreSQL and versioned forward migrations

- **Status:** Accepted
- **Date:** 2026-08-03
- **Owner:** Ruben Hernandez
- **Scope:** Private, non-commercial learning MVP
- **Related architecture:** [ADR-0002](0002-use-a-modular-monolith-and-relational-data-boundary.md)
- **Related platform:** [Learning MVP platform and delivery design](../architecture/deployment/mvp-platform-and-delivery.md)

## Context

ADR-0002 accepts one relational application boundary but leaves the database product
and schema-evolution approach open. The MVP needs uniqueness, transactions, filtering,
pagination, aggregate ratings, coherent catalogue publication, and reliable backup and
restore. Keycloak also requires production-ready relational persistence.

The zero-cost OCI design cannot rely on a managed PostgreSQL service, so the initial
environment must operate the database itself without weakening data guarantees.

## Decision

Use a supported PostgreSQL version as the initial relational database product.

Run one PostgreSQL server per environment with:

- one application database and role;
- one separate Keycloak database and role;
- logical ownership inside the application database for Catalogue and Ratings;
- database constraints for critical invariants such as one active rating per
  `UserId + GameId`.

Represent every application schema change as an ordered immutable forward migration.
Applied migrations are never edited. CI proves migration from an empty database and,
after the first released schema exists, upgrade from the latest supported prior state.
One deployment actor runs migrations before application replacement.

Use expand/contract for destructive or incompatible changes. Application rollback is
allowed only when the previous version remains schema-compatible. Otherwise use a
forward fix, proven down migration, or explicit restore according to the change plan.

The migration product remains an implementation choice for the technology baseline.
It must support PostgreSQL, checksummed ordered migrations, CI execution, and visible
failure; Flyway and Liquibase are acceptable candidates.

## Alternatives considered

### Oracle Autonomous Database

It has an Always Free offer and teaches an Oracle-managed service, but it changes the
SQL/runtime boundary, reduces PostgreSQL portability, and does not match the selected
learning stack as directly.

### Embedded or file database

It simplifies local startup but provides poor fidelity for concurrency, migrations,
constraints, and recovery in the deployed slice.

### Managed PostgreSQL free tier

It reduces operational burden, but introduces another provider and free-tier lifecycle
outside the selected zero-cost OCI boundary. Reconsider it if self-operation becomes
the dominant risk.

### Automatic schema generation at startup

It is convenient during prototyping but does not provide reviewed, repeatable,
auditable production-like evolution or a safe rollback boundary.

## Consequences

### Positive

- PostgreSQL is mature, portable, well supported, and suitable for the domain and
  learning goals.
- Explicit migrations make schema change testable and operationally visible.
- Separate databases/roles isolate Keycloak persistence from application ownership.
- Standard logical backups provide a provider-independent exit path.

### Negative

- The owner must patch, monitor, back up, and restore PostgreSQL in `dev`.
- Application and Keycloak share one physical database failure boundary.
- Forward-compatible migration discipline adds implementation and deployment work.

## Risks and mitigations

- **Data loss:** encrypted off-VM backups and tested isolated restores.
- **Cross-component access:** separate databases, roles, credentials, and least
  privilege.
- **Migration/application skew:** serialize deployment, expose migration version, and
  fail readiness on incompatible schema.
- **Unsafe rollback:** require compatibility analysis and prefer expand/contract.
- **Resource pressure:** set bounded connections and retention; measure before adding
  replicas or a managed service.

## Follow-up actions

- Select the supported PostgreSQL and migration-tool versions in the technology
  baseline.
- Define initial application and Keycloak databases, roles, migrations, and health
  checks.
- Add empty-schema and upgrade migration tests.
- Document and rehearse backup, restore, and schema-compatible application rollback.
