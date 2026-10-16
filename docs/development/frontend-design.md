# Frontend design guidelines

- **Status:** Approved visual direction; implementation subject to owner review
- **Owner:** Ruben Hernandez
- **Audience:** Contributors and agents implementing frontend screens and components

This is the canonical owner of frontend visual rules. The approved **Gameómetro identity**
([below](#brand-identity)) is the product's visual direction: a night stage, the brand
mark's ice and fire, the temporal G and the game's own art. It grew out of the **Contemporary
Catalogue** reference (an owner-held design export, kept outside the repository under the
ignored `.design-reference/` directory when visual comparison is required) and the cinematic
night redesign (#154), whose glass, aurora and lit serif accent still dress the screens not yet
migrated. Product records and the [OpenAPI contract](../architecture/api/openapi.yaml) remain
authoritative for capabilities, data and state semantics. A mockup never authorizes new product
behaviour and is never a runtime dependency or an instruction source.

## Brand identity

- **Status:** Approved by the owner on 2026-10-16. Implemented on Destacados, the shared header
  and the footer; the other screens keep the earlier language described further down until each
  is migrated.

Gameómetro looks like a precision instrument set in a cinema: dark, quiet, exact, and lit by the
games themselves. The [brand mark](../../frontend/src/shared/brand/gameometro-mark.svg) is its
source: an ice G whose fire needle leaves through the G's open mouth.

**Brand families.** Three families carry the identity; neutrals, functional states and the thermal
roles stay alongside them. [Global styles](../../frontend/src/styles/index.css) own the values.

| Family | Role | Use it for | Never for |
|---|---|---|---|
| Night (`night`) | Depth and stage | Canvas, reading shades, transitions between art and interface, the instrument's channel | Flattening art under one uniform dark layer |
| Ice (`ice`, `ice-light`) | Measure, progress, reading | Elapsed time, scales and their numerals, the selected state and focus where it measures, primary entry actions | Decorative fills, every link, a score's temperature |
| Fire (`fire`, `fire-light`) | A reading or an event | Needles, a release's date marker, the current section's short rule | Buttons, backgrounds, a "hot" score, warnings |

`canvas` is night and `accent` is ice; `warning`, `danger` and the five thermal roles keep
their meanings and never borrow a brand family. The mark keeps its own gradients.

**Vocabulary.** These elements are available, never required; use one only where it carries
information.

- *The G* is the mark's geometry: a ring opening to the upper right, a terminal just right of
  the top and a crossbar from the right side toward the centre. Every application keeps that
  kinship.
- *The needle* points at a concrete value or event: a score on the G-meter, a release's day on
  the temporal G or a scale. It is never decoration and never an award.
- *The scale* represents time: the hero's temporal G or a compact month scale. Use it where a
  calendar position means something, never as an ornament on screens without one.
- *The G-meter and temperatures* read scores only, under
  [product identity](#product-identity-and-thermal-language). Time scales never take a
  temperature, and fire on a scale is never a hot score.
- *The stage* is the art's own light, graded so the interface reads over it.

**The temporal G** is the represented month drawn in the owner-selected reference silhouette.
Its outer and inner cubic Bézier rims are traced in one fixed SVG viewport; their midpoint
curve supplies arc length, the temporal fill, ticks and markers. The compact, near-circular
bowl, naturally curved bottom, open right side and integrated arm remain identical for every
month, title, score set and viewport. Scaling always preserves both axes equally, including
the phone emblem. [`month-dial-geometry.ts`](../../frontend/src/features/releases/month-dial-geometry.ts)
owns the trace and arc-length sampling; there is no content-sized ellipse or superellipse.
The copy occupies a separate reading zone, with a bounded title size. The canonical inset rectangle
is fixed relative to the SVG viewport, clears the inner bowl and arm, and vertically centres the
copy regardless of subtitle or scores. Titles stay inside its width; score sources use two compact
columns. Content that exceeds the zone scales down proportionally to fit in full, without
scrollbars, clipping or moving below the G. Phones retain the existing compact emblem with full-width copy beneath the art.
The `Lanzamiento del mes` context sits outside the instrument's reading zone. The description and
automatic selection message remain absent. The rail is a thinner blue band around the reference
centreline, with feathered, barely visible rims, soft dark separation from the art and a fading arm. Day 1, every fifth day and
the last day carry longer divisions; all divisions are centred within the band and inset from both
rims. Quiet, unoutlined numerals sit outside it. The elapsed blue stays clearly visible on dark and
bright art, including a complete past month. Days advance clockwise from just after the arm's
corner to just before the top terminal, equally spaced by arc length for 28, 29, 30 and 31 days.
One ice light fills the days gone by the API's `evaluatedOn` (`Europe/Madrid`), with a close, faint
glow: all of a past month, up to today in the current month, none of a future one. A fine `hoy`
notch marks today in the current month only. A tapered fire needle with a circular pivot and warm
core, following the brand mark, marks the presented release's day in every phase; compact scales
use the same blade-and-pivot language. A release known only to its month, quarter or year spans the
scale, dashed when its interval reaches beyond the month; one outside the month gets a chevron at
that end; an unknown date gets a dashed ring named `Por confirmar`. A day is never invented. The G
is decorative: a visually hidden sentence states the same progress and day. While the page stays
open across midnight in Madrid the selection is read again, so the scale follows the API's new
date; the browser's clock only times that read.

**Compact month scales** unroll the same month in a line under a card: the crossbar as the first
upright tick, the elapsed days in the same ice, today's notch, the release's fire needle and its
compact date. They stay thinner and quieter than the titles and readings beside them.

**Stage and art.** The game's art leads, at its own colours. Its blurred light tints the stage at
a capped brightness, the art reaches full strength only clear of the copy, and a directional
shade on the copy's side plus the G's dark face carry the text, so dark, bright, saturated and
busy art all keep their character. A landscape image fills its frame weighted to its upper part;
a panoramic banner, about 2.2:1 or wider by its own loaded ratio, keeps most of its width
instead of a central crop; a `contain` image is shown whole over its own blurred light. Provider
images are never stored, resampled or replaced.

**Composition.** One hero, one G, one clear reading order, and the next section visible below.
Light comes from the art and the instrument; both the enclosing G and the phone emblem animate
the elapsed-time fill on entry, respecting reduced motion. Motion is one orchestrated entrance and the scroll
parallax, opacity-only where a browser check measures, and nothing moves under reduced motion.
Avoid generic patterns: purple or pink decorative gradients, aurora washes, glass panels for
their own sake, neon or tube-like glows, permanent particles, dashboard chrome, orange buttons,
and gauges, needles or G shapes without a value to show.

**Where it applies.** Destacados, the header and the footer implement it now. The header's
current section carries a short fire rule, and its search and controls sit on night with ice
edges, not glass. Recientes, Próximos, search, the game page, Mis puntuaciones and the hosted
identity screens keep the earlier language below until they are migrated. A migration adopts the
families, the stage and the vocabulary where they carry meaning, and never forces a temporal G
where no month is represented. Until then:

- the rules below that call for glass, aurora fills, the lit serif accent, IBM Plex Mono kickers
  or spark badges describe those screens only, and new work extends them no further;
- the browser checks in `frontend/tests/featured-design.spec.ts` assert Destacados' identity
  composition.

## Mandatory composition

- Use the full-width header with the Gameómetro mark and wordmark, primary
  navigation, integrated catalogue search and the server-owned account control. It floats
  over the page's stage on a soft scrim rather than a solid bar, in the
  [brand identity](#brand-identity): the current section carries a short fire rule, and search
  and account sit on night with ice edges. Post-MVP (#151), the navigation is named **Lanzamientos** and holds
  **Destacados**, **Recientes** and **Próximos**; Destacados is the landing route. The account
  control provides `Mi cuenta`, `Mis puntuaciones` and CSRF-protected logout for an
  authenticated session. Post-MVP (#162), anonymous browsing also exposes primary
  `Iniciar sesión` and secondary `Crear cuenta` links that start BFF/OIDC navigation
  directly to the hosted Gameómetro identity screens;
  discovery stays anonymous and the inline rating authentication boundary remains available.
  Keep catalogue search prominent within the
  header and omit the explanatory context strip. On phones below 620px, the compact mark,
  the three release sections, search and account icons share one row from 360px; below
  360px the sections take a full-width glass row of their own under that row. Search opens the
  existing catalogue search in a keyboard-accessible dialog; the account icon opens
  either the two anonymous entry links or the authenticated account actions in a
  keyboard-accessible panel. Keep their accessible names and visible focus. On tablet, identity, navigation and
  the account control share the first row, with navigation beside the identity, and search
  spans the next row; below 720px the identity is the compact mark and the sections and
  entry links tighten, so the account control never wraps onto a row of its own. The
  single desktop row keeps the compact mark until 1180px for the same reason.
- Align header and main content to the shared `page-container`, capped at 1320px with 28px
  desktop, 24px tablet and 16px phone gutters. Both release windows use the same cinematic
  stage, title, glass filter dock, cover-led catalogue grid and closing dock. The dock places
  the result total on the left and the pager on the right, with both pager links on one row
  on phones; it omits a repeated page position and is absent without results. Switching
  between Destacados and the release windows belongs to the main navigation only.
- Keep the current release view as the only `h1`: **Lanzamientos recientes**, **Próximos
  lanzamientos**, or **Lanzamientos del mes** on Destacados. Show the API-derived release window beside **Ya disponibles** or **En
  calendario** above the title; omit the redundant evaluation-date label. Never replace API
  dates with a hard-coded relative period.
  On phones, keep the kicker and the API-derived period on one row and show both
  boundaries as day/month/year to fit without changing the desktop wording.
- Treat 1320px, 834px and 390px as the representative review widths, while supporting reflow
  from 320px. The catalogue uses six compact columns at desktop, about four at tablet and two
  at phone widths. Do not allow page-level horizontal overflow; the filter and navigation
  rails may scroll horizontally on narrow screens.
- Beyond the approved featured releases ([below](#featured-releases)), do not add a ranking,
  editorial description, publisher, studio, new release window or destination solely
  because it appears in a reference.
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
  accessible popover preserving each hidden release's date, platform and region. The API
  presents at most one release per platform, so a platform never repeats on a card. The card
  height never grows with the number of releases.

## Visual language and tokens

- [Global styles](../../frontend/src/styles/index.css) own the executable semantic tokens,
  shared dimensions and breakpoints, including the `night`, `ice` and `fire` brand families,
  and the [foundation](../../frontend/src/styles/foundation.css) owns the shared glass, glow,
  aurora and grain values. Use `canvas`, `surface`, `raised`, `ink`, `muted`, `subtle`,
  `accent`, `aurora`, `line`, `warning` and `danger` roles and the brand families through those
  conventions instead of placing palette values in JSX.
- Screens not yet migrated to the [brand identity](#brand-identity) use a near-black ink-blue
  canvas, glass surfaces and one signature light: moonlit periwinkle turning to aurora violet,
  used for primary actions and title accents. Scores speak the thermal language of [product identity](#product-identity-and-thermal-language)
  instead. Otherwise warm light belongs to the art alone; `warning` stays reserved for review
  and lifecycle states.
- Typography: Mona Sans is the interface and display family — its wide, heavy cut sets game
  titles at poster scale and page titles at a compact size on one line, and its regular width sets everything else. A title's
  accent word is set in Instrument Serif italic and lit by the aurora gradient (**Lanzamientos
  _recientes_**, **Resultados para _«consulta»_**, **Mis _puntuaciones_**); the heading still
  reads as one phrase to assistive technology. IBM Plex Mono sets kickers, labels and
  operational metadata. The Gameómetro wordmark is outlined from Mona Sans' wide heavy cut.
  Use the locally hosted assets with system fallbacks and `font-display: swap`. Font licences
  live under
  [public/assets/fonts](../../frontend/public/assets/fonts/README.md).
- Covers carry the catalogue rhythm. The standard cover frame has a 14px radius, a quiet
  edge and a restrained shadow. Its ratio is the one the approved provider actually
  delivers, so `object-fit: cover` crops nothing off real artwork.
  Global styles own the executable value. Release and search result cards show no status,
  provenance, cover attribution or **Ver ficha** action: the game page owns those, including the
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

## Product identity and thermal language

- Post-MVP (#162), the first authentication screen is hosted by Keycloak. Its
  `gameometro` theme pairs a framed night stage with the glass form card. The stage
  carries the lockup and, over its darker foreground, the story: the claim in the title
  voice (**Tu criterio.** with **_Tu historia._** in the lit serif), one sentence on what
  an account keeps and the keypad's ice-to-fire key as ten steps. From 1024px wide and
  600px tall the stage takes the larger left column and stays in view while a long form
  scrolls, and the card and its catalogue return form the only interactive column beside
  it; narrower screens stack the lockup over the night art, the card, then the story. The
  brand appears once. Use the canonical local fonts, Gameómetro branding and
  Spanish-first copy in the informal voice across sign-in, registration, recovery, reset,
  errors and required actions. Inherit Keycloak's templates and account-flow logic: the
  theme adds only the story through the footer hook, copy, local assets and styles. Do not
  reproduce the catalogue header or introduce a React login screen. Keep registration
  prominent and validation and recovery readable.

- The user-facing product is **Gameómetro**, always accented in visible copy, the document
  title and branding (the [product decision](../product/assumptions-and-decisions.md) owns the
  name). Repository, package and other technical identifiers keep their names; use
  `gameometro` only where a technical identifier cannot carry the accent.
- The identity is the Tilde: a moonlit G whose gauge needle, in fire, leaves through the G's
  mouth at the angle of an acute accent, and a wordmark whose `ó` carries the same needle.
  The canonical vector sources are the mark, wordmark and lockup in
  [`shared/brand`](../../frontend/src/shared/brand/); the favicon in `frontend/public` is the
  mark on a night tile with heavier geometry, and the touch icon is its raster. The wordmark
  is outlined from the bundled Mona Sans; the mark is product-owned geometry. Keep the G ice
  and the needle fire, never draw the needle without its G, and do not restate the lockup in
  CSS or in other files. The header inlines the mark and wordmark so the needle can answer
  hover and focus.
- A score is also read as a temperature. `thermalBand` in
  [`shared/score`](../../frontend/src/shared/score/thermal-band.ts) is the only mapping and
  owns the exact edges, upper bounds inclusive: freeze (Congelado) up to 2, cold (Frío) up to 4,
  warm (Templado) up to 6, hot (Caliente) up to 8 and burn (Ardiendo) above 8, so each band holds
  two personal values (1–2, 3–4, 5–6, 7–8, 9–10) and a mean of 8,1 already burns. The band is
  presentation only: it never changes a rating, the aggregate or their meaning, the number stays
  the authority beside it, a band shown is also named in words, and a missing score has no band.
- The G-meter is the mark as an instrument: its scale runs clockwise from the crossbar (0) to
  the G's terminal (10), notched at the band edges, and its trail and needle take the reading's
  temperature; without a score it rests with no needle. The brand mark is the meter read beyond
  10. It reads the community mean, the personal panel's current value and each
  `Mis puntuaciones` score.
- Thermal colours belong to score readings and the keypad's key alone (the hosted sign-in
  repeats that key), never to navigation, controls or states, and each reading shows one
  temperature. Only the extremes add a climate,
  on the community panel: frost for freeze, breathing embers for burn. The thermal semantic
  roles live in global styles and their paint in [thermal styles](../../frontend/src/styles/thermal.css).

## Depth and motion

Depth and motion belong to the visual language, not to individual screens. Global styles own
the executable values; these constraints hold wherever they are used:

- The canvas carries one fixed ambient field: slowly drifting low-opacity aurora washes plus a
  generated grain tile that keeps large dark gradients from banding. It never scrolls with the
  content, never sits above it, and nothing readable depends on it. Both of its layers overscan
  the viewport, so a phone's URL bar showing or hiding never uncovers an edge.
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
  cover tilt on hover; feedback on press and selection; a G-meter needle sweeping to its
  reading, the brand needle's twitch on hover and focus, and a burning panel's breathing
  embers. Declare it inside
  `@media (prefers-reduced-motion: no-preference)` instead of disabling it afterwards, so
  reduced motion is the default and nothing animates or transitions there.
- Below 620px the ambient field and the stage hold still, exactly as under reduced motion:
  perpetual motion beneath glass makes every blurred, blended and masked surface redraw each
  frame, which flickers on high-density phones while they scroll (#209). Wider layouts keep the
  stage's push-in, mist, motes and parallax and the field's drift.
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
  platform wordmarks keep their ratio at the row height. Genres, summary, developers,
  publishers, game modes and scores stay absent from suggestions even when the shared
  search response carries additional metadata; the identification row does not grow.
- The full search results page (#188) follows the owner's approved results reference and
  shares the release windows' stage, title treatment and six-column grid. An
  active query is the only `h1`, **Resultados para «consulta»**, under the **Catálogo de
  juegos** kicker, with no explanatory paragraph; before a query the page keeps **Buscar
  juegos**. The game total and page position sit above the grid (for example,
  `153 juegos del catálogo local · Página 1 de 26`), and the pager below repeats the
  position before its previous/next actions. Each card shows the cover with one known year,
  an inclusive range or `Por confirmar` as its glass chip, the title link, up to two
  localized product genres as quiet text, a `Coincidencia: alias` chip only when the alias differs from the title, and up to three
  platform icons with the exact `+N` of further platforms at the card foot, all from the
  compact release summary.
  Post-MVP (#279 phase 4, implemented; awaiting owner review), the readings of the
  [discovery score rules](#featured-releases) follow the title as two compact rows, Gameómetro
  above **Jugadores IGDB**, so marks and means align. A card shows only its available sources
  and omits the block without any; the IGDB attribution is its only link besides the title. A
  card without scores keeps no reserved space, while the platform row stays at the foot.
  It never renders release rows or status chips, so its height never follows the release
  count. Platform names stay available to assistive technology and as tooltips. Summary,
  developer, publisher and game modes stay absent. Recent/upcoming cards likewise add
  only up to two genres, preserving date, platform and region as primary context. Their
  titles reserve two lines and clamp overflow; genres and platform/region reserve one
  line each with presentation-only ellipsis, keeping a grid row aligned. Full labels
  remain in the DOM; release overflow preserves further dates.
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
  unknown platforms retain a generic gamepad. Mundial is a globe; Europa, Norteamérica, Japón,
  Asia, Corea, Nueva Zelanda, Brasil and Australia use owner-provided marks; any other region
  keeps a generic location marker, and the unconfirmed region its own. A seeded platform or
  region is recognized by its stable identifier, and one acquired later by its catalogue label,
  because its identifier differs per environment. Other application dropdowns share the same
  control styling and keyboard behaviour. Selections preserve their existing state owner
  and reset pagination where applicable. On phones, keep both compact selectors on
  one row; selected values may truncate visually, but the full value remains
  accessible in the control and list. Controls keep a visible focus indicator.
  Never hard-code the available choices from the mockup.
- The upcoming window alone adds **Incluir fechas aproximadas** beside the dock: a native
  labelled checkbox in its own glass pill as tall as the dock, unchecked by default, whose
  focus ring outlines the pill. It shares the dock's row while it fits, wraps below the
  dock rather than stretching it over two rows, and spans the column on phones. Toggling
  it keeps the other selections, resets pagination and lives in the URL; leaving for the
  recent window drops it. An empty exact-day selection names this opt-in in its notice.
- Release results show the game total followed by the current page position (for example,
  `99 juegos · Página 1 de 9`). Use spacing between the heading and filters, and between
  results and footer controls, without separator rules.
- Show a region name exactly as the API returns it. The catalogue owns the Spanish display
  label ([`CAT-008`](../architecture/domain/mvp-domain-model.md)), so the frontend never
  translates, reformats or maps region names. It selects, filters and keys regions by
  identifier, never by label; only a decorative region mark may follow the label.
- Keep native links for navigation and buttons for actions. Use `aria-current` for the active
  route or filter, visible `:focus-visible`, the skip link and explicit focus movement after
  route and pagination changes. A cover may be pointer-accessible, but each card keeps one
  primary keyboard stop: the title link on release, search and personal-rating cards.
  Rating title links do not stretch over the card's editing controls.
- Keep reduced-motion and forced-colour support. Do not require hover, animation, a fixed
  desktop width or a sticky header that can obscure focus.

## Featured releases

Post-MVP (#151), **Destacados** is the landing route and implements the
[brand identity](#brand-identity). The heading is the only `h1`, **Lanzamientos del mes**, beside
the month selector: the previous-month link, the represented month and the next-month link,
with no caption and no selection note. The current month is the landing route itself. The
selector offers only the months of the current calendar year, which the API's trusted date
states: January's previous step and December's next step stay in place as disabled links
(dimmed, announced as unavailable, outside the tab order), and the selector waits as a
placeholder until a response states the current month. A requested month the API rejects,
malformed or of another year, returns to the landing route; another year's selection is never
presented. Popularity is attention: never call a selection the best, a winner or a quality
judgement, never show its value, and mark the featured release with the fire needle, never a
crown, trophy or medal. The response's selection state and freshness stay in the model; the page
does not restate them as a note (owner decision, 2026-10-16).

Every featured frame is landscape and shows the item's `featuredImage` as the contract says: a
`fill` image is cropped to the frame, while a `contain` image, such as a portrait cover, is shown
whole with its own corners over its own light, blown up and blurred. The hero frames a panoramic
`fill` banner by its loaded ratio ([stage and art](#brand-identity)). An image is never stretched,
and a portrait cover is never cropped to landscape. When an image fails to load, a card's frame
steps down to the provider cover shown whole, then to the product-owned landscape fallback; the
hero steps down to the designed fallback and keeps its cover, shown whole, only as the last
resort. The composition never breaks.

The hero is the month's featured release. Its art spans the stage to the right edge and up
behind the header on wide screens; the temporal G stands on the left and frames the copy. The
copy holds the **Lanzamiento del mes** badge with the fire needle, then always the canonical
title as Gameómetro's own wordmark, never a provider logo: Mona Sans' widest heavy cut, in
capitals, cast in moonlit silver that the art may tint only through a blend that keeps every
channel light. A subtitle that the canonical title introduces with a colon or a spaced dash
follows in Mona Sans in ice; the lockup drops that delimiter visually, while the heading's text
and accessible name keep the canonical title exactly. The wordmark's size follows the name so
its longest word fits one line and the whole name a few lines within a fixed range. Then one
meta row (the presented release's compact date, its platforms as marks, an unrecognized platform
keeping its name, and its region), up to two localized genres as quiet text, and the score
readings. The hero shows no summary: the game page owns it, and the response keeps it. Missing
enrichment is omitted without invented values. Ordinary lifecycle and Full Release labels are
absent; only exceptional delay, cancellation, unknown-date or pending-review notices carry a
chip. The canonical title is the native game link, its pointer target stretched over the hero,
its keyboard focus outlining it, and pointing anywhere on it underlines the title. The IGDB score
attribution remains a separate native link above that target; links never nest. Further
release dates keep their overflow control immediately below and outside the linked frame.
Developer, publisher and game modes stay absent from featured discovery.

Score readings (#255 phase 2) are set as the instrument's readouts, without boxes: the G-meter
or IGDB's [locally served official mark](../../frontend/public/assets/providers/README.md), the
value with its /10 scale and the count beneath, the two sources side by side in the hero and
beneath the title on cards. Only **Otros lanzamientos destacados** shows counts from 1000 as
`+XK`, using whole thousands rounded down (`3548` becomes `+3K`); the exact count remains in the
tooltip and accessible text. Sources wrap whole onto a second line only when they cannot fit
legibly, never shrinking or truncating text. Omit each unavailable source and the whole block
when neither has a valid mean and positive count; never invent an empty reading. Gameómetro
reuses the G-meter, thermal bands and labels, while IGDB keeps neutral ink. Both retain their
/10 value and their own vote count, including one vote. IGDB attribution links to that Game's
source work and is named **Jugadores IGDB**. An external score has no Gameómetro temperature.

**Otros lanzamientos destacados** follows, with **Ver todos los lanzamientos** (Recientes) and up
to five wide artwork cards straight on the stage. Each card's landscape frame carries the
context-selected media under `FEAT-003` in the
[domain model](../architecture/domain/mvp-domain-model.md); a screenshot may carry the game's
existing decorative logo, and artwork is shown as it is. Beneath the frame come the compact
month scale with the release's compact date, the title on one line (its full text stays the link
and its tooltip) as the only keyboard stop, the readings, one release line with up to three
platform marks, the exact `+N` and the region, then up to two localized genres and the overflow
control. Pointing at a card's art or title lights and underlines its title link. Cards omit
summary, developer, publisher and game modes. Fewer games mean fewer cards. An unranked or empty
month replaces the hero and the row with an informational or empty notice that names the month
and links to Recientes.

From 1024px the G and the art share the hero, and the row has five columns; at 1440×900 and
1320×900 the hero and the whole row open within the first view. Tablets put the art above the G,
which stands centred over its faded lower edge, and lay the row in three columns. Phones stack
the 16:9 art over the copy, keep the G as a small emblem over the art's lower corner, and retain
the whole frame as the game link. Their meta row sets the date, the platforms and the region as
three groups spread across one row whenever they fit, the platforms in the cards' compact form
(the first two marks and the exact `+N`, every name still announced); a group that cannot fit
moves whole to a second row, so no group ever breaks or shrinks. Below 480px each card takes the
full column. Forced colours keep the art beside or above the copy, never behind it.

## Public game details

The game page is a premium, editorial game hub built only from the contract's data (#233). It
opens on a stage lit by the cover this page already displays — blown up, blurred and graded,
never resampled or stored — and from tablet width the same artwork returns as a soft echo behind
the title, under a reading shade that keeps the copy set on the stage legible over the brightest
cover. It adds no request, no asset and no licence surface beyond that cover. Title and summary
read directly on this shaded stage; scores and structured facts keep restrained glass surfaces.
The opening carries the identity and the readings; one information card closes the page with
metadata followed by the calendar. Each company role is stated separately, even when its value
matches another role.

From 1024px the large cover, in the shared frame with its own glow beneath it, stands at the
left, immediately above the community reading, the IGDB players' reading when one exists, and
the compact personal reading. The right
column opens with **Ficha del catálogo**, the canonical title and the summary as editorial
content, without a separate summary card or visible repeated heading. **Información del juego**
follows within that column as one card at its natural height, with no empty rows, forced
stretching or sticky facts. Its **Fechas y plataformas** subsection uses the card's full width
below the metadata. Tablets keep the cover beside the title and summary, then give the
readings a shared row with comfortable widths before the unified information card spans the
page below: the community and personal readings side by side or, with an IGDB players' reading,
the two sources side by side and the personal reading beneath the community's. Phones open with
the title, then cover, the readings in that order, summary and information, with the calendar
nested last. Source order
follows this phone reading order, so keyboard navigation never jumps back to the identity after
the summary. The title uses the wide display cut at a restrained scale,
following both the available editorial width and the viewport, stepping down twice for long
names and wrapping even an unbroken word. Normal titles retain their display scale where space
allows. Aliases follow in the lit serif. For a mouse, the cover leans toward the pointer and
catches a glare; touch, pen, reduced
motion and forced colours keep it still. Release-discovery cards, full search results and
`Mis puntuaciones` rows carry scores under the discovery rules above; the search typeahead keeps
them absent, and other screens carry none until #279 phase 4 reviews them.

The community reading is the most prominent: the G-meter beside the large mean, its temperature
in words and `Basada en N puntuaciones`. “Sin nota todavía” keeps its place with a smaller meter
and tighter spacing, giving the empty reading less prominence; an unavailable reading retains
room for its explanation. The distribution remains a backend/API capability and is
not rendered on this page.

Post-MVP (#279 phase 3, implemented; awaiting owner review): **Jugadores IGDB** follows as a
separate reading in the community reading's shape and glass, lit by the product's aurora rather
than a temperature. The white [IGDB mark](../../frontend/public/assets/providers/README.md)
stands in the meter's place, so both means share one column, and is the only link, to this
game's source work. The mean keeps its /10 scale, followed by `Basada en N valoraciones` with
the exact count, including one. It never takes a band, a temperature or the community panel's
climate, and without a valid mean and positive count the whole reading is omitted, never
filled, whatever the community reading shows. Loading reserves no place for it.

**Tu puntuación** stays secondary to the source readings: one compact reading in the
language of `Mis puntuaciones` — its G-meter, the label, the band in words and the value, or
`Puntuar` without one — is a button with a caret that opens an anchored, non-modal glass panel
overlaying what follows, so nothing below moves. The panel holds the prompt, the shared keypad
and its key (`1 · Congelado`, `10 · Ardiendo`), and a quiet **Eliminar puntuación** once a rating
exists. The scale is a labelled group of ten circular buttons; picking a value saves it at once
(create or update through the conditional contract) and closes the panel, returning focus to the
reading, so there is no separate confirm action; picking the current value only closes it.
Opening focuses the pressed value or the scale's stop, and a disabled scale hands focus to the
delete action. Arrow keys only move focus inside the scale, so browsing never saves by accident;
Escape, an outside press or moving focus away close the panel without a command. The current
rating is the one pressed value (filled with its temperature, ring and glow, not colour alone),
and the values below it keep a ring of their own temperature so the scale reads as a thermometer.
Eligibility is expressed by the reading itself: an ineligible game without a rating keeps it
unavailable — still focusable, so focus is never dropped, and described by the reason stated
beside it — while an existing rating still opens a disabled scale with its delete action. There is no separate eligibility block, no "Contexto personal" kicker and
no permanent authentication explanation. Command outcomes stay beside the reading, whether or not
the panel is open: one live status (the reason, saving, deleting, checking or a failed read), a
visually hidden success announcement, and one alert for rejected, conflicting or ambiguous
commands; the last valid personal and community state stays visible and nothing is retried
automatically. An anonymous pick starts authentication with that value; after returning, the
recovered value is persisted once automatically (no command when it equals the existing rating)
and any failure surfaces like any other command.

The summary has a comfortable reading measure below the title: up to eight lines on desktop
and six on narrower screens, with a native **Leer más** / **Mostrar menos** button only when the
text overflows. Its accessible heading remains available to assistive technology, and expansion
keeps the full text recoverable without changing the cover's dimensions or stretching the cards.
It keeps its language for assistive technology and is never translated here; a sourced summary credits its
source and names a language other than Spanish (`Texto original en inglés · Fuente: IGDB`).
Post-MVP (#235), derived Spanish summaries instead state `Traducción automática de
Gameómetro · Fuente del original: IGDB`; a last-valid translation of older source also
states `Traducción pendiente de actualizar`. The text's `lang` is the served language,
while the catalogue's own editorial text, including the notice that no summary exists yet, needs
no credit.

**Información del juego** states **Desarrollador** and **Publisher** as distinct fields, even
when the same companies hold both roles. Each role can list several companies. **Géneros** and
**Modos de juego** follow as scannable chips carrying the catalogue's names exactly as served.
The metadata uses two columns when the card has enough space, with company roles above taxonomy;
narrow cards use a natural single column. Spacing and typography separate fields without inner
cards. Unknown facts are omitted, never filled; when no metadata is known, the card proceeds
directly to its calendar subsection without empty placeholders.

**Fechas y plataformas** is a lower-level heading inside the information card, separated from
known metadata by spacing and one divider, with no separate glass surface. It lists the
presented release of every platform and region, in the API's presented-release order, so a
visitor reads every date without choosing anything first; no
selector is invented and none is needed. Each row reads across one line where it fits and in two
on phones: the platform mark and name, the region mark and name, the date at its own precision
with its known normalized Spanish stage as quiet secondary text beneath it (an unknown stage is
left unstated), and a status chip that states the status in words. A pending review is flagged
on its row with a warning marker that does not rely on colour, and verified evidence is credited
there. Synchronization age is internal operational evidence: no row, notice or source line states
it, and the source attribution names only the sources. Rows are a list whose values are named for assistive
technology, never a table. Further records of the same platform and region stay whole, one row
each, in a closed native disclosure, `Otras fechas registradas (N)`, whose summary carries the
review notice when any of them is pending review. The block closes with its attribution: the
release sources and the latest synchronization day. Further provenance timestamps and the
provider-only verification default stay out of the primary hierarchy. A game without releases
says “No hay lanzamientos comerciales registrados.”.

## Personal ratings collection

`Mis puntuaciones` reuses the catalogue shell, page opening, cover treatment, tokens and
native controls. Present cover-led glass rows, each washed by its own cover's light, with
native cover/title links, up to two localized genres beneath the title, and the editable
**Tu puntuación** reading. No summary, developer, publisher,
game mode or release-specific platform/region context is shown: these are Game ratings.
There is no **Ver ficha** CTA and the complete row is never a link. Post-MVP (#279,
implemented; awaiting owner review), the read-only community and IGDB readings are the
compact readings of the [discovery score rules](#featured-releases), unchanged, in one row
directly beneath the genres: secondary context in the game's column, never a second panel
competing with the personal score. A row shows only its valid sources and omits the readings
without any; the IGDB attribution is its only link besides the title. **Tu puntuación** keeps
the row's right edge: beside the game from tablet width and beneath it on phones, where the
readings wrap within the game's column. Calendar dates are quiet secondary text without hours: one
**Puntuado el …** when creation and update have the same visible date; otherwise compact
**Actualizado el …** with the original date. The personal score
is a Gameómetro reading — its G-meter, its temperature in words and the number — lit by its
band in the language of the game page: it is the reason the row exists. The private filters form one glass control bar rather than
four loose fields, and stay separate from the header's public catalogue search. Pages hold
10 ratings by default, with 10, 20 or 50 to choose from.

Post-MVP (#210), the score reading is the row's only maintenance control: a button with a
caret that opens an anchored, non-modal glass panel. The panel hangs from the reading's right
edge from tablet width and spans the row beneath it on phones. It overlays the rows below
instead of growing its own, so the cover, title and dates never move. The panel holds the
game page's keypad and its key (one shared component), a G-meter and reading for the pending
value in its temperature, and the persisted value named beside it while they differ. Then
come **Cancelar** and **Guardar nota**, and, below a divider, a quiet destructive
**Eliminar puntuación** that asks once more (**Conservar** / **Sí, eliminar**). Picking a value
only makes it pending, and arrow keys only move focus inside the keypad, so neither browsing
nor picking ever sends a command. Opening focuses the pressed value. Escape and Cancel close
the panel and return focus to the reading; an outside press or moving focus away also closes
it. Outcomes show inside the panel: one live status while a command runs and one alert for
rejected, conflicting or ambiguous commands.

There is no manual refresh. The collection is read on entry and on every reload, and again
automatically after a successful update or deletion and after a conflicting or ambiguous
command. A concurrent or ambiguous command keeps further commands disabled until such a read
succeeds; a failed read keeps the existing **Reintentar carga** recovery. Announce outcomes
and move focus to the results after maintenance or pagination. Empty ratings, no search
matches, an exhausted page and load failure remain distinct.

## Server-backed states

| State | Required presentation and behaviour |
|---|---|
| Loading / new selection | One live status plus non-interactive placeholders shaped like the screen they replace; never present previous results as the new selection |
| Refreshing | Keep valid current results usable and announce the refresh |
| Empty | Neutral editorial notice with applicable filter reset or page recovery; never call it a failure |
| Stale | Catalogue synchronization age is never shown to players, on cards or on the game page; a release that requires review keeps its notice. A stale featured ranking keeps its observed-attention caption |
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
2. include long titles, varied date precision, review status, provider artwork and the
   product fallback;
3. compare the captures directly with the approved export;
4. check all affected loading, empty, catalogue-not-ready and failure states; and
5. run only the focused component, browser and documentation gates justified by the change.

The reference's unsupported controls and sample content remain outside the MVP. Do not add a
component library, Storybook, theme engine or speculative abstraction for this visual direction.
