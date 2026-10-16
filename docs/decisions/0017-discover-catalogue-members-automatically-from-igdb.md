# ADR-0017: Synchronize relevant catalogue Games and Releases automatically

- **Status:** Accepted
- **Date:** 2026-09-08
- **Owner:** Ruben Hernandez
- **Refined:** 2026-10-02, owner-approved current provider Release reconciliation (#212)
- **Scope:** Private, non-commercial learning MVP
- **Supersedes in part:** [ADR-0004](0004-synchronize-and-serve-local-catalogue-data.md)
  (membership, cover approval and global publication replacement) and the curation
  clause of the [provider spike](../research/game-data-providers-spike.md)
- **Superseded in part:** [ADR-0020](0020-acquire-platform-and-region-taxonomy-from-releases.md)
  (platform and region taxonomy is now acquired from accepted releases through typed
  provider references, not installed by migration)
- **Extended by:** [ADR-0021](0021-rank-monthly-featured-releases-by-local-igdb-hypes.md)
  (the same per-page, per-Game synchronization also acquires the popularity signal)
- **Extended:** 2026-10-04, #233: the same Game request also acquires game details (summary,
  developer and publisher credits, genres and game modes) under the per-Game atomicity and
  last-valid-state rules below; [use cases](../architecture/application/mvp-use-cases.md)
  and `CAT-009` own the behaviour

- **Extended:** 2026-10-05, owner-requested #155: recurring inbound policies reuse UC-009;
  reconciliation, paging, identity, per-Game atomicity and database fencing remain unchanged.
- **Extended:** 2026-10-14, #255 phase 1: the same Game request also acquires the IGDB player
  score and its rating count under the per-Game atomicity and last-valid-state rules below;
  the [domain model](../architecture/domain/mvp-domain-model.md) (`XSC-001`–`XSC-004`) and
  [use cases](../architecture/application/mvp-use-cases.md) own the behaviour
- **Extended:** 2026-10-15, #245: durable release-date coverage records which intervals a run
  scanned completely and when, independently of bounded run history; see
  [Coverage and freshness](#coverage-and-freshness)
- **Refined:** 2026-10-16, owner-requested #313: a stage-less provider reference that
  restates the only staged reference of one release tuple is redundant evidence, not a
  colliding Release; see [Stable identity and reconciliation](#stable-identity-and-reconciliation) and `REL-014`

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
([ADR-0020](0020-acquire-platform-and-region-taxonomy-from-releases.md) supersedes
this clause: taxonomy is now acquired from accepted releases through typed provider
references; the seeded identities are preserved and their references backfilled.)

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
fail the aggregate rather than being silently merged, with the reason
`RELEASE_TUPLE_CONFLICT`.

The single exception is redundant evidence of one Release (#313). IGDB may restate a staged
release date with a second record that has the same platform, region, date and lifecycle
but no stage. When the references sharing one release tuple include exactly one known stage
(after the stage-preservation rule below) and every other one has none and is not verified,
the staged reference is the Release and the stage-less ones add nothing to it. They are
neither stored nor counted as present: a stored one is removed like any obsolete reference,
in the same Game transaction, and the run counts them as `redundantReleases`. The evidence
selects the kept reference; no title, order or identifier tie-break is used. Two known
stages on one tuple, equal or different, no known stage at all, or verified redundant
evidence remain conflicts: an Unknown stage is missing evidence, never proof of duplication
on its own. A successfully resolved, completely
fetched and validated Game aggregate is authoritative for that Game's current
provider-owned Release set. The adapter queries all release dates for that Game with
`maxReleasesPerGame + 1` rows, below the provider request cap; an overflow sentinel,
unexpected Game/reference, duplicate reference, unreadable response or invalid Release
rejects the aggregate before persistence. A valid empty release set is authoritative too.

Create newly returned references, reconcile existing references without changing their
product identity, and remove previously stored references absent from the complete set.
Presence includes returned evidence whose update is withheld to preserve verification;
withholding an update never makes its reference missing. Removal requires both a typed
IGDB Release Date reference for the same Game and current snapshot provenance
`external_provider / IGDB / release_date`. Product-curated, official-source, unreferenced,
other-provider and shared-provider evidence remains outside this ownership boundary.
Neither stage, date, platform, region, title nor tuple equality establishes removal.
Existing unreferenced records are never heuristically linked.

After locking current revision metadata, the Game transaction removes obsolete owned
snapshots, external references and Release identities, then creates/updates returned
members and advances the existing revision for any content change. Deletion precedes
upsert so a replacement can reuse an obsolete tuple, but all writes roll back together
on any failure. No history, tombstone or soft-delete state is retained. Provider fetch,
overflow, mapping, validation or transaction failure preserves the complete last valid
Game; other Games may succeed independently. A provider-backed verification flag protects
conflicting updates, but does not turn an absent provider reference into current evidence.

Game and release changes are committed together per Game. Invalid updates preserve
that Game's complete last valid state; other valid Games in a partial run remain
committed. Identical published values do not rewrite snapshots or rotate their
revision. A committed Game still records, outside the snapshot, when it re-checked each
provider-owned release it wrote, changed or not; skipped protected evidence, withheld
conflicts and failed Games record no check (#267). That last check is distinct from the
last published change and from the provider's own update time, and is internal operational
evidence rather than player-facing data. Existing verified release evidence cannot be overwritten by conflicting
provider evidence; verification and review remain independent of automatic acquisition. Release stage is
normalized separately from lifecycle in the provider adapter. Enriching Unknown stage
on otherwise unchanged date evidence preserves verification; review follows the
[current-evidence domain rule](../architecture/domain/mvp-domain-model.md#boundaries-and-concepts).
Missing stage cannot erase an established one. Stage has no rating-eligibility effect.

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

### Coverage and freshness

A run *covers* its requested interval when it read every provider page of that interval
and at least one Game succeeded, with outcome `SUCCEEDED` or `PARTIAL`. Coverage means
that the complete interval was scanned at a known time. It does not mean the data is
permanently correct, that the provider cannot change old evidence later, or that Games
outside the interval were refreshed.

Games that fail individually do not prevent coverage. Each one keeps its last valid state,
and coverage records the run's failed Game count with the interval: zero is a clean scan.
A failed Game has no known date, so the count describes the whole run, never one day.

Some runs cover nothing:
- A run interrupted before its last page, by a provider page failure or an unexpected run
  error. Paging is keyset by Game ID rather than date, so an interrupted run leaves no
  complete date sub-interval.
- A run in which no Game succeeded (`FAILED`).
- A skipped command and a repair batch.

Their outcome stays in the run report, and coverage recorded earlier remains in place. An
interrupted run that still committed Games reports `PARTIAL` with
`SYNCHRONIZATION_INTERRUPTED`, never `SYNCHRONIZATION_COMPLETED_WITH_FAILURES`.
Before #245 that code also named page interruptions.

PostgreSQL keeps coverage as disjoint inclusive date intervals per provider. Each interval
records the run that last covered it, when that scan started and completed, and its failed
Game count. The
covering run's completion writes it in the same fenced transaction, so a run's outcome and
its coverage commit together and an abandoned worker cannot record either. A newer covering
run replaces the overlapped part of earlier intervals and leaves their remainder untouched,
even when the newer scan has failed Games and the older one was clean, because it is the
latest complete observation. A
deferred exclusion constraint keeps intervals disjoint. Storage therefore grows with the
distinct days ever covered rather than with the number of runs. Coverage has no foreign key
to `synchronization_run`, so pruning bounded run history never removes it. Coverage does
not contain provider payloads and does not change Game or Release invariants.

*Freshness* is the age of a covered period's last complete scan, and only a caller's
explicit presentation policy can judge it. No threshold is stored and nothing becomes
stale automatically. A provider-independent application read answers an inclusive range
as consecutive covered and never-covered periods. The answer is bounded, and a longer
answer continues from its first unanswered day.

Alternatives considered: deriving coverage from retained runs would lose it to pruning.
An append-only ledger of successes grows with every recurring run and needs history-wide
interval arithmetic on every read. One row per covered day is bounded too, but turns a
multi-year read into one row per day and couples storage to a calendar projection.

### Recurring inbound triggers (#155)

An opt-in in-process Spring scheduled inbound adapter calls the same operation as
the exceptional management command. This fits the approved single-instance monolith
without a broker, distributed scheduler, durable queue or another acquisition path.
Near-term/upcoming recurrence and windows are operational policy outside domain logic.
A dedicated single scheduler thread avoids scheduled-worker parallelism; the existing
PostgreSQL ownership constraint and fencing remain the concurrency authority for all
callers and instances. The trade-off is delayed or missed scheduled work during long
runs or downtime, rather than durable catch-up. Provider/trigger failure stays outside
serving and health dependencies. The [solution architecture](../architecture/mvp-solution-architecture.md)
owns current placement; configuration, policy rationale and acceptance evidence belong
to the [operations runbook](../development/operations-runbook.md#catalogue-synchronization).

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
- **Stage in the release tuple, or no tuple constraint (#313):** rejected; either would
  publish a staged release and its stage-less restatement as two Releases of the same
  commercial event, and a later equal stage would collide again.
- **Collapse every same-tuple pair (#313):** rejected; equal or unknown stages give no
  evidence that selects one reference, and contradictory stages may be distinct events.

## Consequences and limits

Overlap, retries and repeated Games are expected and idempotent. The catalogue can
grow through complete date-window runs, while its serving path stays independent of
provider availability. A release moved completely outside a later requested interval
is not refreshed unless the operator requests an interval that finds another release
of that Game; cross-window maintenance is deliberately deferred. Successful
synchronization is not human verification.
The provider can still publish incorrect evidence; normalization, constraints,
provenance and preservation of the last valid aggregate limit that risk.

Post-MVP #212 adds explicit bounded maintenance of known typed Game references that date
windows cannot rediscover, including Games with undated or obsolete release references.
A keyset batch completely refetches each known Game and delegates to the same validation,
normalization, reconciliation and atomic create/update/delete path as normal synchronization;
it is not another discovery source. Known stages are included because they can be stale too.
An apply batch uses the same database run ownership/fencing as ordinary synchronization.
Dry-run exercises the identical write transaction and constraints, rolls it back, emits no
listing event and changes neither serving state, revision nor operational history. Failed or
partial batches retain the operator checkpoint cursor; retries replay committed Games safely.
Repair may enrich stage and refresh other evidence under the existing verification/review
rules; unavailable evidence is preserved, never guessed. Its procedure and validated evidence
belong to the [operations runbook](../development/operations-runbook.md).

Full historical discovery backfill, another provider, multi-provider reconciliation,
distributed scheduling, brokers and a manual-curation backoffice remain out of scope.
Synthetic development seed IDs are unchanged and are not evidence of real IGDB
identity; they must not be used to validate live acquisition.

## Reconsider when

Revisit when measured date-window volume exceeds acceptable synchronous command duration,
repeated invalid pages require an explicit quarantine policy, `RELEASE_TUPLE_CONFLICT`
recurs for a provider pattern other than the stage-less restatement, provider terms restrict
acquisition, or a second provider is approved. Reopen the release-mode boundary
before public or commercial use as ADR-0001 and the provider spike require.
