# ADR-0002: Use a modular monolith and relational data boundary

- **Status:** Accepted
- **Date:** 2026-07-30
- **Owner:** Ruben Hernandez
- **Scope:** Private, non-commercial learning MVP
- **Related architecture:** [Learning MVP solution architecture](../architecture/mvp-solution-architecture.md)

## Context

The first vertical slice covers catalogue discovery, game details, personal ratings,
aggregate ratings, and bounded catalogue synchronization. Catalogue and Ratings have
different business ownership, but they collaborate synchronously and share
consistency needs. There is one product owner, no demonstrated independent scaling
or deployment need, and no requirement for distributed failure isolation.

Rating uniqueness, safe update and deletion, coherent aggregate results, and atomic
catalogue publication benefit from database constraints and transactions. The
current data is structured and does not require a specialized store.

## Decision

Implement the initial backend as one deployable modular monolith with explicit
`Catalogue and Releases` and `Ratings` business modules.

Use one application-owned relational database as the initial physical data boundary.
Each module owns its logical tables or schema. Modules collaborate through
application contracts and must not read or write another module's persistence
directly.

Apply hexagonal dependency rules inside the modules:

- domain code remains independent from frameworks and infrastructure;
- application use cases define inbound and outbound ports at meaningful boundaries;
- delivery, persistence, identity, provider, and telemetry integrations are adapters;
- automated architecture tests protect dependency and module-ownership rules.

PostgreSQL and versioned forward migrations are subsequently selected by
[ADR-0006](0006-use-postgresql-and-versioned-forward-migrations.md). The persistence
framework, schema layout, and migration tool remain implementation decisions.

## Alternatives considered

### Microservices with separate databases

This offers independent deployment and failure isolation, but adds network
contracts, distributed consistency, more infrastructure, and greater operational
cost without a current requirement.

### A layered monolith without explicit business modules

This is initially simple, but makes ownership and extraction boundaries easier to
erode as catalogue and rating behaviour grows.

### Multiple stores or a non-relational primary store

Specialized stores could support future query or scale needs, but they add
consistency and operational complexity without evidence that the relational model is
insufficient.

## Consequences

### Positive

- One deployable and one transactional data boundary keep the MVP operable by one
  person.
- Database constraints can protect rating uniqueness and other durable invariants.
- Explicit module contracts preserve business ownership and allow later extraction.
- Domain and application tests remain independent from infrastructure choices.

### Negative

- Modules share a deployment and failure boundary.
- Independent scaling is unavailable until a justified extraction.
- A shared database makes accidental cross-module coupling possible.

## Risks and mitigations

- **Boundary erosion:** enforce no cross-module repository or table access with
  architecture tests and code review.
- **Oversized abstractions:** create ports and domain objects only where behaviour or
  an external boundary justifies them.
- **Premature extraction assumptions:** treat module boundaries as ownership rules,
  not promises of future microservices.
- **Database contention:** establish measurements and optimize queries and indexes
  before adding stores or extracting services.

## Follow-up actions

- Use ADR-0006 as the database and migration-strategy decision; select the persistence
  framework and migration tool in the technology baseline.
- Define schema ownership, migrations, constraints, and indexes during
  implementation design.
- Add automated dependency and module-boundary tests with the first vertical slice.
- Revisit this decision only when measured scaling, ownership, security, deployment,
  or failure-isolation needs justify a different boundary.
