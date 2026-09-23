# ADR-0004: Synchronize and serve local catalogue data

- **Status:** Accepted
- **Superseded in part:** catalogue membership, cover approval and global publication replacement, by
  [ADR-0017](0017-discover-catalogue-members-automatically-from-igdb.md)
- **Date:** 2026-07-30
- **Owner:** Ruben Hernandez
- **Scope:** Private, non-commercial learning MVP

## Context

Provider latency, outages, quotas and schemas must not define user-request behavior or
the product model. The application nevertheless needs a curated, refreshable
catalogue and a safe last-known-good state.

## Decision

- Synchronize through a bounded background/administrative use case, never from a
  user request path.
- Translate provider data into product-owned normalized models behind an adapter.
- Historical decision: publish a complete catalogue version atomically. ADR-0017
  replaces this with atomic current Game state and lightweight revision metadata.
- Preserve provider identifiers and provenance for refresh and audit, without
  exposing raw provider payloads as the product contract.
- Bound each synchronization page, per-aggregate write, retry and concurrency level.
- Treat changed cover references conservatively and apply
  [ADR-0001](0001-reference-igdb-cover-images.md).

[ADR-0017](0017-discover-catalogue-members-automatically-from-igdb.md) later replaced
the membership, cover and publication-unit clauses of this record. Atomicity now
applies to each Game aggregate, not a global catalogue copy. Local serving, provider
isolation, bounded work and preservation of last valid Game state remain in force.

Operational synchronization behavior is defined in the
[platform design](../architecture/deployment/mvp-platform-and-delivery.md).

## Alternatives considered

- **Call IGDB during visitor requests:** rejected because provider behavior would
  become product latency and availability.
- **Automatically expose every provider result:** rejected because provider taxonomy
  and quality are not product policy. ADR-0017 keeps this rejection and adds the
  explicit import policy that decides which provider work types become a `Game`.
- **Persist/expose raw payloads or copy image binaries:** rejected because it leaks
  provider coupling and expands retention obligations.

## Consequences

Reads are fast, provider-independent and resilient to synchronization failure, at the
cost of freshness lag, publication storage and an explicit synchronization workflow.

## Reconsider when

Revisit publication and retention mechanics when measured catalogue size or sync
duration makes the current bounded approach insufficient; do not add streaming or
distributed infrastructure without that evidence.
