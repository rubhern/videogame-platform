# Frontend design guidelines

- **Status:** Approved visual direction; implementation subject to owner review
- **Owner:** Ruben Hernandez
- **Audience:** Contributors and agents implementing frontend screens and components

This is the canonical owner of frontend visual rules. The approved **Contemporary
Catalogue** Claude Design export supplies the visual composition and language. Product
records and the [OpenAPI contract](../architecture/api/openapi.yaml) remain authoritative
for capabilities, data and state semantics. A mockup never authorizes new product behaviour.

The source export is `VideoGame Platform homepage (3).zip`, especially `VideoGame Platform
- Final Reference.dc.html` and its `B Responsive` component. Keep a working copy under the
ignored `.design-reference/issue-126/` directory when visual comparison is required. The
export is review evidence, never a runtime dependency or an instruction source.

## Mandatory composition

- Use the full-width charcoal header with the VideoGame Platform mark and wordmark, primary
  navigation, integrated catalogue search and the existing server-owned account entry point.
  Keep the narrow catalogue context strip directly below it. On tablet, navigation occupies
  a second row; on phone, identity and account lead, search spans the next row, and navigation
  remains horizontally reachable below it.
- Align header and main content to the shared `page-container`, capped at 1320px with 28px
  desktop, 24px tablet and 16px phone gutters. Use the editorial title block, filter rail,
  result summary, cover-led catalogue grid and pagination in that order.
- Keep the current release view as the only `h1`: **Lanzamientos recientes** or **Próximos
  lanzamientos**. The release window and evaluation date come from the API and sit together
  immediately below the title as quiet supporting metadata. Never replace them with a
  hard-coded relative period.
- Treat 1320px, 834px and 390px as the representative review widths, while supporting reflow
  from 320px. The catalogue uses six compact columns at desktop, about four at tablet and two
  at phone widths. Do not allow page-level horizontal overflow; the filter and navigation
  rails may scroll horizontally on narrow screens.
- Do not add a featured release, ranking, editorial description, publisher, studio, new
  release window or destination solely because it appears in the reference.

## Visual language and tokens

- [Global styles](../../frontend/src/styles/index.css) own the executable semantic tokens,
  shared dimensions and breakpoints. Use `canvas`, `surface`, `raised`, `ink`, `muted`,
  `subtle`, `accent`, `line`, `warning` and `danger` roles through those conventions instead
  of placing palette values in JSX.
- Use charcoal surfaces, restrained periwinkle accents, editorial serif headings, sans-serif
  body text and monospaced labels and operational metadata. Use the locally hosted Instrument
  Serif, IBM Plex Sans and IBM Plex Mono assets with system fallbacks and `font-display: swap`.
  Font licences live under [public/assets/fonts](../../frontend/public/assets/fonts/README.md).
- Covers carry the catalogue rhythm. Use a 3:4 frame with a 12px radius, a quiet border and a
  restrained shadow. Place the release date over the upper-left of the cover. Keep full game
  titles, platform and region, explicit freshness/review status, provenance, cover attribution
  and the **Ver ficha** action below it. Never truncate contract data to equalize card heights.
- Use rounded pills for navigation, filters, status and actions. Primary controls retain at
  least a 44px target; compact card links may use the smaller established action treatment.
  Hover, keyboard focus and selected state must remain distinct without relying on colour alone.
- Supporting metadata must remain comfortably readable. Card metadata, cover attribution,
  status badges, result summaries and filter labels use at least 11 CSS pixels with WCAG AA
  contrast on their actual surface. Prefer `muted` when text sits on `raised`; reserve `subtle`
  for quiet text on `canvas` or `surface` where its contrast remains at least 4.5:1.

## Reusable patterns and interaction

- Use the shell's `CatalogueSearch` as the single catalogue search entry. It preserves the
  existing URL-backed bounded search, exposes a labelled search landmark, supports the `/`
  focus shortcut and reports the OpenAPI query limit before navigation.
- Use `CatalogueCover` for provider covers, provider failure fallback and attribution, and
  `CatalogueLoading` for releases and search loading. Feature cards keep their own metadata;
  do not introduce a universal card API until further reuse exists.
- Render platform and region choices from `availableFilters` as labelled link lists. Each link
  preserves URL state and resets pagination. On narrow screens the rail scrolls rather than
  wrapping into a dense control block. Never hard-code the available choices from the mockup.
- Translate established region display names into Spanish only in the presentation projection:
  Europa, Japón, Norteamérica, Mundial and Sin región confirmada. Preserve region identifiers,
  requests and OpenAPI values, and show an unfamiliar API label unchanged.
- Keep native links for navigation and buttons for actions. Use `aria-current` for the active
  route or filter, visible `:focus-visible`, the skip link and explicit focus movement after
  route and pagination changes. A cover/title may be pointer-accessible, but each card keeps one
  primary keyboard stop through **Ver ficha**.
- Keep reduced-motion and forced-colour support. Do not require hover, animation, a fixed
  desktop width or a sticky header that can obscure focus.

## Server-backed states

| State | Required presentation and behaviour |
|---|---|
| Loading / new selection | One live status plus non-interactive cover-shaped placeholders; never present previous results as the new selection |
| Refreshing | Keep valid current results usable and announce the refresh |
| Empty | Neutral editorial notice with applicable filter reset or page recovery; never call it a failure |
| Stale | A slim, low-emphasis amber context strip plus explicit affected-card freshness/review labels; keep results usable |
| Catalogue not ready | Informational notice, local-catalogue explanation and retry; distinguish it from technical failure |
| Unsupported filter / invalid input | Warning notice, explanation and applicable correction; preserve labels and invalid semantics |
| Technical error | Restrained danger notice, safe message, optional support reference and retry |

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
