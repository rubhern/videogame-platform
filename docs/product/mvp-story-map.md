# Learning MVP story map

- **Status:** Ready for prototype and vertical-slice planning
- **Product Brief:** [v0.3 — Approved](product-brief.md)
- **Last updated:** 2026-07-24
- **Owner:** Ruben Hernandez
- **Editable board:** [Open the FigJam story map](https://www.figma.com/board/4OfeyWSF3rvEhDE5HGKUK8)
- **PNG export:** [Download the repository export](assets/mvp-story-map.png)

This story map turns the approved primary journey into the smallest coherent
learning MVP. It is an alignment aid, not a detailed implementation backlog or an
architecture decision.

![VideoGame Platform learning MVP story map](assets/mvp-story-map.png)

## Outcome and audience

The priority user is a release-aware, Spanish-speaking multiplatform player who
researches several games per month and already keeps a wishlist, backlog, rating
history, or similar personal record.

The MVP should let that player:

> Discover a relevant release, understand the game and its release context, record
> a rating, and retrieve or maintain that rating later.

The underlying demand problem remains an accepted learning hypothesis. Synthetic
research informs the map but is not treated as real-user evidence.

## Story map

| Activity | User steps | MVP stories |
|---|---|---|
| Discover relevant releases | Open recent or upcoming releases; filter by platform or region; search the bounded catalogue | See Spanish-first release information; narrow results; search by title or alternative title; understand zero results and catalogue coverage |
| Evaluate a game | Select a game; read its game page | Open the correct internal game; understand essential catalogue and platform-release information, provenance, freshness, date precision, and verification state |
| Rate the game | Register or sign in when ready to rate; create or change a rating; see personal and aggregate context | Resume the same game after authentication; keep one integer rating from 1 to 10 per user and game; create, update, or delete it; see arithmetic mean, count, and distribution |
| Return to personal ratings | Open `Mis puntuaciones`; find and manage a rating | Retrieve only the signed-in player's ratings; search and sort them; open the related game; edit or delete a rating directly |

## MVP release cut

The first release is one complete, observable vertical slice across all four
activities. It includes:

- a Spanish-first, mobile-first responsive experience;
- a deliberately bounded catalogue sourced from IGDB under the approved private,
  non-commercial learning mode;
- recent and upcoming releases with platform and region filters;
- title and alternative-title search;
- a game page with explicit release provenance, freshness, precision, and review
  state;
- an established registration and sign-in approach;
- one active integer rating from 1 to 10 per user and game;
- create, update, and delete behaviour;
- arithmetic mean to one decimal, rating count, and distribution;
- a minimal `Mis puntuaciones` view with search, sorting, direct edit, and delete;
- basic journey analytics and operational visibility.

## Acceptance criteria

### Release discovery

- Recent and upcoming releases use Spanish-first product copy.
- Platform and region filters update the result set, show their active values, and
  can be cleared.
- Search matches a title or alternative title within the bounded catalogue.
- Loading, empty, error, zero-result, and catalogue-boundary states are explicit
  enough for the user to understand what happened.

### Game page

- Selecting a release or search result opens the correct internal game.
- The page shows the essential game and platform-release information needed by the
  journey.
- Platform, region, date precision, provenance, freshness, and verification state
  remain explicit; the product never invents a more precise release date.
- Provider identifiers and provider-specific concepts do not become the public
  product contract.

### Authentication and rating

- Authentication is required at the rating boundary, not for release discovery or
  reading the game page.
- Successful registration or sign-in returns the player to the same game context.
- Only an authenticated user can create, change, or remove a rating.
- A rating is an integer from 1 to 10, and only one active rating exists per user and
  game.
- Invalid or failed operations preserve the existing rating and return an actionable
  error.

### Personal and aggregate context

- A successful change is reflected in the player's rating without ambiguity.
- The aggregate shows the arithmetic mean to one decimal, rating count, and
  distribution.
- Editing or deleting a rating keeps the game page and `Mis puntuaciones`
  consistent.

### `Mis puntuaciones`

- The view contains only the signed-in player's ratings.
- It provides an understandable empty state, search, sorting, and a direct link to
  each game.
- A player can edit or delete a rating directly from the view.

## Cross-cutting guardrails

- Keep IGDB data bounded and curated, reconcile ambiguous displayed release dates
  manually, and maintain product-owned Spanish aliases.
- Use local normalized metadata without copied provider images or external ratings.
- Enforce authentication, authorization, validation, privacy, and safe logging at
  the relevant boundaries.
- Treat accessibility, useful errors, relevant automated tests, catalogue freshness,
  journey analytics, and operability by one person as part of the slice.
- Do not introduce a production architecture, framework, database, queue, cache, or
  deployment model through this product map.

## Success gates

- **Journey gate:** at least four of five representative users complete release
  discovery → game page → rating → `Mis puntuaciones` without assistance or a
  blocking usability problem.
- **Engineering gate:** the slice is automated, tested, observable, documented, and
  operable by one person before another major capability starts.
- **Provider release-mode gate:** public deployment, monetization, copied images,
  redistribution, or broad unattended synchronization reopens
  [Q-005](open-questions.md) before deployment.

Signals such as game-page conversion, rating activation, later rating retrieval,
repeat release use, zero-result failures, catalogue freshness, synchronization
success, and journey error rate guide learning. They are not product-market-fit
targets.

## Explicitly after the MVP

- broad catalogue coverage or multiple simultaneous providers;
- personal libraries, custom lists, and following;
- written reviews, comments, community features, and advanced moderation;
- recommendations, complex rankings, statistics, and trend analysis;
- professional reviews or third-party scores;
- detailed editions, DLC, expansions, and exhaustive technical requirements;
- native mobile applications;
- platform-specific aggregates, verified-play claims, and advanced
  anti-manipulation;
- prices, stores, and comparison;
- microservices, event streaming, data lakes, and multi-region deployment.

## Sources

- [Product Brief v0.3](product-brief.md)
- [Product assumptions](assumptions.md)
- [Open questions and decisions](open-questions.md)
- [Product glossary](glossary.md)
