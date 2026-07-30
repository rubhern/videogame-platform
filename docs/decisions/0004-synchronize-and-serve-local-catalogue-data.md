# ADR-0004: Synchronize and serve local catalogue data

- **Status:** Accepted
- **Date:** 2026-07-30
- **Owner:** Ruben Hernandez
- **Scope:** Private, non-commercial learning MVP
- **Related architecture:** [Learning MVP solution architecture](../architecture/mvp-solution-architecture.md)
- **Related cover decision:** [ADR-0001](0001-reference-igdb-cover-images.md)

## Context

The MVP needs a bounded, curated catalogue with provider-independent game identity,
release information, provenance, freshness, and approved cover references. IGDB is
approved with limitations for the private learning scope, but provider availability,
rate limits, taxonomy, identifiers, and payload quality must not become the public
product contract.

Visitor and rating flows require predictable local reads. New provider discoveries
and changed covers require explicit curation or review; synchronization must not
silently expand the bounded catalogue or approve image use.

## Decision

Synchronize IGDB metadata through a backend anti-corruption adapter outside user
request paths and serve normalized product-controlled data from the local relational
boundary.

Synchronization is an internal scheduled or operator-triggered use case. It:

1. fetches typed references for explicitly curated catalogue members;
2. maps provider data to provider-independent candidates;
3. validates identity, release precision, provenance, and data-quality rules;
4. stages newly discovered games outside the domain for explicit curation;
5. marks new or materially changed covers `pending_review` and uses the product
   fallback;
6. publishes only coherent updates for existing curated members;
7. preserves the previous valid snapshot on fetch, mapping, validation, or
   publication failure.

User request paths never call IGDB as a fallback. When no valid local snapshot
exists, they return the stable `CATALOGUE_NOT_READY` application error. Approved
covers remain direct, attributed, allowlisted CDN references under ADR-0001; the
application does not copy, proxy, or persist provider image binaries.

## Alternatives considered

### Call IGDB from visitor requests

This reduces local synchronization work but couples user latency and availability to
the provider, leaks provider concerns into application flows, and complicates rate
limits and stable contracts.

### Import all provider results automatically

This creates a broader catalogue quickly, but violates the approved bounded scope
and bypasses curation, identity, and cover-review controls.

### Store and expose raw provider payloads

This is easy to ingest, but makes provider taxonomy and identifiers part of product
state and weakens validation and future provider replacement.

### Copy or proxy provider cover binaries

This could isolate runtime CDN failure, but conflicts with the accepted cover usage
boundary and adds storage, redistribution, and lifecycle obligations.

## Consequences

### Positive

- Catalogue and rating requests are independent from live IGDB API availability.
- Public identity and API payloads remain provider-independent.
- Validate-before-publish and last-valid snapshot semantics prevent partial or
  corrupt state.
- Explicit staging preserves the bounded catalogue and cover-review policy.

### Negative

- Catalogue data is intentionally not real-time.
- Synchronization, staging, freshness, and operational visibility must be built and
  maintained.
- Initial catalogue membership and review require owner curation.

## Risks and mitigations

- **Stale catalogue:** expose freshness and last synchronization state, then define
  cadence and stale thresholds from the approved product contract.
- **Silent data loss:** publish coherent snapshots atomically and never treat a
  partial provider response as deletion.
- **Provider schema drift:** use defensive mapping, fixtures, rejected-record
  telemetry, and contract tests.
- **Unbounded growth:** fetch curated references and stage discoveries rather than
  admitting them automatically.
- **Cover-policy regression:** separate synchronization from approval and enforce
  ADR-0001 allowlisting, attribution, and fallback.

## Follow-up actions

- Define the synchronization trigger, cadence, retry limits, stale thresholds, and
  operator visibility during implementation design.
- Define candidate staging, review state, snapshot publication, and provider
  reference persistence.
- Reuse the authenticated IGDB PoC fixtures for adapter contract tests.
- Add tests proving that provider failures preserve the previous valid snapshot and
  that synchronization cannot admit games or approve covers automatically.
