# Frontend design guidelines

- **Status:** Approved visual direction; implementation subject to owner review
- **Owner:** Ruben Hernandez
- **Audience:** Contributors and agents implementing frontend screens and components

This is the canonical owner of frontend visual rules. The approved **Gameómetro identity**
([below](#brand-identity)) is the product's visual direction: a night stage, the brand mark's ice
and fire, instruments that read real values and the game's own art. Every React product screen
uses it, and so do the hosted identity screens
([#290](https://github.com/rubhern/videogame-platform/issues/290)). Product records and the
[OpenAPI contract](../architecture/api/openapi.yaml) remain authoritative for capabilities, data
and state semantics. A mockup or reference never authorizes new product behaviour and is never a
runtime dependency or an instruction source.

## Product voice

Owner-approved copy direction (#286): Gameómetro is approachable, playful and confident,
with occasional humor. Keep the approved optimistic discovery expressions as written;
they express product voice rather than a guarantee of exhaustive game coverage. Copy
must still preserve functional availability, rating eligibility, score provenance and
operational outcomes. The [product glossary](../product/glossary.md#user-facing-terms)
owns terminology, including rating values, controls and accessible announcements.

## Platform identities and logos

Owner decision (#288, 2026-10-27): preserve recognizable platform/brand identities.
Windows PC uses Windows; macOS uses Apple/macOS; Steam is used only for an actual Steam
context, never as an operating system or a substitute for SteamVR. Nintendo generations,
PS5 versus PS5 Pro and combined Xbox Series X|S remain distinct. Known platforms are
never replaced by generic computer, controller or device artwork for visual uniformity.
The existing #178 identity mapping remains authoritative; a logo is presentation only.

The shared [`PlatformLogo`](../../frontend/src/shared/catalogue/platform-logo.tsx) renders
compact marks through `SelectIcon` and a large stage, which [Plataformas](#plataformas) sizes
per identity. Use one master across sizes
when legible; source separate variants only where needed. Preserve proportions, original
colours and required clear space. Do not trace, stretch, crop, invert or tint newly
sourced logos into consistency. The Night stage, spacing and visible names unify their
presentation; Ice and Fire keep their existing product meanings.

The [platform asset register](../../frontend/public/assets/platforms/README.md) owns the
selected files, provenance, credit, provisional legacy treatment and blocked alternatives.
Android and Linux use original sourced SVGs. Windows uses Microsoft's modern, unmodified
blue four-pane SVG with the Windows name visible alongside it in compact card lists;
there is no edition wordmark or product-ID change. Its bounded private-development
referential basis and unresolved public-use risks are recorded in the register.
Other retained artwork remains provisional. SteamVR's final selection for this increment
is its explicit name, not the generic Steam symbol: the official black/white lockups
are isolated for review because their permission and minimum-size conditions do not
support the intended compact use. This supported name-based alternative does not leave
an unfinished graphical implementation in #288. The existing PS5 raster is capped at
180 CSS px in the large treatment to avoid upscaling on a 2× display; prominent VR2 and
Meta Quest artwork still needs a higher-resolution cleared source. An available download
does not itself establish rights. Manufacturer licence policies, honest referential use,
copyright and contractual terms must be distinguished; private-development acceptance
does not authorize public release. Public-use review follows the existing release gates
and does not keep this implementation issue open after owner acceptance.

Every containing control, heading or list keeps the full product-owned name; logos are
decorative and never add duplicate screen-reader names. Failed newly sourced artwork
keeps the known brand name. Only genuinely unknown platforms use the named generic
controller fallback. The shared footer exposes required artist/licence notices through
the native **Créditos de marcas** disclosure. Forced colours preserve names independently
of the artwork. Review compact contexts and large specimens at 320, 390, 834 and 1320px,
including 2× density, keyboard access and the accessibility tree.

## Brand identity

- **Status:** Approved by the owner on 2026-10-16 and implemented on Destacados, the shared
  header and the footer. Post-MVP (#318, implemented; awaiting owner acceptance), both release
  windows, search, the game page and `Mis puntuaciones` adopt it with their states.

Gameómetro looks like a precision instrument set in a cinema: dark, quiet, exact, and lit by the
games themselves. The [brand mark](../../frontend/src/shared/brand/gameometro-mark.svg) is its
source: an ice G whose fire needle leaves through the G's open mouth.

**Brand families.** Three families carry the identity; neutrals, functional states and the thermal
roles stay alongside them. [Global styles](../../frontend/src/styles/index.css) own the values.

| Family | Role | Use it for | Never for |
|---|---|---|---|
| Night (`night`) | Depth and stage | Canvas, reading shades, transitions between art and interface, the instrument's channel | Flattening art under one uniform dark layer |
| Ice (`ice`, `ice-light`) | Measure, progress, reading | Elapsed time, scales and their numerals, the selected state and focus where it measures, primary entry actions | Decorative fills, every link, a score's temperature |
| Fire (`fire`, `fire-light`) | A reading or an event | Needles, a release's date marker, the current section's short rule; in the [site footer](#site-footer), its column rules and the slogan's last line | Buttons, backgrounds, a "hot" score, warnings |

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

**Day bar.** Post-MVP (#285, implemented; awaiting owner review), the release explorer unrolls a
frame of days the same way, one division per day under the title: the days gone by the API's
`evaluatedOn` in the same ice, `hoy` beside today's numeral, the explored range raised in a band
of ice over the rail, and one fire needle, with its pivot, on each day a release on the page falls
on. The frame is the explored range when it spans a week or more, and a shorter range's
Monday-to-Sunday weeks otherwise; a frame the visitor was exploring stays while it still holds the
range, so picking one day of a month keeps the month's scale, through filters, pages and a return
from a game page. Every day is a native button below its division that explores that day alone; the one selected day
is ringed in ice and announced as pressed, and days of the frame outside the range quiet down. The
days form one toolbar with a single tab stop (the selected day, else today inside the range, else
its first day) whose arrow keys, Home and End move between days without exploring; Enter or Space
explores the focused day. Each day reads in full to assistive technology (`viernes, 14 de agosto
de 2026`), with `hoy` and `en el periodo` where they apply; a week or two adds the weekday's
initial under each numeral. Day columns keep at least 30px, so a frame wider than the screen
scrolls sideways inside the bar, centred on the explored day, and never scrolls the page. When the
range is narrower than its frame, **Ver todo el periodo** follows the bar and explores the whole
frame again. Pointing at or focusing a release lifts its needle and quiets the others. Releases
known only to a month, quarter or year take no needle; the bar never invents a day. The rail is
decorative: the readout beside the title and each card state the same dates in words.

**Where it applies.** Every React product screen uses the families, the stage and the vocabulary
where they carry meaning, and each keeps its own composition: Destacados is the period's stage,
the release explorer a cover wall under its day bar, search a catalogue index, the game page
its own cover's stage, `Mis puntuaciones` a thermal ledger, TOPómetro a podium over a
ledger Plataformas a showcase of platform stages and a platform's
Explorar catálogo the release explorer's cover wall under that platform's own mark. No screen repeats the temporal G or
becomes another landing hero. Glass panels, aurora washes, the lit serif accent, tracked mono
kickers and spark badges are retired: no React or hosted identity screen uses them, and new
work never brings them back.

## Mandatory composition

- Use the full-width header with the Gameómetro identity, primary navigation, integrated
  catalogue search and the server-owned account control, under the
  [header and navigation](#header-and-navigation) rules. Keep catalogue search prominent within
  the header and omit the explanatory context strip.
- Align header and main content to the shared `page-container`, capped at 1320px with 28px
  desktop, 24px tablet and 16px phone gutters. The release explorer opens with the title and
  the API's evaluated range as its readout, the [day bar](#brand-identity), then its controls in
  two rows — the range (**Desde**, **Hasta**, **Aplicar** and, for a named range, **Esta
  semana**) with **Incluir fechas aproximadas**, then **Ordenar por** and the facets — then the
  cover grid and a closing row. The closing row places the result total on the left and the
  pager on the right, with both pager links on one row on phones; it omits a repeated page
  position and is absent without results. Moving between Destacados, the explorer and the calendar belongs
  to the Lanzamientos disclosure; there is no recent/upcoming switch (#285). Inside **Lanzamientos**, a
  **Lista | Calendario** switch in Destacados's **Semana | Mes** voice closes the title's row and
  moves between the explorer and the [release calendar](#release-calendar) (#250), carrying the
  filters and the month; **Lanzamientos** stays current in the navigation on both.
- Keep the current screen's title as the only `h1`: **Explorar lanzamientos**, or
  **Destacados de la semana** / **Destacados del mes** on Destacados. The explorer has no context
  line. The API-evaluated range follows the title as the instrument's readout in ice, at the
  precision the API states (`10–16 ago 2026`, `28 sep – 25 oct 2026`, `14 ago 2026` for one
  day), while assistive technology reads it in full (`Del 10 de agosto de 2026 al 16 de agosto
  de 2026`, `El 14 de agosto de 2026`); omit the evaluation-date label. Never replace API dates
  with a hard-coded relative period. The readout and its bar wait as quiet placeholders only
  while a read is under way; a failed read shows neither.
- Treat 1320px, 834px and 390px as the representative review widths, while supporting reflow
  from 320px. The release explorer's cover grid uses six compact columns at desktop, about four at
  tablet and two at phone widths; search's index uses three, two and one. Do not allow page-level horizontal overflow; the filter and navigation
  rails may scroll horizontally on narrow screens.
- Beyond the approved featured releases ([below](#featured-releases)) and
  [TOPómetro](#topómetro), do not add a ranking,
  editorial description, publisher, studio, new release window or destination solely
  because it appears in a reference.
- The release explorer uses the plain night canvas, without decorative artwork behind the
  header or filters, on phones and wider screens. It opens with the range, order and facet
  controls and twelve results per default page so six columns form two rows on wide desktop.
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

## Header and navigation

- **Status:** Post-MVP (#358, owner-requested; implemented, awaiting owner review). These
  responsive rules supersede the earlier phone and tablet header layouts.

**Surface.** The header floats over the page's stage on a soft scrim rather than a solid bar, in
the [brand identity](#brand-identity): the current section carries a short fire rule, and search
and account sit on night with ice edges. Its labels keep their size at every width; when space
runs out the layout changes, never the type.

**Sections.** The primary navigation holds three sections, in this order on every width and named
in the [product glossary](../product/glossary.md#user-facing-terms):

1. **Lanzamientos** (#385), one disclosure containing **Destacados**, **Explorar** and
   **Calendario**, in that order. The native button exposes its expanded state and controlled
   panel; native links use Tab, Enter and normal browser navigation. Escape closes it and returns
   focus to the button; leaving it, pressing outside or navigating closes it. The active release
   section keeps the short fire rule and is announced as current; its active destination is
   highlighted and announced as the current page. Preserve the account panel's surface, spacing
   and focus language.
2. **TOPómetro** (#386), a native link of its own outside the disclosure, so leaving the
   disclosure for it closes the disclosure. It is named by the TOPómetro lockup at the labels'
   cap height, dimmed like a muted label and lit when hovered or current.
3. **Plataformas** (#358), a native link to the [Plataformas](#plataformas) index (#287), current
   on it whatever platform it has open.

A section whose page is shown carries the fire rule and `aria-current="page"`; **Plataformas** also
carries the rule, as `aria-current="true"`, on a platform's destination such as
[Explorar catálogo](#explorar-catálogo).

**Account.** For an authenticated session the account control provides `Mi cuenta`,
`Mis puntuaciones`, **Tus estadísticas**, **Mis plataformas**
([preferred platforms](#preferred-platforms), #295) and CSRF-protected logout, and
**Panel de administrador** only when the session names that capability (#247). Post-MVP (#162),
anonymous browsing exposes primary `Iniciar sesión` and secondary `Crear cuenta` links that start
BFF/OIDC navigation directly to the hosted Gameómetro identity screens; discovery stays anonymous
and the inline rating authentication boundary remains available.

**Desktop, from 1024px.** One row: identity, the three sections, search and account. Search
gives way first, from 680px down to 200px, before the sections lose anything; the compact mark
replaces the lockup until 1180px so the row keeps that minimum.

**Tablet, 720–1023px.** Two rows: the lockup and the account control above, the sections and
search below, where search takes the remaining width. The anonymous entry links stay visible.

**Compact, below 720px.** One row: the lockup, never the lone mark, then search and the **Menú**
button. Below 620px search is an icon that opens the existing catalogue search in a
keyboard-accessible dialog; from 620px it is the field itself. The visible sections and the
account control are absent: the menu carries both.

**The menu.** A disclosure, not a modal: a native button named **Menú** with its expanded state
and controlled sheet. Its glyph is a long and a short tick of a scale that cross into a close
mark while it is open; a small ice point on it shows a signed-in session. It opens an opaque night
sheet under the header row, with an ice top edge, over a scrim that dims the page and takes the
tap that closes it, so nothing beneath acts; the header row stays usable above the scrim. The
sheet holds, in reading order:

- the `Principal` navigation: **Lanzamientos** in the page titles' wide voice with its three
  destinations beneath in Destacados's **Semana | Mes** voice, then **TOPómetro**, by its lockup,
  and **Plataformas** as full-width rows in that voice, each with a chevron;
- the account, apart from discovery in a dock on its own surface: **Tu cuenta** with
  `Iniciar sesión` and `Crear cuenta` side by side, or **Mi cuenta** with its avatar and the
  account actions, logout last after a hairline.

Focus stays on the button when the sheet opens, and the sheet follows it in the tab order.
Escape closes it and returns focus to the button; leaving it, pressing outside, following a link
or showing another page closes it, and a link to the page already shown returns focus to the
button. A query change on the same page, such as the **Mis plataformas** checkbox on the
explorer, keeps it open. A sheet taller than the viewport scrolls inside itself.

**Motion and forced colours.** The sheet unrolls from the header row, its sections follow in
reading order and the glyph's ticks cross, all under the [motion rules](#depth-and-motion);
nothing moves under reduced motion. Forced colours outline the current page in `Highlight` and
return every header surface to system colours.

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
  titles as one phrase at Destacados's compact size and a game's canonical title as Gameómetro's
  wordmark; its condensed cut sets the instrument's readouts — context lines, periods, dates,
  labels and the reading of a temperature in words — and its regular width sets everything else.
  Titles carry no lit accent word. The Gameómetro wordmark is outlined from Mona Sans' wide heavy
  cut. Use the locally hosted assets with system fallbacks and `font-display: swap`. Font
  licences live under [public/assets/fonts](../../frontend/public/assets/fonts/README.md).
- Covers carry the catalogue rhythm. The standard cover frame has a 4px radius, a quiet inner
  edge and a restrained shadow; pointing at a card lights that edge in ice and underlines its
  title, as Destacados's cards do, and keyboard focus outlines the frame. Its ratio is the one
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

## Transactional email presentation

- **Status:** Owner-requested visual direction; implementation pending review (#323). No live rollout authorized.

The `gameometro` email theme uses a shared, table-based HTML layout and narrow
per-flow adapters for recovery, verification, administrative actions and SMTP tests.
Keycloak supplies action URLs, action lists and formatted expiration values; its
plain-text renderers remain inherited. The [message bundle](../../docker/keycloak/themes/gameometro/email/messages/messages_es.properties)
owns Spanish text in both alternatives. HTML interpolation escapes values; the
presentation never constructs tokens, changes expiration or dispatches account actions.
Verification wording also covers a manual request when signup verification is optional.

The owner's email reference directs a dark reading surface, clear system typography,
blue security accents and a prominent orange action. This email-specific orange CTA
is explicitly requested; the [product identity](#brand-identity) owns other interfaces.
Use a bounded single reading column, presentation tables, inline styles and a fixed
Outlook desktop wrapper. Borders and solid colours carry the hierarchy without
cinematic artwork, gradients, external fonts, social icons or decorative security
claims. The native action appears as a button and an underlined alternative link;
plain text includes the complete URL. Keep the neutral greeting, product sign-off
and automatic-message footer without invented contacts or reply policies.

The approved [canonical logo](../../frontend/src/shared/brand/gameometro-logo.svg)
is rasterized by the existing Playwright-based [email logo renderer](../../scripts/render-keycloak-email-logo.mjs)
into the theme's PNG resource; geometry and colours remain unchanged. The owner
explicitly permits first-party static HTTPS images. The template uses
`${url.resourcesUrl}/img/gameometro-logo.png`, Keycloak's native email resource
mechanism, with an appropriate alt and a permanently visible product-name caption.
The logo is optional for comprehension and action; no SPI, CID, data URI, tracking
query or third-party image host is introduced. Keep the PNG synchronized with its
canonical SVG when that approved asset changes; #321 is not assumed approved.

Local development uses the local Keycloak origin; its resource links are not suitable
for external recipients. Before external delivery, the browser-facing Keycloak hostname
must generate absolute public HTTPS action and image URLs, and the existing
`/resources/*` route must serve the PNG anonymously. URL publication, proxy trust,
real certificates and deployed reachability remain with #275/#276; a theme must never
hardcode Docker aliases, localhost, a proposed domain or a separate image host.

The [isolated Mailpit review](../../scripts/review-keycloak-email.sh) checks native
flows, both alternatives, image bytes/reachability, responsive layouts, blocked images
and reduced CSS. Its additional synthetic HTTPS-hostname mode uses disposable local
TLS and reserved DNS; it does not prove a real public certificate or ingress. Only
validated static asset URLs may remain in captures; redact authentication URLs before
saving anything. Ignored local captures are review evidence, excluded from the portal.
Gmail, Outlook and Apple Mail may proxy/block images, recolour dark emails or ignore
cosmetic styles. Native inbox and assistive-technology acceptance remain distinct
from Chromium and automated accessibility evidence.

## Product identity and thermal language

- Post-MVP (#162), the first authentication screen is hosted by Keycloak. Post-MVP (#290
  iteration 1, implemented; awaiting owner review), its `gameometro` theme follows the
  [brand identity](#brand-identity) on every screen it renders — sign-in, registration and its
  terms, recovery, reset, email verification, information, errors, expired links and required
  actions. One night stage spans the page, painted in CSS into the document's background: ice
  light gathering behind the brand at the top left, quieter light behind the panel and along
  the foot, and the canvas grain, with no image, no fixed layer and nothing that moves. The
  form is an opaque night panel with a hairline and an ice top edge; its title is set in the
  page-title voice under a short fire rule, the screen's one warm accent, as the current section
  carries one in the navigation. Fields, buttons, links, messages and the terms plate follow
  [visual language and tokens](#visual-language-and-tokens) and the notices' tone edge; errors
  are stated in words beneath their field, never by colour alone. The story stays the product's
  approved copy: the claim in the title voice (**Tu criterio.** with **Tu historia.** quieter
  beneath it, no lit accent), one short invitation and the keypad's ice-to-fire key as ten steps
  under a readout label. From 1024px wide and 600px tall the lockup holds the top-left corner,
  the story the left column, held mid-screen while a long form scrolls, and the panel and its
  catalogue return the right column; narrower screens stack the lockup, the panel, the return
  and then the story, with nothing decorative beside the form. The brand appears once, as the
  canonical lockup; no G watermark is drawn, since a G without a value is a pattern to avoid.
  Use the canonical local Mona Sans, Gameómetro branding and Spanish-first copy in the informal
  voice. Inherit Keycloak's templates and account-flow logic: the theme adds only the story
  through the footer hook, copy, local brand and font assets and styles. Do not reproduce the
  catalogue header or introduce a React login screen. Keep registration prominent and
  validation and recovery readable. Art for the stage remains an optional later iteration,
  never a dependency of this composition.

- The user-facing product is **Gameómetro**, always accented in visible copy, the document
  title and branding (the [product decision](../product/assumptions-and-decisions.md) owns the
  name). Repository, package and other technical identifiers keep their names; use
  `gameometro` only where a technical identifier cannot carry the accent.
- The identity is the Tilde: a moonlit G whose gauge needle, in fire, leaves through the G's
  mouth at the angle of an acute accent, and a wordmark whose `ó` carries the same needle.
  The owner-approved integrated lockup (#321) uses the mark as the initial G, followed by
  outlined `ameómetro`, so there is one visual G. Accessible names keep **Gameómetro** in full.
  The canonical vector sources are the standalone mark and shared lockup in
  [`shared/brand`](../../frontend/src/shared/brand/); the favicon in `frontend/public` is the
  mark on a night tile with heavier geometry, and the touch icon is its raster. The lettering
  is outlined from the bundled Mona Sans; the mark is product-owned geometry. Keep the G ice
  and the needle fire, never draw the needle without its G, and do not restate the lockup in
  CSS or in other files. The header, footer and hosted identity screens share the lockup;
  compact headers keep the standalone mark. The header inlines the SVG so the needle can
  answer hover and focus.
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
  The [release calendar](#release-calendar)'s blue, yellow, orange and red day densities are
  their own roles (`density-1` to `density-4`, owner decision #250), never the thermal ones: a
  count of games is no score and no temperature.
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
  three of the covers the page presents; TOPómetro by its podium's covers; a game's page by its own cover. The release explorer
  keeps the plain night canvas under its opening and cover wall. Each image's hue, not
  its luminance, is blown up, blurred and capped in brightness, the copy's side stays in shade,
  and the light falls away into night before the content. It adds no request, stores and
  transforms no image, is hidden from assistive technology and takes no input. A product
  fallback never lights a stage; without art — before results, on a failed read or on the
  not-found route — the stage is plain night.
- Depth comes from light and shadow, never glass: covers and overlays carry a restrained shadow,
  overlays that carry controls are opaque night with a hairline and an ice or thermal top edge,
  and there is one easing curve, one overshoot for presses and three durations. Do not add a
  per-component shadow scale, a backdrop blur or a halo.
- Motion is purposeful and limited to transform, opacity and clip: Destacados's orchestrated
  entrance and parallax ([below](#featured-releases)); a quiet entrance for arriving content;
  the day bar's light running to today and its needles landing; a needle lifting for the
  release a visitor points at; TOPómetro's podium rising onto its steps; Plataformas's four
  stages rising in turn; a cover easing a little closer on hover; feedback on press and
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
- The full search results page (#188) reads as an index of games. An active query is the
  only `h1`, **Resultados para «consulta»**, under the **Búsqueda de juegos** context line, with
  no explanatory paragraph; before a query the page keeps **Buscar juegos**. The game total and
  page position sit above the index (for example, `153 juegos · Página 1 de 26`), and the pager below repeats the position before its previous/next actions. Each entry
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
  publisher and game modes stay absent. Release explorer cards likewise add only up to two
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
  frame: the cover grid for the release explorer, the index for search, the detail composition
  for a game page and ledger lines for the personal collection. Placeholders themselves stay silent; the
  screen keeps exactly one live status.
- Render platform and region choices from `availableFilters` in labelled select-only
  comboboxes, in one row of night controls after **Ordenar por** (**Fecha de lanzamiento**,
  **Hype**, **Puntuación Gameómetro**, **Jugadores IGDB**); the two score orders carry their
  sources' own marks — the Gameómetro G and IGDB's white attribution mark, proportional and
  never tinted — instead of a generic icon. On phones the order spans its row and both facets
  share the next one. The entire control opens its
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
- Below 620px the range, order, facets and approximate dates fold under one **Filtros y orden**
  night control, closed by default and announced as expanded or collapsed, whose line states
  what they hold (`Hype · 2 filtros · Fechas aproximadas`), so the first covers stay near the
  top; the day bar stays open above it. Wider screens never draw the toggle and always show
  the controls.
- The range is two native, labelled date fields in the night controls' voice; both bounds are
  inclusive, they fill with the API's evaluated range, and a range applies on **Aplicar** or
  Enter, never while a date is half typed. A range the API would reject is explained in place
  (`El día de fin no puede ser anterior al de inicio`, `Elige como máximo 92 días`) instead of
  being sent; on phones both dates share a row. A malformed or rejected range in a shared URL
  reads as the current week.
- **Incluir fechas aproximadas** follows the range only while the explored range reaches beyond
  the API's evaluation date: a native labelled checkbox in its own control as tall as the
  selectors, unchecked by default, whose focus ring outlines the control. It shares the range's
  row while it fits and wraps below it on phones. Toggling it keeps the other selections,
  resets pagination and lives in the URL; choosing a range that ends by today drops it, and a
  failed read keeps an active choice removable. Empty selections use the approved shared release
  message; this opt-in remains available without changing its meaning.
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
including query-driven pages. Release range, day, order and filter changes and Featured
week/month changes preserve context; switching discovery sections or submitting another
catalogue search starts at the top. Shorter results may naturally clamp the viewport to the
available height. The main-content landmark remains focusable for route changes and the
skip link, but never draws a page-sized focus outline; visible keyboard-focus indicators
remain on interactive controls.

Post-MVP (#285, implemented; awaiting owner review), a game page's way back names the screen it
was opened from — **Volver a Destacados**, **Volver a lanzamientos**, **Volver al calendario**
(#250), **Volver al TOPómetro** (#386), **Volver al catálogo** (#389), **Volver a resultados**, **Volver a mis puntuaciones** or **Volver a tus estadísticas** —
and returns to it with the same period, range, order, filters,
search and page, restoring that screen's reading position while the document keeps it. Every
link into a game page from those screens, including a header suggestion opened on them, carries
the origin in the history entry's React Router state, never in the URL, so a shared game link
stays clean; the origin survives a reload and browser history and is validated on every read, so
only a same-origin path to one of those screens is ever followed
([`return-context.ts`](../../frontend/src/shared/navigation/return-context.ts)). A direct link or
any other state returns to Destacados. The way back is a new history entry that restores the
origin entry's offset; Back and Forward keep their own behaviour.

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

Personal filters never enter the URL, history state or browser storage under
[personal ratings](#personal-ratings-collection). Post-MVP (#285, owner request), a return to a
`Mis puntuaciones` history entry, by its way back or by Back, recalls that entry's search,
order and page from document memory only, as saved offsets are; a new visit, a reload or any
other entry reads the default collection.
The initial default collection read is fresh while mounted and still refreshes on
mutation invalidation; leaving the page immediately discards its query cache.

## Site footer

Post-MVP ([#283](https://github.com/rubhern/videogame-platform/issues/283), owner-approved mockup;
implemented, awaiting owner review), the shared shell closes every product route with one compact
footer. The lockup leads, closely followed by the slogan on three short lines (**Un hombre
elige.**, **Un esclavo obedece.**, **Nosotros te ayudamos a elegir.**), set below a section title's
size so it never outweighs the logo, the last line in fire. Two navigation columns, **Gameómetro**
and **Legal**, each named by its title over a short fire rule, sit to the right from 720px wide,
with the identity a little below their titles, and beneath the identity on narrower screens. The
closing lines stand on a deeper night floor instead of behind a divider, with a faint ice glint
along its top edge: `© <current year> Gameómetro` beside **Datos de videojuegos: IGDB**, whose
underlined link opens IGDB, and then the signature *Ideado, desarrollado y mantenido por Rubén
Hernández* as the last line. Those are the footer's only fire and its only ornament. The legal
documents and **Contacto** follow the
[legal publication gate](data-protection.md#publication-and-product-integration), so production
shows the identity and the closing lines alone until then. **Sobre Gameómetro** links to
`/sobre-gameometro` ahead of its page by owner decision; until that page exists the route reads as
not found.

## Release calendar

Post-MVP (#250, implemented; awaiting owner review), **Calendario de destacados**
(`/lanzamientos/calendario`) is the second view of **Lanzamientos**, built by the owner's request
from a reference mockup. It reads one month at a time on the plain night canvas, from the current
month on, and is active only from today; [`UC-016`](../architecture/application/mvp-use-cases.md) owns its counting and bounds.
The title is the only `h1`, with the **Lista | Calendario** switch closing its row and the month
as the instrument's readout beneath (`Noviembre 2026`). Then one row: the explorer's
**Plataforma** and **Región** facets on the left, and on the right the month steps around up to
four month chips (`NOV 2026`), the served month edged in ice. The previous step at the current
month and the next one at the forward bound stay in place, disabled. Month, chosen day and
filters live in the URL (`month`, `day`, `platformIds`, `regionIds`); choosing a day replaces the
history entry, a month adds one, and a month the API rejects returns to the current month with
the filters kept.

The month is a traditional Monday-to-Sunday table whose weekday names head the columns; days of
neighbouring months stay as faint empty cells, so no weekday holds another month and no date
repeats. Every day of the month is a native button announced in full with its count
(`viernes, 13 de noviembre de 2026: 6 lanzamientos`, `sin lanzamientos`, `hoy`); a count is of
highlighted games; the full explorer can list more games for that day. A day with releases states its count beside a dot and takes its
total-Hype colour as a quiet atmosphere, its light rising from a lower corner over a faint ridge
of night, in four fixed bands of the summed Hype of all highlighted games that day: blue from 10,
yellow from 30, orange from 100 and red from 150 (owner request, 2026-10-21). A game contributes
once per day across its matching platforms and regions; `UC-016` owns aggregation and eligibility. [`release-calendar-model.ts`](../../frontend/src/features/releases/release-calendar-model.ts)
is the only mapping. The bands are an owner-requested exception to *fire never colours an
action* and to the thermal rule: they represent total Hype rather than release count, have no
legend and are not quality scores. The count stays visible; each coloured day's tooltip and
accessible description also name its interest level (`Interés bajo`, `Interés moderado`,
`Interés alto`, `Interés muy alto`) so colour is not the sole indication. Empty days stay plain night with a hairline. Days of the current month before today
keep only their quiet number, with no count, colour or action, and are announced as gone by;
today carries `hoy` and a fine ice notch. The chosen day is ringed in
ice, a shape as well as a light. One tab stop enters the month (the chosen day, else today, else
the first active day); arrow keys move over the active days by day and week, Home and End to the
week's ends, and Enter or Space chooses. Without an active day in the URL the first day with
releases is chosen, else none. Releases known only to a month, quarter or year never sit on a
day, and the calendar adds no line for them (owner decision); the explorer's **Incluir fechas
aproximadas** remains where they are listed.

The chosen day follows beneath under the section's short fire rule: its heading
(`Viernes, 13 de noviembre`), the count with `Mostrando 3` when more exist, **Ver todos los
lanzamientos del día** (the full explorer on that day in the same order and platform/region
context, including games outside the calendar's highlights) and up to three compact highlighted
rows. The explorer has no highlights filter. The preview reads the first games in Hype order,
with its page size capped by both three and the highlighted count.
Each row holds the cover in the standard frame, the title as its only keyboard stop with the
pointer target stretched over the row, the platform marks and region, up to two genres, the
review notice when the API requires it, the [discovery score](#featured-releases) readings and a
quiet way in. The calendar shows only the highlights defined by `UC-016`; Hype orders those games and is never shown or named in the interface.
The reference's wide landscape art and descriptions are not reproduced: the release read carries
portrait covers and no summary, and the explorer's cards omit summaries too.

Below 1024px the weekday names shorten and a day shows its count as a number beside its dot.
Below 620px the composition adapts rather than shrinks: the switch takes its own line, the
facets share one row, the month's name sits between its steps in place of the chips and the
readout, the weekdays become letters (`L M X J V S D`) while the full names stay the headers'
text, and each day is a compact square at least 44px tall holding its number and density dot,
its count kept in its name and in the chosen day's heading. The chosen day reads down one column
with **Ver todos** closing it at full width. Loading keeps the calendar's frame as placeholders
with one live status; an empty month is the shared empty notice with its filter recovery; a
catalogue still preparing, an unsupported filter and a technical failure use the
[server-backed states](#server-backed-states), and a failed day read is its own danger notice
with a retry. Forced colours drop the densities and keep the counts, the dots and the outlined
chosen day in system colours.

## Featured releases

Post-MVP (#151; weeks #329, implemented and awaiting owner review; named in #341), **Destacados**
is the featured route and implements the [brand identity](#brand-identity). It opens on the current
week; `DEFAULT_FEATURED_GRANULARITY` in
[`featured-search.ts`](../../frontend/src/features/releases/featured-search.ts) is the only place
that decides it. The heading is the only `h1`, **Destacados de la semana** or **Destacados
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
beneath the title on cards. Only **Más lanzamientos destacados** shows counts from 1000 as
`+XK`, using whole thousands rounded down (`3548` becomes `+3K`); the exact count remains in the
tooltip and accessible text. Sources wrap whole onto a second line only when they cannot fit
legibly, never shrinking or truncating text. Omit each unavailable source and the whole block
when neither has a valid mean and positive count; never invent an empty reading. Gameómetro
reuses the G-meter, thermal bands and labels, while IGDB keeps neutral ink. Both retain their
/10 value and their own vote count, including one vote. IGDB attribution links to that Game's
source work and is named **Jugadores IGDB**. An external score has no Gameómetro temperature.

**Más lanzamientos destacados** follows, with **Ver todos los lanzamientos** (the release
explorer on exactly the represented week or month, #285) and up
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
period and offers **Explorar lanzamientos** on the represented period; an unranked notice names the missing Hype data without a value, and a week's empty notice says that no release with a confirmed day
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

## TOPómetro

Post-MVP (#386, implemented; awaiting owner review), **TOPómetro** (`/topometro`) ranks the best
released games by one source of player ratings, under
[`UC-018`](../architecture/application/mvp-use-cases.md) and `RNK-001`–`RNK-004` in the
[domain model](../architecture/domain/mvp-domain-model.md). The owner's mockup is a reference,
not a specification.

**Opening.** The `h1` is the [TOPómetro lockup](../../frontend/src/shared/brand/topometro-logo.svg),
named `TOPómetro` for assistive technology: `TOP` in the mark's ice with an O read beyond its
top, the ring opening where the fire needle leaves, as it leaves the G, then `ómetro` from the
Gameómetro wordmark with its needle accent. It is outlined from the bundled Mona Sans at the
wordmark's width and weight; it is never restated in CSS. No claim, source note, ranked-games
count or method disclosure accompanies it (owner decision, 2026-10-26). The source selector stands
opposite on wide screens and beneath on phones: one night control parted in two native links,
**Jugadores IGDB** (the default) and **Comunidad Gameómetro**, each with its own untinted mark,
the chosen one lit in ice and announced as current. Only the URL's `source=gameometro` names the
community; any other value reads as the default. The stage is lit by the podium's covers.

**Podium.** Places 1–3 are an ordered list in reading order 1, 2, 3; from the phone up the
winner stands between second and third. Each place stands its cover on a step of night with an
ice lip over one shared floor, its number engraved in the step's face: the winner's step is the
tallest with the brightest lip and a numeral in the lockup's moonlit silver, the others outlined
in ice. Places differ by scale, height and light only, never by a gold, silver, bronze or any
colour that could read as a score, and no crown, medal or needle marks a place. Beneath the cover
come the title (the game link, its pointer target stretched over the place), the known years and
the platform marks, then the reading. Fewer ranked games show fewer places; nothing is filled.

**Readings.** The reading that placed a game is the page's protagonist: the value with `/10`, the
source and the exact count, never abbreviated, in the source's own word (`1 puntuación`,
`5.494 valoraciones`). A Gameómetro reading keeps the G-meter, its temperature in words and its
value in the band's own thermal colour; an IGDB reading keeps neutral ink and its white mark as
the attribution link. Phone podium columns stack the mark over the numbers so nothing is cut.

**Ledger.** Places from the fourth on follow the podium without a visible heading (`Clasificación`
for assistive technology): an ordered list of lines between
hairlines, each line the place's number in the wide heavy cut, the cover, the title as its only
keyboard stop besides the IGDB attribution, the known years and up to two genres, the platform
marks and the reading. As in `Mis puntuaciones`, a Gameómetro line's left edge takes its score's
temperature, so the column reads as a thermometer; an IGDB line keeps a neutral edge. From
1024px the platforms take a column of their own; phones stack the facts beside the cover.

**Continuation.** Each page holds twenty places, the first being the podium. Only **Cargar más
juegos** reads the next page; scrolling never does (owner decision, 2026-10-26). It is one wide
native button closing the ledger, in the control surface with an ice line, that also shows the
places it brings (`Puestos 21–40`) beside a chevron pointing down, on one line from 620px and
stacked beneath its label on phones; the places are visual only, so its accessible name stays
`Cargar más juegos`. The batch it brings receives focus at its first game, and a visually hidden
live status states `Mostrando N de T juegos`. The end reads **Has llegado al final del
TOPómetro**. A game that moved between page reads keeps its first place and is never listed
twice. A failed further page keeps every loaded place and offers its own retry. Switching source
starts again at that source's podium and never shows the other ranking meanwhile. Loaded pages
stay in the document's memory, so Back and **Volver al TOPómetro** return to the same places and
reading position, while a reload or a shared link opens at the podium.

**States.** Loading keeps the podium's frame and four ledger lines as placeholders under one live
status. An empty source is the empty notice with a way to the other source; a catalogue not yet
ready and a technical failure use the [server-backed states](#server-backed-states). The ranking
is public: it never varies on the session or `Mis plataformas`.

**Motion.** The podium enters once: third, second, then the winner rising onto steps that grow
from the floor, each G-meter sweeping to its reading; newly read lines fade in. Reduced motion
shows the settled page; forced colours keep the steps, numerals and edges in system colours.

## Plataformas

Post-MVP (#287, implemented; awaiting owner review), **Plataformas** (`/plataformas`) is a
showcase of platforms that leads to each one's destinations. It reads the public
[`listPlatforms`](../architecture/api/openapi.yaml) directory, the platforms at least one current
release uses, and adds no contract. The owner gave a direction without a mockup; the
composition below is this implementation's.

**Opening.** The `h1` **Plataformas** alone, without a lead (owner decision, 2026-10-27), on the
plain night canvas: logos are not game art, so no stage light and no G.

**Featured platforms.** Four cards, in the owner's order: **Nintendo Switch 2**, **PlayStation 5**,
**Xbox Series X|S** and **Windows PC**, each by the catalogue's own name, the same everywhere
(owner decision, 2026-10-27). [`platform-context.ts`](../../frontend/src/features/platforms/platform-context.ts)
owns this small presentation mapping: each family resolves to product taxonomy IDs (#178), today
one seeded ID each, and may group several later; nothing is merged by name and no provider ID
reaches the browser. Featured versus other is curation, never a domain subtype. Each card is a
night surface whose upper stage holds the platform's large logo in its own optical box, sized per
identity within the [asset register](../../frontend/public/assets/platforms/README.md)'s 2× limits,
so the four read at one weight. A quiet cool light falls from above and pools under the logo; it
never takes a brand colour. The name heads the body, then the destinations. The cards render without waiting for the directory; once it is
read, a family without games adds **Aún sin juegos en Gameómetro** after its destinations, so rows
stay aligned. One row of four from 1024px, two by two from 560px and one per row on phones, where
the stage keeps the logo large.

**Destinations.** Every platform offers exactly three, in this order: **Próximos destacados**
(#388, attention by Hypes), **TOPómetro** (#387, player scores, named by its lockup as in the
header) and **Explorar catálogo** (#389, live for every platform, [below](#explorar-catálogo)). [`platform-destinations.ts`](../../frontend/src/features/platforms/platform-destinations.ts)
holds them; a destination becomes a native link only when its feature supplies a path for the
platform's context, and otherwise reads **Próximamente** without being a link or a tab stop.
Activating one changes nothing else on the index. From 560px they are full-width rows between
hairlines, each at least 48px tall, with the state at the row's end or a chevron that leans on
hover; on phones they form a dock of three tiles at least 64px tall, whose labels break between
words only. Pointing at or focusing a linked destination lights its card's edge in ice and eases
its logo a little closer. TOPómetro never leads to Destacados.

**Otras plataformas.** Below a hairline, quieter than the featured cards: its `h2` alone, without a
lead, then only the owner's other platforms (owner decision, 2026-10-27), in this order, when the
directory holds games for them: Android, iOS, Linux, Mac, PlayStation 4, Meta Quest 3, Nintendo
Switch, Xbox One and PlayStation VR2. These are acquired per environment, so the presentation
mapping recognizes them by their catalogue name, as it does their marks; two platforms sharing a
name stay two, and the catalogue's own name is shown. Each is a native disclosure button with its
compact mark, its name and a chevron that points down while closed and up while open, announced
as expanded or collapsed. Opening one shows its three destinations inside its own item, under or
beside its name, never in a separate panel, and closes any other; opening the open one closes it.
The open platform is named in the URL (`?plataforma=<platformId>`) and keeps the reading position,
so it is shareable and Back undoes it; focus stays on the button, the destinations follow it in the
tab order, and a shared link opens it without moving focus. The open item takes an ice top edge and
light. On phones the platforms form one column and open like an accordion, the destinations as the
cards' dock of three tiles. From 560px they flow as a row of chips, at least 176px wide, and the open
chip grows sideways to hold its destinations as three compact tiles, each stating its state under
its label, wrapping beneath its name only when the row cannot hold them; its neighbours keep their
compact height. The page opts out of the browser's scroll anchoring, so opening a platform never
moves the page. A featured family named in the URL is already on the page; a platform not on offer
reads **Esa plataforma no tiene juegos en Gameómetro ahora mismo** with **Ver todas las
plataformas**, and never opens.

**States.** The featured cards stay through every directory state. Loading keeps the directory's
shape as placeholders under one status; no other platform is an empty notice; a catalogue not
ready and a technical failure use the [server-backed states](#server-backed-states) inside Otras,
with their retry. The index is public and never varies on the session or `Mis plataformas`.

**Motion.** The four cards rise in turn once and an opened platform's destinations fade in; reduced motion shows the
settled page and forced colours keep every edge and name in system colours.

## Explorar catálogo

Post-MVP (#389, implemented; awaiting owner review), **Explorar catálogo**
(`/plataformas/<plataforma>/catalogo`) lists every game Gameómetro knows on one platform, without
typing, under [`UC-019`](../architecture/application/mvp-use-cases.md) and `PCAT-001`–`PCAT-003` in
the [domain model](../architecture/domain/mvp-domain-model.md). The path names a featured family by
its key or another platform by its own ID, resolved through
[`platform-context.ts`](../../frontend/src/features/platforms/platform-context.ts); a key not on offer
reads **Esa plataforma no tiene juegos en Gameómetro ahora mismo** with **Ver todas las
plataformas**. The owner's mockup is a reference, not a specification.

**Opening.** A trail — **Plataformas / <plataforma> / Explorar catálogo** — leads, its middle step
returning to that platform on the index; phones keep only that step as a way back. The platform's
catalogue name is the `h1` in the page-title voice, read as `Explorar catálogo de <plataforma>`, and
the platform's own mark answers it on the right over a quiet cool pool of light, as the Plataformas
stages hold it, sized per identity; a platform without a mark keeps its name alone. Logos are no
game art, so the canvas stays plain night, without a stage, a lead or a claim.

**Controls.** Always at hand, in this order: **Todos | Ya lanzados | Próximos**, one night control
parted in three native links with the chosen one lit in ice; the platform's own title search
(**Buscar en <plataforma>…**), applied on Enter and explained in place when it could not be sent;
**Ordenar por** — **Fecha (descendente)**, **Fecha (ascendente)**, **Título (A-Z)**, **Título
(Z-A)**, **Puntuación Gameómetro** and **Jugadores IGDB**, each with its mark — and **Filtros**,
which counts what it holds. Without a chosen order the latest dates lead, and among **Próximos** the
soonest. One row from 1024px; from 620px the state beside the search and the order with **Filtros**
beneath; on phones the search leads, the state spans its row and the order shares the next with
**Filtros**, its `Ordenar por` read but not drawn.

**Filters.** Folded by default under **Filtros**, an opaque night panel with an ice top edge holds
**Año de lanzamiento** (**Desde** and **Hasta** over the years the API reports) and **Géneros** as
pressable chips with a check, each applied at once. **Quitar filtros avanzados** and **Ver N juegos**,
full width on phones, close it. The search and active filters stay visible under the controls as
removable chips with **Quitar todo**; the release state is never a chip.

**Cards.** The release explorer's cover grid and card language: six columns from 1200px, five from
1024px, four from 780px, three from 620px and two on phones, 24 games a page. Under the cover come
the presented release's compact date at its own precision after the fire needle, with **Próximo**
in ice for a game still to come, or **Retrasado** in the lifecycle warning, and nothing for a game
that is out; then the title, the game link and only keyboard stop; **Coincidencia: <alias>** when a
search matched an alias; the score readings; the release's region, with its platform's mark only for
a family; and up to two genres. Assistive technology reads the release in full (`Lanzado: 5 de junio
de 2026, Mundial`).

**Results.** The total opens the results — `496 juegos en Gameómetro`, never a claim of every game
the platform has — with the page position from 620px and **Cambiar plataforma** opposite: a
disclosure listing the featured platforms, then the other platforms on offer, as links to their
catalogue that keep the search, state, order and filters. The closing row states the page and the
pager. Every choice lives in the URL and returns to the first page; while a choice's page loads, the
next choice builds on it. A game page returns with **Volver al catálogo** to the same selection and
reading position.

**States.** Loading keeps the cover grid's frame; an empty selection offers **Quitar filtros** or
**Ver todos**, an empty platform says it has no games in Gameómetro yet, and a catalogue not ready,
an invalid search, an unsupported filter and a technical failure use the
[server-backed states](#server-backed-states). The page is public and never varies on the session
or `Mis plataformas`.

**Motion.** The mark and controls fade in once and results rise as on the release explorer; reduced
motion shows the settled page and forced colours outline the chosen state, genres and platform.

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
right column opens with **Ficha del juego** and the title, then the readings — the community
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
size, so a mean from one or two votes states its sample as plainly as its value. “Sin puntuación
todavía” keeps its place with a smaller, resting meter and a calm statement, giving the empty
reading less prominence; an unavailable reading retains room for its explanation. The empty
explanation invites a first rating only when the game's existing eligibility explicitly permits
it; an ineligible game states that rating is unavailable, and a pending read never invites a
rating. The distribution remains a backend/API capability and is not rendered on this page.

Post-MVP (#359, implemented; awaiting owner review), once any player has scored a platform,
a quiet ice text control, **Por plataforma** with a caret, follows the count inside the
community reading; without platform scores it is absent rather than empty. It opens an
anchored, non-modal, opaque panel with an ice top edge, overlaying what follows, that lists
each platform the API returns in its order: the platform's mark and name, `N
puntuación`/`puntuaciones` beneath, and its mean in plain ink opposite, with one footnote
saying a player who scores several platforms counts once in the community score. It never
repeats a G-meter, a temperature or a thermal colour, so the community reading keeps its
authority. Escape, an outside press or moving focus away close it, returning focus to the
control; it adds at most one line to the readings band. The panel anchors to the count
row so its full width stays inside the viewport, including on phones.

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

Post-MVP (#359, implemented; awaiting owner review), a signed-in player may rate by platform
without the reading growing: it shows the rating's one contribution, `8,5/10` when it is a
platform mean (`9/10` stays whole), and names that source for assistive technology. In the
panel, a direct rating keeps the keypad as the quick general score, which never touches saved
platforms, with any played platforms as one quiet `Jugado en …` line; a platform-scored rating
shows its breakdown — mark, name and each platform's own score in its temperature — in the
keypad's place, so a general pick can never silently replace platform scores. Beneath, behind a
hairline, a full-width **Puntuar por plataforma** (or **Editar plataformas**) opens the platform
sheet, present only when the game has release platforms or the rating already names some, and
disabled with the scale when the game is ineligible. The sheet is a native modal dialog: opaque
night with an ice top edge, a centred sheet up to 32rem wide from 620px and a bottom sheet across
the width on phones, never taller than most of the screen. Its header holds **Puntuar por
plataforma** and a close control, its body — the only part that scrolls — a one-line lead and
the game's platforms (then any saved platform the game no longer releases on) as native
checkboxes with their marks; a checked platform gains a score control (`Puntuar` or its score in
its temperature, edged in ice otherwise) that opens the shared keypad beneath that row, one at a
time, with **Quitar la nota de …** once scored. **Tu puntuación general** and its keypad
appear only when every platform is unchecked. Checking any platform hides the general
score and excludes it from the preview and save command; at least one selected platform
must have a score before saving. Unchecking every platform restores the general keypad
and any general score held in the draft. A sticky footer shows the result as the player's own reading
— G-meter, value, band in words and one plain source line (`Tu puntuación general`, `Media de tus
2 plataformas puntuadas`, adding that it replaces the general score when it does) — above
**Cancelar** and **Guardar**, enabled only for a valid change. Nothing is sent until **Guardar**;
Escape or **Cancelar** closes without a command and focus returns to the reading. Opening lands
on the first platform, and the sheet itself takes none of the reading's temperature. A pick
recovered after sign-in is never applied over platform scores: a notice says so instead.

The summary has a comfortable reading measure below the title: up to eight lines on desktop
and six on narrower screens, with a native **Leer más** / **Mostrar menos** button only when the
text overflows. Its accessible heading remains available to assistive technology, and expansion
keeps the full text recoverable without changing the cover's dimensions or stretching the cards.
It keeps its language for assistive technology and is never translated here; a sourced summary credits its
source and names a language other than Spanish (`Texto original en inglés · Fuente: IGDB`).
Post-MVP (#235), derived Spanish summaries instead state `Traducción de
Gameómetro · Original: IGDB`; a last-valid translation of older source also
states `Traducción pendiente`. The underlying provenance still identifies an automatic
translation. The text's `lang` is the served language,
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
status); then the region mark and name and the known special stage as quiet secondary text in its
[glossary label](../product/glossary.md#user-facing-terms) (a full release and an unknown stage
are left unstated), beneath the date on phones and beside it on a wide
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
A–Z**, **Título Z–A**, **Puntuación más alta**, **Puntuación más baja**) and the page size. Searching still
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
come **Cancelar** and **Guardar puntuación**, and, below a divider, a quiet destructive
**Eliminar puntuación** that asks once more (**Conservar** / **Sí, eliminar**). Picking a value
only makes it pending, and arrow keys only move focus inside the keypad, so neither browsing
nor picking ever sends a command. Opening focuses the pressed value. Escape and Cancel close
the panel and return focus to the reading; an outside press or moving focus away also closes
it. Outcomes show inside the panel: one live status while a command runs and one alert for
rejected, conflicting or ambiguous commands.

Post-MVP (#359, implemented; awaiting owner review), each line reads the rating's one
contribution (`8,5/10` for a platform mean) and, when the rating names platforms, one quiet line
beneath the reading: up to three platform marks with the exact `+N`, and `Media de N
plataformas` when the score is their mean; the line never grows into a per-platform breakdown.
A platform-scored rating opens the same panel with its breakdown and the delete action instead
of the keypad and **Guardar puntuación**; a general score saved here keeps the platforms a
rating names. Every panel ends with a full-width **Puntuar por plataforma** (or **Editar
plataformas**) that closes it and opens the game page's own platform sheet, unchanged, over the
ledger; only then is the game's public detail read for its release platforms, with one quiet
status while it loads and an alert if it fails. Closing the sheet returns focus to the line's
reading, and a save announces the outcome and reads the collection again.

There is no manual refresh. The collection is read on entry and on every reload, and again
automatically after a successful update or deletion and after a conflicting or ambiguous
command. A concurrent or ambiguous command keeps further commands disabled until such a read
succeeds; a failed read keeps the existing **Reintentar carga** recovery. Announce outcomes
and move focus to the results after maintenance or pagination. Empty ratings, no search
matches, an exhausted page and load failure remain distinct.

## Preferred platforms

Post-MVP (#295, implemented; awaiting owner review). A signed-in player's preferred platforms
narrow release discovery. Both operations live in **Mi cuenta**: the **Mis plataformas** link
manages the saved choice, and the checkbox beside it applies or sets it aside.

**The choice.** The first-run welcome (`/bienvenida`) and **Mis plataformas** share one
composition on the plain night canvas, without a stage, G or score instrument: the page
opening (**Te damos la bienvenida a Gameómetro** over **¿A qué juegas?**, or **Mi cuenta** over
**Mis plataformas**), one short lead (the welcome's: *Elige tus plataformas y te mostraremos
primero los juegos que responden a tus intereses. Elige varias, o ninguna si prefieres verlo
todo.*; Mis plataformas': *Elige las plataformas que saldrán por defecto. Puedes modificarlo desde
el menú de Mi cuenta*), then the platform tiles. Only the catalogue platforms with an owner-provided mark are
offered (owner decision); the rest of the taxonomy is never selectable. Every tile is a native
checkbox covering a night control with a hairline: the platform's mark at tile height, the
wordmarks keeping their ratio, and its catalogue name. A chosen tile takes an ice top edge,
ice-light mark and name and a filled ring with a tick, so selection is a shape as well as a
light; hover lights the edge, keyboard focus outlines the tile, and the tick's press is the only
motion. Tiles run in auto-filling columns on desktop, two on phones and one below 360px. One live
line counts the choice (`2 plataformas elegidas`). The welcome closes with **Guardar y
continuar**, enabled once something is chosen, and a quiet **Ahora no**, then a footnote naming
where the choice lives; **Mis plataformas** closes with **Guardar cambios**, enabled only for an
unsaved change, **Quitar todas** and its outcome in the same line (**Selección guardada**), and
one **Volver a Gameómetro** link to the landing page at its foot. Actions take the full width on phones. Loading, empty, failed reads and failed saves use
the [server-backed states](#server-backed-states); a failed save keeps the selection.

**The offer.** The welcome is product UI after Keycloak, never a registration step. A signed-in
player who has never chosen is sent to it once per document on the way into Destacados, the
explorer or the calendar, carrying the destination, which it restores after saving or skipping; a rating return
or any other page never redirects. Skipping records an empty choice and never blocks discovery.

**Applying them.** The authenticated account panel holds one **Mis plataformas** row: a native
checkbox, named *Filtrar por mis plataformas* and described by the platforms it applies, then
the link that opens the management page. Without preferences the row is the link alone. The
checkbox is one document-wide switch, on in every new document and kept only in its memory,
never in the URL, history or browser storage, so Destacados, the explorer and the calendar always
agree and a shared link resolves with the reader's own preferences. Discovery screens carry no
control or shortcut of their own, and no screen prints the list of applied platforms: only the
explorer's and the calendar's **Plataforma** summarize them as `Mis plataformas` (and the
explorer's folded phone controls name it). Choosing platforms by hand in **Plataforma** starts
from what it shows and turns the switch off; **Quitar filtros** shows every platform; checking the switch
again replaces a manual choice. None of these changes the saved choice. An empty view of the
preferred platforms says so (**Sin lanzamientos para tus plataformas**, **Tus plataformas no
tienen lanzamientos en …**) and offers **Ver todas las plataformas**, which turns the switch off.
Search, game pages and `Mis puntuaciones` never apply preferences.

## Catalogue administration

Post-MVP (#247, implemented; awaiting owner review), `/administracion/catalogo` is the
operator's ledger of catalogue acquisition, not a product screen: plain night without a
stage, art, G or score instrument, under the shared header and page opening (**Administración
del catálogo** over **Sincronización y cobertura**). Players never see it; a signed-out
visitor gets the sign-in notice and a signed-in account without the role a warning notice,
both from the server's answer rather than from hidden navigation.

The ordinary tabs split the page, **Ejecuciones** (the default) and **Cobertura**: a labelled
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

Post-MVP (#342, implemented; awaiting owner review), beneath the run in flight come its
controls: a plain **Detener sincronización** while no stop has been asked for and, only
while the run is stalled, a `danger` **Abandonar ejecución**; each stretches to the full
width on phones. Either opens, in place, one confirmation for that run: a labelled group
with a danger rule (a border, not colour alone), its interval in the question, what the
action does and does not do in words (a stop is not instant, committed Games stay, the
interval is not covered, nothing retries, schedules continue; abandonment only for a
process that no longer exists, in `warning` text), and two buttons, the safe one first
and focused (**Seguir sincronizando** / **Sí, detener**, **Conservar ejecución** / **Sí,
abandonar**). Escape or the safe button closes it and returns focus to its trigger, and a
replaced run closes it. A requested stop turns the run's state into **Detención
solicitada**, with its time among the facts and a quiet line saying the run continues to
its next safe point; the stop action disappears and the status is read every five
seconds. When a command settles, focus moves to one status region beneath the controls:
a stop reads **Se detendrá en su próximo punto seguro** until the run finishes, then
**Detención completada** or **Terminó antes de detenerse** with the run's result in the
words of **Ejecuciones recientes**; an abandonment reads **Abandonada y cerrada** as a
`warning`. A finished, vanished or still-alive run, a lost CSRF proof or a lost role is a
`warning` saying nothing changed, and the first three re-read the status; a lost session
offers sign-in; any other failure is a `danger` notice that asks to check the status
before repeating, with its support reference. In **Ejecuciones recientes**, a stopped run
reads **Detenida**, an operator's abandonment **Abandonada por un operador**, and a run
whose stop was asked for says when, and whether it finished before reaching a safe point.
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

Post-MVP (#246, implementation prepared; owner review and private-dev acceptance pending),
**Sincronización histórica** is a third URL-selected tab within the same plain administration
ledger. Tabs wrap on narrow screens. This tab has its own lifecycle and controls: explicit
inclusive date fields, a required skip/reprocess choice for covered months, then a confirmation
with the safe **Cancelar** action first and focused. Escape returns focus to its trigger.
Start, stop and resume each name their consequences; command feedback takes focus and reports
conflicts without losing persisted progress. Historical controls never issue ordinary stop
commands.

The backend's state, requested dates, current/pending month, completed/omitted/remaining counts,
month-based native progress bar, current available counters, completed-month processing totals,
known quality failures and activity/timing facts form one ledger. Unknown counters read **No
disponible**. Completed months do not imply error-free acquisition, and totals do not claim
distinct catalogue identities. Operational failures and interruptions carry a warning and
explicit resume. A separate line names an ordinary run that makes history wait; **Cobertura**
remains the canonical scanned-period view. Poll every five seconds while active and every
fifteen seconds otherwise, with no ETA or browser-local progress. Actions stack on phones;
keyboard focus, forced colours and native controls retain their meaning.

## Personal statistics

Post-MVP (#254, refined with personal platforms by #363; implemented, awaiting owner review),
**Tus estadísticas** opens from `Mis puntuaciones` and `Mi cuenta` at
`/mis-puntuaciones/estadisticas`. It is a personal score atlas on night, composed for phones
first. Light comes only from the two games presented as extremes; it never repeats the
temporal G. The [use case](../architecture/application/mvp-use-cases.md#personal-rating-statistics-uc-015-post-mvp-254)
owns meaning, thresholds, ranking and tie rules.

The reading order is: the opening, **Así puntúas**, **Dónde has jugado**, the extremes, the
genres beside the twelve months, then the calculation notes in a native disclosure. Sections
sit between hairlines rather than in cards, and each one uses a different shape (columns, a
ledger of bars, covers, a calendar) so the page keeps a rhythm instead of repeating one
chart.

- **Opening.** The collection total in the wide heavy cut is paired with the existing score
  G-meter and its separate mean and temperature. On phones they share one row, so the first
  viewport shows the total, the mean and most of the distribution. A total of four or more
  digits steps down a size. The one-line invitation shows only from tablet width. A
  collection of one to four games adds the small-sample caveat beneath.
- **Así puntúas.** One thermal scale: ten equal columns without gaps, each a native button
  that names its score, exact count and share and toggles a selection that the readout
  below announces. The five temperatures sit directly under their two columns with their
  name, count and share, replacing a separate summary. A tapered fire blade with its pivot,
  the needle language of compact scales, marks the exact mean on the same scale, labelled
  `Media 7,2` in the readout voice. It runs behind the columns, so counts stay legible; the
  readout states that the needle is the unrounded mean while each column gathers the
  scores that round to it. Empty columns keep a quiet `0` and draw no bar.
- **Dónde has jugado.** The coverage of the collection comes first as the page's one pie,
  a ring, because it is the only partition the section has: every rated game falls in
  exactly one slice — recorded on one platform (ice), on several (ice-light) or on none,
  left as the empty, outlined part of the ring. Its centre states the share with a platform,
  and beside it the figure (`62 de 84 juegos con plataforma`) and a legend give each slice's
  count and share. Platform counts overlap, so they never become slices of a pie. Then come up to six
  platforms as a ledger, each with its mark and name, one ice bar and that platform's
  personal mean. The bar's length is the games played there. Its solid part is the games
  scored on that platform, and its outlined, hatched part the games only marked as played;
  a key names both, so the two never depend on colour. The counts follow in words
  (`31 juegos`, `14 con puntuación`). The mean takes its temperature, with the band in
  words, or reads `Sin puntuar`. Counts are measures, so they stay ice; only the mean is
  thermal. Further platforms are counted beneath, and a note states that platform counts
  overlap and that each mean uses only that platform's scores. On phones the ring sits beside
  its legend and each platform reads down its own column beside its mean; from 620px the bar
  takes the middle column, and from 1000px the ring and its legend lead a column of their own. Without any recorded platform the section
  says so and explains **Puntuar por plataforma**, with no empty chart. Nothing in it claims
  ownership, playing time or a favourite platform.
- **Extremes.** Two game covers anchor the extremes, with full title links and personal
  scores, a platform mean with its decimal (#359). Coincident extremes present one game with
  the shared reading. On phones the covers shrink and the two games stack.
- **Genres and months.** Overlapping genre groups take a count bar or a score bar, selected
  with **Más puntuados** / **Mejor puntuados**; every genre mean carries its sample. The
  quieter twelve-month count chart in ice follows its own column's width: two half-years of
  six months when narrow, one row of twelve when wide. The first month and every January
  name their year. Counts too wide for a narrow column use compact notation, and the month
  table in its native disclosure keeps every exact value. From 720px the genres and the
  months sit side by side.

Text and native controls carry every reading without SVG, colour, hover or a tooltip; touch
selects. Only the opening enters once and bar changes transition, and reduced motion removes
both. Forced colours use system text for bars, the needle and their counts, keep the
platform bars' fill-and-outline contrast, stripe the ring's several-platform slice and outline a
selection. Empty collections show a
quiet invitation to find a game, with no simulated charts or meaningless instrument. Loading
and failures use the shared server-backed state language below.

## Server-backed states

| State | Required presentation and behaviour |
|---|---|
| Loading / new selection | One live status plus non-interactive placeholders shaped like the screen they replace; never present previous results as the new selection |
| Refreshing | Keep valid current results usable and announce the refresh |
| Empty | Neutral editorial notice with applicable filter reset or page recovery; never call it a failure |
| Stale | Catalogue synchronization age is never shown to players, on cards or on the game page; a release that requires review keeps its notice. A stale featured ranking keeps its observed-attention caption |
| Catalogue not ready | Informational notice, approved data-unavailability explanation and retry; distinguish it from technical failure |
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
