# IGDB exploration quick guide

Development reference for [the collection](igdb-exploration.postman_collection.json).
This guide owns provider exploration; the [story map](../../docs/product/mvp-story-map.md)
owns product scope. `core`, `useful` and `future` below describe exploration relevance,
not approved new requirements. Future uses are hypotheses.

## Start in a few minutes

1. Import the collection and [IGDB environment](igdb.postman_environment.json).
   Select **VideoGame Platform - IGDB Exploration**, independently of the product's
   local environment. No backend or database is needed.
2. Register a confidential Twitch application and obtain an app access token using
   the [client credentials flow](https://dev.twitch.tv/docs/authentication/getting-tokens-oauth/#client-credentials-grant-flow):
   `POST https://id.twitch.tv/oauth2/token`, with form body `client_id`,
   `client_secret`, `grant_type=client_credentials`. Keep the secret private. Set
   `igdbClientId` and `igdbAccessToken` privately in Postman; never export populated
   credentials into Git. On expiry, obtain a new app token.
3. Send **The Witcher 3 - expanded game card**. Collection defaults select IGDB game
   `1942`, PC platform `6`, and Portal 2 `72` for the co-op example. All are provider
   IDs, independent of product UUIDs. Search and inspect identity before substituting
   another game.
4. Run lookup requests before dependent examples: copy the chosen region, company,
   series, franchise, age-rating or popularity IDs to their named collection variables
   (or environment overrides). Empty defaults prevent accidental use of old enum IDs.
   `igdbAgeRatingIds` and `igdbPopularGameIds` accept comma-separated numeric IDs.
   Change `igdbGameId` deliberately; responses do not silently change your selection.

`igdbBaseUrl` and all query defaults live in the collection. Empty date variables
are evaluated per request: `igdbNowEpoch` is now, `igdbFromEpoch` is 30 days before,
`igdbToEpoch` is 90 days after. Override them with UTC Unix **seconds**, using an
exclusive upper boundary. Keep the same explicit dates across multiple pages.
`igdbPageSize` defaults to 20 and is guarded at 1–50; `igdbOffset` starts at zero.

Use a current Postman desktop version supporting
[`pm.execution.skipRequest`](https://learning.postman.com/docs/tests-and-scripts/write-scripts/postman-sandbox-reference/pm-execution/#pmexecutionskiprequest):
unresolved or invalid query variables skip sending and explain the problem in the console.
Send requests individually, or run a selected folder after resolving its variables.
For the runner, choose one iteration, sequential execution and at least 350 ms delay;
other clients share the quota. Tests check HTTP success, array shape and page bounds,
allowing empty data. A `401` needs credential inspection/token renewal; a `429` needs
slower execution. No automatic retries or full-catalogue crawl are included.

## Reading the queries

IGDB requests use `POST`, a raw APICalypse body, `Client-ID` and bearer authentication.
`fields` selects the payload; `where` filters; `sort` orders; `limit` caps root rows;
`offset` skips rows. Statements end with semicolons. See the
[official reference](https://api-docs.igdb.com/#reference) for syntax.

**Expander** follows resource references with dotted fields, such as
`genres.name` or `involved_companies.company.name`. Compare the game card with the
company and taxonomy requests. A root `limit 1` does not cap expanded arrays:
relationship inspection is deliberately separate, and growing series/child/media
lists use paged endpoint requests.

For offset exploration, add the page size to `igdbOffset` until a shorter page arrives.
Non-ranked listings use unique ID order. The advanced ID-cursor request instead
advances `igdbAfterGameId` to the final ID. Neither freezes changing provider data.
Search uses provider relevance; the popularity request is a single ranked snapshot,
without offset because ties have no promised total order. IGDB's default limit is 10,
maximum 500; this collection deliberately uses smaller pages.

The **Game-page evidence bundle** batches card, releases, languages and duration into
one `multiquery` call. Results are named separately. Its four queries are independent:
one cannot consume another's returned IDs, and the batch is not an atomic snapshot.
The API permits at most ten subqueries. Details and examples:
[multiquery](https://api-docs.igdb.com/#multi-query).

Image metadata supplies `image_id`; a rendition URL has the shape
`https://images.igdb.com/igdb/image/upload/t_cover_big/IMAGE_ID.jpg`.
Normalize protocol-relative URLs to HTTPS. Use suitable image sizes, preserve
attribution and follow [ADR-0001](../../docs/decisions/0001-reference-igdb-cover-images.md)
for product covers. Video IDs belong to YouTube. See
[images/CDN](https://api-docs.igdb.com/#images).

The [API limits](https://api-docs.igdb.com/#rate-limits) are four requests/second
and eight concurrent open requests. Use Postman's desktop agent for external calls;
IGDB does not allow direct browser JavaScript access through CORS.

## Included endpoints

Grouped rows list every endpoint used; expanded reference fields do not imply
additional standalone requests. See [endpoint schemas](https://api-docs.igdb.com/#endpoints)
for complete field definitions.

| Endpoint | Purpose | Relevance | Example information |
|---|---|---|---|
| `games` | Identity/detail hub | core | Title, type, relationships |
| `release_dates` | Release evidence | core | Platform, region, precision |
| `release_date_regions` | Release-region lookup | core | Region reference |
| `date_formats` | Date-precision lookup | core | Format reference |
| `release_date_statuses` | Release-state lookup | core | Status label |
| `covers` | Cover evidence | core | CDN identifier |
| `platforms` | Platform lookup | core | Name, abbreviation |
| `game_types`, `game_statuses` | Work classification | core | Type/status references |
| `alternative_names` | Alias evidence | core | Name, explanatory comment |
| `genres` | Classification | core (#233, expanded on `games`) | Genre labels |
| `themes` | Classification | useful | Labels |
| `game_modes` | Play capabilities | core (#233, expanded on `games`) | Modes |
| `multiplayer_modes` | Play capabilities | useful | Co-op limits |
| `involved_companies`, `companies` | Credits | core (#233, expanded on `games`) | Developer/publisher roles |
| `collections`, `franchises` | Series/universe | useful | Membership context |
| `artworks`, `screenshots` | Featured media | core (metadata) | Image identifier, dimensions, transparency, animation |
| `logos` | Featured title logo | core (metadata) | Read by game; identifier, dimensions, transparency |
| `game_videos` | Rich media | future | Trailers |
| `game_localizations`, `regions` | Regional presentation | future | Titles, localized covers |
| `language_supports`, `languages`, `language_support_types` | Language evidence | future | Audio/subtitle/interface support |
| `age_ratings`, `age_rating_organizations`, `age_rating_categories` | Age classifications | future | PEGI/ESRB labels |
| `game_time_to_beats` | Duration evidence | future | Estimates, sample count |
| `websites`, `website_types` | External links | useful | Official/community/store sites |
| `external_games`, `external_game_sources` | Provider mappings | future | External source and UID |
| `popularity_types`, `popularity_primitives` | Discovery signals | core (Visits) | Signal value, calculation time |
| `search` | Cross-resource discovery | future | Mixed game/series results |
| `multiquery` | Batch exploration | useful | Independent evidence bundles |

## Relationships and product fit

`games` points to releases, taxonomy, companies, media, aliases, localizations,
languages, age classifications and other games. Releases link their own platform,
release-region, date-format and status. Company credits attach roles to a
company/game pair. Language support joins game, language and support kind.
Localization `region` uses **regions**, a different resource from **release_date_regions**.
Duration and popularity carry integer `game_id` values; resolve those with a bounded
`games` query rather than assuming an expandable game reference.

The current acquisition path is
[IgdbQueries.java](../src/main/java/com/videogameplatform/catalogue/adapter/provider/igdb/IgdbQueries.java)
inside the catalogue provider adapter; the operator flow is documented in the
[backend README](../README.md#persistence-and-observability).
The [isolated PoC](../../tools/igdb-poc/README.md) and its
[historical evidence](../../docs/research/igdb-poc-results.md) serve different purposes.
These examples do not alter acquisition rules or import eligibility.

Potential uses to evaluate:

- **Current product:** inspect identity, aliases, cover and per-platform/region release
  evidence against the approved scope; provider fields do not guarantee local delivery.
- **Richer detail:** the summary, developer/publisher credits, genres and game modes are
  acquired since #233; themes, language support, age labels and duration still need
  coverage and user-value evidence before adoption.
- **Discovery/recommendations:** series and similar games are candidate signals; none
  proves personalized relevance or demand. The Visits primitive alone ranks featured
  releases ([ADR-0021](../../docs/decisions/0021-rank-monthly-featured-releases-by-local-igdb-hypes.md));
  other primitives remain exploration.
- **Multimedia:** featured discovery uses the metadata of artworks, screenshots and logos
  under the amended [ADR-0001](../../docs/decisions/0001-reference-igdb-cover-images.md);
  `artwork_type` is deprecated in favour of `image_type`, whose values are not documented.
  Observed labels include `Artwork`, `Key art with logo`, `Key art without logo`,
  `Historical artwork`, covers (`Alternative cover`, `Square cover`, `Historical cover`),
  `Game logo (color|black|white)` and icons; FEAT-003 reads only title key art and the
  cover, logo and icon labels. Trailers and regional covers still need separate licensing,
  attribution and presentation decisions.
- **Statistics/popularity:** compare signal types and timestamps; provider critic/user
  scores are distinct from product-owned personal ratings and aggregates.
- **Future capabilities:** co-op filters and external store mapping require validation;
  a store UID does not prove stock, ownership, regional availability or price.

All remain acquisition hypotheses. User request paths continue serving normalized
local state; the [solution architecture](../../docs/architecture/mvp-solution-architecture.md)
remains authoritative.

## Caveats and deliberate exclusions

- Some official examples still use deprecated enums. This collection uses `game_type`,
  `game_status`, `collections`, `date_format`, `release_region`, age-rating
  `organization`/`rating_category`, website `type`, `external_game_source`,
  `game_release_format` and `external_popularity_source`. Do not reuse legacy numeric
  enum values as reference IDs. See [enum migration](https://api-docs.igdb.com/#migration-enums-to-tables).
- Preserve approximate date evidence; timestamps alone do not prove an exact day or
  commercial release. The official examples contain a seconds/milliseconds wording
  inconsistency; these requests use seconds, matching the field/reference contract.
- Fields and relations can be absent, incomplete or change. Empty arrays are valid.
  Estimates and scores need counts; provider metadata does not prove user demand.
  Expanded relationship arrays are for single-game inspection, not scalable crawls.
  Popularity primitives are refreshed daily; inspect `calculated_at` before calling
  a signal current. They do not establish a historical trend by themselves.
- Every requested endpoint family is included. `game_versions`, collection
  membership/relation/type endpoints, detailed age-content descriptions, engines,
  characters, achievements, webhooks and dumps have no standalone examples: the
  selected relationships/labels answer this guide's questions with less noise.
  These exclusions do not claim those resources are obsolete. Search can still
  return a character reference.
- `fields *`, obsolete `games.follows`, legacy category/status/region enums and
  deprecated `games.collection` are excluded. No `/trending` endpoint is invented;
  popularity primitives provide the supported signal exploration.
- IGDB availability does not authorize public/commercial use or redistribution. The
  [Product Brief](../../docs/product/product-brief.md) and accepted provider decisions
  retain their existing boundary. Live authenticated results are not bundled here;
  run the samples privately to assess actual coverage.
