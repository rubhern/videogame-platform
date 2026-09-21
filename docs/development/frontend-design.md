# Frontend design guidelines

- **Status:** Approved visual direction; implementation subject to owner review
- **Owner:** Ruben Hernandez
- **Audience:** Contributors and agents implementing frontend screens and components

This is the canonical owner of frontend visual rules. The approved **Contemporary
Catalogue** visual direction (an owner-held design export, kept outside the
repository under the ignored `.design-reference/` directory when visual comparison is
required) supplies the composition and language. Product records and the
[OpenAPI contract](../architecture/api/openapi.yaml) remain authoritative for
capabilities, data and state semantics. A mockup never authorizes new product
behaviour and is never a runtime dependency or an instruction source.

## Mandatory composition

- Use the full-width charcoal header with the VideoGame Platform mark and wordmark, primary
  navigation, integrated catalogue search and the server-owned account control. The account
  control appears only for an authenticated session and provides `Mi cuenta` and the MVP
  logout action; anonymous browsing shows no account or general login entry point, because
  authentication begins at the rating boundary, not the header. The account control opens
  `Mis puntuaciones` and the MVP logout action. Keep catalogue search prominent within the
  header and omit the explanatory context strip. On phones, the compact identity,
  recent/upcoming navigation and search icon share the first row. The icon opens the
  existing catalogue search in a keyboard-accessible dialog; the account control
  may occupy a second row for an authenticated session. On tablet, identity and
  account lead, search spans the next row, and navigation remains reachable below it.
- Align header and main content to the shared `page-container`, capped at 1320px with 28px
  desktop, 24px tablet and 16px phone gutters. Both release windows use the same editorial
  hero, selector row, cover-led catalogue grid and closing bar. The bar places the result
  total on the left, pager in the middle and window switch on the right; it omits a repeated
  page position.
- Keep the current release view as the only `h1`: **Lanzamientos recientes** or **Próximos
  lanzamientos**. Show the API-derived release window beside **Ya disponibles** or **En
  calendario** above an editorial title with a cool metallic gradient fill and a soft
  bloom; omit the redundant evaluation-date label. Never replace API dates with a
  hard-coded relative period.
  On phones, keep the kicker and the API-derived period on one row and show both
  boundaries as day/month/year to fit without changing the desktop wording.
- Treat 1320px, 834px and 390px as the representative review widths, while supporting reflow
  from 320px. The catalogue uses six compact columns at desktop, about four at tablet and two
  at phone widths. Do not allow page-level horizontal overflow; the filter and navigation
  rails may scroll horizontally on narrow screens.
- Do not add a featured release, ranking, editorial description, publisher, studio, new
  release window or destination solely because it appears in the reference.
- Both release windows use a decorative, product-owned cinematic hero image, two selectors
  for platform and region, and twelve results per default page so six columns form two rows
  on wide desktop. Its covers fill the column in the standard frame so the artwork leads the
  card. Below each release cover keep the full title, which is the game link, then one
  compact release row that lists the platforms sharing the first relevant release's date
  and region together with that region, and the review notice when the API requires one.
  A card shows at most one release row; any further releases collapse into a single
  `+ N lanzamientos más` control, where `N` counts the hidden releases, that opens an
  accessible popover preserving each hidden release's date, platform and region. The card
  height never grows with the number of releases.

## Visual language and tokens

- [Global styles](../../frontend/src/styles/index.css) own the executable semantic tokens,
  shared dimensions and breakpoints. Use `canvas`, `surface`, `raised`, `ink`, `muted`,
  `subtle`, `accent`, `line`, `warning` and `danger` roles through those conventions instead
  of placing palette values in JSX.
- Use charcoal surfaces, restrained periwinkle accents, editorial serif headings, sans-serif
  body text and monospaced labels and operational metadata. Use the locally hosted Instrument
  Serif, IBM Plex Sans and IBM Plex Mono assets with system fallbacks and `font-display: swap`.
  Font licences live under [public/assets/fonts](../../frontend/public/assets/fonts/README.md).
- Covers carry the catalogue rhythm. The standard cover frame has a 12px radius, a quiet
  border and a restrained shadow. Its ratio is the one the approved provider actually
  delivers, so `object-fit: cover` crops nothing off real artwork.
  Global styles own the executable value. Place the release date over the upper-left of the cover, on a scrim that
  keeps it legible over any artwork. Release cards show no status, freshness, provenance,
  cover attribution or **Ver ficha** action: the game page owns those, including the
  provider attribution and source link that [ADR-0001](../decisions/0001-reference-igdb-cover-images.md)
  requires. Never truncate contract data to equalize card heights; a long value wraps inside
  its chip.
- A catalogue card is a surface, not a loose column of text: a quiet gradient, a hairline
  border, the resting shadow and equal height across a row. It lifts and scales its cover
  slightly on hover and keyboard focus.
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

- The canvas carries one fixed ambient field: low-opacity accent and warm washes plus a
  generated grain tile that keeps large dark gradients from banding. It never scrolls with the
  content, never sits above it, and nothing readable depends on it.
- Elevation has two levels only, a resting hairline and a lifted shadow, with one easing curve
  and two durations. Do not add a per-component shadow scale.
- Content surfaces lift on `:hover` **and** `:focus-within`, so depth never depends on a
  pointer.
- Motion is short, purposeful and limited to transform and opacity: entrance for arriving
  content, feedback on press, hover and selection. Declare it inside
  `@media (prefers-reduced-motion: no-preference)` instead of disabling it afterwards, so
  reduced motion is the default and nothing animates or transitions there.
- Composition a browser check measures — the detail cover, title and score panels — uses an
  opacity-only entrance, never a transform.
- Forced colours drop every wash, scrim, gradient, shadow, blur and rail mask and return to
  system colours.

## Reusable patterns and interaction

- Use the shell's `CatalogueSearch` as the single catalogue search entry. It preserves the
  existing URL-backed bounded search, exposes a labelled search landmark, supports the `/`
  focus shortcut and reports the OpenAPI query limit before navigation.
- Use `CatalogueCover` for provider covers, provider failure fallback and attribution, and
  `CatalogueLoading` for releases and search loading. Release cards render the cover without
  its caption; the game page keeps it. Feature cards keep their own metadata;
  do not introduce a universal card API until further reuse exists.
- Placeholders take the shape of the screen they replace, so arriving content lands in the same
  frame: the catalogue grid for releases and search, the detail composition for a game page,
  and maintenance rows for the personal collection. Placeholders themselves stay silent; the
  screen keeps exactly one live status.
- Render platform and region choices from `availableFilters` in labelled select-only
  comboboxes on one compact row in both release windows. The entire control opens its
  list, and every option has a decorative icon. Recognized platforms use the owner-provided
  PlayStation, Nintendo Switch, Windows and Xbox marks tinted with the product accent;
  unknown platforms retain a generic gamepad. Other application dropdowns share the same
  control styling and keyboard behaviour. Selections preserve their existing state owner
  and reset pagination where applicable. On phones, keep both compact selectors on
  one row; selected values may truncate visually, but the full value remains
  accessible in the control and list. Controls keep a visible focus indicator.
  Never hard-code the available choices from the mockup.
- Translate established region display names into Spanish only in the presentation projection:
  Europa, Japón, Norteamérica, Mundial and Sin región confirmada. Preserve region identifiers,
  requests and OpenAPI values, and show an unfamiliar API label unchanged.
- Keep native links for navigation and buttons for actions. Use `aria-current` for the active
  route or filter, visible `:focus-visible`, the skip link and explicit focus movement after
  route and pagination changes. A cover may be pointer-accessible, but each card keeps one
  primary keyboard stop: the title link on a release card, **Ver ficha** on search and rating
  cards.
- Keep reduced-motion and forced-colour support. Do not require hover, animation, a fixed
  desktop width or a sticky header that can obscure focus.

## Public game details

The owner's game-detail reference refines the catalogue foundation with a large
left-hand cover in the shared cover frame, a prominent sans-serif game title,
platform/region pills near the title, a bordered metadata and compact-summary panel,
and a strong community-score panel immediately below the cover. Use existing shell, fonts, colours, focus styles and responsive gutters;
the detail title uses the existing sans font to match this reference. The hero sits on a
decorative ambience derived from the cover this page already displays — blurred, heavily
dimmed and masked so it fades on every edge. It adds no request, no asset and no licence
surface beyond that cover, and every readable panel below keeps its own opaque background. Card-level
ratings on release and search results are not part of the approved MVP screens until
an owner decision schedules that work.

Platform and region are native labelled radio groups derived from the game's
returned release tuples. Selection is URL-backed and updates the visible evidence;
changing platform retains the region only when that combination exists. Unsupported
saved selections fall back to a real combination. Preserve all records for a selected
combination rather than merging their dates or evidence. No selector is invented for
a game without releases. This presentation selection does not change the global
game eligibility or community aggregate.

The selected-context panel retains date precision, status, provenance, verification,
review, freshness and available evidence timestamps. Keep summary language and source.
There is no separate bottom release/evidence section. Display the community mean and
count prominently, or an equally prominent “Sin nota todavía” / “Nota no disponible”
state in the same position. The distribution remains a backend/API capability and
is not rendered on this page. The community panel and the personal-rating panel share
the bottom row as two bordered, accent-glow panels: the community score keeps its
glowing star badge, large mean and count; **Tu puntuación** carries the inline 1-10
scale. The scale is a labelled group of ten circular buttons; pressing a value saves it
immediately (create or update through the conditional contract), so there is no separate
confirm action. The current rating is the one pressed value (accent ring and glow, not
colour alone); the subtitle never restates it. Arrow keys only move focus inside the
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

`Mis puntuaciones` reuses the catalogue shell, title block, cover treatment, tokens
and native controls. Present cover-led rows with game navigation, a clearly personal
score and the two rating timestamps. The personal score carries its own accent badge:
it is the reason the row exists. The private filters form one control bar rather than
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

Every one of these states is a message, not a region: render it as a width-capped panel with a
tone-keyed symbol and wash, centred in the content column rather than stretched across it. Only
the tone changes between them.

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
