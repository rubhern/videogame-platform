# ADR-0018: Maintain a Ratings-owned rebuildable game-listing projection

- **Status:** Accepted
- **Date:** 2026-09-13
- **Owner:** Ruben Hernandez
- **Scope:** UC-008 `Mis puntuaciones` reads
- **Issue:** [#32](https://github.com/rubhern/videogame-platform/issues/32)

## Context

`Mis puntuaciones` must search, sort, count and page only the authenticated user's
active ratings, and each row needs public game context (navigation, canonical title,
approved aliases and resolved cover). That context is owned by Catalogue, but the
module boundary forbids Ratings from reading Catalogue tables, and the scalability
invariants forbid a provider call or a per-row cross-module lookup on the user request
path. Resolving context per rated game at request time would reintroduce N+1 work and
couple the private read to Catalogue availability.

## Decision

- Keep a Ratings-owned, rebuildable projection (`ratings.game_listing` and
  `ratings.game_listing_alias`) holding the public listing fields Ratings needs.
- Populate it only through Catalogue application contracts (`GetGameListingUseCase` /
  `GameListingReadPort`), never through cross-module SQL or provider types.
- Refresh synchronously and transactionally at each source of change: the Catalogue
  synchronization adapter emits `GameListingChanged` inside its Game write
  transaction and a synchronous Ratings listener refreshes the projection before
  commit, so a projection failure rolls back that Game publication; rating creation
  refreshes the projection before persisting the rating. Any future listing or alias
  writer must publish the same notification inside its transaction.
- Backfill missing context for already-rated games at startup in keyset batches; an
  incomplete backfill fails startup rather than serving an incomplete private list.
- Serialize per-game refreshes across instances with a per-game transaction advisory
  lock; hold no process-local cache.
- Read in one read-only `REPEATABLE READ` transaction over Ratings tables only:
  scope by `userId` first, then let PostgreSQL apply the case- and
  diacritic-insensitive all-token word-prefix match over title and aliases, count,
  deterministically order (chosen key then `gameId` ascending as the unique
  tie-breaker), `LIMIT` and `OFFSET`, materializing only `O(pageSize)` rows in Java.
- Expose each item's current strong rating `entityTag` as the conditional validator
  for direct update and delete; it is not a collection cache validator.

## Alternatives considered

- **Per-request cross-module context resolution:** rejected because it reintroduces
  N+1 work on the user path and couples the private read to Catalogue availability.
- **Cross-module SQL join into Catalogue tables:** rejected because it breaks the
  module boundary.
- **Asynchronous, eventually-consistent projection:** rejected for the MVP because it
  can serve an incomplete private list; synchronous in-transaction refresh keeps the
  list correct.
- **Separate search engine or denormalized global table:** rejected without a measured
  PostgreSQL limitation.

## Consequences

The private read is bounded, deterministic, user-scoped and stateless, and it never
calls a provider or another module at request time. In exchange, Ratings duplicates a
bounded slice of Catalogue listing data, every listing writer must publish
`GameListingChanged` in its transaction to keep the projection fresh, and startup pays
a bounded backfill cost proportional to the number of already-rated games.

## Evidence and reconsideration triggers

`PersonalRatingsScalabilityIT` exercises the user-scoped count, ordering,
tie-breaking and page bounds against real PostgreSQL. Revisit an asynchronous
projection if the synchronous refresh materially degrades rating-write or
synchronization latency under measurement; revisit keyset pagination for measured
high-offset or exact-count problems; and revisit the backfill strategy for measured
startup cost as the rated-game set grows.
