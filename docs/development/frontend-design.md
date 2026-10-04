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

- Use the full-width header with the Gameómetro mark and wordmark, primary
  navigation, integrated catalogue search and the server-owned account control. It floats
  over the page's stage on a soft scrim rather than a solid bar; navigation is a glass
  segmented control whose current item carries the aurora fill, and search and account are
  glass pills. Post-MVP (#151), the navigation is named **Lanzamientos** and holds
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
  shared dimensions and breakpoints, and the [foundation](../../frontend/src/styles/foundation.css)
  owns the shared glass, glow, aurora and grain values. Use `canvas`, `surface`, `raised`,
  `ink`, `muted`, `subtle`, `accent`, `aurora`, `line`, `warning` and `danger` roles through
  those conventions instead of placing palette values in JSX.
- Use a near-black ink-blue canvas, glass surfaces and one signature light: moonlit
  periwinkle turning to aurora violet, used for the current navigation item, primary actions
  and title accents. Scores speak the thermal language of [product identity](#product-identity-and-thermal-language)
  instead. Otherwise warm light belongs to the art alone; `warning` stays reserved for review
  and freshness states.
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
  primary keyboard stop: the title link on a release or search result card, **Ver ficha** on rating
  cards.
- Keep reduced-motion and forced-colour support. Do not require hover, animation, a fixed
  desktop width or a sticky header that can obscure focus.

## Featured releases

Post-MVP (#151), **Destacados** follows the owner's approved featured-releases reference
within this language. It opens on the release windows' night stage. The kicker
**Selección del mes** shares a row with the month selector, one glass pill holding the
previous-month link, the represented month beside a calendar mark and the next-month
link; the current month is the landing route itself, and on the narrowest phones the
pill wraps under the kicker. The selector offers only the months of the current calendar
year, which the API's trusted date states: January's previous step and December's next
step stay in place as disabled links (dimmed, announced as unavailable, outside the tab
order), and the pill waits as a placeholder until a response states the current month. A
requested month the API rejects, malformed or of another year, returns to the landing
route; another year's selection is never presented. The only `h1`, **Lanzamientos _del mes_**, is set larger than
the list titles, and beside it, after a hairline, a lit spark states the rule:
"Selección automática según atención actual", or the date attention was last observed
when the ranking is stale. Popularity is attention: never call a selection the best, a
winner or a quality judgement, never show its value, and badge it with the spark, never
a crown, trophy or medal.

Every featured frame is landscape and shows the item's `featuredImage` as the contract
says: a `fill` image is cropped to the frame, while a `contain` image, such as a portrait
cover, is shown whole as a poster with its own corners, ring and glow over its own light,
blown up and blurred. An image is never stretched, and a portrait cover is never cropped
to landscape. When an image fails to load, a card's frame steps down to the provider
cover shown whole, then to the product-owned landscape fallback; the hero steps down to
the designed fallback and keeps its cover, shown whole, only as the last resort. The
composition never breaks. Provider images are never resampled or stored.

The hero is the month's featured release, cinematic. Key art keeps its subject clear of
the logo space at its left, so the copy takes the hero's left and the landscape image
spans the rest to the right edge, filling its frame without stretching, with a crop
weighted toward the upper part of the frame, where key art keeps faces. The image fades
in behind the copy without a hard panel edge and carries a light grade (a touch of
contrast, a faint grain, shade at the top and the bottom, a soft vignette), so a bright
illustration reads as lit key art and dark art keeps its detail. The whole card is lit by
the same image, blurred and dimmed. On phones the art sits above the copy, and the badge
and the title settle over its faded lower edge. Keep the rounded frame, quiet border and
glow. A hero without landscape media shows the designed fallback, lit by the game's own
cover, never the cover itself.

The copy holds the **Lanzamiento del mes** badge, then always the canonical title as
Gameómetro's own wordmark, never a provider logo. The name is set in Mona Sans' widest
heavy cut, in capitals, cast in moonlit silver with a glint. The hero's image may tint
that metal only through a blend that keeps every channel light, under a restrained
shadow and lilac glow. A subtitle that the canonical title introduces with a colon or a
spaced dash follows in the lit serif of the page's accents; the lockup drops that
delimiter visually, while the heading's text and accessible name keep the canonical title
exactly. The wordmark's size follows the name, so its longest word fits one line and the
whole name three lines within a fixed range, with balanced and emergency wrapping. Then one
meta row (the presented release's compact date, its platforms as marks separated by dots,
with an unrecognized platform keeping its name, and its region), chips for that release's
lifecycle and any known stage on their own row, the overflow control for further releases,
then the hero's one action, **Ver ficha**, at its usual size; **Ver todos los
lanzamientos** below opens the release lists. A reference's description and genres have no
contract data and stay omitted.

**Otros lanzamientos _destacados_** follows, with **Ver todos los lanzamientos** (Recientes)
and up to five wide artwork cards straight on the stage, not inside glass. Each card's
landscape frame carries the date chip and context-selected media under `FEAT-003` in the
[domain model](../architecture/domain/mvp-domain-model.md). A screenshot may carry the
game's existing decorative logo; artwork is shown as it is. Keep the overlay restrained
and centered cover cropping stable across breakpoints. Beneath the frame come the title on one line (its full text stays the link and
its tooltip) as the only keyboard stop, then one release line with up to three platform
marks, the exact `+N` and the region, and the overflow control. Fewer games mean fewer
cards. An unranked or empty month replaces the hero and the row with an informational or
empty notice that names the month and links to Recientes.

From 1024px the copy and the art share the hero, sized so the hero and the whole row open
within 1320×900, and the row of cards has five columns. Tablets keep both sides with the
art starting further right, so the title never sits on its brightest part, space the meta
row instead of dividing it, and lay the row in three columns. Phones stack the 16:9 art
over the copy, with the badge and the title over its faded edge, and give **Ver ficha** the
full width. Their meta row sets the date, the platforms and the region as three groups
spread across one row whenever they fit, the platforms in the cards' compact form (the
first two marks and the exact `+N`, every name still announced); a group that cannot fit
moves whole to a second row, so no group ever breaks or shrinks. Below 480px each card
takes the full column. Forced colours keep the art beside or above the copy, never behind
it.

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
left, immediately above the community reading and the compact personal reading. The right
column opens with **Ficha del catálogo**, the canonical title and the summary as editorial
content, without a separate summary card or visible repeated heading. **Información del juego**
follows within that column as one card at its natural height, with no empty rows, forced
stretching or sticky facts. Its **Fechas y plataformas** subsection uses the card's full width
below the metadata. Tablets keep the cover beside the title and summary, then give the two
readings a shared row with comfortable
widths before the unified information card spans the page below. Phones open with the title,
then cover, both readings, summary and information, with the calendar nested last. Source order
follows this phone reading order, so keyboard navigation never jumps back to the identity after
the summary. The title uses the wide display cut at a restrained scale,
following both the available editorial width and the viewport, stepping down twice for long
names and wrapping even an unbroken word. Normal titles retain their display scale where space
allows. Aliases follow in the lit serif. For a mouse, the cover leans toward the pointer and
catches a glare; touch, pen, reduced
motion and forced colours keep it still. Card-level ratings on release and search results are
not part of the approved MVP screens until an owner decision schedules that work.

The community reading is the most prominent: the G-meter beside the large mean, its temperature
in words and `Basada en N puntuaciones`. “Sin nota todavía” keeps its place with a smaller meter
and tighter spacing, giving the empty reading less prominence; an unavailable reading retains
room for its explanation. The distribution remains a backend/API capability and is
not rendered on this page. **Tu puntuación** stays secondary to it: one compact reading in the
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
source and names a language other than Spanish (`Texto original en inglés · Fuente: IGDB`),
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
left unstated), and a status chip that states the status in words. A pending review and stale
local data are flagged on their row with a warning marker that does not rely on colour, and
verified evidence is credited there. Rows are a list whose values are named for assistive
technology, never a table. Further records of the same platform and region stay whole, one row
each, in a closed native disclosure, `Otras fechas registradas (N)`, whose summary carries the
review notice when any of them is pending review. The block closes with its attribution: the
release sources and the latest synchronization day. Further provenance timestamps and the
provider-only verification default stay out of the primary hierarchy. A game without releases
says “No hay lanzamientos comerciales registrados.”.

## Personal ratings collection

`Mis puntuaciones` reuses the catalogue shell, page opening, cover treatment, tokens and
native controls. Present cover-led glass rows, each washed by its own cover's light, with
game navigation, a clearly personal score and the two rating timestamps; a row reads game,
then score. On phones the score spans the row beneath the cover and title. The personal score
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
