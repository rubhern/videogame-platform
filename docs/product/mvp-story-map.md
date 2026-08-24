# Learning MVP story map

- **Status:** Journey gate `PASS`
- **Owner:** Ruben Hernandez
- **Editable snapshot:** [FigJam board](https://www.figma.com/board/4OfeyWSF3rvEhDE5HGKUK8)
- **Repository export:** [PNG](assets/mvp-story-map.png)

The map is the planning boundary for one complete journey, not a detailed backlog or
architecture source.

| Activity | User outcome | MVP capability |
|---|---|---|
| Discover | Find a relevant recent/upcoming release | Bounded catalogue, platform/region filters, title/alias search, explicit empty/boundary states |
| Evaluate | Understand the correct game and release | Game page, provider-independent identity, date precision, provenance, freshness/review state, attributed cover/fallback |
| Rate | Record one personal score after release | Inline 1–10 selector, authentication on confirmation, replay-safe return, create/update/delete, distinct aggregate/personal context |
| Return | Find and maintain the rating | Authenticated `Mis puntuaciones`, search, sort, game navigation, edit, delete |

## Release-cut acceptance

- Spanish-first, mobile-first and responsive; semantic, keyboard-accessible, and
  understandable in loading, empty, stale, degraded, validation, and error states.
- Public discovery/game reads require no sign-in. Personal operations derive owner
  identity from the authenticated principal.
- Release dates preserve day/month/quarter/year/unknown precision. Availability is
  never treated as commercial release.
- A game can be rated only after a qualifying commercial release. Failed commands
  preserve the previous personal and aggregate state.
- At most one active rating exists per user/game. Aggregate mean, count, distribution,
  and no-rating state remain coherent.
- Provider data is normalized locally; user requests never call IGDB. Covers follow
  [ADR-0001](../decisions/0001-reference-igdb-cover-images.md).
- The complete slice includes relevant tests, security, observability, and operational
  evidence.

The accepted synthetic round reached 4/5 unaided and the focused regression resolved
the blocking rating-state inconsistency. This closes the internal journey gate but
does not validate demand or real-user behaviour. See the
[prototype](clickable-prototype.md) and [synthesis](../research/simulated-round-synthesis.md).

Anything outside the [Product Brief](product-brief.md) MVP boundary remains deferred,
even if it appeared in the original vision or synthetic feedback.
