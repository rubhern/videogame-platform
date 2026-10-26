# ADR-0034: Rank TOPómetro from Catalogue's score projections

- **Status:** Proposed
- **Date:** 2026-10-26
- **Owner:** Ruben Hernandez
- **Scope:** The global player-rating ranking, TOPómetro (`UC-018`, #386), and its reuse by
  platform TOPómetros (#387)
- **Relates to:** [ADR-0002](0002-use-a-modular-monolith-and-relational-data-boundary.md),
  [ADR-0015](0015-query-published-release-pages-with-postgresql.md),
  [ADR-0029](0029-project-community-scores-for-the-release-order.md) and
  [ADR-0031](0031-count-one-community-vote-per-rating-across-platforms.md)

## Context

TOPómetro ranks released games by one source of player ratings at a time: the IGDB players'
score stored with each game (#255) or the Gameómetro community mean (#359). PostgreSQL must
select, order by the unrounded mean, break ties by count and `GameId`, count and page before
any game reaches Java, and a game's displayed score must never disagree with its position. A
game is ranked only while it has a release that has effectively occurred under the rating
eligibility rules, which Catalogue owns as release evidence. The IGDB score is already
Catalogue's own state. The community score belongs to Ratings, whose tables Catalogue may not
read; ADR-0029 already projects it into Catalogue for the release explorer, but only at the one
decimal Gameómetro shows, which cannot rank `8,33` above `8,25`.

## Decision

- The ranking is a Catalogue read (`RankGamesUseCase`, named interface `rankings`) over
  Catalogue-owned tables only: `game_external_score` for IGDB players and
  `game_community_score` for the community, each walked through a partial index in ranking
  order, with an `EXISTS` over the current publication's releases that mirrors the rating
  eligibility policy. Each source declares its minimum sample in `RankGamesUseCase.Source` and
  the query binds it: 100 ratings for IGDB players and one for the community (`RNK-002`, owner
  decision of 2026-10-26). The page's games then receive their snapshot, compact release summary
  and genres. Count and page run in one repeatable-read snapshot.
- The community projection of ADR-0029 gains `exact_mean`, the unrounded mean at the eighteen
  places Ratings compares means at. `RecordCommunityScoresUseCase` now carries that exact mean
  and derives the shown one-decimal mean from it, so Ratings writes both in the same
  transaction and under the same per-game lock as every rating change. The startup backfill
  re-projects every row that lacks the exact mean; the ranking skips such a row until then.
- TOPómetro shows the reading its order used, from the same row and statement: the IGDB score
  rounded for display as everywhere else, and the community's projected one-decimal mean and
  count. Unlike the release explorer, it does not ask Ratings for a separate summary, so a
  rating committed between two queries can never show a score that contradicts its position.
- Pagination stays page and offset, with server-assigned positions, as the API conventions
  approve. Scores can change between two page reads; the client keeps the first occurrence of a
  game. Platform TOPómetros (#387) are expected to add a platform predicate to the same
  release `EXISTS` and reuse the read, the endpoint and the page.

## Alternatives considered

- **Order the community page by the shown mean:** rejected; equal displayed means would order
  by count, contradicting the issue's rule that the unrounded mean decides.
- **Store a contribution sum and divide in SQL:** rejected; ordering by an expression needs an
  expression index and still recomputes per row, for no gain over one stored value.
- **Read the displayed community summary from Ratings, as the explorer does:** rejected for the
  ranking; a second snapshot could show a value that disagrees with the position.
- **A ranking table or materialized view refreshed periodically:** rejected; it adds a refresh
  job and a staleness window without a measured need, while the partial indexes already stop
  after the requested page.
- **Put the IGDB minimum in the partial index predicate:** rejected; it would fix a product
  threshold in the schema, and a bound parameter could not use it. The rank index already
  yields scores in order, and the minimum filters them on the way.
- **Keyset pagination:** deferred; offset positions are simple and exact, and the measured cost
  of a deep page is small. Revisit with the triggers below.

## Consequences

The ranking is exact, bounded and computed in PostgreSQL, Catalogue still never reads Ratings
tables, and the same projection serves two consumers. Rating writes store one more column in
the statement they already run. A row written by an older application version during a rollout
keeps its previous exact mean until the game's next rating write, as ADR-0029 already accepts
for the shown mean. The count scans every scored game of the source with an indexed release
probe, which is the read's dominant cost.

## Evidence and reconsideration triggers

`JdbcGameRankingReadAdapterIntegrationTest`, `GameRankingApiIntegrationTest` and
`PersonalRatingApiIntegrationTest` prove the order, ties, eligibility, one place per game,
paging, source separation and the projected exact mean; the opt-in `GameRankingScalabilityIT`
records the plans. At 100,000 games with 80,000 IGDB scores on the local Testcontainers
PostgreSQL 18, the first page took about 1.3 ms, page 200 about 15 ms and the count about
75–90 ms, with both rank indexes used. Revisit when the count or deep pages become measurably
slow, when a platform-scoped ranking (#387) cannot use the same plan, or when a ranking needs a
confidence-weighted or blended formula, which the owner has excluded.
