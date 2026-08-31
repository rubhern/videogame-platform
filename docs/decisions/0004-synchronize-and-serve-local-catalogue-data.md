# ADR-0004: Synchronize and serve local catalogue data

- **Status:** Accepted
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
- Translate provider data into product-owned candidate models behind an adapter.
- Validate and publish a complete bounded catalogue version atomically; continue
  serving the last valid publication on failure.
- Preserve provider identifiers and provenance for refresh and audit, without
  exposing raw provider payloads as the product contract.
- Bound each synchronization page, batch, retry and concurrency level.
- Treat changed cover references conservatively and apply
  [ADR-0001](0001-reference-igdb-cover-images.md).

Operational synchronization behavior is defined in the
[platform design](../architecture/deployment/mvp-platform-and-delivery.md).

## Alternatives considered

- **Call IGDB during visitor requests:** rejected because provider behavior would
  become product latency and availability.
- **Automatically expose every provider result:** rejected because provider taxonomy
  and quality are not product policy.
- **Persist/expose raw payloads or copy image binaries:** rejected because it leaks
  provider coupling and expands retention obligations.

## Consequences

Reads are fast, provider-independent and resilient to synchronization failure, at the
cost of freshness lag, publication storage and an explicit synchronization workflow.

## Reconsider when

Revisit publication and retention mechanics when measured catalogue size or sync
duration makes the current bounded approach insufficient; do not add streaming or
distributed infrastructure without that evidence.
