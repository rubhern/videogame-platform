# Frontend design guidelines

- **Status:** Approved visual direction; implementation subject to owner review
- **Owner:** Ruben Hernandez
- **Audience:** Contributors and agents implementing frontend screens and components

This is the canonical owner of frontend visual rules. The approved **Contemporary
Catalogue** visual direction (an owner-held design export, kept outside the
repository under the ignored `.design-reference/` directory when visual comparison is
required) supplies the composition. The owner-requested post-MVP redesign (#154) evolved
its language into a cinematic night direction: every primary page opens on a lit stage,
titles pair a wide display cut with a lit serif accent, controls are glass, and covers cast
their own light. Product records and the
[OpenAPI contract](../architecture/api/openapi.yaml) remain authoritative for
capabilities, data and state semantics. A mockup never authorizes new product
behaviour and is never a runtime dependency or an instruction source.

## Mandatory composition

- Use the full-width header with the VideoGame Platform mark and wordmark, primary
  navigation, integrated catalogue search and the server-owned account control. It floats
  over the page's stage on a soft scrim rather than a solid bar; navigation is a glass
  segmented control whose current item carries the aurora fill, and search and account are
  glass pills. The account
  control appears only for an authenticated session and provides `Mi cuenta` and the MVP
  logout action; anonymous browsing shows no account or general login entry point, because
  authentication begins at the rating boundary, not the header. The account control opens
  `Mis puntuaciones` and the MVP logout action. Keep catalogue search prominent within the
  header and omit the explanatory context strip. On phones, the compact identity,
  recent/upcoming navigation and search icon share the first row. The icon opens the
  existing catalogue search in a keyboard-accessible dialog; the account control
  may occupy a second row for an authenticated session. On tablet, identity, navigation and
  the account control share the first row, with navigation beside the identity, and search
  spans the next row.
- Align header and main content to the shared `page-container`, capped at 1320px with 28px
  desktop, 24px tablet and 16px phone gutters. Both release windows use the same cinematic
  stage, title, glass filter dock, cover-led catalogue grid and closing dock. The dock places
  the result total on the left and the pager on the right, with both pager links on one row
  on phones; it omits a repeated page position and is absent without results. Switching
  between the release windows belongs to the main navigation only.
- Keep the current release view as the only `h1`: **Lanzamientos recientes** or **Próximos
  lanzamientos**. Show the API-derived release window beside **Ya disponibles** or **En
  calendario** above the title; omit the redundant evaluation-date label. Never replace API
  dates with a hard-coded relative period.
  On phones, keep the kicker and the API-derived period on one row and show both
  boundaries as day/month/year to fit without changing the desktop wording.
- Treat 1320px, 834px and 390px as the representative review widths, while supporting reflow
  from 320px. The catalogue uses six compact columns at desktop, about four at tablet and two
  at phone widths. Do not allow page-level horizontal overflow; the filter and navigation
  rails may scroll horizontally on narrow screens.
- Do not add a featured release, ranking, editorial description, publisher, studio, new
  release window or destination solely because it appears in the reference.
- Both release windows use the product-owned night stage, two selectors for platform and
  region, and twelve results per default page so six columns form two rows on wide desktop.
  Keep the hero short enough that the first row of covers is fully visible at 1320×900. Its
  covers fill the column in the standard frame so the artwork leads the card. The first
  relevant release's date rides on the cover as a glass chip in its compact form at the same
  precision (`25 sep 2026`, `sep 2026`, `T3 2026`, `2026`, `Por confirmar`); the full wording
  stays in the release row for assistive technology. Below each release cover keep the full
  title, which is the game link, then one compact release row that lists the platforms sharing
  that date and region together with that region, and the review notice when the API requires
  one.
  A card shows at most one release row; any further releases collapse into a single
  `+ N lanzamientos más` control, where `N` counts the hidden releases, that opens an
  accessible popover preserving each hidden release's date, platform and region. The card
  height never grows with the number of releases.

## Visual language and tokens

- [Global styles](../../frontend/src/styles/index.css) own the executable semantic tokens,
  shared dimensions and breakpoints, and the [foundation](../../frontend/src/styles/foundation.css)
  owns the shared glass, glow, aurora and grain values. Use `canvas`, `surface`, `raised`,
  `ink`, `muted`, `subtle`, `accent`, `aurora`, `line`, `warning` and `danger` roles through
  those conventions instead of placing palette values in JSX.
- Use a near-black ink-blue canvas, glass surfaces and one signature light: moonlit
  periwinkle turning to aurora violet, used for the current navigation item, primary actions,
  the pressed rating, score arcs and title accents. Warm light belongs to the art alone;
  `warning` stays reserved for review and freshness states.
- Typography: Mona Sans is the interface and display family — its wide, heavy cut sets game
  titles at poster scale and page titles at a compact size on one line, and its regular width sets everything else. A title's
  accent word is set in Instrument Serif italic and lit by the aurora gradient (**Lanzamientos
  _recientes_**, **Resultados para _«consulta»_**, **Mis _puntuaciones_**); the heading still
  reads as one phrase to assistive technology. IBM Plex Mono sets kickers, labels and
  operational metadata. The brand wordmark keeps its own Instrument Serif; product identity
  belongs to the branding work. Use the locally hosted assets with system fallbacks and
  `font-display: swap`. Font licences live under
  [public/assets/fonts](../../frontend/public/assets/fonts/README.md).
- Covers carry the catalogue rhythm. The standard cover frame has a 14px radius, a quiet
  edge and a restrained shadow. Its ratio is the one the approved provider actually
  delivers, so `object-fit: cover` crops nothing off real artwork.
  Global styles own the executable value. Release and search result cards show no status,
  freshness, provenance, cover attribution or **Ver ficha** action: the game page owns those, including the
  provider attribution and source link that [ADR-0001](../decisions/0001-reference-igdb-cover-images.md)
  requires. Never truncate contract data to equalize card heights; a long value wraps inside
  its chip.
- A catalogue card is lit by its own cover: a blurred copy of the same artwork glows beneath
  it, and on hover or keyboard focus the card lifts, a glass surface gathers it together, the
  glow spills past its edges as a coloured halo and a band of light sweeps the cover. Cards
  keep equal height across a row. The title link's target covers the whole card, so the card
  is one pointer target while the title stays its only keyboard stop, and its focus ring
  outlines the card.
- Provider covers arrive at one fixed CDN size, so every frame wider than that size upscales
  them. Treat that as a permanent condition of [ADR-0001](../decisions/0001-reference-igdb-cover-images.md),
  not a defect: covers carry a faint grain, a vignette and a small micro-contrast lift so the
  interpolation reads as texture rather than blur, and the large game-detail frame — the only
  one no provider cover size can fill — adds a light unsharp mask. These are presentation
  effects over the delivered image. Never resample, cache or re-encode provider artwork, and
  never apply them in forced colours.
- Use rounded pills for navigation, filters, status and actions. Primary controls retain at
  least a 44px target; compact card links may use the smaller established action treatment.
  Hover, keyboard focus and selected state must remain distinct without relying on colour alone.
- Supporting metadata must remain comfortably readable. Card metadata, cover attribution,
  status badges, result summaries and filter labels use at least 11 CSS pixels with WCAG AA
  contrast on their actual surface. Prefer `muted` when text sits on `raised`; reserve `subtle`
  for quiet text on `canvas` or `surface` where its contrast remains at least 4.5:1.
- Identifiers a person may have to repeat — currently the support correlation reference — stay
  monospaced and keep their exact casing. Never case-transform them for style.

## Depth and motion

Depth and motion belong to the visual language, not to individual screens. Global styles own
the executable values; these constraints hold wherever they are used:

- The canvas carries one fixed ambient field: slowly drifting low-opacity aurora washes plus a
  generated grain tile that keeps large dark gradients from banding. It never scrolls with the
  content, never sits above it, and nothing readable depends on it.
- Every primary page opens on a cinematic stage anchored to the top of the document, so it
  runs behind the header: the product-owned night art for the release windows, search (a
  castle-and-moon crop), `Mis puntuaciones` (a warmer valley crop), the not-found route and a
  game page that failed to load; a game's own cover on its page. The stage is graded with
  scrims for the copy, drifting mist, rising motes of light and a grain pass, pushes in
  slowly and sinks slower than the page scrolls where the browser can drive that from scroll
  position alone. It fades into the canvas, is hidden from assistive technology and takes no
  input. The night art ships as product-owned WebP in desktop and phone sizes.
- Elevation has a resting hairline, a lifted shadow and one luminous accent glow for selected,
  focused and key values, with one easing curve, one overshoot for presses and three
  durations. Do not add a per-component shadow scale.
- Glass — a translucent surface with backdrop blur, a hairline edge and an inner highlight —
  is the one surface for panels, docks, notices and controls over the stage. Text on glass
  keeps its contrast against the darkest art it can cover. A glass container that holds open
  lists sits on its own stacking layer, so its lists paint above later content.
- Content surfaces lift on `:hover` **and** `:focus-within`, so depth never depends on a
  pointer.
- Motion is purposeful and limited to transform and opacity: the stage's push-in, mist,
  motes and parallax; entrance for arriving content; lift, light sweeps and the mouse-only
  cover tilt on hover; feedback on press and selection. Declare it inside
  `@media (prefers-reduced-motion: no-preference)` instead of disabling it afterwards, so
  reduced motion is the default and nothing animates or transitions there.
- Composition a browser check measures — the page openings' title block and filter dock, and
  the detail cover, title and score panels — uses an opacity-only entrance, never a transform.
- Forced colours drop every wash, scrim, gradient, shadow, blur and rail mask and return to
  system colours.

## Reusable patterns and interaction

- Use the shell's `CatalogueSearch` as the single catalogue search entry. It preserves the
  existing URL-backed bounded search, exposes a labelled search landmark, supports the `/`
  focus shortcut and reports the OpenAPI query limit before navigation.
- The search's typeahead popup (#156) follows the owner's approved search-popup reference:
  a glass panel anchored under the search at its width, a `Resultados (N)` header with the
  `Enter` hint, compact rows (landscape cover crop, title, then platform icons, a divider,
  any alias and the year), the accent-bordered active row with its `↵` badge, and a
  separated **Ver todos los resultados** footer. It is a combobox whose focus stays in the
  input. From tablet width the rows use the larger page scale and the desktop search column
  grows to 680px, so the search and its popup share that width; platform icons are separated
  by a dot, with none after the last. Loading keeps the previous row count as placeholders; empty and failure states are
  compact messages that never block the full search. On phones it spans the search
  dialog's row, wraps metadata instead of clipping it and drops the keyboard hints. Wide
  platform wordmarks keep their ratio at the row height. Genres, companies and scores in
  the reference are not part of the contract and stay omitted.
- The full search results page (#188) follows the owner's approved results reference and
  shares the release windows' stage, title treatment and six-column grid. An
  active query is the only `h1`, **Resultados para «consulta»**, under the **Catálogo de
  juegos** kicker, with no explanatory paragraph; before a query the page keeps **Buscar
  juegos**. The game total and page position sit above the grid (for example,
  `153 juegos del catálogo local · Página 1 de 26`), and the pager below repeats the
  position before its previous/next actions. Each card shows the cover with one known year,
  an inclusive range or `Por confirmar` as its glass chip, the title link, a
  `Coincidencia: alias` chip only when the alias differs from the title, and up to three
  platform icons with the exact `+N` of further platforms at the card foot, all from the
  compact release summary.
  It never renders release rows or status chips, so its height never follows the release
  count. Platform names stay available to assistive technology and as tooltips.
- Release windows, search results and `Mis puntuaciones` share one page opening: the
  cinematic stage, the kicker and the display title with its lit accent, directly under
  the header with tight spacing, so the first row of covers shows without scrolling. Titles longer than about 26 characters, usually a visitor's query, step down a size. Every
  paginated list uses the same previous/next actions, each with its direction arrow, and a
  result total sits left-aligned with the content it counts.
- Use `CatalogueCover` for provider covers, provider failure fallback and attribution, and
  `CatalogueLoading` for releases and search loading, with one placeholder per result the
  page will show. Release and search result cards render the cover without its caption; the
  game page keeps it. Feature cards keep their own metadata;
  do not introduce a universal card API until further reuse exists.
- Placeholders take the shape of the screen they replace, so arriving content lands in the same
  frame: the catalogue grid for releases and search, the detail composition for a game page,
  and maintenance rows for the personal collection. Placeholders themselves stay silent; the
  screen keeps exactly one live status.
- Render platform and region choices from `availableFilters` in labelled select-only
  comboboxes in one glass dock with the period in both release windows; on phones the dock
  becomes a two-row panel. The entire control opens its
  list, and every option has a decorative icon. Recognized platforms use the owner-provided
  PlayStation, Nintendo Switch, Windows and Xbox marks tinted with the product accent;
  unknown platforms retain a generic gamepad. Other application dropdowns share the same
  control styling and keyboard behaviour. Selections preserve their existing state owner
  and reset pagination where applicable. On phones, keep both compact selectors on
  one row; selected values may truncate visually, but the full value remains
  accessible in the control and list. Controls keep a visible focus indicator.
  Never hard-code the available choices from the mockup.
- Release results show the game total followed by the current page position (for example,
  `99 juegos · Página 1 de 9`). Use spacing between the heading and filters, and between
  results and footer controls, without separator rules.
- Translate established region display names into Spanish only in the presentation projection:
  Europa, Japón, Norteamérica, Mundial and Sin región confirmada. Preserve region identifiers,
  requests and OpenAPI values, and show an unfamiliar API label unchanged.
- Keep native links for navigation and buttons for actions. Use `aria-current` for the active
  route or filter, visible `:focus-visible`, the skip link and explicit focus movement after
  route and pagination changes. A cover may be pointer-accessible, but each card keeps one
  primary keyboard stop: the title link on a release or search result card, **Ver ficha** on rating
  cards.
- Keep reduced-motion and forced-colour support. Do not require hover, animation, a fixed
  desktop width or a sticky header that can obscure focus.

## Public game details

The game page is a premium game hub built only from the contract's data. It opens on a
stage lit by the cover this page already displays — blown up, blurred and graded, never
resampled or stored — so it adds no request, no asset and no licence surface beyond that
cover; every readable panel keeps its own glass background. From tablet width the large
cover, in the shared frame with its own glow beneath it, stands beside the title and both
score panels, which form one block on the cover's baseline; the release context and the
summary span the page below. On phones the order is cover, title, both scores, then the
release context. The scores belong to the game, so they never sit after the evidence. The
title is the display cut at poster scale, stepping down for long titles, and aliases follow in
the lit serif. For a mouse, the cover leans toward the pointer and catches a glare; touch,
pen, reduced motion and forced colours keep it still. Card-level ratings on release and
search results are not part of the approved MVP screens until an owner decision schedules
that work.

Platform and region are native labelled radio groups derived from the game's
returned release tuples. Selection is URL-backed and updates the visible evidence;
changing platform retains the region only when that combination exists. Unsupported
saved selections fall back to a real combination. Preserve all records for a selected
combination rather than merging their dates or evidence. No selector is invented for
a game without releases. This presentation selection does not change the global
game eligibility or community aggregate.

The release-context panel holds the platform/region selectors — on phones each group is
one swipeable rail of chips with platform and region icons — and then one card per record
for the selection. It retains date precision, status, provenance, verification, review,
freshness and available evidence timestamps. Each record leads with its own date and a
status chip that states the status in words; the remaining evidence follows as a quieter
grid of monospaced labels, and a warning value keeps a non-colour marker. The summary sits in
its own panel beside the context from desktop width. Keep summary language and source.
There is no separate bottom release/evidence section. Display the community mean and
count prominently, or an equally prominent “Sin nota todavía” / “Nota no disponible”
state in the same position. The distribution remains a backend/API capability and
is not rendered on this page. The community panel and the personal-rating panel are two
glass, accent-lit panels: the community score is a lit dial whose arc fills to the mean out
of ten, with the large mean inside it and the count beside it (a dashed, empty dial for the
no-score states); **Tu puntuación** carries the inline 1-10 scale as a keypad of two rows
of five, or one row of ten once its panel keeps every target at least 44px. The scale is a
labelled group of ten circular buttons; pressing a value saves it
immediately (create or update through the conditional contract), so there is no separate
confirm action. The current rating is the one pressed value (aurora fill, ring and glow,
not colour alone), and the values below it stay faintly lit so the scale reads as a gauge;
the subtitle never restates it. Arrow keys only move focus inside the
scale, so browsing never saves by accident, and a quiet **Eliminar puntuación** action
appears once a rating exists. Eligibility is expressed by the control itself: an
ineligible game keeps the scale disabled and states the reason in the panel subtitle.
There is no separate eligibility block, no "Contexto personal" kicker and no permanent
authentication explanation. The rating belongs to the game: the platform/region
selection never changes personal or community state. Command outcomes use one live
status for the panel subtitle and a visually hidden success announcement, and one alert for rejected,
conflicting or ambiguous commands; the last valid personal and community state stays
visible and nothing is retried automatically. An anonymous press starts authentication
with that value; after returning, the recovered value is persisted once automatically
(no command when it equals the existing rating) and any failure surfaces like any other
command.

Genre, developer and publisher are absent from the current approved detail contract
and local model, so omit them. Adding them requires a separate scoped contract-first
change with local persistence and provenance. Do not invent companies, genre, marketing
taglines or background artwork from the reference. List/follow actions remain deferred.

## Personal ratings collection

`Mis puntuaciones` reuses the catalogue shell, page opening, cover treatment, tokens and
native controls. Present cover-led glass rows, each washed by its own cover's light, with
game navigation, a clearly personal score and the two rating timestamps; a row reads game,
score, then its actions, and the edit form and outcomes open beneath them. The personal score
is an aurora tile in the language of the game page's pressed value: it is the reason the row
exists. The private filters form one glass control bar rather than
four loose fields, and stay separate from the header's public catalogue search. Direct
editing expands a labelled native 1–10
selection with Save/Cancel actions; deletion remains a distinct action. Announce
outcomes and move focus to the results after maintenance or pagination. A concurrent
or ambiguous command requires a successful read before another command is enabled.
Empty ratings, no search matches, an exhausted page and load failure remain distinct.

## Server-backed states

| State | Required presentation and behaviour |
|---|---|
| Loading / new selection | One live status plus non-interactive placeholders shaped like the screen they replace; never present previous results as the new selection |
| Refreshing | Keep valid current results usable and announce the refresh |
| Empty | Neutral editorial notice with applicable filter reset or page recovery; never call it a failure |
| Stale | Keep results usable without a page-wide warning or a per-card freshness label; the game page states freshness explicitly, and a release that requires review keeps its card notice |
| Catalogue not ready | Informational notice, local-catalogue explanation and retry; distinguish it from technical failure |
| Unsupported filter / invalid input | Warning notice, explanation and applicable correction; preserve labels and invalid semantics |
| Technical error | Restrained danger notice, safe message, optional support reference and retry |

Every one of these states is a message, not a region: render it as a width-capped glass panel
with a lit, tone-keyed orb and wash, centred in the content column rather than stretched across
it. Only the tone changes between them. The notice owns its recovery and its way out: when it replaces a
whole route, as on the game page, its heading is the page heading and the retry and return
actions sit inside it. Filter placeholders appear only while loading; a failed read omits the
filters its response would have supplied rather than showing empty controls.

Use the existing live-region semantics. Do not add announcements to individual skeletons or
replace a contract state with decorative success content.

## Verification

Apply the [risk-based validation policy](delivery-lifecycle.md). Component tests protect URL,
state and interaction semantics. Focused browser checks own responsive reflow, CSS, keyboard
focus, forced colours and automated accessibility evidence. For material visual changes:

1. render representative current data at 1320px, 834px and 390px;
2. include long titles, varied date precision, stale/review status, provider artwork and the
   product fallback;
3. compare the captures directly with the approved export;
4. check all affected loading, empty, catalogue-not-ready and failure states; and
5. run only the focused component, browser and documentation gates justified by the change.

The reference's unsupported controls and sample content remain outside the MVP. Do not add a
component library, Storybook, theme engine or speculative abstraction for this visual direction.
