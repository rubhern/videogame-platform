# ADR-0031: Count one community vote per rating across platform scores

- **Status:** Proposed
- **Date:** 2026-10-21
- **Owner:** Ruben Hernandez
- **Scope:** Personal ratings by platform and the Gameómetro community aggregate (`UC-003`, `UC-005` to `UC-008`, `UC-015`, #359)
- **Relates to:** [ADR-0018](0018-project-personal-ratings-listing-in-ratings.md), [ADR-0020](0020-acquire-platform-and-region-taxonomy-from-releases.md) and [ADR-0029](0029-project-community-scores-for-the-release-order.md)

## Context

Until #359 a rating was one integer 1–10 per `UserId + GameId`, and the community mean,
count and distribution averaged those integers. The owner approved letting a player score
the same game differently per platform (PlayStation 5, PC, Switch…), declare platforms
played without a score, and still rate directly without naming any platform. Each player
must keep exactly one vote in the Gameómetro score, while the product also shows each
platform's own mean as a basis for future platform statistics. The existing ratings must
stay valid without a manual migration and without inventing a platform.

## Decision

- **One rating, one vote.** The rating keeps its identity (`RAT-010`). It has exactly one
  score source: a direct value, or the scores the player gave the platforms they played on.
  The rating's contribution is the direct value or the arithmetic mean of its scored
  platforms. The community mean averages contributions per player, and its count is
  players, never platform scores.
- **Explicit transitions.** A command's `value` is the direct score and is required exactly
  when no resulting platform carries a score. `platforms`, when present, replaces the whole
  played-platform set; when absent, the saved set is kept, so a value-only command from
  an older client never deletes platforms. A direct value combined with platform scores is
  rejected rather than resolved silently, and removing the last platform score needs a new
  direct value in the same command. No hidden direct value is kept for later restoration.
- **Ratings-owned normalized storage.** `ratings.rating_platform` holds one row per played
  platform with its optional score, removed with the rating. The rating row keeps the
  direct value, now nullable, plus the platform score sum and count written in the same
  transaction. PostgreSQL derives the contribution from them in a generated column, stored
  to twenty decimal places, and a row constraint enforces exactly one score source. Every
  aggregate still reads one row per player.
- **Catalogue identity, Ratings labels.** A platform is the Catalogue taxonomy's product
  `PlatformId`. A newly named platform must be one of the game's current release
  platforms, read through the existing game-details application contract; one already
  saved stays valid whatever later synchronization holds. `ratings.platform_label`
  keeps the platform names that ratings use, as `genre_label` does for genres, so reads and
  owner deletion never call Catalogue.
- **Precision and presentation.** Means use unrounded contributions and round half up to
  one decimal only when exposed. Each player sits in exactly one distribution bucket, their
  contribution rounded half up to the nearest integer, so bucket counts still equal the
  count. The per-platform reading is the mean of that platform's explicit scores and the
  number of players who scored it; a played platform without a score is not counted.
- **Projection unchanged.** The Catalogue community-score projection
  ([ADR-0029](0029-project-community-scores-for-the-release-order.md)) receives the same
  one-vote mean and voter count under the same per-game lock.

## Alternatives considered

- **Count every platform rating as an independent global vote:** rejected. A player who
  rates three platforms would weigh three times as much in the Gameómetro score, and the
  count would mean platform scores rather than people.
- **Keep an independent global rating beside platform ratings:** rejected by the owner. Two
  numbers that can disagree would need a rule for which one counts, and an unscored global
  value would be a hidden vote.
- **Keep the old direct value while platform scores apply and restore it later:** rejected.
  It restores a stale score the player may no longer see.
- **Compute contributions on every read from the platform rows:** rejected. Every
  aggregate and the personal ordering would join and group platform rows per player instead
  of reading one row each.
- **Store platform codes, or only a per-request list from Catalogue:** rejected. Platform
  codes differ from the taxonomy's `PlatformId` used by discovery and preferences, and
  names fetched per request would make reads and deletion depend on Catalogue.

## Consequences

The community score keeps its meaning and stays a single PostgreSQL aggregate per game,
and the platform readings come from one indexed scan of scored platform rows. Writes now
replace a small platform set and its labels in the rating transaction. Clients see a
breaking contract change: a personal rating's `value` is null when it is scored by
platform, and `contribution` carries its vote (OpenAPI 5.0.0). The game-details contract
now exposes the taxonomy `PlatformId` for each release's platform, as every other
catalogue read already did. Once any platform-scored rating exists, an application
version before #359 can no longer read every rating, so a rollback after that point
needs a forward fix rather than a plain image rollback.

## Evidence and reconsideration triggers

`RatingScoreTest`, `PersonalRatingServiceTest`, `JdbcPersonalRatingStoreIntegrationTest`,
`RatingPlatformMigrationIntegrationTest`, `PersonalRatingApiIntegrationTest` and
`PersonalRatingInsightsIntegrationTest` prove the vote, the transitions, half-up
presentation of an exact half-way mean, bucket counts, atomic rollback, constraint
enforcement, migration of historical ratings and the API's codes. Revisit when platform
rankings or statistics need a per-platform projection, when a platform set per rating
needs more than 50 entries, or when a measured write or aggregate cost appears.
