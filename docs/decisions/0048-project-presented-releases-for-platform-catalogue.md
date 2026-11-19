# ADR-0048: Project presented releases for the platform catalogue

- **Status:** Proposed
- **Date:** 2026-11-19
- **Owner:** Ruben Hernandez
- **Issue:** [#577](https://github.com/rubhern/videogame-platform/issues/577)
- **Related:** [ADR-0006](0006-use-postgresql-and-versioned-forward-migrations.md),
  [ADR-0029](0029-project-community-scores-for-the-release-order.md)

## Context

The [platform catalogue use case](../architecture/application/mvp-use-cases.md)
must keep one representative release per game across the selected scope. For Windows
PC, a production request spent 4.27 seconds in SQL deriving the representative releases
and aggregating the resulting games. The same path took roughly 1.3 seconds on private dev
and spilled about 100 MB of temporary data. The release selection and the HTTP contract
remain the same.

## Decision

Keep a Catalogue-owned PostgreSQL projection of each game's winning release per
publication and platform. Store the winner's validity intervals and occurrence class so
the Madrid evaluation date can change the answer between synchronizations. A temporal
primary key disallows overlapping winners. For a family, compare the member platforms'
winners before filtering, then merge their ordered page prefixes. Every order ends in
the game ID. Fetch titles, covers, release details, and matched aliases for page rows only.

Keep exact counts and contextual facets on the projection. The numerical page contract
still consumes the requested offset. Rare searches can take the indexed title or alias
path and sort only the matches. Filtered exact counts and facets still grow with the
matching scope, so track them separately in query-plan evidence before adding aggregate
projections.

Flyway populates the projection from local data in one transaction while fencing
writes to its source tables. Synchronization and direct SQL repairs refresh affected
games in the source write transaction. Title, genre, region precedence and score edits
use the same refresh path. Concurrent writers serialize per game and a failed refresh
rolls back the source write. The provider is outside this path.

## Consequences

The projection adds storage and write work to Catalogue. The Flyway upgrade may delay
writers while it reads the source and builds indexes. Existing application versions can
read the expanded schema and their writes use the same triggers. A failed migration leaves
the previous schema and source data intact.

On a local PostgreSQL 18 copy of the private-dev Catalogue with 264,080 release
snapshots and 111,902 Windows PC games, the new query used the date-order index for
the first page, page 200 and an RPG genre filter. A search for `civilization` sorted
15 page candidates. All four reads returned identical results to the previous query
and wrote zero temporary blocks. The exact summary and facets accounted for most
of the remaining broad-scope cost. Private-dev host performance and a production
trace after rollout are separate validation steps.

The release explorer still chooses representatives after applying a calendar range
and other release predicates. Its date window can change the winner, so the platform
catalogue projection cannot answer that query. Its latency needs its own plan and
evidence before changing the read model.
