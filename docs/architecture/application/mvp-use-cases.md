# Learning MVP application use cases

- **Status:** Approved
- **Owner:** Ruben Hernandez
- **Scope:** Application operations and guarantees; not HTTP, screens, SQL, or framework classes

## Catalogue

| ID       | Operation                           | Actor                 | Required behaviour                                                                                                                                                                                   |
|----------|-------------------------------------|-----------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `UC-001` | Explore the releases of a date range | Visitor             | Post-MVP (#285): application derives the evaluation date and validates an inclusive range of at most 92 days, or derives the current Monday-to-Sunday week; PostgreSQL classifies each release against the evaluation date from its effective date (not from a persisted status), then groups the matching releases by game so a result is one game presenting at most one of its range-and-filter-matching releases per platform, and counts, orders by date, Hype or community score, and pages games; cancelled is always excluded and delayed once its day has come; days after today show exact-day releases unless the visitor opts into approximate dates; TBA never belongs to a range; empty/fallback are valid states |
| `UC-002` | Search bounded catalogue            | Visitor               | Normalize the query once; PostgreSQL matches canonical titles/approved aliases, ranks, counts, uniquely orders and pages; zero/multiple matches are valid; never call provider                       |
| `UC-003` | View game details                   | Visitor/optional user | Return coherent game/releases/eligibility/aggregate; personal rating is a separate authenticated resource; unavailable aggregate/fallback may degrade a valid page                                   |
| `UC-009` | Synchronize catalogue from provider | Operator / scheduler  | Synchronize every provider Game in a caller-supplied inclusive release-date interval in one call; page internally; reconcile stable Game and Release references; commit valid Games independently |
| `UC-010` | Browse weekly or monthly featured releases | Visitor          | Application derives evaluation date, the current ISO week and calendar month (or validates a selected period inside the navigation bounds); PostgreSQL selects the qualifying releases inside the period, ranks their games by the local popularity signal with a unique tie-breaker, limits the ranking to six and presents at most one release per platform; unranked, empty, stale and fallback are valid states; never call provider |
| `UC-011` | Inspect catalogue acquisition state | Catalogue operator    | Post-MVP (#247): read the run in flight, at most twenty finished retained runs with their outcome and counters, and durable coverage of an inclusive release-date range as bounded consecutive periods; local evidence only, never a provider call or a synchronization start; only a session holding the operator authority |
| `UC-012` | Request bounded catalogue synchronization | Catalogue operator | Post-MVP (#248): validate an inclusive release-date interval of at most 184 days between 1950 and 2099, take the single run ownership that every trigger shares and answer with the accepted run before any provider work; the ordinary `UC-009` run continues in the background and is followed through `UC-011`; a run in flight or missing provider access starts nothing; only a session holding the operator authority, with CSRF proof |
| `UC-013` | Stop or abandon a catalogue synchronization run | Catalogue operator | Post-MVP (#342): for one run named by its identifier, whatever triggered it, record a stop request its worker honours at its next safe point (before its next provider page or Game), or abandon and fence it only while its heartbeat is older than the abandonment period; committed Games stay, a stopped or abandoned run never records coverage, nothing is retried and schedules do not change; a finished, unknown or still-alive run changes nothing; only a session holding the operator authority, with CSRF proof |
| `UC-016` | Count a month's highlighted releases per day | Visitor | Post-MVP (#250): application derives the evaluation date and the current month in `Europe/Madrid` and validates a requested month from the current one to the last month wholly inside the forward acquisition horizon, never an earlier one; PostgreSQL counts, per day from the evaluation date to the month's end, the games with local Hypes >= 10 and a qualifying exact-day release under `UC-001`'s rules and filters; days already gone by and month, quarter, year or unknown dates are never counted; at most 31 days; empty is a valid state; never call provider |
| `UC-017` | Orchestrate historical catalogue synchronization | Catalogue operator | Post-MVP (#246): explicitly start, stop, resume and inspect one durable target through sequential calendar-month UC-009 runs; preserve completed months, separate data-quality completion from operational failures and yield to scheduled updates; see [historical guarantees](#historical-catalogue-synchronization-uc-017-post-mvp-246) |

`UC-001` results are grouped by game before pagination: a game appears once and carries,
of the releases matching the requested range and active filters, only the presented
release of each platform (see below), so differing platforms stay visible with the
region, date and date precision of each presented release.
Releases follow one ascending date order: the last day of the effective period (the day
itself for an exact date), then an exact day before a month, quarter or year ending the
same day, then the unique `releaseId`. A game is positioned by its first presented
release under that order, then its case-insensitive canonical title and `gameId`, and
its releases keep the same order. Post-MVP (#285), the visitor may instead order games
by Hype, highest first; by the Gameómetro community mean at the one decimal shown,
highest first and then by more ratings; or by the stored external player score (currently
IGDB players) at the one decimal of its /10 presentation, highest first and then by more
of that source's ratings. A game without the chosen signal, including one whose last check
found no external score, follows every game with one and is never treated as zero, and ties continue with the date order and
`gameId`. Hype is the locally stored IGDB evidence that `UC-010` ranks with, and its
value is never exposed; the external player score is Catalogue's own stored evidence
(#255), read without any provider call. The community mean comes from a Catalogue-owned projection that
Ratings maintains through a Catalogue application contract inside each rating write
([ADR-0029](../../decisions/0029-project-community-scores-for-the-release-order.md)), so
Catalogue never reads Ratings tables and PostgreSQL orders before it pages. Counting
and pagination are over games, so `totalItems` counts games. The releases grouped under
one game are explicitly bounded, so request memory is `O(pageSize x releaseGroupLimit)`
plus bounded taxonomy; persistent filtering/counting/ordering/pagination/grouping never
occurs over a complete Java snapshot.

Post-MVP (#255 phase 2, implemented; awaiting owner review): discovery enriches only
its already selected Game IDs (at most one page, or six featured Games) with two
bounded application batches. Catalogue supplies its phase-1 current external player
scores; Ratings supplies its own community summaries. API delivery composes them,
without Catalogue depending on Ratings or reading its tables. A missing external
pair is absent; an unavailable community read remains unknown, distinct from zero
ratings. Work-level scores never change release selection, featured ranking or
rating-write eligibility, and change the order and pagination only when a visitor
explicitly orders the release explorer by community or external player score (#285). Discovery reads call no provider. Validators cover the
complete represented scores and their observation times; stored responses must
revalidate before reuse, and rating commands invalidate the frontend's discovery
queries. HTTP caching never expires a persisted score. Presentation follows the
[quality policy](../../product/product-brief.md#trustworthy-quality-and-community-direction).

Post-MVP (#212, implemented): current legitimate releases remain distinct. Real provider
data often holds several for one game and platform, such as regional releases, early or
advance access beside a later date, or a still-returned approximate estimate beside an
exact day. Synchronization removes obsolete provider references before presentation. A
context presents one of them per platform (`REL-013`), chosen in PostgreSQL by one
precedence: a release neither cancelled nor delayed, then a Full Release, then one not pending review, then
verified evidence, then the most precise date, then Worldwide before a specific region
before an unconfirmed region, then the context's own date order, and finally a stable
region order and the unique `releaseId`. Discovery applies it to the matching releases with
the earliest date first, so a region filter presents that region's own release, while facets and `totalItems` still come from
every matching release. The game page applies it with the earliest date first to each
platform and to each platform and region: it lists the presented release of every platform
and region together (#233), and any further records of a combination stay whole behind one
disclosure. Presentation never deletes, merges or
rewrites a release and never changes rating eligibility, which still evaluates every
current release.

Post-MVP (#285, implemented; awaiting owner review), one explorer replaces the recent and
upcoming windows and their fixed 1, 2 or 4 week horizons. The visitor names an inclusive
range of calendar days, past, future, around today or a single day, of at most 92 days;
without one the application evaluates the current Monday-to-Sunday week in
`Europe/Madrid`. Only calendar arithmetic validates a range: one bound without the other,
an end before the start or a longer range is rejected rather than read. The trusted
evaluation date splits the range. Up to and including it, a release belongs when it has
happened: an exact day inside the range, or a month, quarter or year period that has
ended and overlaps the range, neither cancelled nor delayed. After it, a release belongs
when it is expected on an exact day inside the range and is not cancelled, so a delayed
release with a future day stays relevant. A shared range is therefore a fixed interval
whose classification follows the evaluation date, and the response states the evaluated
range. Changing the range, order or filters resets the page, and the URL carries every
choice.

Approximate dates (#214, adjusted by #285): the days from the evaluation date on show
only exact days by default. With **Incluir fechas aproximadas**, offered only while the
range reaches beyond today, a month, quarter or year period that has not ended joins when
it overlaps that part of the range, each at its own precision; it changes nothing for a
range that ends earlier. No release receives an invented day, and a release with an
unknown (TBA) date has no period, so it never belongs to a range. PostgreSQL applies the
choice before facets, count, ordering and pagination, so `availableFilters` and
`totalItems` describe it. Precision is the only rule: no platform lifecycle applies.

Platform and region filters are multi-select facets: values within one dimension combine
with `OR` and the two dimensions combine with `AND`. No value selected for a dimension
means it is unfiltered (`Todas`), which is distinct from selecting the concrete
`Worldwide` region. `availableFilters` is contextual and faceted, computed in PostgreSQL:
platform options reflect the current range and the active region selection (never
narrowed by the active platform set), region options reflect the current range and the
active platform selection (never narrowed by the active region set), and a currently
selected valid value always stays representable. A future or historical platform or
region therefore does not appear in an unrelated range merely because it exists.
The wire shape of these parameters and the response lives in
[`openapi.yaml`](../api/openapi.yaml).

Post-MVP (#250, implemented; awaiting owner review), `UC-016` is the highlights calendar (**Calendario de destacados**), a second
view of **Lanzamientos** beside the explorer. It reads one calendar month at a time, the
current month in `Europe/Madrid` by default; navigation never goes back, and it reaches forward
to the same last month that `UC-010` months reach, so both monthly views share one bound. The
calendar is active from the evaluation date on (owner decision): the current month is counted
from today, and a day that has gone by is never counted or chosen. A day holds only releases
with that exact day, classified exactly as `UC-001` classifies a range of that single day
without approximate dates, with the same multi-select filters and contextual facets. Its count
is of games, not releases. Only games with locally stored IGDB Hypes >= 10 qualify; lower,
null or absent Hype is excluded before contextual facets, counts and pagination. The threshold is fixed, informed by the private-dev Hype distribution reviewed for the owner's
2026-10-21 request, rather than a percentile recalculated per request. The count covers highlights only; the full explorer lists all games for that day and the same
platform/region filters, so its total can be larger.
A release known only to its month, quarter or year, or with an unknown (TBA) date, is never
placed on a day or counted (owner decision: no separate imprecise-date line). PostgreSQL
filters, groups, counts and sums the local Hype once per game and day, irrespective of
matching platforms or regions. The daily `totalHype` follows the same filters as its count,
includes every qualifying game (not only the preview), and drives the calendar colours. Work stays
in PostgreSQL over the shared period index, so a response holds at most 31 days
and no game page is read. The games of a chosen day are an ordinary `UC-001` read of that one
day ordered by Hype with `UC-001`'s fallback and tie-breakers. The preview's page size is the
smaller of three and the highlighted-game count: high-Hype games precede every lower or
missing signal, so a short highlighted day never fills its preview with other games.
**Ver todos los lanzamientos del día** opens the unrestricted explorer for that day in the
same order and platform/region context. No highlights filter exists in the explorer or its API.
The calendar never shows numeric Hype.
The API owns the bounds and the evaluation date; the browser never decides "today".

Post-MVP (#151, implemented; #329, implemented and awaiting owner review): `UC-010` is
the landing view of release discovery, **TOPómetro** (named in #341), beside the release explorer under **Lanzamientos** (#285). It
represents one calendar period, an ISO week from Monday to Sunday or a calendar month, both
evaluated in `Europe/Madrid`; the URL carries the selection. The frontend opens on the
current week (owner decision, #329), while a request naming no period still reads the
current month. Navigation is bounded by the application, never by the client: weeks reach
16 weeks before and after the current week; months reach 10 calendar months back, across
years, and forward to the last calendar month lying wholly inside the configured forward
acquisition horizon, the widest future window of the recurring
[catalogue synchronization](../../development/operations-runbook.md#catalogue-synchronization)
policies that are not disabled. That bound says what acquisition is configured to cover,
not that a period was scanned or that the catalogue is complete; coverage stays the
operational evidence ADR-0017 owns. A period outside the bounds is rejected as an invalid
filter, and the response names the adjacent periods still inside them. A release qualifies for the period when its known date lies
wholly inside it (an exact day of it or, for a month, month precision equal to the month;
quarter, year and unknown dates never do, and a week admits exact days only) and it is
neither cancelled, delayed nor pending
review, the same negative evidence that blocks rating eligibility. Only Full Release
qualifies. A candidate also satisfies `FEAT-001`: an accepted import type, no edition
parent, its own known first release in the period, and positive Hypes. A game whose
first-release evidence falls in another week than its catalogued day therefore features in
its month but in no week; nothing assigns it to an invented week. These gates affect
only featured discovery; the normal catalogue and the release explorer remain unchanged.
Post-MVP (#295), a request may name explicit platforms: only a qualifying release on one of them makes
its game a candidate and is presented, before ranking and limiting, so a restricted selection is
the ranking of the restricted candidates and never a filtered copy of the unrestricted six.
Candidates are ranked by the locally stored popularity signal, currently IGDB Hypes, highest first,
with the unique `gameId` as the only tie-breaker. A candidate without a signal is not
ranked and nothing is invented for it; it remains a normal catalogue and discovery
result. The first ranked game is the period's featured release (*Lanzamiento de la
semana* or *Lanzamiento del mes*) and up to five more follow; fewer are shown rather than filling slots. Each presents,
of its qualifying releases in the period, at most one per platform under the shared
presented-release precedence with the earliest date first. The selection states why it
holds what it holds: ranked, popularity unavailable (qualifying releases without any
signal) or no qualifying releases. A ranked selection carries the freshness of its
oldest signal under the catalogue freshness policy; a stale ranking is still the last
valid local one. Any other period is ranked with the same current local signals, because
no popularity history is kept, so consecutive periods may legitimately repeat games. Popularity measures provider-observed attention, never
quality, a rating or an editorial recommendation, and its value is not exposed. Work is
bounded by the period's releases and request memory by the six ranked games and their
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

Post-MVP (#279 phase 4, implemented; awaiting owner review): `UC-002` also returns the work-level
scores of only the returned page's Games, through the same two bounded application batches
that discovery composes (#255): Catalogue's current external player scores and Ratings' own
community summaries, one PostgreSQL query each and none for an empty page. API delivery
composes them after matching, ranking, counting and paging, which they never change. Missing
pairs, unavailable statistics and a failed external read keep the discovery semantics, and
the search calls no provider. Its validator covers the scores and a stored page revalidates
before reuse; rating commands also invalidate the frontend's search results. Typeahead
suggestions receive the same response but never present the scores.

`UC-009` reconciles all relevant new and known Games in the requested date interval through one operation.
Post-MVP (#245, implemented; awaiting owner review): a run that read every provider page
of its interval and committed at least one Game records the whole interval as durable
coverage, with its failed Game count, together with its completion. An interrupted run
covers nothing. A separate bounded
application read reports covered and never-covered periods without calling the provider;
[ADR-0017](../../decisions/0017-discover-catalogue-members-automatically-from-igdb.md#coverage-and-freshness)
owns the semantics.
Post-MVP (#155, implemented; private-dev exercise pending): manual and scheduled inbound
adapters supply that same request. Recurrence and moving-window policy stay in the
scheduled adapter, outside application reconciliation and domain rules; the
[solution architecture](../mvp-solution-architecture.md) owns its placement. A busy
provider returns `SKIPPED / SYNCHRONIZATION_ALREADY_RUNNING` without provider work;
no credentials returns `SKIPPED / SYNCHRONIZATION_DISABLED` without persistence work.
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
rating reads and writes are outside this public operation. Post-MVP (#359), the available
aggregate also carries each scored platform's reading, from the same read-only snapshot,
and each release names its platform by the taxonomy's `PlatformId`, as every other
catalogue read does.

Post-MVP (#279 phase 3, implemented; awaiting owner review): `UC-003` also returns the
Game's current external player score, read for its one Game ID through the same Catalogue
application batch that discovery uses (#255). API delivery composes it beside the
Ratings-owned aggregate, so neither module reads the other. A missing or invalid pair is
absent, never zero; it never blends with the aggregate or affects eligibility. A failed score
read is a Catalogue read failure, as on discovery, never an invented absence. The public
validator covers the score, and the read calls no provider.

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

Post-MVP (#255 phase 1, acquisition and storage implemented; awaiting review): `UC-009` acquires the IGDB player score (`rating`) and its rating
count with the existing Game request, under `XSC-001`–`XSC-004`. Critic and combined
scores are never requested. The adapter declares the 0–100 scale and maps no pair or zero
ratings to a stated absence; a partial pair, an out-of-range mean or a non-integer count is
an invalid answer counted as `player_score_invalid`. The application records the score
with the work's attribution page and the provider's Game-record update time, or keeps the
last valid score when the answer is invalid or the score cannot be attributed. Inside each
Game transaction a valid answer upserts one current row per Game, source and kind: an
unchanged re-check advances only the check time, an earlier or replayed check never moves
it backwards, and a failed Game rolls the score back with everything else. Explicit repair
refreshes it with each Game. The score does not advance the catalogue revision: discovery validators include
its current state independently of that revision. Existing Games gain a score only when a scheduled window, a manual
interval or an explicit repair reconciles them again; there is no backfill, score expiry or
rating-count threshold.

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

## Preferred platforms

| ID       | Operation                           | Actor               | Required behaviour                                                                                                                            |
|----------|-------------------------------------|---------------------|-----------------------------------------------------------------------------------------------------------------------------------------------|
| `UC-014` | Choose and manage preferred platforms | Authenticated user / visitor | Post-MVP (#295): any visitor reads the selectable platforms, the local taxonomy entries that a current release uses; the authenticated user reads and atomically replaces their own set of at most 50, keyed by the server-derived `UserId`, where every value names a taxonomy platform and an empty set clears it; the first saved choice, empty included, records that a choice was made; never a provider call, a Keycloak attribute or a public read that varies on the session |

Post-MVP (#295, implemented; awaiting owner review): preferred platforms are an explicit,
user-chosen discovery filter, never a recommendation, an inference from ratings or an
ownership inventory. Catalogue owns them beside the taxonomy they reference, so a
preference can only name a platform the catalogue holds and Catalogue still never reads
Ratings or identity tables. A replacement takes the user's choice row first, so concurrent
replacements of one user's set serialize and the last one wins whole; an invalid set
changes nothing. The selectable list probes each taxonomy platform once against the
current publication's releases, so its work follows the bounded taxonomy, and it fails
rather than truncating beyond 500 platforms.

The browser resolves the preferences into the explicit platform filter of the public
`UC-001`, `UC-010` and `UC-016` reads, so those reads stay publicly cacheable and identical for
every session. They apply by default on TOPómetro, Explorar lanzamientos and the release
calendar; one document-wide switch in **Mi cuenta** turns them off for those screens without
changing the saved set, and a manual platform selection in the explorer or the calendar sets
them aside rather than intersecting with them.
The frontend offers only the platforms it presents with an owner-provided mark (owner
decision); the API keeps the whole selectable taxonomy. `UC-002`, `UC-003` and `UC-008` never apply preferences. A signed-in user who has
not chosen yet is offered the choice once per document on the way into release discovery,
after authentication; saving or skipping records the choice, and rating and personal
returns are never interrupted.

## Identity and ratings

| ID       | Operation                           | Actor               | Required behaviour                                                                                                                            |
|----------|-------------------------------------|---------------------|-----------------------------------------------------------------------------------------------------------------------------------------------|
| `UC-004` | Authenticate and resume rating      | Visitor             | Store short-lived tamper-resistant context; derive user from principal; atomically consume/replay-protect; return to allowlisted game context |
| `UC-005` | Create rating                       | Authenticated user  | Validate every score 1–10, one score source and any named platforms against the game's releases, and current eligibility; prevent duplicate; update personal/aggregate coherently |
| `UC-006` | Update rating                       | Authenticated owner | Scope by principal + game; validate scores/score source/platforms/eligibility/concurrency; keep saved platforms unless the command replaces them; preserve previous state on failure |
| `UC-007` | Delete rating                       | Authenticated owner | Scope by principal + game; delete the rating with its platforms regardless of current eligibility; update aggregate coherently                |
| `UC-008` | View/search/sort `Mis puntuaciones` | Authenticated user  | Scope by user before search/sort/count/page; default updated-descending; score order by unrounded contribution; unique `gameId` tie-breaker; bounded projected genres, each paged rating's platforms and mean/count for the paged games; isolated aggregate failure preserves personal context |
| `UC-015` | Inspect personal rating statistics | Authenticated user | Post-MVP (#254, owner-requested implementation; awaiting review): derive fixed-size statistics only from the principal’s current active ratings, in one local PostgreSQL snapshot; no inferred playing behaviour, recommendations or provider request |

The client never supplies a trusted user/evaluation date. Scoped absence returns
`RATING_NOT_FOUND` without revealing another user's state.

Post-MVP (#359, [ADR-0031](../../decisions/0031-count-one-community-vote-per-rating-across-platforms.md)):
a rating may also name the platforms its user played on, each with an optional score.
A command's direct value is required exactly when no resulting platform carries a score,
and must be absent when one does; `RATING_SCORE_SOURCE_INVALID` rejects a command that
leaves no score source or two of them, so a direct value never silently replaces platform
scores and removing the last platform score needs a direct value in the same command. A
command that names platforms replaces the whole set (an empty set clears it); one that
names none keeps the saved set, which is what every earlier client sends. A repeated
platform or more than 50 are `RATING_PLATFORMS_INVALID`; a newly named platform that is not
one of the game's current release platforms, read through Catalogue's game-details
contract, is `PLATFORM_NOT_SUPPORTED`, while a platform already on the rating stays valid.
Validation of scores and the platform set precedes any read; precondition, eligibility
and platform support follow, and any failure changes nothing. The write stores the rating,
its platform rows and their Ratings-owned labels in one transaction under the per-game lock,
and the response carries the rating's mode, direct value, exposed contribution and
platforms with the recomputed aggregate. Platform choices are never inferred from preferred
platforms (#295), devices or releases, and a pick recovered after authentication
(`UC-004`) is a direct value that is never applied over platform scores. Authentication cancellation
or invalid/expired/replayed context creates no rating. Any failed rating command
preserves personal and aggregate state.

Post-MVP (#279, `Mis puntuaciones`, implemented; awaiting owner review): `UC-008` also returns
each paged Game's current external player score. After the user-scoped search, sort, count
and page, API delivery reads it for only that page's Game IDs through the same Catalogue
application batch as discovery (#255): one PostgreSQL query, none for an empty page. Ratings
keeps its own community summary. A missing pair is absent, never zero, and a failed external
read keeps the discovery semantics. Neither changes the personal rating, its order or the
page, and the read calls no provider.

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
existing personal ratings, preferred platforms (`UC-014`, #295) and session logout. Post-MVP (#274,
[ADR-0027](../../decisions/0027-revoke-and-end-bff-sessions-in-the-application.md)):
logout ends the application session and then that browser's Keycloak session through
standard OIDC, so the next sign-in on the device asks for credentials again; other
devices and users keep their sessions. Keycloak returns only to the realm's exact
post-logout URI, so logout always lands on the landing page instead of preserving the
previous public page. Prepared in #374: opt-in remembered sign-in and renewal follow
[ADR-0027](../../decisions/0027-revoke-and-end-bff-sessions-in-the-application.md).
Logout appears only with authenticated account actions. If the BFF expires before
that logout click is processed, provider confirmation completes the requested logout;
anonymous entry offers sign-in and registration. A session revoked
by the owner or beyond its applicable lifetime acts as anonymous from its next request,
and a personal command that
would start after revocation is refused with `AUTHENTICATION_REQUIRED`.

### Personal rating statistics (UC-015, post-MVP #254)

Implemented; awaiting owner review. **Tus estadísticas** describes the authenticated
user's current surviving ratings and, post-MVP (#363), the platforms recorded on them;
never community/external scores, ownership or playing time.
Each game contributes once to the total, arithmetic mean and ten integer distribution
buckets, with its one contribution (#359): the direct score, or the unrounded mean of its
scored platforms, placed in the bucket of that contribution rounded half up. Means round
half up to one decimal; the empty mean is null. The UI applies
the existing score temperatures, groups adjacent bucket pairs and warns that one to
four games are a small sample, without inferring a personality or preference.

Genres use the Ratings-owned rebuildable Catalogue projection from
[ADR-0018](../../decisions/0018-project-personal-ratings-listing-in-ratings.md).
Each game contributes once to every assigned genre; genre totals overlap. Missing
genres are counted explicitly. Return up to six genres by count descending then genre
ID ascending, and up to six by unrounded mean descending, count descending then genre
ID ascending, requiring at least three games for the latter. Always show sample sizes.
The highest and lowest values each name one game, the smallest game ID in a tie, and
the exact tied-game count; equal extremes are explicitly described as such. They compare
unrounded contributions and expose the value half up to one decimal.

Activity returns exactly twelve zero-filled calendar months, ending in the API's
evaluated month in `Europe/Madrid`, plus the number of older ratings. It counts current
ratings by original creation month: edits do not move them and deletes remove them.
It is neither a play/completion log nor historical score evolution. The evaluation
date is application-owned.

Platforms (#363) read only the played platforms the user recorded on current ratings
under [ADR-0031](../../decisions/0031-count-one-community-vote-per-rating-across-platforms.md);
nothing is inferred from Catalogue releases, preferred platforms (#295) or devices. A
platform's played count is the distinct rated games recorded on it, so a game on two
platforms counts on both and platform counts overlap; the games recorded on two or more
platforms are counted. Its scored count is the games given an explicit score on that
platform and its mean averages only those integer scores, half up to one decimal, null
without any; a platform played without a score, a direct score and the game's
contribution never reach it. Ratings without any recorded platform stay valid and are
counted. Return up to six platforms by played count descending, scored count descending,
case-insensitive name then platform ID ascending, plus the number of further platforms.

PostgreSQL scopes by principal before aggregation, ordering and limits in one read-only
repeatable-read snapshot. Java receives ten buckets, two lists of at most six genres,
at most two games, twelve months and at most six platforms: constant memory as the
user's collection grows. Database work grows with that user's ratings, their
at-most-fifty projected genre links and at-most-fifty platforms per game, with indexed
user/game access; no process-local correctness state.
The browser keeps this personal response only in session-bound document memory,
clears it on session changes and re-reads after rating mutations. HTTP responses are
`no-store`. The wire contract owns response shapes and errors.

## Common result and failure rules

- Product identifiers are internal; provider types/IDs do not escape their adapter.
- Date precision, provenance, verification, and review remain explicit. Catalogue
  synchronization freshness is internal operational evidence, never a player-facing state.
- Personal and aggregate ratings remain separate.
- Empty, no-rating, stale-but-usable, fallback-cover, ineligible, and unavailable
  aggregate states are not generic technical errors.
- Stable codes drive clients; localized copy is delivery-owned. Technical responses
  expose correlation, never secrets, raw provider payloads, SQL, or stack traces.

| Category               | Principal codes / guarantees                                                                                                                                                                                                          |
|------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Validation             | `COVERAGE_RANGE_INVALID`, `SEARCH_QUERY_INVALID`, `FILTER_INVALID` (including a malformed or out-of-bounds featured week or month), `PLATFORM_NOT_SUPPORTED` (also an unknown preferred platform or a rating's newly named platform outside the game's releases), `REGION_NOT_SUPPORTED`, `SORT_INVALID`, `RATING_VALUE_INVALID`, `RATING_PLATFORMS_INVALID`, `RATING_SCORE_SOURCE_INVALID`; do not execute invalid work |
| Authentication/replay  | `AUTHENTICATION_REQUIRED/FAILED/CANCELLED`, `RETURN_CONTEXT_INVALID/EXPIRED/REPLAYED`; no duplicate logical command                                                                                                                   |
| Authorization          | `ACCESS_DENIED` for a signed-in session without the operation's authority; decided before validation or any read                                                                                                                       |
| Domain/conflict        | `GAME_NOT_FOUND`, `RATING_NOT_ELIGIBLE`, `RATING_ALREADY_EXISTS`, `RATING_NOT_FOUND`, `RATING_WRITE_CONFLICT`, `RELEASE_DATA_REVIEW_REQUIRED`; preserve valid state                                                                   |
| Local reads            | `CATALOGUE_NOT_READY`, `CATALOGUE_READ_FAILED`, `RATING_STATISTICS_READ_FAILED`, `PERSONAL_RATINGS_READ_FAILED`; never request-path provider fallback or cross-user partial data                                                      |
| Writes                 | `RATING_WRITE_FAILED`, `SYNCHRONIZATION_WRITE_FAILED`; previous valid state remains                                                                                                                                                   |
| Provider normalization | `PROVIDER_AUTHENTICATION_FAILED/RATE_LIMITED/UNAVAILABLE/RESPONSE_INVALID/MAPPING_FAILED`, `EXTERNAL_REFERENCE_CONFLICT`, `RELEASE_DATA_INVALID`, `COVER_REFERENCE_INVALID`; isolate the affected Game and keep last valid local data |

Database constraints/transactions must enforce concurrent rating uniqueness, coherent
writes, and one game per provider reference. Synchronization expands catalogue
membership only through the import policy, and publishes a cover only after it
validates. Exact HTTP mapping is owned by OpenAPI and API conventions; the internal
synchronization command is an operator endpoint on the management port and is
deliberately outside the product contract. On the product port, `UC-011` reads its run
and coverage evidence, `UC-012` requests one bounded run and `UC-013` stops or abandons one
run, all only for the catalogue operator
([ADR-0028](../../decisions/0028-authorize-catalogue-operators-with-a-bff-client-role.md));
no command holds the browser open for provider work.
A failed, interrupted, stopped or abandoned run appears in that history without changing
coverage; an abandoned run has no counters rather than zero ones. A stop request is
evidence on the run, not a result: the run shows it while it is still in flight, and
keeps it whether it then stopped or finished first. Freshness stays a presentation
reading of the last complete scan, never a stored state, and the read is never shown to
players.

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

### Historical catalogue synchronization (UC-017, post-MVP #246)

Owner-requested extension, implemented subject to review and private-dev acceptance. The
catalogue operator explicitly starts one historical target and chooses whether already-covered
months are skipped or re-run. Deterministic calendar-month chunks clip the first and last
month to the requested dates. Only UC-009 acquires provider data, with all supported Games
and platforms and unchanged per-Game reconciliation. Completed months, including empty ones,
are durable checkpoints independent of run-history retention. Known data-quality failures
follow the existing assessment; operational failures pause progression for explicit resume.

Stop affects only this target and its linked run. Committed Games survive; a partial month
replays idempotently after explicit resume. Startup never starts or resumes historical work.
Scheduled synchronization takes priority at month boundaries. A due regular window is deferred
rather than lost, with one coalesced pending request per existing policy. Both pending policies
run before the next historical chunk; history then continues automatically only while still
activated. Deferred periodic intent survives restart, independently of historical activation
and stop. Inspection reads constant-size
local progress, including completed/skipped/remaining months, current counters, timings and
stable failure reasons. Month completion is acquisition evidence, never a guarantee that all
Games were imported without errors. The existing coverage view remains canonical. Commands
require the authenticated catalogue-operator authority, same-origin BFF and CSRF proof.
