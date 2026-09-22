# Learning MVP application use cases

- **Status:** Approved
- **Owner:** Ruben Hernandez
- **Scope:** Application operations and guarantees; not HTTP, screens, SQL, or framework classes

## Catalogue

| ID       | Operation                           | Actor                 | Required behaviour                                                                                                                                                                                   |
|----------|-------------------------------------|-----------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `UC-001` | Browse recent/upcoming releases     | Visitor               | Application derives evaluation date/window; PostgreSQL classifies recent/upcoming from the effective release date against the window (not from a persisted status), then groups the matching releases by game so a result is one game with only its view-and-filter-matching releases, and counts, uniquely orders, and pages games; cancelled is excluded from both views and delayed from recent; TBA upcoming sorts last; stale/empty/fallback are valid states |
| `UC-002` | Search bounded catalogue            | Visitor               | Normalize the query once; PostgreSQL matches canonical titles/approved aliases, ranks, counts, uniquely orders and pages; zero/multiple matches are valid; never call provider                       |
| `UC-003` | View game details                   | Visitor/optional user | Return coherent game/releases/eligibility/aggregate; personal rating is a separate authenticated resource; unavailable aggregate/fallback may degrade a valid page                                   |
| `UC-009` | Synchronize catalogue from provider | Operator              | Synchronize every provider Game in an operator-supplied inclusive release-date interval in one call; page internally; reconcile stable Game and Release references; commit valid Games independently |

`UC-001` results are grouped by game before pagination: a game appears once and carries
only the releases matching the requested view and active filters, each release preserved
independently so differing platforms, regions, dates and date precision stay visible.
Upcoming orders releases by date precision first (exact day, then month, quarter, year,
and finally TBA/unknown), then by effective period, canonical title and `gameId`; recent
orders by effective period, canonical title and `gameId`; both end in the unique
`releaseId` tie-breaker. A game is positioned by its first matching release under that
order, with `gameId` as the final deterministic tie-breaker, and its releases keep the
same order. Counting and pagination are over games, so `totalItems` counts games. The
releases grouped under one game are explicitly bounded, so request memory is
`O(pageSize x releaseGroupLimit)` plus bounded taxonomy; persistent
filtering/counting/pagination/grouping never occurs over a complete Java snapshot.

Visitors select a fixed 1, 2, or 4 week horizon for either view; both default to one
week. The application evaluates the inclusive recent range from `today - (7 × weeks - 1)`
through today and the upcoming range from today through `today + 7 × weeks` in
`Europe/Madrid`. The upcoming predicate still excludes exact-day releases that have
already occurred today while preserving #177's partially known periods and TBA
classification. Changing the horizon resets the page; the URL carries the selection,
and the returned evaluated range is shown to the visitor. The range is recalculated on
each request, so a shared URL describes a moving horizon rather than a fixed historical
interval.

Platform and region filters are multi-select facets: values within one dimension combine
with `OR` and the two dimensions combine with `AND`. No value selected for a dimension
means it is unfiltered (`Todas`), which is distinct from selecting the concrete
`Worldwide` region. `availableFilters` is contextual and faceted, computed in PostgreSQL:
platform options reflect the current window and the active region selection (never
narrowed by the active platform set), region options reflect the current window and the
active platform selection (never narrowed by the active region set), and a currently
selected valid value always stays representable. A future or historical platform or
region therefore does not appear in an unrelated current window merely because it exists.
The wire shape of these parameters and the response lives in
[`openapi.yaml`](../api/openapi.yaml).

`UC-002` normalizes the query and the searchable catalogue text with one rule, so
matching is case- and diacritic-insensitive without ever rewriting a display title.
Only an approved alias is searchable. Ordering ends in unique `gameId` after match
rank and normalized canonical title; a game matched through several aliases stays one
result and separate games matching one query stay separate. Release context per result
is explicitly bounded, so request memory is `O(pageSize x releaseContextLimit)`.

`UC-009` reconciles all relevant new and known Games in the requested date interval through one operation.
[ADR-0017](../../decisions/0017-discover-catalogue-members-automatically-from-igdb.md)
owns Game selection, import policy, stable Release identity and per-Game atomicity.
It also acquires the platform and region taxonomy that accepted releases use, resolving
each provider entity through a typed external reference and creating the product entity
when the reference is unknown, per
[ADR-0020](../../decisions/0020-acquire-platform-and-region-taxonomy-from-releases.md);
provider identifiers never become product identity and names are never used to merge.
A partial run preserves successful Games and the failed
Game's last valid state. Provider DTOs and transport details remain in the adapter;
no public request invokes it.

`UC-003` reads complete game evidence from one local publication. Its
[read port](../../../backend/src/main/java/com/videogameplatform/catalogue/application/details/port/GameDetailsReadPort.java)
owns the operational aliases/releases bounds; exceeding them fails the read rather
than evaluating a partial context. Catalogue supplies the application-derived Madrid
date and release context; Ratings evaluates eligibility and reads its own aggregate.
No rating contribution returns an empty aggregate; an isolated statistics failure
returns an explicit unavailable aggregate while preserving the game page. Personal
rating reads and writes are outside this public operation.

## Identity and ratings

| ID       | Operation                           | Actor               | Required behaviour                                                                                                                            |
|----------|-------------------------------------|---------------------|-----------------------------------------------------------------------------------------------------------------------------------------------|
| `UC-004` | Authenticate and resume rating      | Visitor             | Store short-lived tamper-resistant context; derive user from principal; atomically consume/replay-protect; return to allowlisted game context |
| `UC-005` | Create rating                       | Authenticated user  | Validate 1–10 and current eligibility; prevent duplicate; update personal/aggregate coherently                                                |
| `UC-006` | Update rating                       | Authenticated owner | Scope by principal + game; validate value/eligibility/concurrency; preserve previous state on failure                                         |
| `UC-007` | Delete rating                       | Authenticated owner | Scope by principal + game; delete regardless of current eligibility; update aggregate coherently                                              |
| `UC-008` | View/search/sort `Mis puntuaciones` | Authenticated user  | Scope by user before search/sort/count/page; default updated-descending; unique `gameId` tie-breaker                                          |

The client never supplies a trusted user/evaluation date. Scoped absence returns
`RATING_NOT_FOUND` without revealing another user's state. Authentication cancellation
or invalid/expired/replayed context creates no rating. Any failed rating command
preserves personal and aggregate state.

## Common result and failure rules

- Product identifiers are internal; provider types/IDs do not escape their adapter.
- Date precision, provenance, verification, review, and freshness remain explicit.
- Personal and aggregate ratings remain separate.
- Empty, no-rating, stale-but-usable, fallback-cover, ineligible, and unavailable
  aggregate states are not generic technical errors.
- Stable codes drive clients; localized copy is delivery-owned. Technical responses
  expose correlation, never secrets, raw provider payloads, SQL, or stack traces.

| Category               | Principal codes / guarantees                                                                                                                                                                                                          |
|------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Validation             | `SEARCH_QUERY_INVALID`, `FILTER_INVALID`, `PLATFORM_NOT_SUPPORTED`, `REGION_NOT_SUPPORTED`, `SORT_INVALID`, `RATING_VALUE_INVALID`; do not execute invalid work                                                                       |
| Authentication/replay  | `AUTHENTICATION_REQUIRED/FAILED/CANCELLED`, `RETURN_CONTEXT_INVALID/EXPIRED/REPLAYED`; no duplicate logical command                                                                                                                   |
| Domain/conflict        | `GAME_NOT_FOUND`, `RATING_NOT_ELIGIBLE`, `RATING_ALREADY_EXISTS`, `RATING_NOT_FOUND`, `RATING_WRITE_CONFLICT`, `RELEASE_DATA_REVIEW_REQUIRED`; preserve valid state                                                                   |
| Local reads            | `CATALOGUE_NOT_READY`, `CATALOGUE_READ_FAILED`, `RATING_STATISTICS_READ_FAILED`, `PERSONAL_RATINGS_READ_FAILED`; never request-path provider fallback or cross-user partial data                                                      |
| Writes                 | `RATING_WRITE_FAILED`, `SYNCHRONIZATION_WRITE_FAILED`; previous valid state remains                                                                                                                                                   |
| Provider normalization | `PROVIDER_AUTHENTICATION_FAILED/RATE_LIMITED/UNAVAILABLE/RESPONSE_INVALID/MAPPING_FAILED`, `EXTERNAL_REFERENCE_CONFLICT`, `RELEASE_DATA_INVALID`, `COVER_REFERENCE_INVALID`; isolate the affected Game and keep last valid local data |

Database constraints/transactions must enforce concurrent rating uniqueness, coherent
writes, and one game per provider reference. Synchronization expands catalogue
membership only through the import policy, and publishes a cover only after it
validates. Exact HTTP mapping is owned by OpenAPI and API conventions; the internal
synchronization command is an operator endpoint on the management port and is
deliberately outside the product contract.
