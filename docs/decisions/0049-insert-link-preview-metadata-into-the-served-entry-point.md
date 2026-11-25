# ADR-0049: Insert link-preview metadata into the served entry point

- **Status:** Proposed
- **Date:** 2026-11-19
- **Owner:** Ruben Hernandez
- **Scope:** Product origin of every environment
- **Issue:** [#581](https://github.com/rubhern/videogame-platform/issues/581)
- **Related:** [ADR-0001](0001-reference-igdb-cover-images.md),
  [ADR-0003](0003-use-a-same-origin-bff-and-http-json-api.md),
  [#537](https://github.com/rubhern/videogame-platform/issues/537)

## Context

Shared links are the main way new players currently arrive. WhatsApp, Telegram, Discord, X,
Bluesky and similar clients build a preview from the HTML the server returns; none of them runs
JavaScript. The React application is a same-origin SPA. Every browser route was a forward to one
static `index.html`, so every link previewed as the generic «Gameómetro» text with no image, and
metadata React sets in the browser never reached a preview.

Next.js, server rendering and React Server Components remain deferred (`AGENTS.md`). #537 owns
search-engine SEO and excludes new rendering infrastructure by default. It needs per-route titles,
descriptions and canonical URLs too.

## Decision

**A — Backend metadata insertion.** The Spring route that already serves the SPA replaces a
marked block in the `<head>` of the packaged `index.html` with the route's tags. The tags are
HTML-escaped and read from local catalogue data. Every client receives the same document, so
there is no user-agent detection.

- **One owner of page metadata.** `PageMetadataResolver` (`api.delivery.frontend`) decides which
  routes describe themselves, with what text and canonical path. `FrontendDocument` renders the
  tags. #537 reuses both for titles, descriptions and canonical links instead of building a second
  mechanism.
- **Route set.**
  - A game page gets its title, the summary the page shows (its local Spanish translation when
    one exists, otherwise a fixed sentence), its approved cover and its canonical URL. The stored
    «no summary yet» notice counts as no summary (#537).
  - Home, TOPómetro, Lanzamientos, Destacados, Calendario and Plataformas get their own title and
    description. Since #537, so do Sobre Gameómetro, Contacto and the four legal pages.
  - A platform's Explorar catálogo, TOPómetro and Próximos destacados are named after the
    platform. A featured family's URL key maps to its seeded product ID, and any other key is the
    platform's own ID.
  - These pages use the default share image.
  - Every other route, every private route included, gets only the generic site tags. No user
    data, session state or query string ever reaches the head.
  - Personal, administration and search routes, and every not-found document, add
    `<meta name="robots" content="noindex">` (#537).
- **Tags.** `<title>`, `description`, `og:site_name`, `og:locale` `es_ES`, `og:type` `website`,
  `og:title`, `og:description` and `og:image` with `og:image:alt`. The default image also states
  its 1200×630 size. `twitter:card` is `summary_large_image`. A described page adds `og:url` and
  `<link rel="canonical">`.
- **Canonical game URL.** `/games/{gameId}/{slug}`, with the game's current slug: every internal
  link already uses this form. `/games/{gameId}` and an outdated slug serve the same page and
  name that URL as canonical. #537 may revise this from its audit.
- **Descriptions** are shortened by one rule: whitespace runs collapse, and text longer than 160
  characters is cut at the last word boundary with an ellipsis, never inside a code point.
- **Status.** A game identifier the current catalogue does not hold returns 404 with generic tags.
  The SPA still loads and shows its own not-found state, which is the true-404 behaviour #537
  asks for. A failed catalogue read serves generic tags with 200, because a preview is an
  enhancement of the page, not its content. Since #537, a platform key that names no platform is
  also a 404, so it is never advertised as content; a failed platform read keeps the page's
  unnamed title with 200.
- **Absolute URLs** are built from the request's public origin, which the edge forwards under
  the existing `SERVER_FORWARD_HEADERS_STRATEGY: NATIVE`. That is the same source as the OIDC
  `{baseUrl}`, so no new runtime input exists.
- **Images.** A game preview uses the cover the page already shows, at the same IGDB size, in
  JPEG instead of WebP, because several preview clients do not decode WebP. This amends the
  ADR-0001 allowlist by one extension for this rendition only. The image is still fetched by the
  client straight from the IGDB CDN. The preview links to the game page, which shows the
  attribution, as the 2026-09-20 amendment of ADR-0001 requires for every displayed cover.
  A product-owned cover is an SVG fallback that preview clients do not render, so it and a missing
  cover use the default share image. That image is product-owned, rendered from the canonical
  logo by `scripts/render-share-image.mjs` and described in the
  [frontend design guidelines](../development/frontend-design.md#link-previews).
- **Browser title.** The application resets the title to «Gameómetro» at startup, so the server
  title of the landing route never outlives it during in-app navigation; screens keep owning
  their titles. Every described page therefore sets the same title the server wrote (#537):
  otherwise a crawler that runs JavaScript would read «Gameómetro» instead.

## Cost and caching

- **Per request.** The template is read once at startup. A game page costs one SQL statement
  whose joins are all on unique keys (`uq_current_game`, the summary-translation and
  content-translation primary keys and the current-publication flag). A platform page costs one
  primary-key read; every other route costs no read. On the local catalogue of 22,812 games the
  game statement executed in 1.5 ms with 10 shared buffers.
- **No provider calls.** Nothing on the request path calls a provider. The entry point adds no
  metric, so no game or platform identifier becomes a metric tag.
- **Caching.** The document is `Cache-Control: public, no-cache` with a strong `ETag` over the
  rendered bytes, and answers `If-None-Match` with 304. Every use revalidates, so a release or a
  catalogue change is seen at once and the hashed assets it names always exist. The content is
  identical for every user, so `public` is safe. Nothing is cached in the process, so any number
  of instances serve the same bytes.
- **Edge, CSP and upgrader.**
  - The edge proxies these routes as before, under the existing per-source and aggregate limits,
    so no Caddyfile change is needed.
  - The tags load nothing in the page, so the document Content Security Policy is unchanged.
  - No runtime input, secret or protected setting is added, so the production upgrader is
    unchanged.

## Alternatives considered

- **B — Static prerendered stubs** generated at build or synchronization time. Rejected. They
  are one file per game, which grows with the catalogue and goes stale between syncs. They also
  need a new storage and serving path, and an outdated slug or an unknown game still needs the
  application.
- **C — Bot-only responses by user agent at the edge.** Rejected. It is cloaking, its detection
  is fragile, and it needs two code paths and a second renderer outside the application.
- **Server rendering (Next.js, React Server Components).** Deferred by `AGENTS.md`. It is far
  more than previews need.

## Consequences

- A shared Gameómetro link previews its real title, description and, for a game, its cover,
  without JavaScript.
- The entry point is now a rendered response rather than a static file. A template without
  exactly one marked block fails application startup instead of serving stale tags.
- Preview clients cache previews on their own terms. A changed cover or title reaches an already
  shared link only when the client fetches it again.
- A visitor whose identifier names no game now gets a 404 document instead of a 200 one.
- The default share image must be re-rendered when the canonical logo changes.

## Reconsider when

- A search engine is shown to need structured data, or page content, in the HTML before
  JavaScript. #537 keeps its JSON-LD in the rendered game page, which needs no extra read here.
- A preview client is shown to require something this document cannot carry.
- The ADR-0001 image policy changes.

## Public player profile extension (#593)

[ADR-0053](0053-publish-revocable-player-profiles-by-alias.md) extends this insertion
mechanism to explicitly published aliases. Its privacy policy owns metadata,
indexing, freshness and status for those routes.
