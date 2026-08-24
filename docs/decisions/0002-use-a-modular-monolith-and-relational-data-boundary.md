# ADR-0002: Use a modular monolith and relational data boundary

- **Status:** Accepted
- **Date:** 2026-07-30
- **Owner:** Ruben Hernandez
- **Scope:** Private, non-commercial learning MVP

## Context

The product is developed and operated by one person, its principal data is relational
and its domain boundaries still need evidence. Distributed services and stores would
add deployment and consistency costs before supplying product value.

## Decision

Build one deployable modular monolith backed by one PostgreSQL service:

- organize code by business modules, not only technical layers;
- keep domain and application framework-independent behind inbound and outbound
  ports;
- give each module logical ownership of its tables and prevent bypassing module
  boundaries merely because the physical database is shared;
- communicate through explicit application contracts or events where useful;
- place Spring wiring in each owning module's `configuration` composition root;
- keep external providers behind adapters and keep correctness independent of
  process-local state.

The current module and dependency view is canonical in the
[solution architecture](../architecture/mvp-solution-architecture.md).

## Alternatives considered

- **Microservices and separate databases:** rejected as premature operational and
  consistency complexity.
- **Layered monolith without business modules:** rejected because ownership and
  change boundaries would remain implicit.
- **Multiple stores or non-relational primary storage:** rejected without an access
  pattern that requires them.

## Consequences

The system remains simple to build, transact and operate while preserving explicit
domain boundaries. Discipline and architecture tests are required because the
compiler and database cannot enforce every logical boundary. A future extraction
would still require evidence and deliberate data separation.

## Reconsider when

Revisit only when measured independent scaling, availability, release cadence,
ownership or data-boundary needs cannot be met inside the modular monolith.
