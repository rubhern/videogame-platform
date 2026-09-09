# ADR-0017: Synchronize relevant catalogue Games and Releases automatically

- **Status:** Accepted
- **Date:** 2026-09-08
- **Owner:** Ruben Hernandez
- **Scope:** Private, non-commercial learning MVP
- **Supersedes in part:** [ADR-0004](0004-synchronize-and-serve-local-catalogue-data.md)
  (membership, cover approval and global publication replacement) and the curation
  clause of the [provider spike](../research/game-data-providers-spike.md)

## Context

Manual catalogue membership and per-cover approval blocked unattended acquisition.
The intermediate implementation also separated discovery from maintenance and copied
the complete catalogue for every successful run. Neither is necessary for the
release-focused product: an operator should request synchronization, and local
identity should determine whether a record is created or updated.

## Decision

There is one automatic catalogue synchronization operation. The operator supplies an
inclusive release-date interval (`from`, `to`), not a discovery/maintenance command
or a maximum number of Games. One POST attempts the complete requested interval.

### Complete interval and internal paging

The provider adapter reads IGDB `release_dates` whose date is between the operator's
inclusive `from` and `to`. The upper boundary becomes the next day's exclusive UTC
instant in the IGDB query. The operation does not impose a total Game limit: the
selected dates are its scope, including a historical interval when the operator
deliberately requests one.

A configured `providerPageSize` (maximum 500) bounds each IGDB page;
it does not bound total Games or total work in the POST. Each Game fetch remains
bounded by `maxReleasesPerGame` plus an overflow sentinel, so an incomplete aggregate
is never published. The release-date query is ordered and keyset-paged by IGDB Game
ID. IDs are deduplicated inside each page; advancing past the last Game is safe because
that Game's separate aggregate query retrieves all of its Releases, including release
rows beyond the discovery-page boundary. Application memory is `O(providerPageSize +
maxReleasesPerGame)`; total database/network/time work grows with the unique Games in
the requested interval.

The Game-ID cursor exists only in the application stack for that POST. There is no
persistent candidate queue or synchronization checkpoint. One POST loops until IGDB
returns the final page, then records the run. A failed Game is counted and does not
stop later Games; its transaction rolls back while earlier and later valid Game commits
survive. A provider page failure ends the run as failed or partial. A future request
starts the interval again and relies on stable-reference idempotence.

### Stable identity and reconciliation

The normal migration installs the existing approved platform/region taxonomy.
Acquisition does not require demonstration Games or synthetic provider IDs from
the optional development seed. Existing taxonomy identities are preserved.

Provider Game IDs are deduplicated before resolving product identity. An unknown
typed IGDB Game reference creates a random internal `GameId`, Game, external
reference, current state, supported releases and valid cover atomically. A known
reference resolves the same Game. Provider IDs never become product IDs or slugs;
no title matching occurs.

The import policy admits main Games, remakes, remasters and standalone expansions.
Add-ons, seasons, episodes, bundles, packs, updates, mods, forks and ports are
deferred rather than silently creating duplicate product works.

An IGDB `release_date.id` identifies an external Release reference independently
of its mutable date, platform, region and status. Unknown references create an
internal Release; known references reconcile that Release. Multiple release dates
for one Game, including rows on different pages or cycles, remain multiple Releases
of exactly one Game. Tuple constraints validate coherence but never resolve identity.
Different provider references that collide with a product uniqueness constraint
fail the aggregate rather than being silently merged. Missing provider releases
are not deleted; existing records without an external release reference are not
heuristically linked by date or title.

Game and release changes are committed together per Game. Invalid updates preserve
that Game's complete last valid state; other valid Games in a partial run remain
committed. Identical published values do not rewrite snapshots or rotate their
revision. Existing verified release evidence cannot be overwritten by conflicting
provider evidence; verification and review remain independent of automatic acquisition.

A valid cover is published or replaced automatically. Invalid/missing cover data
keeps the last valid cover, or the product fallback for a new Game. This applies the
approved usage mode in [ADR-0001](0001-reference-igdb-cover-images.md), not per-image
human approval.

### Current state, not global copies

There is one current Game snapshot and one current Release snapshot per identity.
The legacy `catalogue_publication` table and its foreign-key names remain only as
singleton revision metadata for compatible public reads, seeds and cache validators.
Its ID does not rotate; a content change updates its version in the Game transaction.
It is not a historical catalogue snapshot. There is no catalogue-wide copying,
publication rotation or publication retention workflow.

`synchronization_run` is bounded operational history with the requested interval,
outcome and counters, not catalogue content. PostgreSQL enforces one active run per
provider; writes fence abandoned runs before a successor can publish.
Public readers continue using repeatable-read local PostgreSQL state and never call
IGDB. Provider DTOs remain inside the adapter; domain/application use normalized
provider-independent values.

## Alternatives considered

- **Operator-selected discovery and maintenance commands:** rejected; local external
  references determine create/update, not operator choice.
- **Reconcile every known historical Game as a second source:** deferred; the explicit
  operator interval is the complete scope of this simpler command.
- **Release identity from date/platform/region or position:** rejected; mutable
  evidence cannot safely identify the Release being corrected.
- **Global copy-on-write publications:** rejected; per-Game transactions preserve
  coherence without storage and work proportional to catalogue size.
- **Title matching or manual approval:** rejected; deterministic references and
  validation support unattended acquisition without a backoffice.

## Consequences and limits

Overlap, retries and repeated Games are expected and idempotent. The catalogue can
grow through complete date-window runs, while its serving path stays independent of
provider availability. A release moved completely outside a later requested interval
is not refreshed unless the operator requests an interval that finds another release
of that Game; cross-window maintenance is deliberately deferred. Successful
synchronization is not human verification.
The provider can still publish incorrect evidence; normalization, constraints,
provenance and preservation of the last valid aggregate limit that risk.

Full historical backfill, another provider, multi-provider reconciliation,
distributed scheduling, brokers and a manual-curation backoffice remain out of scope.
Synthetic development seed IDs are unchanged and are not evidence of real IGDB
identity; they must not be used to validate live acquisition.

## Reconsider when

Revisit when measured date-window volume exceeds acceptable synchronous command duration,
repeated invalid pages require an explicit quarantine policy, provider terms restrict
acquisition, or a second provider is approved. Reopen the release-mode boundary
before public or commercial use as ADR-0001 and the provider spike require.
