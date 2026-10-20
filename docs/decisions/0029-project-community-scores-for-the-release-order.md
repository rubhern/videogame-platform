# ADR-0029: Project community scores into Catalogue for the release order

- **Status:** Proposed
- **Date:** 2026-10-20
- **Owner:** Ruben Hernandez
- **Scope:** The release explorer's community-score order (`UC-001`, #285)
- **Relates to:** [ADR-0002](0002-use-a-modular-monolith-and-relational-data-boundary.md), [ADR-0015](0015-query-published-release-pages-with-postgresql.md), [ADR-0018](0018-project-personal-ratings-listing-in-ratings.md) and [ADR-0021](0021-rank-monthly-featured-releases-by-local-igdb-hypes.md)

## Context

#285 lets a visitor order the games of a release range by their Gameómetro community
mean, highest first, with games without ratings last. The order must apply before
pagination, so PostgreSQL has to sort every game of the range by the mean, not only the
page Java already holds. Catalogue owns the range query
([ADR-0015](0015-query-published-release-pages-with-postgresql.md)); Ratings owns every
rating and computes each game's mean on demand. The module boundary forbids Catalogue from
reading Ratings tables, Catalogue does not depend on Ratings while Ratings already depends
on Catalogue, and the scalability invariants forbid loading a range's games into memory to
sort them, or a per-game call on the request path. Ordering by Hype has no such problem:
the IGDB Hypes that rank TOPómetro ([ADR-0021](0021-rank-monthly-featured-releases-by-local-igdb-hypes.md))
are already Catalogue's own evidence.

## Decision

- Keep a Catalogue-owned projection, `catalogue.game_community_score`, of each rated
  game's community mean at the one decimal Gameómetro shows and its rating count. A game
  without ratings has no row, so a missing score never reads as zero. The range query
  joins it only for the community-score order.
- Ratings maintains it through a Catalogue application contract,
  `RecordCommunityScoresUseCase`, in the direction of the existing dependency. Every
  rating write takes a per-game transaction advisory lock, writes the rating, reads the
  game's aggregate and replaces the projected row in the same transaction, so the
  projection commits or rolls back with the rating and concurrent writes never project a
  stale aggregate. Any future rating writer must do the same.
- A startup backfill walks rated games in keyset batches, asks Catalogue which of them
  hold no row and projects each missing one under the same lock; an incomplete backfill
  fails startup. No migration reads Ratings tables, as in
  [ADR-0018](0018-project-personal-ratings-listing-in-ratings.md).
- The displayed community summary still comes from Ratings for the returned page; the
  projection is only an ordering key.

## Alternatives considered

- **Order a page in Java with the Ratings batch:** rejected; it orders only the current
  page, so pagination would be wrong.
- **Fetch every game of the range, then order with Ratings:** rejected; per-request
  memory would follow the range, not the page.
- **Read Ratings tables or a Ratings view from the Catalogue query:** rejected; it breaks
  the module boundary.
- **Catalogue listening to a Ratings event:** rejected; it adds a Catalogue-to-Ratings
  dependency and an asynchronous gap without need.
- **A Ratings-owned copy of release data:** rejected; it duplicates the release model to
  order one list.

## Consequences

The order is exact, bounded and computed in PostgreSQL before pagination, and Catalogue
stays independent of Ratings. In exchange, Catalogue holds a small derived copy of a
Ratings value, rating writes do one more single-row statement under a per-game lock, and
startup reads the rated games in batches to find unprojected ones. A row can drift only if
a writer bypasses the contract, such as a direct database edit or an older application
version still writing during a rollout; the next write for that game corrects it.

## Evidence and reconsideration triggers

`JdbcReleaseBrowseReadAdapterIntegrationTest` and `PersonalRatingApiIntegrationTest` prove
the order, ties, missing scores and that create, update and delete keep the projection
exact; the opt-in `ReleaseBrowseScalabilityIT` records the query plans. Revisit when a
measured rating-write latency or lock contention problem appears, when the startup batches
become measurably slow, or when a second consumer needs the community score inside
Catalogue.
