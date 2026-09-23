# ADR-0020: Acquire platform and region taxonomy from accepted releases

- **Status:** Accepted
- **Date:** 2026-09-22
- **Owner:** Ruben Hernandez
- **Scope:** Private, non-commercial learning MVP
- **Supersedes in part:** [ADR-0017](0017-discover-catalogue-members-automatically-from-igdb.md)
  (the clause "the normal migration installs the existing approved platform/region
  taxonomy")

## Context

ADR-0017 kept platform and region taxonomy as a fixed set installed by migration, and
the IGDB adapter mapped provider platforms and release regions to that set through a
configured slug/name allowlist. An unmapped provider platform was a reported mapping
failure. This froze the supported taxonomy to the MVP allowlist and made mutable
provider slugs and names behave like identity, so a legitimate new platform could not
appear without a configuration change, and a provider rename risked merging or
dropping releases.

`Platform` and `Region` are provider-independent product concepts. The catalogue
should support the taxonomy that accepted releases actually use, without importing the
whole provider catalogue and without provider identifiers leaking into the product.

## Decision

Platform and region taxonomy is acquired from accepted releases through typed provider
external references, not from a fixed allowlist.

When synchronization accepts a release, the adapter emits the provider platform and
release-region as typed references keyed by their provider entity ID, with the slug and
name carried only as descriptive metadata. Within the same accepted-state write, the
store resolves each reference: a known reference reuses the existing product platform
or region; an unknown one creates the product entity and its external reference
(`platform_external_reference`, `region_external_reference`). Product taxonomy keeps
its internal UUID identity. Provider IDs are external references only and never become
product IDs or browser-facing identity. Resolution never matches by title, name or
slug, so a provider rename does not change or merge product identity, and an absent
release region resolves to the product `unknown` sentinel, which has no provider
reference.

The complete provider platform/region catalogue is not eagerly synchronized. The
existing seeded platforms and regions keep their product UUID identity; a migration
backfills their provider references so a real synchronization reuses them instead of
creating duplicates.

## Alternatives considered

- **Keep the migration-installed allowlist (ADR-0017):** rejected; it froze taxonomy
  to the MVP set and treated mutable slugs and names as identity.
- **Eagerly synchronize the whole IGDB platform/region catalogue:** deferred; no
  evidence requires master-data synchronization, and acquisition from accepted
  releases covers the taxonomy the product actually serves.
- **Match provider entities by normalized name or slug:** rejected; mutable
  descriptors cannot safely establish identity and would silently merge distinct
  entities.

## Consequences and limits

New platforms and regions appear automatically as accepted releases introduce them,
with stable product identity across runs. The serving path stays provider-independent
and never calls IGDB. Release filtering and available-filter discovery remain
PostgreSQL-side over this taxonomy. A newly acquired platform or region has no
frontend icon until one is added; the frontend resolves icons by stable product ID
with an accessible generic fallback, so acquisition never breaks the UI. Broad
provider master-data reconciliation and a taxonomy administration backoffice remain
out of scope.

## Reconsider when

Revisit when validation or reconciliation needs the standalone IGDB platform/region
master endpoints beyond the data carried on accepted releases, when a second provider
is approved, or when editorial or configurable taxonomy metadata (for example a
semantic icon key) becomes a demonstrated product requirement.
