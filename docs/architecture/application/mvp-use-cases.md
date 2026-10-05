# Learning MVP application use cases

- **Status:** Approved
- **Owner:** Ruben Hernandez
- **Scope:** Application operations and guarantees; not HTTP, screens, SQL, or framework classes

## Catalogue

| ID       | Operation                           | Actor                 | Required behaviour                                                                                                                                                                                   |
|----------|-------------------------------------|-----------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `UC-001` | Browse recent/upcoming releases     | Visitor               | Application derives evaluation date/window; PostgreSQL classifies recent/upcoming from the effective release date against the window (not from a persisted status), then groups the matching releases by game so a result is one game presenting at most one of its view-and-filter-matching releases per platform, and counts, uniquely orders, and pages games; cancelled is excluded from both views and delayed from recent; upcoming shows exact-day releases unless the visitor opts into approximate dates, where TBA sorts last; stale/empty/fallback are valid states |
| `UC-002` | Search bounded catalogue            | Visitor               | Normalize the query once; PostgreSQL matches canonical titles/approved aliases, ranks, counts, uniquely orders and pages; zero/multiple matches are valid; never call provider                       |
| `UC-003` | View game details                   | Visitor/optional user | Return coherent game/releases/eligibility/aggregate; personal rating is a separate authenticated resource; unavailable aggregate/fallback may degrade a valid page                                   |
| `UC-009` | Synchronize catalogue from provider | Operator              | Synchronize every provider Game in an operator-supplied inclusive release-date interval in one call; page internally; reconcile stable Game and Release references; commit valid Games independently |
| `UC-010` | Browse monthly featured releases    | Visitor               | Application derives evaluation date and the current calendar month (or validates a selected month); PostgreSQL selects the qualifying releases inside the month, ranks their games by the local popularity signal with a unique tie-breaker, limits the ranking to six and presents at most one release per platform; unranked, empty, stale and fallback are valid states; never call provider |

`UC-001` results are grouped by game before pagination: a game appears once and carries,
of the releases matching the requested view and active filters, only the presented
release of each platform (see below), so differing platforms stay visible with the
region, date and date precision of each presented release.
Upcoming orders releases by date precision first (exact day, then month, quarter, year,
and finally TBA/unknown), then by effective period, canonical title and `gameId`; recent
orders by effective period, canonical title and `gameId`; both end in the unique
`releaseId` tie-breaker. A game is positioned by its first presented release under that
order, with `gameId` as the final deterministic tie-breaker, and its releases keep the
same order. Counting and pagination are over games, so `totalItems` counts games. The
releases grouped under one game are explicitly bounded, so request memory is
`O(pageSize x releaseGroupLimit)` plus bounded taxonomy; persistent
filtering/counting/pagination/grouping never occurs over a complete Java snapshot.

Post-MVP (#212, implemented): current legitimate releases remain distinct. Real provider
data often holds several for one game and platform, such as regional releases, early or
advance access beside a later date, or a still-returned approximate estimate beside an
exact day. Synchronization removes obsolete provider references before presentation. A
context presents one of them per platform (`REL-013`), chosen in PostgreSQL by one
precedence: a release neither cancelled nor delayed, then a Full Release, then one not pending review, then
verified evidence, then the most precise date, then Worldwide before a specific region
before an unconfirmed region, then the context's own date order, and finally a stable
region order and the unique `releaseId`. Discovery applies it to the matching releases with
the view's date order (latest first for recent, soonest first for upcoming), so a region
filter presents that region's own release, while facets and `totalItems` still come from
every matching release. The game page applies it with the earliest date first to each
platform and to each platform and region: it lists the presented release of every platform
and region together (#233), and any further records of a combination stay whole behind one
disclosure. Presentation never deletes, merges or
rewrites a release and never changes rating eligibility, which still evaluates every
current release.

Visitors select a fixed 1, 2, or 4 week horizon for either view; both default to one
week. The application evaluates the inclusive recent range from `today - (7 × weeks - 1)`
through today and the upcoming range from today through `today + 7 × weeks` in
`Europe/Madrid`. The upcoming predicate still excludes exact-day releases that have
already occurred today while preserving #177's partially known periods and TBA
classification. Changing the horizon resets the page; the URL carries the selection,
and the returned evaluated range is shown to the visitor. The range is recalculated on
each request, so a shared URL describes a moving horizon rather than a fixed historical
interval.

Post-MVP (#214, implemented): upcoming discovery shows only releases with an exact day
by default. Month, quarter, year and unknown (TBA) releases stay stored unchanged and
join, each at its own precision and after exact days in the order above, only when the
visitor explicitly opts in with **Incluir fechas aproximadas**. The choice exists for
the upcoming view only; recent keeps classifying partial periods that have ended. It
composes with the week horizon and both filter dimensions because PostgreSQL applies it
before facets, count, ordering and pagination, so `availableFilters` and `totalItems`
describe the chosen precision. Changing it resets the page and the URL carries it.
Precision is the only rule: no platform lifecycle applies, so a future release with an
accepted exact day stays visible whatever its platform.

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

Post-MVP (#151, implemented): `UC-010` is the landing view of release discovery,
**Destacados**, beside Recientes and Próximos. It represents one calendar month: the
current one in `Europe/Madrid` by default, or another month of the current calendar year
the visitor selects; the URL carries the selection. A month of another year is rejected
as an invalid filter, so historical years are never browsed. A release qualifies for the month when its known date lies
inside it (an exact day of the month, or month precision equal to the month; quarter,
year and unknown dates never do) and it is neither cancelled, delayed nor pending
review, the same negative evidence that blocks rating eligibility. Only Full Release
qualifies. A candidate also satisfies `FEAT-001`: an accepted import type, no edition
parent, its own known first release in the month, and positive Hypes. These gates affect
only featured discovery; normal catalogue, recent and upcoming discovery remain unchanged.
Candidates are ranked by the locally stored popularity signal, currently IGDB Hypes, highest first,
with the unique `gameId` as the only tie-breaker. A candidate without a signal is not
ranked and nothing is invented for it; it remains a normal catalogue and discovery
result. The first ranked game is the month's featured release (*Lanzamiento del mes*)
and up to five more follow; fewer are shown rather than filling slots. Each presents,
of its qualifying releases in the month, at most one per platform under the shared
presented-release precedence with the earliest date first. The selection states why it
holds what it holds: ranked, popularity unavailable (qualifying releases without any
signal) or no qualifying releases. A ranked selection carries the freshness of its
oldest signal under the catalogue freshness policy; a stale ranking is still the last
valid local one. Another month is ranked with the same current local signals, because
no popularity history is kept. Popularity measures provider-observed attention, never
quality, a rating or an editorial recommendation, and its value is not exposed. Work is
bounded by the month's releases and request memory by the six ranked games and their
bounded release groups; the popularity table is never scanned. Each ranked game presents
its stored context-selected landscape image and optional secondary-card logo
(`FEAT-003`, `FEAT-004`); the hero always renders the canonical title as product-owned text.
Without a suitable stored image a card shows its cover whole, and without a provider cover the
product-owned landscape fallback; the hero goes straight to that designed fallback and never
presents a cover, so no frame ever stretches or crops a portrait cover.

`UC-009` acquires Hypes, the first-release date and edition evidence with the existing
Game request. The adapter normalizes the count and UTC calendar date and replaces the
provider parent identity with a boolean. The application combines that boolean with
its existing import allowlist into product eligibility. Inside each Game transaction,
a valid answer replaces complete featured evidence, including absent Hypes or first
release; a mapping failure or failed Game keeps the last valid state. No Visits lookup
or provider calculation timestamp remains. Current-release repair also receives this
evidence with the Game. Hero and card media are selected independently under `FEAT-003`:
artworks and screenshots arrive with the Game, logos use a bounded page lookup, and missing or
invalid media keep the last valid selection. Featured evidence and media do not advance
the catalogue revision. Explicit repair has no logo lookup.
[ADR-0021](../../decisions/0021-rank-monthly-featured-releases-by-local-igdb-hypes.md)
records the decision.

`UC-002` normalizes the query and the searchable catalogue text with one rule, so
matching is case- and diacritic-insensitive without ever rewriting a display title.
Only an approved alias is searchable. Ordering ends in unique `gameId` after match
rank and normalized canonical title; a game matched through several aliases stays one
result and separate games matching one query stay separate. Release context per result
is explicitly bounded, so request memory is `O(pageSize x releaseContextLimit)`.
Post-MVP (#187, implemented): each result also carries a compact release summary that
PostgreSQL aggregates from the game's complete stored release set, only for the returned
page, so it adds `O(pageSize)` memory and never changes matching, ranking, counting or
paging.

Post-MVP (#156, implemented): the header search offers typeahead suggestions through
the same `UC-002` read, with no separate endpoint or matching rule. From 2 trimmed
code points and 250 ms after the latest keystroke, it requests the first page of five;
a superseded request is cancelled and can never render. Each suggestion projects only
the returned cover, title and explaining alias, plus the platforms and years of the
#187 compact release summary, never the bounded release context: up to three platform
icons, the exact `+N` of further distinct platforms, and one known year, an inclusive
range, or `Por confirmar`. Choosing one opens the game;
`Enter` without an active suggestion keeps the existing full search. The threshold
gates suggestions only and does not change what an explicit search accepts.

Post-MVP (#188, implemented): the full results page projects each result the same way:
cover, title, explaining alias, and the platforms and year of the #187 compact release
summary, never the bounded release context. Matching, ranking, page size and pagination
are unchanged; the game page keeps the complete release context.

`UC-009` reconciles all relevant new and known Games in the requested date interval through one operation.
[ADR-0017](../../decisions/0017-discover-catalogue-members-automatically-from-igdb.md)
owns Game selection, import policy, stable Release identity and per-Game atomicity.
It also acquires the platform and region taxonomy that accepted releases use, resolving
each provider entity through a typed external reference and creating the product entity
when the reference is unknown, per
[ADR-0020](../../decisions/0020-acquire-platform-and-region-taxonomy-from-releases.md);
provider identifiers never become product identity and names are never used to merge.
A complete valid Game aggregate creates/updates returned typed Release references and
removes absent provider-owned references in the same Game transaction. Curated, official,
unreferenced and other-provider evidence is preserved. Failed/incomplete/invalid reads or
writes preserve that Game's entire last valid state; a partial run preserves other successful
Games. Removal is a content change and advances the existing catalogue revision; public
reads and rating eligibility use only current remaining Releases. Provider DTOs and transport details remain in the adapter;
no public request invokes it.

`UC-003` reads complete game evidence from one local publication. Its
[read port](../../../backend/src/main/java/com/videogameplatform/catalogue/application/details/port/GameDetailsReadPort.java)
owns the operational aliases/releases bounds; exceeding them fails the read rather
than evaluating a partial context. It returns every release, platform by platform in
presented-release order. Catalogue supplies the application-derived Madrid
date and release context; Ratings evaluates eligibility and reads its own aggregate.
No rating contribution returns an empty aggregate; an isolated statistics failure
returns an explicit unavailable aggregate while preserving the game page. Personal
rating reads and writes are outside this public operation.

Post-MVP (#233, data phase and game-page redesign implemented): `UC-009`
acquires each Game's summary, developer and publisher credits, genres and game modes with
the existing Game request, and `UC-003` serves them from PostgreSQL only. The adapter
keeps only the developer and publisher roles of the provider's company credits, so a
company holding both appears in both lists and other credited roles never cross the port.
Companies, genres and game modes resolve through typed external references to product
identity, created on first sight; a company name follows the latest valid answer, while
genre and game-mode labels follow the source/localization ownership below. A valid
answer replaces the provider-owned summary and every credit, genre and game-mode link in
the Game transaction, including clearing what the provider no longer states, and a content
change advances the catalogue revision. An incoherent answer keeps the Game's last valid
details while the rest of the Game still synchronizes; a failed Game keeps everything.
A product editorial summary or a summary from another source is never replaced. Without a
summary the read returns the product's editorial notice that none exists yet, and empty
lists for missing credits, genres or modes; nothing is invented. The read bounds each list
and fails rather than truncating.

Post-MVP (#235, architecture approved; implementation awaiting review): after each valid
Game commits, acquisition localizes its English summary and unknown genres/game modes.
Known typed references receive curated Spanish labels immediately. Source wording is
kept separately, and a rename never overwrites curation. Unchanged normalized content
reuses the durable translation; changed source requests a new derivation, with failure
preserving the last valid Spanish content and the new source. A summary absent from a
valid answer retains the existing explicit no-summary behavior. Editorial and other-source
summaries stay outside enrichment ownership. `UC-003` serves Spanish when available,
marks it as product-derived, and retains source language/provenance; missing translation
serves the source, while stale last-valid content has explicit status. No read calls inference.

The operator localization command pages taxonomy and stored summary targets by
`(kind, internal UUID)` with a maximum of 100 per batch, skips current/curated content,
and retains a failed batch's cursor for safe replay. Successful PostgreSQL state is the
durable progress. It uses no IGDB request or distributed job framework. [ADR-0022](../../decisions/0022-localize-catalogue-content-during-acquisition.md)
owns the runtime and concurrency decision.

## Identity and ratings

| ID       | Operation                           | Actor               | Required behaviour                                                                                                                            |
|----------|-------------------------------------|---------------------|-----------------------------------------------------------------------------------------------------------------------------------------------|
| `UC-004` | Authenticate and resume rating      | Visitor             | Store short-lived tamper-resistant context; derive user from principal; atomically consume/replay-protect; return to allowlisted game context |
| `UC-005` | Create rating                       | Authenticated user  | Validate 1–10 and current eligibility; prevent duplicate; update personal/aggregate coherently                                                |
| `UC-006` | Update rating                       | Authenticated owner | Scope by principal + game; validate value/eligibility/concurrency; preserve previous state on failure                                         |
| `UC-007` | Delete rating                       | Authenticated owner | Scope by principal + game; delete regardless of current eligibility; update aggregate coherently                                              |
| `UC-008` | View/search/sort `Mis puntuaciones` | Authenticated user  | Scope by user before search/sort/count/page; default updated-descending; unique `gameId` tie-breaker; bounded projected genres and mean/count for the paged games; isolated aggregate failure preserves personal context                                          |

The client never supplies a trusted user/evaluation date. Scoped absence returns
`RATING_NOT_FOUND` without revealing another user's state. Authentication cancellation
or invalid/expired/replayed context creates no rating. Any failed rating command
preserves personal and aggregate state.

Post-MVP (#162, implemented): `UC-004` also supports general account entry from the
anonymous header directly through BFF/OIDC to the Spanish-first Gameómetro Keycloak
theme. `Crear cuenta` adds Keycloak's supported `prompt=create` to the same
authorization request, with server-generated PKCE, state and nonce. Keycloak owns all
credential, registration and recovery forms. `/login` is only a server redirect for
compatibility and never renders a product page. General entry stores one bounded, short-lived local
product destination in the existing session and consumes it once after authentication.
Only known discovery, search, game and personal-rating paths and their navigation query
keys are allowed; authentication/server paths, external destinations, encoded path
tricks and control characters fall back to the landing page. Direct entry without
context also returns to the landing page. Starting general entry replaces an older
rating intent, and starting a rating intent replaces general return context. Failed
general authentication consumes the context and returns to its safe product destination
without automatically restarting authentication; a rating failure keeps its game
outcome and never executes a command. Authenticated account navigation exposes only
existing personal ratings and session logout. Logout preserves a public browsing
context, or returns a personal/account page to the landing page.

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
| Validation             | `SEARCH_QUERY_INVALID`, `FILTER_INVALID` (including a malformed featured month), `PLATFORM_NOT_SUPPORTED`, `REGION_NOT_SUPPORTED`, `SORT_INVALID`, `RATING_VALUE_INVALID`; do not execute invalid work                                  |
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

### Explicit current-release repair (UC-009, post-MVP #212)

Ordinary synchronization stores normalized release stage alongside lifecycle evidence.
Adding stage to otherwise unchanged evidence preserves accepted date verification;
conflicting verified updates are retained. Provider-only review is recomputed under the
[domain review rule](../domain/mvp-domain-model.md#boundaries-and-concepts), including clearing
stale requirements when current evidence is unambiguous. Unknown incoming stage cannot erase a
known stage. Provider vocabulary remains in the anti-corruption adapter. Stage eligibility
policy is unchanged.

Operator repair replays bounded date windows through ordinary synchronization, then visits
all known typed Game references in bounded, deterministic Game-ID keyset batches. It includes
Games with known stages and those whose releases no longer occur in date windows. Each Game
is completely fetched and follows the identical normal reconciliation path: create new
references, reconcile returned references and remove absent provider-owned current Releases
atomically. It neither discovers unknown Games through reference repair nor guesses which
record is stale from dates/tuples. Source ownership and completeness rules are those of
[ADR-0017](../../decisions/0017-discover-catalogue-members-automatically-from-igdb.md).

Dry-run validates the same persistence constraints in a rolled-back Game transaction with
no listing event, serving-state, revision or run-history change. Apply uses the same database
run ownership and fencing as synchronization. A failed Game retains its complete state;
other Games can commit independently. A partial/failed/skipped batch retains its checkpoint
cursor, so safe retries replay successes idempotently. A completed checkpoint performs only
summaries; a fresh checkpoint rechecks later provider changes. This is explicit operator
maintenance on private management, not a product-facing endpoint or historical ledger.
