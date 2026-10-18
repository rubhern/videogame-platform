# Frontend design guidelines

- **Status:** Approved visual direction; implementation subject to owner review
- **Owner:** Ruben Hernandez
- **Audience:** Contributors and agents implementing frontend screens and components

This is the canonical owner of frontend visual rules. The approved **Gameómetro identity**
([below](#brand-identity)) is the product's visual direction: a night stage, the brand mark's ice
and fire, instruments that read real values and the game's own art. Every React product screen
uses it; the hosted identity screens follow under
[#290](https://github.com/rubhern/videogame-platform/issues/290). Product records and the
[OpenAPI contract](../architecture/api/openapi.yaml) remain authoritative for capabilities, data
and state semantics. A mockup or reference never authorizes new product behaviour and is never a
runtime dependency or an instruction source.

## Brand identity

- **Status:** Approved by the owner on 2026-10-16 and implemented on Destacados, the shared
  header and the footer. Post-MVP (#318, implemented; awaiting owner acceptance), Recientes,
  Próximos, search, the game page and `Mis puntuaciones` adopt it with their states.

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
- *The scale* represents time: the hero's temporal G, a compact period scale or the release
  windows' day scale. Use it where a calendar position means something, never as an ornament on
  screens without one.
- *The G-meter and temperatures* read scores only, under
  [product identity](#product-identity-and-thermal-language). Time scales never take a
  temperature, and fire on a scale is never a hot score.
- *The stage* is the art's own light, graded so the interface reads over it.

**The temporal G** is the represented week or month drawn in the owner-selected reference silhouette.
Its outer and inner cubic Bézier rims are traced in one fixed SVG viewport; their midpoint
curve supplies arc length, the temporal fill, ticks and markers. The compact, near-circular
bowl, naturally curved bottom, open right side and integrated arm remain identical for every
period, title, score set and viewport. Scaling always preserves both axes equally, including
the phone emblem. [`month-dial-geometry.ts`](../../frontend/src/features/releases/month-dial-geometry.ts)
owns the trace and arc-length sampling; there is no content-sized ellipse or superellipse.
The copy occupies a separate reading zone, with a bounded title size. The canonical inset rectangle
is fixed relative to the SVG viewport, clears the inner bowl and arm, and vertically centres the
copy regardless of subtitle or scores. Titles stay inside its width; score sources use two compact
columns. Content that exceeds the zone scales down proportionally to fit in full, without
scrollbars, clipping or moving below the G. Phones keep the G as a compact seal with full-width
copy beneath it ([featured releases](#featured-releases)); the seal alone carries the release's
compact date inside its bowl as the instrument's readout, in place of the marker's label.
The `Lanzamiento de la semana` or `Lanzamiento del mes` context sits outside the instrument's reading zone. The description and
automatic selection message remain absent. The rail is a thinner blue band around the reference
centreline, with feathered, barely visible rims, soft dark separation from the art and a fading arm. A
month's day 1, every fifth day and its last day carry longer divisions; a week's seven days all do.
All divisions are centred within the band and inset from both rims. Quiet, unoutlined numerals sit
outside it: each day's date in the month, and in a week the day that starts a new month adds its
short name (`1 nov`). The elapsed blue stays clearly visible on dark and bright art, including a
complete past period. Days advance clockwise from just after the arm's corner to just before the
top terminal, equally spaced by arc length: Monday to Sunday for a week, 28 to 31 days for a month,
over the API's own window, so a week crossing months or years reads as one scale. One ice light
fills the days gone by the API's `evaluatedOn` (`Europe/Madrid`), with a close, faint glow: all of a
past period, up to today in the current one, none of a future one. A fine `hoy` notch marks today
in the current period only. A tapered fire needle with a circular pivot and warm
core, following the brand mark, marks the presented release's day in every phase; compact scales
use the same blade-and-pivot language. A release known only to its month, quarter or year spans the
scale, dashed when its interval reaches beyond the period; one outside the period gets a chevron at
that end; an unknown date gets a dashed ring named `Por confirmar`. A day is never invented. The G
is decorative: a visually hidden sentence states the same progress and day. While the page stays
open across midnight in Madrid the selection is read again, so the scale follows the API's new
date; the browser's clock only times that read.

**Compact period scales** unroll the same week or month in a line under a card: the crossbar as the first
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

**Compact window scale.** The release windows unroll their API window the same way, one division
per day under the title: the days gone by the API's `evaluatedOn` in the same ice, `hoy` under
today, and one fire needle, with its pivot, on each day a release on the page falls on. An
upcoming window starts tomorrow, so today leads the scale as one lit division. Pointing at or
focusing a release lifts its needle and quiets the others. Releases known only to a month,
quarter or year take no needle; the scale never invents a day. It is decorative: the period
beside the title and each card state the same dates in words.

**Where it applies.** Every React product screen uses the families, the stage and the vocabulary
where they carry meaning, and each keeps its own composition: Destacados is the period's stage,
the release windows a cover wall under their day scale, search a catalogue index, the game page
its own cover's stage and `Mis puntuaciones` a thermal ledger. No screen repeats the temporal G or
becomes another landing hero. Glass panels, aurora washes, the lit serif accent, tracked mono
kickers and spark badges are retired: no React screen uses them, and new work never brings
them back.

## Mandatory composition

- Use the full-width header with the Gameómetro mark and wordmark, primary
  navigation, integrated catalogue search and the server-owned account control. It floats
  over the page's stage on a soft scrim rather than a solid bar, in the
  [brand identity](#brand-identity): the current section carries a short fire rule, and search
  and account sit on night with ice edges. Post-MVP (#151), the navigation is named **Lanzamientos** and holds
  **Destacados**, **Recientes** and **Próximos**; Destacados is the landing route. The account
  control provides `Mi cuenta`, `Mis puntuaciones` and CSRF-protected logout for an
  authenticated session, and **Panel de administrador** only when the session names
  that capability (#247). Post-MVP (#162), anonymous browsing also exposes primary
  `Iniciar sesión` and secondary `Crear cuenta` links that start BFF/OIDC navigation
  directly to the hosted Gameómetro identity screens;
  discovery stays anonymous and the inline rating authentication boundary remains available.
  Keep catalogue search prominent within the
  header and omit the explanatory context strip. On phones below 620px, the compact mark,
  the three release sections, search and account icons share one row from 360px; below
  360px the sections take a full-width row of their own under that row. Search opens the
  existing catalogue search in a keyboard-accessible dialog; the account icon opens
  either the two anonymous entry links or the authenticated account actions in a
  keyboard-accessible panel. Keep their accessible names and visible focus. On tablet, identity, navigation and
  the account control share the first row, with navigation beside the identity, and search
  spans the next row; below 720px the identity is the compact mark and the sections and
  entry links tighten, so the account control never wraps onto a row of its own. The
  single desktop row keeps the compact mark until 1180px for the same reason.
- Align header and main content to the shared `page-container`, capped at 1320px with 28px
  desktop, 24px tablet and 16px phone gutters. Both release windows share one opening — the
  context line, the title with the API window as its readout, the
  [compact window scale](#brand-identity) and one row of filter controls — then the cover grid
  and a closing row. The closing row places the result total on the left and the pager on the
  right, with both pager links on one row on phones; it omits a repeated page position and is
  absent without results. Switching between Destacados and the release windows belongs to the
  main navigation only.
- Keep the current release view as the only `h1`: **Lanzamientos recientes**, **Próximos
  lanzamientos**, or **Lanzamientos semanales** / **Lanzamientos del mes** on Destacados. **Ya disponibles** or **En
  calendario** introduces the title, and the API-derived window follows it as the instrument's
  readout in ice, at the precision the API states (`11–17 oct 2026`, `28 sep – 25 oct 2026`),
  while assistive technology reads it in full (`Del 11 de octubre de 2026 al 17 de octubre de
  2026`); omit the redundant evaluation-date label. Never replace API dates with a hard-coded
  relative period. The readout and its scale wait as quiet placeholders only while a read is
  under way; a failed read shows neither.
- Treat 1320px, 834px and 390px as the representative review widths, while supporting reflow
  from 320px. The release windows' cover grid uses six compact columns at desktop, about four at
  tablet and two at phone widths; search's index uses three, two and one. Do not allow page-level horizontal overflow; the filter and navigation
  rails may scroll horizontally on narrow screens.
- Beyond the approved featured releases ([below](#featured-releases)), do not add a ranking,
  editorial description, publisher, studio, new release window or destination solely
  because it appears in a reference.
- Both release windows use the plain night canvas, without decorative artwork behind the
  header or filters, on phones and wider screens. They open with the platform and region
  selectors and twelve results per default page so six columns form two rows on wide desktop.
  Keep the opening short enough that the first row of covers is fully visible at 1320×900. Its
  covers fill the column in the standard frame so the artwork leads the card. Under the cover,
  the first relevant release's date follows the fire needle in its compact form at the same
  precision (`25 sep 2026`, `sep 2026`, `T3 2026`, `2026`, `Por confirmar`), in the ember the
  period scale gives a release's date on Destacados; the full wording stays in the release row
  for assistive technology. Then the title, which is the game link, the score readings, and one
  compact release row: the platforms sharing that date and region as their marks (the first
  three drawn, every name announced and the exact `+N` of the rest), then the region. Up to two
  genres and the review notice, when the API requires one, follow. Each card is a subgrid of its
  row, so dates, titles, readings and release rows align across the row without reserving space
  the row does not need.
  A card shows at most one release row; any further releases collapse into a single
  `+ N lanzamientos más` control, where `N` counts the hidden releases, that opens an
  accessible popover preserving each hidden release's date, platform and region. The API
  presents at most one release per platform, so a platform never repeats on a card. The card
  height never grows with the number of releases.

## Visual language and tokens

- [Global styles](../../frontend/src/styles/index.css) own the executable semantic tokens,
  shared dimensions and breakpoints, including the `night`, `ice` and `fire` brand families, and
  the [foundation](../../frontend/src/styles/foundation.css) owns the control, overlay, hairline
  and instrument values and the grain. Use the `canvas`, `surface`, `raised`, `ink`, `muted`,
  `subtle`, `accent`, `line`, `warning` and `danger` roles and the brand families through those
  conventions instead of placing palette values in JSX.
- Ice is the one interface light: primary entry actions, focus, selection and the instruments'
  readouts. Scores speak the thermal language of
  [product identity](#product-identity-and-thermal-language) instead. Otherwise warm light
  belongs to the art and to fire's events alone; `warning` stays reserved for review and
  lifecycle states.
- Typography: Mona Sans is the only interface and display family. Its wide, heavy cut sets page
  titles as one phrase at Destacados' compact size and a game's canonical title as Gameómetro's
  wordmark; its condensed cut sets the instrument's readouts — context lines, periods, dates,
  labels and the reading of a temperature in words — and its regular width sets everything else.
  Titles carry no lit accent word. The Gameómetro wordmark is outlined from Mona Sans' wide heavy
  cut. Use the locally hosted assets with system fallbacks and `font-display: swap`. Font
  licences live under [public/assets/fonts](../../frontend/public/assets/fonts/README.md).
- Covers carry the catalogue rhythm. The standard cover frame has a 4px radius, a quiet inner
  edge and a restrained shadow; pointing at a card lights that edge in ice and underlines its
  title, as Destacados' cards do, and keyboard focus outlines the frame. Its ratio is the one
  the approved provider actually delivers, so `object-fit: cover` crops nothing off real
  artwork. Global styles own the executable value. Release and search result cards show no
  status, provenance, cover attribution or **Ver ficha** action: the game page owns those,
  including the provider attribution and source link that
  [ADR-0001](../decisions/0001-reference-igdb-cover-images.md) requires. Never truncate contract
  data to equalize card heights; a long value wraps inside its tag.
- A catalogue card keeps one pointer target and one keyboard stop: the title link's target
  covers the card, the title stays its only keyboard stop besides a score attribution, and its
  focus ring outlines the cover. Cards keep equal height across a row and never glow, lift or
  sweep light.
- Provider covers arrive at one fixed CDN size, so every frame wider than that size upscales
  them. Treat that as a permanent condition of [ADR-0001](../decisions/0001-reference-igdb-cover-images.md),
  not a defect: covers carry a faint grain, a soft edge shade and a small micro-contrast lift so
  the interpolation reads as texture rather than blur, and the large game-detail frame — the only
  one no provider cover size can fill — adds a light unsharp mask. These are presentation
  effects over the delivered image. Never resample, cache or re-encode provider artwork, and
  never apply them in forced colours.
- Controls sit on night with a hairline edge, as the header's search and account do: 6px
  corners for buttons, selectors, fields and the personal readings, 3–4px for tags, overflow
  controls and overlays. The primary action is filled ice-light; danger keeps an outlined danger
  tone; fire never colours an action. Primary controls retain at least a 44px target; compact
  card controls may use the smaller established treatment. Hover, keyboard focus and selected
  state must remain distinct without relying on colour alone.
- Supporting metadata must remain comfortably readable. Card metadata, cover attribution,
  status notices, result summaries and filter labels use at least 11 CSS pixels with WCAG AA
  contrast on their actual surface. Prefer `muted` on controls and overlays; reserve `subtle`
  for quiet text on `canvas` or `surface` where its contrast remains at least 4.5:1.
- Identifiers a person may have to repeat — currently the support correlation reference — stay
  monospaced in IBM Plex Mono, the only use of a mono face, and keep their exact casing. Never
  case-transform them for style.

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
  prominent and validation and recovery readable. This hosted theme predates the
  [brand identity](#brand-identity) and still carries the night art, glass card and lit serif
  that no React screen uses any more; [#290](https://github.com/rubhern/videogame-platform/issues/290)
  owns its migration, so the theme copies those assets from `frontend/public` until then.

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
  10. It reads the community mean, the personal reading's current value and each
  `Mis puntuaciones` score.
- Thermal colours belong to score readings, the keypad and its key (the hosted sign-in repeats
  that key) and the edge of a `Mis puntuaciones` line, which takes its own personal score's
  temperature; never to navigation, controls or states, and each reading shows one temperature.
  The community meter carries its temperature as a close, faint light of its own; no reading
  adds a climate, a particle or a perpetual motion. The thermal semantic roles live in global
  styles and their paint in [thermal styles](../../frontend/src/styles/thermal.css).

## Depth and motion

Depth and motion belong to the visual language, not to individual screens. Global styles own
the executable values; these constraints hold wherever they are used:

- The canvas is night with faint grain painted directly into its background to keep dark
  gradients from banding. It follows the document and never sits above readable content.
  Do not implement it as a viewport-sized fixed translucent layer: deep scrolling and the
  mobile browser toolbar must not invalidate the header or page content.
- Featured, search, personal ratings and game details open on a stage lit by art they already
  show, anchored to the top of the document so it runs behind the header: Destacados by its
  featured release ([below](#featured-releases)); search and `Mis puntuaciones` by up to
  three of the covers the page presents; a game's page by its own cover. The release windows
  keep the plain night canvas under their opening and cover wall. Each image's hue, not
  its luminance, is blown up, blurred and capped in brightness, the copy's side stays in shade,
  and the light falls away into night before the content. It adds no request, stores and
  transforms no image, is hidden from assistive technology and takes no input. A product
  fallback never lights a stage; without art — before results, on a failed read or on the
  not-found route — the stage is plain night.
- Depth comes from light and shadow, never glass: covers and overlays carry a restrained shadow,
  overlays that carry controls are opaque night with a hairline and an ice or thermal top edge,
  and there is one easing curve, one overshoot for presses and three durations. Do not add a
  per-component shadow scale, a backdrop blur or a halo.
- Motion is purposeful and limited to transform, opacity and clip: Destacados' orchestrated
  entrance and parallax ([below](#featured-releases)); a quiet entrance for arriving content;
  the window scale's light running to today and its needles landing; a needle lifting for the
  release a visitor points at; a cover easing a little closer on hover; feedback on press and
  selection; a G-meter needle sweeping to its reading and the brand needle's twitch on hover and
  focus. Declare it inside `@media (prefers-reduced-motion: no-preference)` instead of disabling
  it afterwards, so reduced motion is the default and nothing animates or transitions there.
- Nothing decorative animates perpetually: perpetual motion beneath blurred, blended and masked
  surfaces redraws them every frame and flickers on high-density phones while they scroll
  (#209).
- Composition a browser check measures — page openings, filters, the detail cover, title and
  readings — uses an opacity-only entrance, never a transform.
- Forced colours drop every stage, scale, shade, gradient, shadow and mask and return to system
  colours.

## Reusable patterns and interaction

- Use the shell's `CatalogueSearch` as the single catalogue search entry. It preserves the
  existing URL-backed bounded search, exposes a labelled search landmark, supports the `/`
  focus shortcut and reports the OpenAPI query limit before navigation.
- The search's typeahead popup (#156) follows the owner's approved search-popup reference:
  an opaque night overlay with an ice top edge, anchored under the search at its width, a
  `Resultados (N)` header with the `Enter` hint, compact rows (landscape cover crop, title, then
  platform icons, a divider, any alias and the year), the ice-edged active row with its `↵`
  badge, and a separated **Ver todos los resultados** footer. It is a combobox whose focus stays in the
  input. From tablet width the rows use the larger page scale and the desktop search column
  grows to 680px, so the search and its popup share that width; platform icons are separated
  by a dot, with none after the last. Loading keeps the previous row count as placeholders; empty and failure states are
  compact messages that never block the full search. On phones it spans the search
  dialog's row, wraps metadata instead of clipping it and drops the keyboard hints. Wide
  platform wordmarks keep their ratio at the row height. Genres, summary, developers,
  publishers, game modes and scores stay absent from suggestions even when the shared
  search response carries additional metadata; the identification row does not grow.
- The full search results page (#188) reads as the catalogue's index. An active query is the
  only `h1`, **Resultados para «consulta»**, under the **Catálogo de juegos** context line, with
  no explanatory paragraph; before a query the page keeps **Buscar juegos**. The game total and
  page position sit above the index (for example, `153 juegos del catálogo local · Página 1 de
  26`), and the pager below repeats the position before its previous/next actions. Each entry
  sets its cover beside its record, separated from the next by a hairline rather than boxed,
  in three columns on desktop, two on tablet and one on phones: one known year, an inclusive
  range or `Por confirmar` in the readout voice and in ice (a year is no event, so never fire),
  the title link, a `Coincidencia: alias` line only when the alias differs from the title, then
  the readings, up to two localized product genres as quiet text, and up to three platform
  marks with the exact `+N` of further platforms, all from the compact release summary.
  Post-MVP (#279 phase 4, implemented; awaiting owner review), the readings of the
  [discovery score rules](#featured-releases) stack as two compact rows, Gameómetro above
  **Jugadores IGDB**, so marks and means align. An entry shows only its available sources and
  omits the block without any; the IGDB attribution is its only link besides the title. An
  entry without scores keeps no reserved space. It never renders release rows or status chips,
  so its height never follows the release count, and entries in one row share one height.
  Platform names stay available to assistive technology and as tooltips. Summary, developer,
  publisher and game modes stay absent. Recent/upcoming cards likewise add only up to two
  genres, preserving date, platform and region as primary context. Their titles clamp at two
  lines; genres and the region keep one line with presentation-only ellipsis. Full labels remain
  in the DOM; release overflow preserves further dates.
- Release windows, search results and `Mis puntuaciones` share one page opening: the stage lit
  by their own covers, the context line and the one-phrase title, directly under the header with
  tight spacing, so the first row of covers shows without scrolling. Titles longer than about 26
  characters, usually a visitor's query, step down a size. Every paginated list uses the same
  previous/next actions, each with its direction arrow, and a result total sits left-aligned with
  the content it counts.
- Use `CatalogueCover` for provider covers, provider failure fallback and attribution, and
  `CatalogueLoading` for releases and search loading, with one placeholder per result the
  page will show. Release and search result cards render the cover without its caption; the
  game page keeps it. Feature cards keep their own metadata;
  do not introduce a universal card API until further reuse exists.
- Placeholders take the shape of the screen they replace, so arriving content lands in the same
  frame: the cover grid for the release windows, the index for search, the detail composition
  for a game page and ledger lines for the personal collection. Placeholders themselves stay silent; the
  screen keeps exactly one live status.
- Render platform and region choices from `availableFilters` in labelled select-only
  comboboxes, in one row of night controls with the period in both release windows; on phones
  the period spans the row and both facets share the next one. The entire control opens its
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
- The upcoming window alone adds **Incluir fechas aproximadas** after the filters: a native
  labelled checkbox in its own control as tall as the selectors, unchecked by default, whose
  focus ring outlines the control. It shares the filters' row while it fits, wraps below them
  rather than stretching them over two rows, and spans the column on phones. Toggling
  it keeps the other selections, resets pagination and lives in the URL; leaving for the
  recent window drops it. An empty exact-day selection names this opt-in in its notice.
- Release results show the game total followed by the current page position (for example,
  `99 juegos · Página 1 de 9`). Use spacing between the opening and the results, and between
  results and the closing row, without separator rules.
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

## Navigation and scroll position

New page and result-page navigation starts at the top; an explicit fragment targets its
named element. Browser Back/Forward restores each history entry's reading position,
including query-driven pages. Release filters and Featured week/month changes preserve
context; switching discovery sections or submitting another catalogue search starts at
the top. Shorter results may naturally clamp the viewport to the available height.

React Router owns restoration after the destination's bounded TanStack Query read is
ready. Saved offsets live only in the document's memory: never persist them in
`localStorage`, `sessionStorage` or IndexedDB, which stay empty under the
[privacy findings](../research/public-release-rights-and-privacy.md#third-party-requests-from-the-browser).
A reload or a return from the identity provider therefore starts at the top. Mount restoration with that ready destination, not its hydration fallback, whose
shorter height can clamp a saved offset. Keep focus movement separate from scrolling:
route and pagination focus uses
`preventScroll`, while the skip link still takes the reader to main content. Do not reset
scroll after artwork, fonts or background refreshes, or add timed scroll retries.
Hydration reuses the existing loading surfaces without mounting page data or command
effects. Navigation announcements remain separate from the result's live status.
The shared header, page titles and controls must paint on their first return into the
viewport, regardless of scroll direction or browser-toolbar resizing.

Query-only game-detail navigation, including consuming the authentication-return
marker, keeps the existing game read; it must not overwrite a rating command's returned
statistics with an unnecessary reload. Explicit query refresh and mutation reconciliation
remain owned by TanStack Query.

Personal filters remain transient under [personal ratings](#personal-ratings-collection):
returning to that route reads its default collection rather than persisting personal
filter state in history or browser storage.
The initial default collection read is fresh while mounted and still refreshes on
mutation invalidation; leaving the page immediately discards its query cache.

## Featured releases

Post-MVP (#151; weeks #329, implemented and awaiting owner review), **Destacados** is the
landing route and implements the [brand identity](#brand-identity). It opens on the current
week; `DEFAULT_FEATURED_GRANULARITY` in
[`featured-search.ts`](../../frontend/src/features/releases/featured-search.ts) is the only place
that decides it. The heading is the only `h1`, **Lanzamientos semanales** or **Lanzamientos
del mes**, beside the period selector, with no caption and no selection note. The selector is
the instrument's readout. First comes a compact **Semana | Mes** switch: two links in the
condensed voice, parted by a fine upright, the selected one in ice over a short ice bar, without
a filled background, frame or dropdown. Then the previous-period step, the represented period
and the next-period step. A week reads as its exact days at the precision they need (`12–18 oct
2026`, `28 sep – 4 oct 2026`, `28 dic 2026 – 3 ene 2027`), read in full by assistive technology;
a month reads as `Octubre 2026`. While another period is shown, a quiet underlined **Semana
actual** or **Mes actual** link follows and returns to the current one. Switching granularity
always opens that granularity's current period. Every choice is a native link, so the period
lives in the URL and in browser history: `/` is the current period of the default granularity,
`?granularity=` names the current period of the other one, and `?week=YYYY-Www` or
`?month=YYYY-MM` names a period. Only the API states the navigation bounds and the
adjacent periods. A step at a bound, or a step whose bounds are still loading, stays in place
as a disabled link (dimmed, announced as unavailable, outside the tab order). The readout waits
as a placeholder until the URL or a response names the period. A malformed or contradictory URL
takes its canonical form. A period the API rejects returns to the current period of the same
granularity, and its selection is never presented. Ice marks the selection, focus and elapsed
time. Fire stays with the release's day and the featured-release badge, never a control. On
desktop the title, the switch and the steps share one row. Below 620px the title takes the
first row, the switch and the steps share the second, and the way back takes the end of that
row or a short line of its own. Popularity is attention: never call a selection the best, a winner or a quality
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

The hero is the period's featured release. Its art spans the stage to the right edge and up
behind the header on wide screens; the temporal G stands on the left and frames the copy. The
copy holds the **Lanzamiento de la semana** or **Lanzamiento del mes** badge with the fire needle, then always the canonical
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
period scale with the release's compact date, the title on one line (its full text stays the link
and its tooltip) as the only keyboard stop, the readings, one release line with up to three
platform marks, the exact `+N` and the region, then up to two localized genres and the overflow
control. Pointing at a card's art or title lights and underlines its title link. Cards omit
summary, developer, publisher and game modes. Fewer games mean fewer cards. An unranked or empty
period replaces the hero and the row with an informational or empty notice that names the
period and links to Recientes; a week's empty notice says that no release with a confirmed day
starts inside it. Nothing fills an empty period with unrelated releases.

From 1024px the G and the art share the hero, and the row has five columns; at 1440×900 and
1320×900 the hero and the whole row open within the first view. Below 1024px the art starts
behind the period selector and fades in under it, so the page opening and the art read as one
stage; over the art the badge keeps a close, edgeless shade of night. Tablets put the art above
the G, which stands centred over its faded lower edge, and lay the row in three columns.
Post-MVP (#325, implemented; awaiting owner review), phones frame the art a little taller than
16:9, capped in height on wider phones, and set the G as the release's seal: about two fifths of
the column wide, on the copy's left, rising over the art's faded foot with a soft shade of night
in its bowl. The bowl reads the release's compact date at its own precision — the day over its
month and year, a coarser period over its year, `Por confirmar` quietly — and the marker on the
rim carries no second label. Beneath the seal, off the art, the badge introduces the title at its
left edge, and the copy follows at the full width of the column, with the whole hero still the
game link. Because the seal reads the date, the release row keeps the date for assistive
technology only. The platforms, the region and the genres then run as one left-aligned sequence,
`platforms · region | genre · genre`: fine dots within a group, a fine upright between the release
and the genres, which stay quieter. The platforms keep the cards' compact form (the first two
marks and the exact `+N`, every name still announced). The sequence wraps only between whole
facts, a fact starting a line drops its separator, and nothing is spread apart, broken or
truncated to hold one line. A period the G cannot draw keeps the date in the row. Below 480px each card takes the full column. Forced colours keep the art
beside or above the copy, never behind it: the page opening, the art and the seal each keep
their own room.

## Public game details

The game page is a premium, editorial game hub built only from the contract's data (#233), set
on a stage lit by the cover it already displays ([depth and motion](#depth-and-motion)). It adds
no request, no asset and no licence surface beyond that cover. The canonical title is set as
Gameómetro's wordmark, as Destacados sets its featured release: Mona Sans' widest heavy cut in
capitals, cast in moonlit silver, with a subtitle that the title introduces with a colon or a
spaced dash following on its own line in ice; the lockup drops that delimiter visually, while the
heading's text and accessible name keep the canonical title exactly. The name's size steps down
twice for a long name, judged on the name alone, wrapping into balanced lines and even breaking an
unbroken word; the subtitle sizes from the name, steps down on its own when long and wraps to a
short, even measure. Aliases follow as quiet text. The stage's light gathers behind the cover,
desaturated and capped, and the copy's side returns to deep night, so a bright or saturated cover
tints the atmosphere without washing the title, the readings or the text. The readings follow the
title between two hairlines, without boxes, in their order of authority; the summary reads
directly on the stage; a spec sheet closes the page. Each company role is stated separately, even
when its value matches another role.

From 1024px the large cover, in the shared frame with its shadow, holds the left column. The
right column opens with **Ficha del catálogo** and the title, then the readings — the community
reading leading at full size, and beside it, behind a hairline, a supporting column with the IGDB
players' reading when one exists above the compact personal reading — then the summary, without a
separate summary card or visible repeated heading. **Información del juego** then spans the page
as a spec sheet at its natural height, with no empty rows, forced stretching or sticky facts. It
keeps the hero's two columns: the metadata under the cover's column and **Fechas y plataformas**
under the copy's, their headings set as peers on the sheet's first line, so the facts and the
calendar read as one composition rather than a list beside a table. Tablets keep the cover beside
the title and the readings, then give the summary and the information the full width, the
calendar below the metadata. Phones open with the title, then cover, the readings in that order,
summary and information, with the calendar nested last. Source order follows this phone reading
order, so keyboard navigation never jumps back to the identity after the summary.

Post-MVP (#325, implemented; awaiting owner review), below 1024px the two source readings form
one band under a single hairline: the community reading in the wider column and **Jugadores IGDB**
beside it behind a vertical hairline, their labels, figures and counts on shared rows so the two
means compare straight across; without an IGDB reading the community's takes the band. **Tu
puntuación** follows beneath both at the band's full width. When the band is narrower than about
17.5em of its own text — enlarged text rather than any phone width from 320px — each reading
takes the full width, IGDB's beneath a hairline, and nothing shrinks or truncates. On phones the
cover stands centred in the column, about two thirds of its width and at most 272px, with its
caption centred beneath it, and the stage's light gathers around the cover itself and fades out
before the title and the readings. Release-discovery cards, full search results and
`Mis puntuaciones` rows carry scores under the discovery rules above; the search typeahead keeps
them absent, and other screens carry none until #279 phase 4 reviews them.

The community reading is Gameómetro's own and the most prominent: the page's largest G-meter
beside the largest mean, its temperature in words and `Basada en N puntuaciones` at a readable
size, so a mean from one or two votes states its sample as plainly as its value. “Sin nota
todavía” keeps its place with a smaller, resting meter and a calm statement, giving the empty
reading less prominence; an unavailable reading retains room for its explanation. The distribution remains a backend/API capability and is
not rendered on this page.

Post-MVP (#279 phase 3, implemented; awaiting owner review): **Jugadores IGDB** follows as a
separate, supporting reading: the community reading's parts at a smaller size and in quieter
neutral ink rather than a temperature, so it never reads as Gameómetro's own. The white
[IGDB mark](../../frontend/public/assets/providers/README.md) stands in the meter's place and is
the only link, to this game's source work. The mean keeps its /10 scale, followed on the same
line while it fits by `Basada en N valoraciones` with the exact count, including one; in the
paired band below the desktop the count takes the counts' shared row. It never takes a band or a temperature, and without a valid mean and positive count the whole reading is omitted, never
filled, whatever the community reading shows. Loading reserves no place for it.

**Tu puntuación** stays secondary to the source readings: one compact reading in the
language of `Mis puntuaciones` — its G-meter, the label, the band in words and the value, or
`Puntuar` without one — is a night control, edged in ice or, once rated, in its temperature, so it
reads as the visitor's own action rather than another metric. Unavailable, it rests unfilled
with a quieter edge and a resting meter, never dashed, and its reason follows beneath. The
control has a caret that opens an anchored, non-modal,
opaque panel, its top edge in the reading's temperature, overlaying what follows, so nothing
below moves. The panel holds the prompt, the shared keypad
and its key (`1 · Congelado`, `10 · Ardiendo`), and a quiet **Eliminar puntuación** once a rating
exists. The scale is a labelled group of ten circular buttons; picking a value saves it at once
(create or update through the conditional contract) and closes the panel, returning focus to the
reading, so there is no separate confirm action; picking the current value only closes it.
Opening focuses the pressed value or the scale's stop, and a disabled scale hands focus to the
delete action. Arrow keys only move focus inside the scale, so browsing never saves by accident;
Escape, an outside press or moving focus away close the panel without a command. The current
rating is the one pressed value (filled with its temperature, with a ring and a close light, not
colour alone),
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
**Modos de juego** follow as scannable tags carrying the catalogue's names exactly as served.
Beside the calendar on a wide sheet the metadata is one column; spanning a tablet sheet it uses
two, with company roles above taxonomy; phones use a natural single column. Post-MVP (#325,
implemented; awaiting owner review), below 1024px each field is a specification line, its label
in a fixed column with the value beside it on the same baseline, and the label returns above its
value when the sheet is narrower than about 19em of its own text, at 320px or under enlarged text.
Spacing and typography separate fields without inner cards. Unknown facts are omitted, never filled; when no
metadata is known, the sheet proceeds directly to its calendar without empty placeholders.

**Fechas y plataformas** is a lower-level heading inside the spec sheet, set in the same display
voice as **Información del juego**, set apart from known metadata by its own column or position,
with no surface of its own; its lines are a ledger between hairlines. It lists the
presented release of every platform and region, so a visitor reads every date without choosing
anything first; no selector is invented and none is needed.

Post-MVP (#325, implemented; awaiting owner review; it replaces one row per platform and region
in the API's presented-release order), the ledger has one line per release *context*: releases
whose date and precision, region, status, stage, review status and verification level are all
identical share a line, which states that context once and then lists every platform it covers,
so four platforms launching together in one region read as one date. Grouping is presentation
only: no Release is merged, rewritten or dropped, a value that differs between releases is never
stated as shared, and a release whose context is its own keeps a line of its own, so a
cancellation on the same day as a launch, a delay or a different stage always reads apart. Lines
run as a calendar, from the earliest period their date can mean to the latest, with unknown dates
last and lines that start together in the API's order; platforms keep the API's order within a
line. Each line opens with the date at its own precision in the readout voice and in ice, with
the status in words opposite it, led by a marker whose shape differs per state (a filled stop once
released, an open ring while ahead, a diamond for a delay, a bar for a cancellation or an unknown
status); then the region mark and name and the known normalized Spanish stage as quiet secondary
text (an unknown stage is left unstated), beneath the date on phones and beside it on a wide
ledger; then the platforms, each mark in one shared slot so the names align, in as many columns
as fit and one beneath the other when none do, a single platform taking the whole line. A pending
review is flagged under the line's platforms with a warning marker that does not rely on colour,
and verified evidence is credited there. Synchronization age is internal operational evidence: no
line, notice or source line states it, and the source attribution names only the sources. Lines
are a list whose values are named for assistive technology — the date, status, region, stage and
the list of platforms each line covers — never a table. Further records of the same platform and
region stay whole in a closed native disclosure, `Otras fechas registradas (N)`, where `N` counts
those records and their lines follow the same grouping, and whose summary carries the review
notice when any of them is pending review. The block closes with its attribution: the
release sources. Further provenance timestamps and the
provider-only verification default stay out of the primary hierarchy. A game without releases
says “No hay lanzamientos comerciales registrados.”.

## Personal ratings collection

`Mis puntuaciones` reuses the catalogue shell, page opening, cover treatment, tokens and
native controls, and reads as a thermal ledger: one line per rated game between hairlines, its
left edge in the temperature of the visitor's own score, so the column reads as the collection's
thermometer while every line still states its number and band. Each line holds the cover and
title as native links, up to two localized genres beneath the title, and the editable
**Tu puntuación** reading. No summary, developer, publisher,
game mode or release-specific platform/region context is shown: these are Game ratings.
There is no **Ver ficha** CTA and the complete row is never a link. Post-MVP (#279,
implemented; awaiting owner review), the read-only community and IGDB readings follow the
[discovery score rules](#featured-releases) directly beneath the genres: secondary context in
the game's column, never a second panel competing with the personal score. A row shows only its
valid sources and omits the readings without any; the IGDB attribution is its only link besides
the title. Post-MVP (#325, implemented; awaiting owner review), the ledger sets them a step
larger than cards so both sources compare without zooming, stacked with marks and means aligned
until the game's column holds them side by side. The cover's provider credit stays visible and
linked as one quiet line in the readout voice beneath the cover. On phones the cover anchors the
line and the game, **Tu puntuación** and its dates read down one column beside it; when that
column is narrow for its text — the narrowest phones or enlarged text — the score and its dates
take the full line beneath the game. From 760px the score keeps the row's right edge, centred
beside the game and its dates. Calendar dates are quiet secondary text without hours: one
**Puntuado el …** when creation and update have the same visible date; otherwise **Actualizado el
…** leads, followed by **Puntuado el …**. Each date stays whole, and one that wraps drops its
separator rather than starting a line with it. The personal score is a Gameómetro reading — its
G-meter, the **Tu puntuación** label, the number at reading size and its temperature in words on
the number's baseline, wrapping beneath it only when it must — in the language of the game page:
it is the reason the row exists, so it stays more prominent than the source readings. The private
filters are night controls under the title, separate from the header's public catalogue search:
one search field whose **Buscar** action is joined to its right edge, then one **Orden** selector
that names the field and the direction together (**Más recientes**, **Más antiguas**, **Título
A–Z**, **Título Z–A**, **Nota más alta**, **Nota más baja**) and the page size. Searching still
waits for **Buscar** or Enter; an order or page size applies on selection and returns to the
first page. Phones set the search on one row and the order and page size on the next, their
words carrying the value without decorative icons; below 360px the order shows its direction
arrow in place of its word. From 720px the three share one row. Pages hold 10 ratings by
default, with 10, 20 or 50 to choose from.

Post-MVP (#210), the score reading is the row's only maintenance control: a night control with
a caret, set apart by a hairline, that opens an anchored, non-modal, opaque panel whose top edge
takes the pending value's temperature. The panel spans the whole line beneath the reading on
phones, hangs from the reading's left edge while the reading sits under the game, and from its
right edge once the reading has a column of its own. It overlays the rows below
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

## Catalogue administration

Post-MVP (#247, implemented; awaiting owner review), `/administracion/catalogo` is the
operator's ledger of catalogue acquisition, not a product screen: plain night without a
stage, art, G or score instrument, under the shared header and page opening (**Administración
del catálogo** over **Sincronización y cobertura**). Players never see it; a signed-out
visitor gets the sign-in notice and a signed-in account without the role a warning notice,
both from the server's answer rather than from hidden navigation.

Two tabs split the page, **Ejecuciones** (the default) and **Cobertura**: a labelled
tablist under the opening whose selection lives in the URL (`tab`), operated by click,
arrow keys, Home and End, and marked by ink, weight and an ice rule rather than colour alone.
Coverage is read only while its tab is open.

Post-MVP (#248, implemented; awaiting owner review), **Ejecuciones** opens with **Solicitar
sincronización**: a lead saying the run is the ordinary reconciliation and answers once it
starts, then two labelled native date fields, **Desde** and **Hasta**, and the primary
action, stacked on phones and in one row from 620px, followed by the limits in words (both
dates included, at most 184 days between 1950 and 2099, one synchronization at a time,
scheduled ones included). The page checks those limits before sending and the server
decides; an invalid date is marked invalid, described beside its field, and takes focus.
While any run is in flight the action is disabled with a quiet line saying why. When a
request settles, focus moves to one status region beneath the form whose notice names the
outcome in words: an accepted request reads **Sincronización en curso** until the ordinary
status shows its result, then the finished run's result and explanation in the same words
as **Ejecuciones recientes**, tone following the result; a run in flight elsewhere, missing
provider access, a lost CSRF proof (renewed for the retry) or a lost role is a `warning`
that says nothing started; a lost session offers sign-in; any other failure is a `danger`
notice with its support reference. While the requested run has not finished the status is
read every five seconds instead of fifteen.

In **Ejecuciones**, **Ejecución actual** states the run in flight, its interval, start and last heartbeat, or
calmly that none runs, with an **Actualizar estado** action; a stalled run adds a `warning`
line, and while a run is in flight the page reads its status again every fifteen seconds.
**Ejecuciones recientes** lists the retained finished runs newest first as hairline cards:
the result in words, its start and duration, interval, stable outcome code, what the result
means for coverage, and the Game, Release and provider-request counters. An abandoned run
says it has no counters rather than showing zeros. The lead states that failed,
interrupted or abandoned runs never change recorded coverage.

**Cobertura** opens with the three terms — covered, never covered (since coverage has
been recorded, whatever Games the catalogue already holds), freshness —
then a year-and-month overview with drill-down rather than a heatmap: a decade's years,
the selected year's months and the selected month's periods, all in the URL (`tab`,
`year`, `month`) and drawn from bounded coverage reads. Year and month tables state the coverage in
words, the covered days, the oldest complete scan and freshness ("Reciente" within seven
days, otherwise its age), plus a note when a covering run had failed Games; a summary the
server's bounded answer did not reach says so instead of claiming never covered. Each status
pairs its words with a marker whose shape differs (filled, half-filled, ring, dashed ring;
a square for a failed or abandoned run), so no state depends on colour, and forced colours
keep the shapes. Tables sit in a labelled, focusable region that may scroll sideways on
phones while the page never does; their cells keep words whole. Choosing a year or month is
a native link, marks it with `aria-current`, and moves focus to the level that changed.

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

Every one of these states is a message, not a region: render it as a width-capped plate on
night, aligned with the start of the content it replaces rather than stretched across it, whose
left rule and kicker take the state's tone (ice for information, a quieter ice for empty,
`warning`, `danger`). Only the tone changes between them. The notice owns its recovery and its
way out: when it replaces a whole route, as on the game page, its heading is the page heading
and the retry and return actions sit inside it. Filter placeholders appear only while loading;
a failed read omits the filters its response would have supplied rather than showing empty
controls.

Use the existing live-region semantics. Do not add announcements to individual skeletons or
replace a contract state with decorative success content.

## Verification

Apply the [risk-based validation policy](delivery-lifecycle.md). Component tests protect URL,
state and interaction semantics. Focused browser checks own responsive reflow, CSS, keyboard
focus, forced colours and automated accessibility evidence;
`frontend/tests/identity-rollout.spec.ts` and `frontend/tests/featured-design.spec.ts` assert the
identity's instruments and the absence of the retired language. For material visual changes:

1. render representative current data at 1320px, 834px and 390px, with reflow at 320px;
2. include long titles, varied date precision, review status, provider artwork and the
   product fallback;
3. compare the captures with Destacados and with the screen's previous captures;
4. check all affected loading, empty, catalogue-not-ready and failure states; and
5. run only the focused component, browser and documentation gates justified by the change.

Do not add a component library, Storybook, theme engine or speculative abstraction for this
visual direction.
