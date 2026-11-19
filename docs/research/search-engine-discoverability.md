# Search-engine discoverability (#537)

Point-in-time audit of what search engines can discover on `gameometro.com`, and the
evidence behind the indexing foundation. It records a baseline and the decisions taken
from it. It is not maintained as the system changes. The code owns current behaviour:
[`SearchIndexingController`](../../backend/src/main/java/com/videogameplatform/api/delivery/frontend/SearchIndexingController.java)
owns the robots directives and sitemap contents.
[`PageMetadataResolver`](../../backend/src/main/java/com/videogameplatform/api/delivery/frontend/PageMetadataResolver.java)
owns each route's title, description, canonical path and indexability, and
[`FrontendRouteController`](../../backend/src/main/java/com/videogameplatform/api/delivery/frontend/FrontendRouteController.java)
owns the server-served SPA routes. The
[solution architecture](../architecture/mvp-solution-architecture.md#search-engine-indexing)
owns the boundary.

Status on 2026-11-19: all four increments are implemented and awaiting review. They are
the audit and route inventory, robots and the sitemap index, per-page metadata and true
404s, and JSON-LD with the rendering evaluation. Increment 3 extends #581's server-inserted
head block
([ADR-0049](../decisions/0049-insert-link-preview-metadata-into-the-served-entry-point.md),
merged as PR #583) rather than adding a second mechanism. Nothing is deployed, and nothing
has been measured in Search Console yet.

## Method

- Anonymous `curl` GETs against production on 2026-11-19, with a Googlebot user agent
  where it matters. No account, cookie or personal data was used.
- Route inventory from `FrontendRouteController`, the React Router table
  (`frontend/src/app/router.tsx`) and the edge's `gameometro.com` site in the production
  Caddyfile.
- Query plans from `EXPLAIN (ANALYZE, BUFFERS)` on a disposable PostgreSQL 18.4 with one
  million synthetic games, keyed like `catalogue.game_snapshot`. The real catalogue's
  size is not representative of the growth the sitemap has to absorb.

## Baseline evidence (production, before this change)

| Request | Status | Observation |
|---|---|---|
| `/`, `/lanzamientos`, `/mis-puntuaciones` | 200 | The same 707-byte `index.html`: `<title>Gameómetro</title>`, one generic description, `<div id="root"></div>` and nothing else |
| `/games/00000000-0000-0000-0000-000000000000/x` | 200 | The same shell, for a game that does not exist (a soft 404) |
| `/no-existe` | 404 | Empty body: unknown paths outside the SPA routes are already true 404s |
| `/robots.txt`, `/sitemap.xml` | 404 | Neither exists |
| `http://gameometro.com/` | no answer | Port 80 is closed; HSTS (`max-age=31536000; includeSubDomains`) covers returning browsers, and crawlers start from HTTPS links |
| `https://www.gameometro.com/` | no answer | No `www` host exists, so there is no duplicate host to canonicalize |
| `https://auth.gameometro.com/robots.txt` | 404 | The identity host exposes only allowlisted Keycloak paths; it is not linked for crawling |
| Entry-point headers | — | `Cache-Control: no-cache, no-store`; no `X-Robots-Tag` |

Deficiencies found:

1. **No crawler-visible content.** Without JavaScript every route is the same empty
   shell with the same title and description. Only a rendering crawler can see anything;
   non-rendering crawlers and link-preview clients see nothing (#581 covers previews).
2. **Soft 404s.** Unknown games answer 200. PR #583 turns unknown or non-UUID
   `/games/...` into 404. Increment 3 adds unknown platform keys.
3. **No robots or sitemap.** Crawlers had no list of canonical URLs and no instruction
   to skip personal pages.
4. **Personal pages answer 200 to anonymous crawlers** with the same shell. They hold no
   personal data without a session: the data comes from `/api/v1/me/*`, which refuses
   anonymous requests. They are still worthless to index.
5. **Two game URL forms resolve.** `/games/{gameId}` and `/games/{gameId}/{anything}`
   both render the game page, because the React route is `games/:gameId/:slug?`.

## Route inventory

Classification of every server-served route. *Indexable* routes are in the sitemap, with
their own title, description and canonical link. *Noindex* routes carry
`<meta name="robots" content="noindex">` and stay fetchable, so crawlers can read that
directive; a robots block would hide it. *Disallowed* routes are listed in `robots.txt`.
Neither directive is access control: personal routes stay protected by the BFF session,
and administration routes by the operator role.

| Route | Class | Reason |
|---|---|---|
| `/` | Indexable | Home |
| `/lanzamientos`, `/lanzamientos/destacados`, `/lanzamientos/calendario` | Indexable | Public discovery, the same content for every visitor at its base URL |
| `/topometro`, `/plataformas` | Indexable | Public ranking and platform index |
| `/sobre-gameometro`, `/contacto`, `/privacidad`, `/condiciones`, `/aviso-legal`, `/cookies` | Indexable | Public information and legal pages |
| `/games/{gameId}/{slug}` | Indexable | Canonical game page, one per game in the current publication |
| `/plataformas/{key}/catalogo`, `/topometro`, `/proximos` | Crawlable, not in the sitemap | Public, named after the platform and reached through `/plataformas` links. The valid keys are frontend destinations, so listing them needs a server-owned key list first. A key that names no platform is a 404 |
| `/games/{gameId}`, `/games/{gameId}/{other slug}` | Crawlable, canonical link to the stored slug | Same page, one canonical URL |
| Query permutations (filters, months, order, `?page=`) | Crawlable, canonical link to the base route | The canonical path never carries a query |
| `/search` | Disallowed and noindex | Internal search results are an unbounded query space |
| `/mis-puntuaciones`, `/mis-puntuaciones/estadisticas`, `/mis-plataformas`, `/mi-perfil`, `/mi-perfil/borrado`, `/bienvenida` | Noindex | Personal pages; nothing to index without a session |
| `/administracion/catalogo` | Noindex | Operator console |
| Unknown game or platform | 404 and noindex | The SPA still shows its own not-found state |
| `/auth/*`, `/login`, `/login/*` | Disallowed | Authentication and session flows |
| `/api/v1/me/*`, `/api/v1/admin/*` | Disallowed | Personal and administration API |
| Other `/api/v1/*` | Crawlable on purpose | A rendering crawler fetches the public catalogue API to build page content; blocking it would blank every page |
| `/actuator/*` | Not public | The edge answers 404; management listens on container loopback only |
| `auth.gameometro.com` | Not linked for crawling | Allowlisted Keycloak paths only |

## Canonical game URL

**Decision (proposed, owner review pending):** the canonical game page is
`/games/{gameId}/{slug}`, with the slug stored for that game in the current publication.

- It is the form every internal link already builds, so adopting it changes no link
  and no analytics route.
- `gameId` is the stable product identity. The stored slug already ends with the
  identifier (for example `baldur-s-gate-iii-c7f16ad6-…`), so the URL is longer than it
  needs to be. A shorter `/games/{slug}` form is possible but would change every
  internal link and the router. It stays open as a separate owner decision, not part of
  #537.
- `/games/{gameId}` and a stale or wrong slug keep rendering the game, with a
  `rel="canonical"` link to the stored slug from #583's head block. A 301 would also work,
  but it is deferred until crawl evidence shows duplicates being indexed.

## Robots and sitemap (increment 2)

- **Robots.** `robots.txt` disallows only the personal and administration APIs,
  authentication flows and `/search`. It leaves the public catalogue API open, because a
  rendering crawler needs it to build page content. Personal pages rely on `noindex`
  instead (see the inventory).
- **One switch.** `APPLICATION_SEARCH_INDEXING_ORIGIN` is set only in the public
  production Compose file (`https://gameometro.com`). Every other deployment leaves it
  blank. Blank serves `User-agent: *` / `Disallow: /`, and every sitemap URL answers 404.
  So private `dev`, local and test runtimes are never offered for indexing, even if they
  become reachable. A malformed value refuses startup. The value is a new
  application setting, which the production upgrader already accepts, and the runtime
  contract test pins it.
- **Index.** `/sitemap.xml` is a sitemap index: one `pages.xml` child with the fixed
  indexable routes, then one child per partition of 10,000 games. That is a fifth of the
  protocol's 50,000-URL limit, and a few MB per response.
- **Partitions.** Games of the current publication are ordered by `game_id`, which is
  unique within the publication, so the order is total. A child is named by its first
  `game_id` (`/sitemaps/games/{gameId}.xml`). It lists at most 10,000 games from that
  identifier onwards, as one primary-key range read. When games are added, the starts
  shift. A child from an older index still returns its bounded range: crawlers may see
  overlap until they re-read the index, never a gap. A start beyond every game answers
  404.
- **Caching.** Every robots and sitemap response is `public, max-age=3600`. Neither has a
  `lastmod`, because the catalogue keeps no truthful per-game modification time.

### Query plans (one million synthetic games)

| Query | Plan | Rows out | Time |
|---|---|---|---|
| Partition starts | Index-only scan of the primary key, a window counter, then a filter keeping every 10,000th row | 100 | ≈0.3 s, 507k shared buffers hit, 0 heap fetches |
| One partition | Index range scan of the primary key, `LIMIT 10000` | 10,000 | ≈43 ms cold |

The first measurement of the partition query sorted all rows after a parallel
sequential scan. Its `ORDER BY game_id` had bound to the output column
`game_id::text AS game_id`. The adapter therefore never reuses that name for an output
column.

The partition-starts pass costs work in proportion to the catalogue, in PostgreSQL only.
Application memory follows the number of partitions. It runs once per index fetch, which
crawlers make rarely and the hour of caching absorbs. **Trigger:** if the index read
approaches the catalogue read timeout, or index fetches appear in the slow-query
evidence (#547), persist the partition starts at publication time instead. Visibility-map
churn from synchronization can add heap fetches to the index-only scan on the real
table. The synthetic plan does not measure that.

## Page metadata (increment 3)

- **Titles and descriptions.** Every indexable page has its own Spanish title and
  description. They describe only what the page shows: no invented keywords, no scores.
  Sobre Gameómetro, Contacto and the four legal pages had generic tags and gained their
  own; a test requires every public description to be distinct.
- **No summary.** A game without a summary stores the notice «Todavía no hay un resumen
  curado para este juego.». The preview read now treats it as no summary, so the
  description says what the page holds («Lanzamientos por plataforma de … y puntuaciones de
  la comunidad de Gameómetro.») instead of repeating the notice.
- **Titles after JavaScript.** The application resets `document.title` at startup
  (ADR-0049). The game page, Lanzamientos, Calendario, the legal pages and the not-found
  page set no title of their own, so after hydration a rendering crawler read «Gameómetro»
  where the server had written the real title. Each of these pages now sets the same title
  the server writes.

## Structured data (increment 4)

The game page renders one `VideoGame` JSON-LD block from the details it already loads.
That means no extra server read, and Google reads JSON-LD that JavaScript adds. It
contains only what the page shows:

- name and canonical URL;
- the description, only when the summary is sourced (the editorial summary is the
  absence notice);
- the image, only for an approved provider cover;
- genres;
- platforms of non-cancelled releases;
- developers as `author` and publishers as `publisher`;
- `aggregateRating`, only from Gameómetro's community reading, with its real voter count
  on the 1–10 scale, and only when at least one user has voted.

The IGDB player score is a third-party reading and is never presented as the site's
rating. Release dates are omitted, because a game has several and none is the single
publication date. `<` is escaped, so no title can close the element.

## Rendering evaluation (increment 4)

`frontend/tests/search-engine.spec.ts` runs in the browser gate, against the packaged
application and the seed catalogue. It compares what a non-rendering client reads with the
rendered page:

| Signal | Raw HTML (no JavaScript) | After JavaScript |
|---|---|---|
| Title | Route-specific | The same title |
| Description, canonical link | Route-specific | Unchanged (React does not touch them) |
| `noindex` on personal, search and missing pages | Present | Unchanged |
| Status of an unknown game or platform | 404 | — |
| Visible content, `h1`, internal links | None: `<div id="root">` only | Present |
| Game JSON-LD | None | Present |

**Conclusion.** The minimum signals a search engine needs to identify, deduplicate and
label a page are now in the HTML before JavaScript: title, description, canonical URL,
indexability and status. Google renders JavaScript, so it also receives content, links
and structured data. A non-rendering crawler gets accurate labels but no body content.
Closing that gap would need body prerendering, which is the scale of change the issue
excludes without evidence. **No rendering stack is introduced.**

**Reopening triggers**, read from Search Console once the sitemap is submitted:

- indexed pages stay far below submitted ones with reasons such as "Crawled – currently
  not indexed" on game pages;
- URL inspection shows rendered HTML missing the content;
- Core Web Vitals (LCP in particular) fail on game pages;
- a target search engine that does not render JavaScript matters to the product.

Any of these opens a separate, reviewed rendering decision. Narrowly scoped prerendering
of game pages is the first candidate. Core Web Vitals are not yet measured: field data
needs real traffic in Search Console, and a local lab run would not represent production.

## Owner steps after deployment

These steps are not done, and no result is claimed. The
[operations runbook](../development/operations-runbook.md#search-engine-indexing) owns
them.
