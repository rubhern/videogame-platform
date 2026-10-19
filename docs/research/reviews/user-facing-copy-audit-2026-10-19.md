# Gameómetro user-facing copy audit — 19 October 2026

- **Status:** Frozen phase-1 evidence for owner review; recommendations are proposals, not approved copy.
- **Scope:** [Issue #286](https://github.com/rubhern/videogame-platform/issues/286), inventory only. No application, API, theme, email or terminology implementation.
- **Audited source:** `1532f1ddb81e0060970dfdbc8112de577976e845` (detached HEAD; clean working tree at start). This report describes that revision, not the currently deployed private environment or current `main`.
- **Terminology comparison:** owner-approved #341 user-facing table fetched from `main` at `595cfc763e39f14770abe1f71e38fe2e5edca75c`. The audited revision predates that table. #341 was still open when inspected; issue closure is not the authority for its recorded owner-approved glossary.
- **Reader:** product owner deciding what to retain, rewrite, remove or standardize before phase 2.

## Evidence and reading conventions

The [documentation map](../../README.md), [Product Brief](../../product/product-brief.md), accepted [journey](../../product/mvp-story-map.md), branch [glossary](../../product/glossary.md), [design guidelines](../../development/frontend-design.md), [delivery lifecycle](../../development/delivery-lifecycle.md) and [work management](../../development/work-management.md) were read. The branch glossary retains `Destacados`; the [approved user-facing glossary at the comparison revision](https://github.com/rubhern/videogame-platform/blob/595cfc763e39f14770abe1f71e38fe2e5edca75c/docs/product/glossary.md#user-facing-terms) requires `TOPómetro` and other changes. These are different revisions, not competing naming proposals. No branch update was made. Do not implement this inventory over newer code without reconciling changes already delivered by #341.

Method: enumerate the route tree and imports; inspect production TS/TSX, CSS, HTML and SVG; extract JSX text, presentation attributes, literals and composed templates with the TypeScript parser; review state branches and API-to-UI mappings; supplement with backend-generated display labels, realm localization, parent Keycloak templates and message bundles. Tests/fixtures establish synthetic render inputs and coverage, not additional shipped wording. No catalogue records, user histories, authentication tokens or live messages were collected.

Each table row is a text or reusable pattern at its actual source usage. Repeated wording across components remains listed to preserve usages. Curly-braced expressions are substitutions, not words printed to users; `${...}` preserves a TypeScript template expression, and conditional expressions document its alternatives. Whitespace is collapsed as HTML does; punctuation and letter case are preserved from the source. Blank decorative image alternatives (`alt=""`), icon-only arrows, checks, separators, scale numerals and counts are described explicitly rather than invented as sentences. CSS may uppercase the display without changing the source/accessibility wording: for example `Cada nota, una temperatura` renders `CADA NOTA, UNA TEMPERATURA`, and `20 ago 2026` renders `20 AGO 2026`. These are presentation variants, not alternate translations. Source links pin the audited revision; source line numbers are one-based.

The inventory contains **1,015 identified entries**, including current text, composed patterns, development-only legal drafts and explicitly conditional inherited definitions. IDs identify source usages rather than a count of unique phrases.

“Source-verified” means the branch exists in code. It does **not** mean every row was independently triggered in a browser. The coverage matrix states the runtime evidence and its limits. “Draft”, “disabled”, “defensive” and “inherited/unverified” are separate from currently reachable normal behavior.

### Dynamic values and representative examples

All examples here are synthetic. `{title}`/`{canonicalTitle}` might be `Juego sintético: Horizonte`, `{alias}` `Horizonte`, platform `Windows PC`, region `Mundial`, genre `Acción`, developer `Estudio Uno`, source `IGDB`. Titles, aliases, summary text, companies, taxonomy, attribution and image alternative text come from normalized catalogue responses and are not enumerated record by record. Personal values/dates come from the current user's rating response; no account name/email is rendered by React's account menu.

Dates retain precision: `19 de octubre de 2026` / `19 oct 2026`, `octubre de 2026` / `oct 2026`, `3.er trimestre de 2026` / `T3 2026`, `2026`, `Fecha por confirmar` / `Por confirmar`. Ranges may be `12–18 oct 2026`, `28 sep – 25 oct 2026`, `29 dic 2026 – 4 ene 2027`; the accessible range states both endpoints in full. A year range `2024–2026` is not an exact release date. Numbers use Spanish formatting: `7,5/10`, `1.234 valoraciones`. Temperature vocabulary remains attached only to Gameómetro personal/community scores. IGDB is a separate source; the branch's displayed scale is `/10`, even though the conceptual glossary describes the source's native `/100` scale. Do not change either scale as an editorial correction.

Support references substitute a correlation identifier when supplied. They are not error codes and no real reference is reproduced. Operator outcome codes are displayed under `Código` as diagnostics and retain their machine spelling.

## Inventory

### Global shell, header, account and search controls

Shared on every React route. Anonymous links are inline on larger screens and in the account popup on phones; mobile search adds a named dialog. Session loading/failure hides the header account entry until session data exists. Only `catalogue_administration` capability exposes the operator link.

| ID | Screen/component | State or trigger | Exact current text | Source (file) | Notes |
| --- | --- | --- | --- | --- | --- |
| SH-001 | index | `HTML meta description` | `Gameómetro: lanzamientos de videojuegos y puntuaciones de la comunidad.` | [index.html:9][src1] | Metadata, not body text. |
| SH-002 | index | `Browser title: all React routes` | `Gameómetro` | [index.html:17][src1] | Runtime confirmed; no route-specific title updates found. |
| SH-003 | account-control | `AccountControl; if session.data?.authenticated !== true` | `Acceder a tu cuenta` | [account-control.tsx:50][src2] | Source-verified; accessible name/announcement. |
| SH-004 | account-control | `AccountControl; if session.data?.authenticated !== true` | `Acceso a tu cuenta` | [account-control.tsx:54][src2] | Source-verified; accessible name/announcement. |
| SH-005 | account-control | `AccountControl; if session.data?.authenticated !== true` | `Iniciar sesión` | [account-control.tsx:55][src2] | Source-verified; literal. |
| SH-006 | account-control | `AccountControl; if session.data?.authenticated !== true` | `Crear cuenta` | [account-control.tsx:56][src2] | Source-verified; literal. |
| SH-007 | account-control | `AccountControl` | `Mi cuenta` | [account-control.tsx:71][src2] | Source-verified; accessible name/announcement. |
| SH-008 | account-control | `AccountControl` | `Mi cuenta` | [account-control.tsx:80][src2] | Source-verified; literal. |
| SH-009 | account-control | `AccountControl; if open` | `Mis puntuaciones` | [account-control.tsx:85][src2] | Source-verified; literal. |
| SH-010 | account-control | `AccountControl; if open; if session.data.capabilities.includes("catalogue_administration")` | `Panel de administrador` | [account-control.tsx:93][src2] | Source-verified; literal. |
| SH-011 | account-control | `AccountControl; if open; if logout.isPending` | `Cerrando…` | [account-control.tsx:112][src2] | Source-verified; literal. |
| SH-012 | account-control | `AccountControl; if open; else logout.isPending` | `Cerrar sesión` | [account-control.tsx:112][src2] | Source-verified; literal. |
| SH-013 | account-control | `AccountControl; if open; if logout.isError` | `No se pudo cerrar la sesión. Vuelve a intentarlo.` | [account-control.tsx:114][src2] | Source-verified; literal. |
| SH-014 | app-shell | `AppShell` | `Saltar al contenido` | [app-shell.tsx:48][src3] | Source-verified; literal. |
| SH-015 | app-shell | `AppShell` | `Gameómetro · Inicio` | [app-shell.tsx:53][src3] | Source-verified; accessible name/announcement. |
| SH-016 | app-shell | `AppShell` | `Lanzamientos` | [app-shell.tsx:59][src3] | Source-verified; accessible name/announcement. |
| SH-017 | app-shell | `AppShell` | `Destacados` | [app-shell.tsx:60][src3] | Source-verified; literal. |
| SH-018 | app-shell | `AppShell` | `Recientes` | [app-shell.tsx:67][src3] | Source-verified; literal. |
| SH-019 | app-shell | `AppShell` | `Próximos` | [app-shell.tsx:74][src3] | Source-verified; literal. |
| SH-020 | app-shell | `AppShell; if navigation.state === "loading"` | `Cargando página…` | [app-shell.tsx:90][src3] | Source-verified; literal. |
| SH-021 | app-shell | `AppShell` | `Gameómetro` | [app-shell.tsx:98][src3] | Source-verified; literal. |
| SH-022 | catalogue-search | `SearchForm` | `Buscar en el catálogo` | [catalogue-search.tsx:117][src4] | Source-verified; literal. |
| SH-023 | catalogue-search | `SearchForm` | `Buscar juegos por título…` | [catalogue-search.tsx:137][src4] | Source-verified; input placeholder. |
| SH-024 | catalogue-search | `SearchForm` | `/` | [catalogue-search.tsx:143][src4] | Source-verified; literal. |
| SH-025 | catalogue-search | `SearchForm` | `Buscar` | [catalogue-search.tsx:144][src4] | Source-verified; literal. |
| SH-026 | catalogue-search | `SearchForm; if tooLong` | `Usa como máximo 100 caracteres.` | [catalogue-search.tsx:148][src4] | Source-verified; literal. |
| SH-027 | catalogue-search | `CatalogueSearch` | `Buscar juegos` | [catalogue-search.tsx:237][src4] | Source-verified; accessible name/announcement. |
| SH-028 | catalogue-search | `CatalogueSearch` | `Buscar juegos` | [catalogue-search.tsx:250][src4] | Source-verified; accessible name/announcement. |
| SH-029 | catalogue-search | `CatalogueSearch` | `×` | [catalogue-search.tsx:266][src4] | Source-verified; literal. |
| SH-030 | catalogue-search | `CatalogueSearch` | `Cerrar búsqueda` | [catalogue-search.tsx:267][src4] | Source-verified; accessible name/announcement. |
| SH-031 | route-loading | `RouteLoading; if location.pathname.startsWith("/games/")` | `Detalle del juego` | [route-loading.tsx:29][src5] | Source-verified; literal. |
| SH-032 | route-loading | `RouteLoading; if location.pathname.startsWith("/games/")` | `Cargando el juego…` | [route-loading.tsx:30][src5] | Source-verified; literal. |
| SH-033 | route-loading | `RouteLoading` | `Cargando página…` | [route-loading.tsx:34][src5] | Source-verified; literal. |

### Destacados: weekly/monthly selection, hero and cards

Route `/` with `granularity=week|month`, optional period. Ranked, attention-unavailable, no-qualifying-release, loading, refreshing, not-ready and error branches are implemented. A stale popularity value is modeled but no distinct stale caption is rendered at this revision.

| ID | Screen/component | State or trigger | Exact current text | Source (file) | Notes |
| --- | --- | --- | --- | --- | --- |
| FT-001 | featured-card | `FeaturedCard; else primaryGroup === undefined` | `{primaryGroup.date}: {primaryGroup.platforms.join(", ")}.` | [featured-card.tsx:72][src6] | Source-verified; dynamic template/branch. |
| FT-002 | featured-card | `Accessible featured card release row` | `{fullDate}: {platformNames joined by comma}. {regionName}` | [featured-card.tsx:73][src6] | Visible short date and platform marks coexist; names preserved for assistive technology. |
| FT-003 | featured-card | `FeaturedCard; else primaryGroup === undefined; if morePlatforms > 0` | `+{morePlatforms}` | [featured-card.tsx:82][src6] | Source-verified; dynamic template/branch. |
| FT-004 | featured-card | `FeaturedCard; else item.genres.length === 0` | `Géneros` | [featured-card.tsx:89][src6] | Source-verified; accessible name/announcement. |
| FT-005 | featured-hero | `FeaturedHero; markLabel` | `Por confirmar` | [featured-hero.tsx:90][src7] | Source-verified; literal. |
| FT-006 | featured-hero | `FeaturedHero; if granularity === "week"` | `Lanzamiento de la semana` | [featured-hero.tsx:109][src7] | Source-verified; literal. |
| FT-007 | featured-hero | `FeaturedHero; else granularity === "week"` | `Lanzamiento del mes` | [featured-hero.tsx:109][src7] | Source-verified; literal. |
| FT-008 | featured-hero | `FeaturedHero; else primaryGroup === undefined` | `Lanzamiento` | [featured-hero.tsx:159][src7] | Source-verified; accessible name/announcement. |
| FT-009 | featured-hero | `FeaturedHero; else primaryGroup === undefined` | `Plataformas` | [featured-hero.tsx:170][src7] | Source-verified; accessible name/announcement. |
| FT-010 | featured-hero | `FeaturedHero; else primaryGroup === undefined; if morePlatforms > 0` | `+{morePlatforms}` | [featured-hero.tsx:186][src7] | Source-verified; dynamic template/branch. |
| FT-011 | featured-hero | `FeaturedHero; else lead.genres.length === 0` | `Géneros` | [featured-hero.tsx:198][src7] | Source-verified; accessible name/announcement. |
| FT-012 | featured-hero | `FeaturedHero; else lead.facts.length === 0` | `Estado del lanzamiento` | [featured-hero.tsx:210][src7] | Source-verified; accessible name/announcement. |
| FT-013 | featured-period-selector | `granularities; label` | `Semana` | [featured-period-selector.tsx:27][src8] | Source-verified; literal. |
| FT-014 | featured-period-selector | `granularities; label` | `Mes` | [featured-period-selector.tsx:28][src8] | Source-verified; literal. |
| FT-015 | featured-period-selector | `words; week; previous` | `Semana anterior` | [featured-period-selector.tsx:32][src8] | Source-verified; literal. |
| FT-016 | featured-period-selector | `words; week; next` | `Semana siguiente` | [featured-period-selector.tsx:32][src8] | Source-verified; literal. |
| FT-017 | featured-period-selector | `words; week; current` | `Semana actual` | [featured-period-selector.tsx:32][src8] | Source-verified; literal. |
| FT-018 | featured-period-selector | `words; month; previous` | `Mes anterior` | [featured-period-selector.tsx:33][src8] | Source-verified; literal. |
| FT-019 | featured-period-selector | `words; month; next` | `Mes siguiente` | [featured-period-selector.tsx:33][src8] | Source-verified; literal. |
| FT-020 | featured-period-selector | `words; month; current` | `Mes actual` | [featured-period-selector.tsx:33][src8] | Source-verified; literal. |
| FT-021 | featured-period-selector | `FeaturedPeriodSelector; spoken; if granularity === "week" && phrase !== null` | `Semana` | [featured-period-selector.tsx:89][src8] | Source-verified; literal. |
| FT-022 | featured-period-selector | `FeaturedPeriodSelector` | `Periodo de los destacados` | [featured-period-selector.tsx:94][src8] | Source-verified; accessible name/announcement. |
| FT-023 | featured-period-selector | `FeaturedPeriodSelector; else label === null \|\| period === null; if granularity === "week"` | `Semana de la selección` | [featured-period-selector.tsx:114][src8] | Source-verified; literal. |
| FT-024 | featured-period-selector | `FeaturedPeriodSelector; else label === null \|\| period === null; else granularity === "week"` | `Mes de la selección` | [featured-period-selector.tsx:114][src8] | Source-verified; literal. |
| FT-025 | featured-releases-shell | `titles; week` | `Lanzamientos semanales` | [featured-releases-shell.tsx:43][src9] | Source-verified; literal. |
| FT-026 | featured-releases-shell | `titles; month` | `Lanzamientos del mes` | [featured-releases-shell.tsx:44][src9] | Source-verified; literal. |
| FT-027 | featured-releases-shell | `liveStatus; if state.status === "loading" \|\| (state.status === "ready" && state.isPlaceholderData); if phrase === null` | `Cargando lanzamientos destacados…` | [featured-releases-shell.tsx:54][src9] | Source-verified; literal. |
| FT-028 | featured-releases-shell | `liveStatus; if state.status === "loading" \|\| (state.status === "ready" && state.isPlaceholderData); else phrase === null` | `Cargando lanzamientos destacados de ${phrase}…` | [featured-releases-shell.tsx:55][src9] | Source-verified; dynamic template/branch. |
| FT-029 | featured-releases-shell | `liveStatus; if state.status === "ready"; if state.isRefreshing` | `Actualizando lanzamientos destacados…` | [featured-releases-shell.tsx:59][src9] | Source-verified; literal. |
| FT-030 | featured-releases-shell | `liveStatus; if state.status === "ready"; case "ranked"` | `Lanzamientos destacados de ${state.model.periodPhrase}` | [featured-releases-shell.tsx:63][src9] | Source-verified; dynamic template/branch. |
| FT-031 | featured-releases-shell | `liveStatus; if state.status === "ready"; case "popularity-unavailable"` | `Aún no hay lanzamientos destacados en ${state.model.periodPhrase}` | [featured-releases-shell.tsx:65][src9] | Source-verified; dynamic template/branch. |
| FT-032 | featured-releases-shell | `liveStatus; if state.status === "ready"; case "no-qualifying-releases"` | `No hay lanzamientos en ${state.model.periodPhrase}` | [featured-releases-shell.tsx:67][src9] | Source-verified; dynamic template/branch. |
| FT-033 | featured-releases-shell | `liveStatus; if state.status === "catalogue-not-ready"` | `El catálogo todavía no está disponible` | [featured-releases-shell.tsx:70][src9] | Source-verified; literal. |
| FT-034 | featured-releases-shell | `UnrankedNotice; if unranked` | `Sin datos de atención` | [featured-releases-shell.tsx:116][src9] | Source-verified; literal. |
| FT-035 | featured-releases-shell | `UnrankedNotice; else unranked` | `Sin lanzamientos` | [featured-releases-shell.tsx:116][src9] | Source-verified; literal. |
| FT-036 | featured-releases-shell | `UnrankedNotice; if unranked` | `Aún no hay lanzamientos destacados en ${model.periodPhrase}` | [featured-releases-shell.tsx:119][src9] | Source-verified; dynamic template/branch. |
| FT-037 | featured-releases-shell | `UnrankedNotice; else unranked` | `No hay lanzamientos en ${model.periodPhrase}` | [featured-releases-shell.tsx:120][src9] | Source-verified; dynamic template/branch. |
| FT-038 | featured-releases-shell | `UnrankedNotice; if unranked` | `${week ? "Esta semana tiene" : "Este mes tiene"} lanzamientos, pero el catálogo local todavía no tiene la señal de atención necesaria para destacarlos.` | [featured-releases-shell.tsx:124][src9] | Source-verified; dynamic template/branch. |
| FT-039 | featured-releases-shell | `UnrankedNotice; else unranked; if week` | `El catálogo local no tiene estrenos con día confirmado dentro de esta semana.` | [featured-releases-shell.tsx:126][src9] | Source-verified; literal. |
| FT-040 | featured-releases-shell | `UnrankedNotice; else unranked; else week` | `El catálogo local no tiene lanzamientos con fecha dentro de este mes.` | [featured-releases-shell.tsx:127][src9] | Source-verified; literal. |
| FT-041 | featured-releases-shell | `UnrankedNotice` | `Ver lanzamientos recientes` | [featured-releases-shell.tsx:129][src9] | Source-verified; literal. |
| FT-042 | featured-releases-shell | `FeaturedReleasesShell; if state.status === "catalogue-not-ready"` | `Catálogo en preparación` | [featured-releases-shell.tsx:159][src9] | Source-verified; literal. |
| FT-043 | featured-releases-shell | `FeaturedReleasesShell; if state.status === "catalogue-not-ready"` | `El catálogo todavía no está disponible` | [featured-releases-shell.tsx:160][src9] | Source-verified; literal. |
| FT-044 | featured-releases-shell | `FeaturedReleasesShell; if state.status === "catalogue-not-ready"` | `Aún no hay una publicación local válida del catálogo. No se consulta ningún proveedor externo para completarla.` | [featured-releases-shell.tsx:161][src9] | Source-verified; literal. |
| FT-045 | featured-releases-shell | `FeaturedReleasesShell; if state.status === "catalogue-not-ready"` | `Reintentar` | [featured-releases-shell.tsx:165][src9] | Source-verified; literal. |
| FT-046 | featured-releases-shell | `FeaturedReleasesShell; if state.status === "catalogue-not-ready"` | `La búsqueda de juegos sigue disponible` | [featured-releases-shell.tsx:168][src9] | Source-verified; literal. |
| FT-047 | featured-releases-shell | `FeaturedReleasesShell; if state.status === "error"` | `Error de carga` | [featured-releases-shell.tsx:174][src9] | Source-verified; literal. |
| FT-048 | featured-releases-shell | `FeaturedReleasesShell; if state.status === "error"` | `No se pudieron cargar los lanzamientos destacados` | [featured-releases-shell.tsx:175][src9] | Source-verified; literal. |
| FT-049 | featured-releases-shell | `FeaturedReleasesShell; if state.status === "error"` | `Reintentar` | [featured-releases-shell.tsx:177][src9] | Source-verified; literal. |
| FT-050 | featured-releases-shell | `FeaturedReleasesShell; if state.status === "error"; else state.correlationId === null` | `Referencia para soporte: {state.correlationId}` | [featured-releases-shell.tsx:181][src9] | Source-verified; dynamic template/branch. |
| FT-051 | featured-releases-shell | `FeaturedReleasesShell; if showsModel && model !== null && model.lead !== null; else model.others.length === 0` | `Otros lanzamientos destacados` | [featured-releases-shell.tsx:202][src9] | Source-verified; literal. |
| FT-052 | featured-releases-shell | `FeaturedReleasesShell; if showsModel && model !== null && model.lead !== null; else model.others.length === 0` | `Ver todos los lanzamientos →` | [featured-releases-shell.tsx:205][src9] | Source-verified; literal. |
| FT-053 | featured-releases-shell | `FeaturedReleasesShell; if showsModel && model !== null && model.lead !== null; else model.others.length === 0` | `→` | [featured-releases-shell.tsx:206][src9] | Source-verified; literal. |
| FT-054 | featured-releases-view-model | `formatMonthPhrase` | `${name} de ${year}` | [featured-releases-view-model.ts:134][src10] | Source-verified; dynamic template/branch. |
| FT-055 | featured-releases-view-model | `formatWeekRange; first; if startYear !== endYear` | `${startDay} de ${startMonth} de ${startYear}` | [featured-releases-view-model.ts:148][src10] | Source-verified; dynamic template/branch. |
| FT-056 | featured-releases-view-model | `formatWeekRange; first; else startYear !== endYear; if startMonth !== endMonth` | `${startDay} de ${startMonth}` | [featured-releases-view-model.ts:150][src10] | Source-verified; dynamic template/branch. |
| FT-057 | featured-releases-view-model | `formatWeekRange` | `del ${first} al ${end}` | [featured-releases-view-model.ts:152][src10] | Source-verified; dynamic template/branch. |
| FT-058 | featured-releases-view-model | `formatWeekPhrase` | `la semana ${formatWeekRange(from, to)}` | [featured-releases-view-model.ts:157][src10] | Source-verified; dynamic template/branch. |
| FT-059 | featured-releases-view-model | `facts; if release.releaseDate.precision === "unknown"` | `Fecha por confirmar` | [featured-releases-view-model.ts:266][src10] | Source-verified; literal. |
| FT-060 | featured-releases-view-model | `facts; if release.reviewStatus === "required"` | `Fecha pendiente de revisión` | [featured-releases-view-model.ts:267][src10] | Source-verified; literal. |
| FT-061 | featured-releases-view-model | `fallbackArt; alt` | `Imagen destacada no disponible de ${title}` | [featured-releases-view-model.ts:277][src10] | Source-verified; dynamic template/branch. |
| FT-062 | month-dial | `MonthDial; else todayPoint === null` | `hoy` | [month-dial.tsx:246][src11] | Source-verified; literal. |
| FT-063 | period-timeline | `weekdays` | `miércoles` | [period-timeline.ts:62][src12] | Source-verified; literal. |
| FT-064 | period-timeline | `weekdays` | `sábado` | [period-timeline.ts:62][src12] | Source-verified; literal. |
| FT-065 | period-timeline | `Weekday vocabulary in full accessible timeline` | `lunes; martes; miércoles; jueves; viernes; sábado; domingo` | [period-timeline.ts:62][src12] | Template; source-verified. Synthetic examples are explained below. |
| FT-066 | month-dial | `Calendar numeral; month appended where a week crosses into a new month` | `{dayNumber} / {dayNumber} {shortMonth}` | [month-dial.tsx:46][src11] | Decorative scale: e.g. 31, 1 nov. The accessible sentence states the release/progress separately. |
| FT-067 | period-timeline | `describeProgress; if timeline.phase === "past"` | `${week ? "semana completa" : "mes completo"}, ${timeline.days} de ${timeline.days} días transcurridos` | [period-timeline.ts:191][src12] | Source-verified; dynamic template/branch. |
| FT-068 | period-timeline | `Completed period accessible progress` | `{semana completa\|mes completo}, {days} de {days} días transcurridos` | [period-timeline.ts:191][src12] | Template; source-verified. Synthetic examples are explained below. |
| FT-069 | period-timeline | `describeProgress; if timeline.phase === "future" \|\| timeline.today === null; if week` | `la semana aún no ha empezado` | [period-timeline.ts:194][src12] | Source-verified; literal. |
| FT-070 | period-timeline | `describeProgress; if timeline.phase === "future" \|\| timeline.today === null; else week` | `el mes aún no ha empezado` | [period-timeline.ts:194][src12] | Source-verified; literal. |
| FT-071 | period-timeline | `describeProgress; if week` | `hoy es ${today?.weekday ?? ""} ${today?.day ?? ""}, día ${timeline.today} de 7` | [period-timeline.ts:198][src12] | Source-verified; dynamic template/branch. |
| FT-072 | period-timeline | `describeProgress` | `hoy es el día ${timeline.today} de ${timeline.days}` | [period-timeline.ts:200][src12] | Source-verified; dynamic template/branch. |
| FT-073 | period-timeline | `describeMark; case "day"; if !week` | `lanzamiento el día ${mark.day}` | [period-timeline.ts:207][src12] | Source-verified; dynamic template/branch. |
| FT-074 | period-timeline | `describeMark; case "day"` | `lanzamiento el ${division?.weekday ?? ""} ${division?.day ?? ""}` | [period-timeline.ts:209][src12] | Source-verified; dynamic template/branch. |
| FT-075 | period-timeline | `describeMark; case "span"` | `lanzamiento sin día confirmado` | [period-timeline.ts:212][src12] | Source-verified; literal. |
| FT-076 | period-timeline | `describeMark; case "before"; if week` | `lanzamiento antes de esta semana` | [period-timeline.ts:214][src12] | Source-verified; literal. |
| FT-077 | period-timeline | `describeMark; case "before"; else week` | `lanzamiento antes de este mes` | [period-timeline.ts:214][src12] | Source-verified; literal. |
| FT-078 | period-timeline | `describeMark; case "after"; if week` | `lanzamiento después de esta semana` | [period-timeline.ts:216][src12] | Source-verified; literal. |
| FT-079 | period-timeline | `describeMark; case "after"; else week` | `lanzamiento después de este mes` | [period-timeline.ts:216][src12] | Source-verified; literal. |
| FT-080 | period-timeline | `describeMark; case "unknown"` | `fecha de lanzamiento por confirmar` | [period-timeline.ts:218][src12] | Source-verified; literal. |
| FT-081 | period-timeline | `describePeriodScale` | `Calendario de ${periodPhrase}: ${describeProgress(timeline)}; ${describeMark(timeline, mark)}.` | [period-timeline.ts:231][src12] | Source-verified; dynamic template/branch. |

| FT-082 | featured-period-selector | `Available adjacent-period link; week/month and previous/next select the prefix` | `{Semana anterior / Semana siguiente / Mes anterior / Mes siguiente}: {periodPhrase}` | [featured-period-selector.tsx:65][src8] | Accessible name. Disabled/unknown steps use the prefix alone, without a date. |

### Recientes / Próximos: filters, cards, dates and pagination

Route `/?view=recent` or `/?view=upcoming`, URL period/filter/page state. Approximate dates are an explicit upcoming opt-in. Page counts refer to games; overflow counts refer to hidden releases.

| ID | Screen/component | State or trigger | Exact current text | Source (file) | Notes |
| --- | --- | --- | --- | --- | --- |
| RL-001 | release-card | `Discovery release card` | `{game.title}; {releaseDate}; {platformNames}; {regionName}` | [release-card.tsx:41][src13] | One presented row, complete date/region in accessible text; remaining releases in overflow. |
| RL-002 | release-card | `ReleaseCard; else primaryGroup === undefined` | `Plataformas de ${item.title}` | [release-card.tsx:57][src13] | Source-verified; dynamic template/branch. |
| RL-003 | release-card | `ReleaseCard; if primaryGroup?.review` | `Estado de los datos de ${item.title}` | [release-card.tsx:69][src13] | Source-verified; dynamic template/branch. |
| RL-004 | release-card | `ReleaseCard; if primaryGroup?.review` | `Información pendiente de revisión` | [release-card.tsx:70][src13] | Source-verified; literal. |
| RL-005 | release-overflow | `overflowLabel; if hiddenReleaseCount === 1` | `+ 1 lanzamiento más` | [release-overflow.tsx:13][src14] | Source-verified; literal. |
| RL-006 | release-overflow | `overflowLabel; else hiddenReleaseCount === 1` | `+ ${hiddenReleaseCount} lanzamientos más` | [release-overflow.tsx:14][src14] | Source-verified; dynamic template/branch. |
| RL-007 | release-overflow | `ReleaseOverflow; if open` | `Otros lanzamientos de ${title}` | [release-overflow.tsx:72][src14] | Source-verified; dynamic template/branch. |
| RL-008 | release-overflow | `ReleaseOverflow; if open` | `{group.platforms.join(" · ")} · {group.region}` | [release-overflow.tsx:80][src14] | Source-verified; dynamic template/branch. |
| RL-009 | release-overflow | `ReleaseOverflow; if open; if group.review` | `Información pendiente de revisión` | [release-overflow.tsx:84][src14] | Source-verified; literal. |
| RL-010 | release-window-scale | `ReleaseWindowScale; if cell.today` | `hoy` | [release-window-scale.tsx:83][src15] | Source-verified; literal. |
| RL-011 | releases-approximate-dates | `ReleasesApproximateDates` | `Incluir fechas aproximadas` | [releases-approximate-dates.tsx:27][src16] | Source-verified; literal. |
| RL-012 | releases-filters | `ReleasesFilters` | `Filtros de lanzamientos` | [releases-filters.tsx:40][src17] | Source-verified; literal. |
| RL-013 | releases-filters | `ReleasesFilters` | `Todas` | [releases-filters.tsx:45][src17] | Source-verified; literal. |
| RL-014 | releases-filters | `ReleasesFilters` | `Plataforma` | [releases-filters.tsx:48][src17] | Source-verified; literal. |
| RL-015 | releases-filters | `ReleasesFilters` | `Todas` | [releases-filters.tsx:62][src17] | Source-verified; literal. |
| RL-016 | releases-filters | `ReleasesFilters` | `Región` | [releases-filters.tsx:65][src17] | Source-verified; literal. |
| RL-017 | releases-filters | `ReleasesFilters; if hasActiveFilters(search)` | `Quitar filtros` | [releases-filters.tsx:79][src17] | Source-verified; literal. |
| RL-018 | releases-pagination | `ReleasesPagination; if isBeyondLastPage` | `Paginación de lanzamientos` | [releases-pagination.tsx:23][src18] | Source-verified; accessible name/announcement. |
| RL-019 | releases-pagination | `ReleasesPagination; if isBeyondLastPage` | `Ir a la última página` | [releases-pagination.tsx:24][src18] | Source-verified; literal. |
| RL-020 | releases-pagination | `ReleasesPagination` | `Paginación de lanzamientos` | [releases-pagination.tsx:40][src18] | Source-verified; accessible name/announcement. |
| RL-021 | releases-pagination | `ReleasesPagination; if hasPrevious` | `← Página anterior` | [releases-pagination.tsx:44][src18] | Source-verified; literal. |
| RL-022 | releases-pagination | `ReleasesPagination; if hasPrevious` | `←` | [releases-pagination.tsx:48][src18] | Source-verified; literal. |
| RL-023 | releases-pagination | `ReleasesPagination; if showPosition` | `Página {page.number} de {Math.max(page.totalPages, 1)}` | [releases-pagination.tsx:52][src18] | Source-verified; dynamic template/branch. Release shell passes showPosition=false; currently hidden there. |
| RL-024 | releases-pagination | `ReleasesPagination; if hasNext` | `Página siguiente →` | [releases-pagination.tsx:57][src18] | Source-verified; literal. |
| RL-025 | releases-pagination | `ReleasesPagination; if hasNext` | `→` | [releases-pagination.tsx:58][src18] | Source-verified; literal. |
| RL-026 | releases-shell | `contexts; recent` | `Ya disponibles` | [releases-shell.tsx:40][src19] | Source-verified; literal. |
| RL-027 | releases-shell | `contexts; upcoming` | `En calendario` | [releases-shell.tsx:41][src19] | Source-verified; literal. |
| RL-028 | releases-shell | `resultsSummary; if isRefreshing` | `Actualizando lanzamientos…` | [releases-shell.tsx:46][src19] | Source-verified; literal. |
| RL-029 | releases-shell | `resultsSummary: total count` | `1 juego / {totalItems} juegos` | [releases-shell.tsx:49][src19] | Template; source-verified. Synthetic examples are explained below. |
| RL-030 | releases-shell | `resultsSummary; if totalPages > 0 && number > totalPages` | `${count} · La página ${number} ya no está disponible` | [releases-shell.tsx:51][src19] | Source-verified; dynamic template/branch. |
| RL-031 | releases-shell | `Requested page beyond totalPages` | `{count} · La página {number} ya no está disponible` | [releases-shell.tsx:51][src19] | Template; source-verified. Synthetic examples are explained below. |
| RL-032 | releases-shell | `resultsSummary; if totalPages > 0` | `${count} · Página ${number} de ${totalPages}` | [releases-shell.tsx:53][src19] | Source-verified; dynamic template/branch. |
| RL-033 | releases-shell | `Nonempty pagination summary` | `{count} · Página {number} de {totalPages}` | [releases-shell.tsx:53][src19] | Template; source-verified. Synthetic examples are explained below. |
| RL-034 | releases-shell | `emptyExplanation; if search.view === "upcoming" && !search.includeApproximateDates` | `Ningún lanzamiento con fecha exacta coincide con esta ventana y estos filtros. Activa «Incluir fechas aproximadas» para ver también los anunciados por mes, trimestre, año o sin fecha confirmada.` | [releases-shell.tsx:59][src19] | Source-verified; literal. |
| RL-035 | releases-shell | `emptyExplanation; else search.view === "upcoming" && !search.includeApproximateDates` | `Ningún lanzamiento del catálogo local coincide con esta ventana y estos filtros.` | [releases-shell.tsx:60][src19] | Source-verified; literal. |
| RL-036 | releases-shell | `ReleasesShell` | `Resultados` | [releases-shell.tsx:137][src19] | Source-verified; literal. |
| RL-037 | releases-shell | `ReleasesShell; if state.status === "loading"` | `Cargando lanzamientos…` | [releases-shell.tsx:141][src19] | Source-verified; literal. |
| RL-038 | releases-shell | `ReleasesShell; if state.status === "catalogue-not-ready"` | `Catálogo en preparación` | [releases-shell.tsx:145][src19] | Source-verified; literal. |
| RL-039 | releases-shell | `ReleasesShell; if state.status === "catalogue-not-ready"` | `El catálogo todavía no está disponible` | [releases-shell.tsx:146][src19] | Source-verified; literal. |
| RL-040 | releases-shell | `ReleasesShell; if state.status === "catalogue-not-ready"` | `Aún no hay una publicación local válida del catálogo. No se consulta ningún proveedor externo para completarla.` | [releases-shell.tsx:147][src19] | Source-verified; literal. |
| RL-041 | releases-shell | `ReleasesShell; if state.status === "catalogue-not-ready"` | `Reintentar` | [releases-shell.tsx:151][src19] | Source-verified; literal. |
| RL-042 | releases-shell | `ReleasesShell; if state.status === "catalogue-not-ready"` | `La búsqueda de juegos sigue disponible` | [releases-shell.tsx:154][src19] | Source-verified; literal. |
| RL-043 | releases-shell | `ReleasesShell; if state.status === "unsupported-filters"` | `Revisa la selección` | [releases-shell.tsx:160][src19] | Source-verified; literal. |
| RL-044 | releases-shell | `ReleasesShell; if state.status === "unsupported-filters"` | `Filtro no admitido` | [releases-shell.tsx:161][src19] | Source-verified; literal. |
| RL-045 | releases-shell | `ReleasesShell; if state.status === "unsupported-filters"` | `Quitar filtros` | [releases-shell.tsx:163][src19] | Source-verified; literal. |
| RL-046 | releases-shell | `ReleasesShell; if state.status === "error"` | `Error de carga` | [releases-shell.tsx:174][src19] | Source-verified; literal. |
| RL-047 | releases-shell | `ReleasesShell; if state.status === "error"` | `No se pudieron cargar los lanzamientos` | [releases-shell.tsx:175][src19] | Source-verified; literal. |
| RL-048 | releases-shell | `ReleasesShell; if state.status === "error"` | `Reintentar` | [releases-shell.tsx:177][src19] | Source-verified; literal. |
| RL-049 | releases-shell | `ReleasesShell; if state.status === "error"; else state.correlationId === null` | `Referencia para soporte: {state.correlationId}` | [releases-shell.tsx:181][src19] | Source-verified; dynamic template/branch. |
| RL-050 | releases-shell | `ReleasesShell; if state.status === "ready" && state.isPlaceholderData` | `Cargando lanzamientos para la nueva selección…` | [releases-shell.tsx:187][src19] | Source-verified; literal. |
| RL-051 | releases-shell | `ReleasesShell; if state.status === "ready" && !state.isPlaceholderData; if state.model.items.length === 0` | `Sin resultados` | [releases-shell.tsx:194][src19] | Source-verified; literal. |
| RL-052 | releases-shell | `ReleasesShell; if state.status === "ready" && !state.isPlaceholderData; if state.model.items.length === 0; if isBeyondLastPage` | `Esta página ya no está disponible` | [releases-shell.tsx:197][src19] | Source-verified; literal. |
| RL-053 | releases-shell | `ReleasesShell; if state.status === "ready" && !state.isPlaceholderData; if state.model.items.length === 0; else isBeyondLastPage` | `Sin lanzamientos para esta selección` | [releases-shell.tsx:198][src19] | Source-verified; literal. |
| RL-054 | releases-shell | `ReleasesShell; if state.status === "ready" && !state.isPlaceholderData; if state.model.items.length === 0; if isBeyondLastPage` | `La página solicitada ya no está disponible para estos resultados.` | [releases-shell.tsx:202][src19] | Source-verified; literal. |
| RL-055 | releases-shell | `ReleasesShell; if state.status === "ready" && !state.isPlaceholderData; if state.model.items.length === 0; if hasActiveFilters(search) && state.model.page.totalItems === 0` | `Quitar filtros` | [releases-shell.tsx:206][src19] | Source-verified; literal. |
| RL-056 | releases-view-model | `viewTitles; recent` | `Lanzamientos recientes` | [releases-view-model.ts:85][src20] | Source-verified; literal. |
| RL-057 | releases-view-model | `viewTitles; upcoming` | `Próximos lanzamientos` | [releases-view-model.ts:86][src20] | Source-verified; literal. |
| RL-058 | releases-view-model | `localCoverPreview; alternativeText` | `Vista previa de la carátula de ${title}` | [releases-view-model.ts:123][src20] | Source-verified; dynamic template/branch. Local development preview only; no preview mapping used in this audit. |
| RL-059 | releases-view-model | `localCoverPreview; attribution; label` | `Vista previa local` | [releases-view-model.ts:124][src20] | Source-verified; literal. Local development preview only; no preview mapping used in this audit. |
| RL-060 | releases-view-model | `toReleasesViewModel; windowDescription` | `Del ${formatCalendarDay(page.window.from)} al ${formatCalendarDay( page.window.to, )}` | [releases-view-model.ts:228][src20] | Source-verified; dynamic template/branch. |
| RL-061 | releases-weeks | `Period selector options` | `1 semana; 2 semanas; 4 semanas` | [releases-weeks.tsx:8][src21] | Template; source-verified. Synthetic examples are explained below. |
| RL-062 | releases-weeks | `ReleasesWeeks` | `Periodo:` | [releases-weeks.tsx:19][src21] | Source-verified; literal. |
| RL-063 | featured-releases-page | `FeaturedReleasesPage; else query.isPending; if query.isError; else query.error.code === "CATALOGUE_NOT_READY"; message` | `No se pudo leer el catálogo local. Inténtalo de nuevo más tarde.` | [featured-releases-page.tsx:41][src22] | Source-verified; literal. |
| RL-064 | releases-page | `unsupportedFilterMessages; PLATFORM_NOT_SUPPORTED` | `La plataforma solicitada no existe en el catálogo local.` | [releases-page.tsx:12][src23] | Source-verified; literal. |
| RL-065 | releases-page | `unsupportedFilterMessages; REGION_NOT_SUPPORTED` | `La región solicitada no existe en el catálogo local.` | [releases-page.tsx:13][src23] | Source-verified; literal. |
| RL-066 | releases-page | `unsupportedFilterMessages; FILTER_INVALID` | `Los filtros solicitados no son válidos.` | [releases-page.tsx:14][src23] | Source-verified; literal. |
| RL-067 | releases-page | `toFailureState; message` | `No se pudo leer el catálogo local. Inténtalo de nuevo más tarde.` | [releases-page.tsx:29][src23] | Source-verified; literal. |

### Catalogue search, suggestions and results

| ID | Screen/component | State or trigger | Exact current text | Source (file) | Notes |
| --- | --- | --- | --- | --- | --- |
| SE-001 | game-search-card | `GameSearchCard` | `Lanzamiento:` | [game-search-card.tsx:29][src24] | Source-verified; literal. |
| SE-002 | game-search-card | `GameSearchCard; else result.matchedAlias === null` | `Coincidencia: {result.matchedAlias}` | [game-search-card.tsx:36][src24] | Source-verified; dynamic template/branch. |
| SE-003 | game-search-card | `GameSearchCard; else platforms.length === 0` | `Plataformas de ${result.title}` | [game-search-card.tsx:44][src24] | Source-verified; dynamic template/branch. |
| SE-004 | game-search-pagination | `GameSearchPagination; if isBeyondLastPage` | `Paginación de resultados` | [game-search-pagination.tsx:17][src25] | Source-verified; accessible name/announcement. |
| SE-005 | game-search-pagination | `GameSearchPagination; if isBeyondLastPage` | `Ir a la última página` | [game-search-pagination.tsx:18][src25] | Source-verified; literal. |
| SE-006 | game-search-pagination | `GameSearchPagination` | `Paginación de resultados` | [game-search-pagination.tsx:30][src25] | Source-verified; accessible name/announcement. |
| SE-007 | game-search-pagination | `GameSearchPagination` | `Página {page.number} de {Math.max(page.totalPages, 1)}` | [game-search-pagination.tsx:31][src25] | Source-verified; dynamic template/branch. |
| SE-008 | game-search-pagination | `GameSearchPagination; if hasPrevious` | `← Página anterior` | [game-search-pagination.tsx:35][src25] | Source-verified; literal. |
| SE-009 | game-search-pagination | `GameSearchPagination; if hasPrevious` | `←` | [game-search-pagination.tsx:36][src25] | Source-verified; literal. |
| SE-010 | game-search-pagination | `GameSearchPagination; if hasNext` | `Página siguiente →` | [game-search-pagination.tsx:40][src25] | Source-verified; literal. |
| SE-011 | game-search-pagination | `GameSearchPagination; if hasNext` | `→` | [game-search-pagination.tsx:41][src25] | Source-verified; literal. |
| SE-012 | game-search-shell | `resultsSummary; if isRefreshing` | `Actualizando resultados…` | [game-search-shell.tsx:33][src26] | Source-verified; literal. |
| SE-013 | game-search-shell | `resultsSummary; count; if totalItems === 1` | `1 juego del catálogo local` | [game-search-shell.tsx:37][src26] | Source-verified; literal. |
| SE-014 | game-search-shell | `resultsSummary; count; else totalItems === 1` | `${totalItems} juegos del catálogo local` | [game-search-shell.tsx:37][src26] | Source-verified; dynamic template/branch. |
| SE-015 | game-search-shell | `Plural search-result count` | `{totalItems} juegos del catálogo local` | [game-search-shell.tsx:37][src26] | Template; source-verified. Synthetic examples are explained below. |
| SE-016 | game-search-shell | `resultsSummary; if totalPages > 0 && number > totalPages` | `${count} · La página ${number} ya no está disponible` | [game-search-shell.tsx:39][src26] | Source-verified; dynamic template/branch. |
| SE-017 | game-search-shell | `Exhausted result page` | `{count} · La página {number} ya no está disponible` | [game-search-shell.tsx:39][src26] | Template; source-verified. Synthetic examples are explained below. |
| SE-018 | game-search-shell | `resultsSummary` | `${count} · Página ${number} de ${Math.max(totalPages, 1)}` | [game-search-shell.tsx:41][src26] | Source-verified; dynamic template/branch. |
| SE-019 | game-search-shell | `Search-result summary` | `{count} · Página {number} de {max(totalPages, 1)}` | [game-search-shell.tsx:41][src26] | Template; source-verified. Synthetic examples are explained below. |
| SE-020 | game-search-shell | `pageTitle; if hasSearchableQuery(params)` | `Resultados para «${params.query}»` | [game-search-shell.tsx:49][src26] | Source-verified; dynamic template/branch. |
| SE-021 | game-search-shell | `pageTitle; else hasSearchableQuery(params)` | `Buscar juegos` | [game-search-shell.tsx:49][src26] | Source-verified; literal. |
| SE-022 | game-search-shell | `GameSearchShell` | `Catálogo de juegos` | [game-search-shell.tsx:77][src26] | Source-verified; literal. |
| SE-023 | game-search-shell | `GameSearchShell` | `Resultados` | [game-search-shell.tsx:81][src26] | Source-verified; literal. |
| SE-024 | game-search-shell | `GameSearchShell; if state.status === "prompt"` | `Buscar en el catálogo` | [game-search-shell.tsx:91][src26] | Source-verified; literal. |
| SE-025 | game-search-shell | `GameSearchShell; if state.status === "prompt"` | `Encuentra un juego por su título` | [game-search-shell.tsx:92][src26] | Source-verified; literal. |
| SE-026 | game-search-shell | `GameSearchShell; if state.status === "prompt"` | `Escribe un título o un título alternativo aprobado en el buscador de la cabecera.` | [game-search-shell.tsx:93][src26] | Source-verified; literal. |
| SE-027 | game-search-shell | `GameSearchShell; if state.status === "loading"` | `Buscando en el catálogo…` | [game-search-shell.tsx:98][src26] | Source-verified; literal. |
| SE-028 | game-search-shell | `GameSearchShell; if state.status === "query-invalid"` | `Consulta no válida` | [game-search-shell.tsx:103][src26] | Source-verified; literal. |
| SE-029 | game-search-shell | `GameSearchShell; if state.status === "query-invalid"` | `La búsqueda no es válida` | [game-search-shell.tsx:104][src26] | Source-verified; literal. |
| SE-030 | game-search-shell | `GameSearchShell; if state.status === "query-invalid"` | `Escribe al menos un carácter con letras o números y como máximo 100 caracteres.` | [game-search-shell.tsx:105][src26] | Source-verified; literal. |
| SE-031 | game-search-shell | `GameSearchShell; if state.status === "catalogue-not-ready"` | `Catálogo en preparación` | [game-search-shell.tsx:111][src26] | Source-verified; literal. |
| SE-032 | game-search-shell | `GameSearchShell; if state.status === "catalogue-not-ready"` | `El catálogo todavía no está disponible` | [game-search-shell.tsx:112][src26] | Source-verified; literal. |
| SE-033 | game-search-shell | `GameSearchShell; if state.status === "catalogue-not-ready"` | `Aún no hay una publicación local válida del catálogo. No se consulta ningún proveedor externo para completarla.` | [game-search-shell.tsx:113][src26] | Source-verified; literal. |
| SE-034 | game-search-shell | `GameSearchShell; if state.status === "catalogue-not-ready"` | `Reintentar` | [game-search-shell.tsx:117][src26] | Source-verified; literal. |
| SE-035 | game-search-shell | `GameSearchShell; if state.status === "error"` | `Error de carga` | [game-search-shell.tsx:125][src26] | Source-verified; literal. |
| SE-036 | game-search-shell | `GameSearchShell; if state.status === "error"` | `No se pudo completar la búsqueda` | [game-search-shell.tsx:126][src26] | Source-verified; literal. |
| SE-037 | game-search-shell | `GameSearchShell; if state.status === "error"` | `Reintentar` | [game-search-shell.tsx:128][src26] | Source-verified; literal. |
| SE-038 | game-search-shell | `GameSearchShell; if state.status === "error"; else state.correlationId === null` | `Referencia para soporte: {state.correlationId}` | [game-search-shell.tsx:132][src26] | Source-verified; dynamic template/branch. |
| SE-039 | game-search-shell | `GameSearchShell; if state.status === "ready" && state.isPlaceholderData` | `Buscando resultados para la nueva consulta…` | [game-search-shell.tsx:139][src26] | Source-verified; literal. |
| SE-040 | game-search-shell | `GameSearchShell; if state.status === "ready" && !state.isPlaceholderData; if state.model.results.length === 0` | `Sin resultados` | [game-search-shell.tsx:153][src26] | Source-verified; literal. |
| SE-041 | game-search-shell | `GameSearchShell; if state.status === "ready" && !state.isPlaceholderData; if state.model.results.length === 0` | `No hay coincidencias en el catálogo local` | [game-search-shell.tsx:154][src26] | Source-verified; literal. |
| SE-042 | game-search-shell | `GameSearchShell; if state.status === "ready" && !state.isPlaceholderData; if state.model.results.length === 0; if state.model.page.totalItems > 0 && state.model.page.totalPages > 0 && state.model.page.number > state.model.page.totalPages` | `La página solicitada ya no está disponible para esta búsqueda.` | [game-search-shell.tsx:159][src26] | Source-verified; literal. |
| SE-043 | game-search-shell | `GameSearchShell; if state.status === "ready" && !state.isPlaceholderData; if state.model.results.length === 0; else state.model.page.totalItems > 0 && state.model.page.totalPages > 0 && state.model.page.number > state.model.page.totalPages` | `Ningún juego del catálogo local coincide con “${params.query}”. El catálogo es una selección acotada, así que un título publicado fuera de él no aparece aquí.` | [game-search-shell.tsx:160][src26] | Source-verified; dynamic template/branch. |
| SE-044 | game-search-shell | `GameSearchShell; if state.status === "ready" && !state.isPlaceholderData; else state.model.results.length === 0` | `Resultados de la búsqueda` | [game-search-shell.tsx:164][src26] | Source-verified; accessible name/announcement. |
| SE-045 | game-search-view-model | `releaseYearLabel; if earliestKnownYear === undefined \|\| latestKnownYear === undefined` | `Por confirmar` | [game-search-view-model.ts:67][src27] | Source-verified; literal. |
| SE-046 | game-search-view-model | `Search card/suggestion year range; one year if equal` | `{earliestKnownYear}–{latestKnownYear}` | [game-search-view-model.ts:71][src27] | Template; source-verified. Synthetic examples are explained below. |
| SE-047 | game-search-view-model | `hiddenPlatformsLabel; if count === 1` | `1 plataforma más` | [game-search-view-model.ts:92][src27] | Source-verified; literal. |
| SE-048 | game-search-view-model | `hiddenPlatformsLabel; else count === 1` | `${count} plataformas más` | [game-search-view-model.ts:92][src27] | Source-verified; dynamic template/branch. |
| SE-049 | game-suggestions-popup | `SuggestionMeta; else platforms.length === 0; else hiddenPlatformCount === 0` | `+{hiddenPlatformCount}` | [game-suggestions-popup.tsx:73][src28] | Source-verified; dynamic template/branch. |
| SE-050 | game-suggestions-popup | `SuggestionMeta; else suggestion.alias === null` | `También «{suggestion.alias}»` | [game-suggestions-popup.tsx:82][src28] | Source-verified; dynamic template/branch. |
| SE-051 | game-suggestions-popup | `announcement; if view.kind === "loading"` | `Buscando sugerencias…` | [game-suggestions-popup.tsx:90][src28] | Source-verified; literal. |
| SE-052 | game-suggestions-popup | `announcement; if view.kind === "error"` | `No se pudieron cargar las sugerencias.` | [game-suggestions-popup.tsx:91][src28] | Source-verified; literal. |
| SE-053 | game-suggestions-popup | `announcement; if view.suggestions.length === 0` | `No hay coincidencias.` | [game-suggestions-popup.tsx:92][src28] | Source-verified; literal. |
| SE-054 | game-suggestions-popup | `announcement` | `${view.suggestions.length} sugerencias disponibles. Usa las flechas para recorrerlas.` | [game-suggestions-popup.tsx:93][src28] | Source-verified; dynamic template/branch. |
| SE-055 | game-suggestions-popup | `Polite status; settled nonempty suggestions` | `{suggestionCount} sugerencias disponibles. Usa las flechas para recorrerlas.` | [game-suggestions-popup.tsx:93][src28] | Template; source-verified. Synthetic examples are explained below. |
| SE-056 | game-suggestions-popup | `GameSuggestionsPopup; if view.kind === "loading"` | `Buscando…` | [game-suggestions-popup.tsx:122][src28] | Source-verified; literal. |
| SE-057 | game-suggestions-popup | `GameSuggestionsPopup; else view.kind === "loading"; if view.kind === "error"` | `Resultados` | [game-suggestions-popup.tsx:124][src28] | Source-verified; literal. |
| SE-058 | game-suggestions-popup | `GameSuggestionsPopup; else view.kind === "loading"; else view.kind === "error"` | `Resultados (${view.totalItems})` | [game-suggestions-popup.tsx:125][src28] | Source-verified; dynamic template/branch. |
| SE-059 | game-suggestions-popup | `GameSuggestionsPopup` | `Enter para {hasActive ? "abrir" : "buscar"}` | [game-suggestions-popup.tsx:127][src28] | Source-verified; dynamic template/branch. |
| SE-060 | game-suggestions-popup | `GameSuggestionsPopup` | `Enter` | [game-suggestions-popup.tsx:128][src28] | Source-verified; literal. |
| SE-061 | game-suggestions-popup | `Visible keyboard hint; aria-hidden; active option / none` | `Enter para abrir / Enter para buscar` | [game-suggestions-popup.tsx:128][src28] | Template; source-verified. Synthetic examples are explained below. |
| SE-062 | game-suggestions-popup | `GameSuggestionsPopup; else view.kind === "loading"; if view.kind === "error"` | `! No se pudieron cargar las sugerencias` | [game-suggestions-popup.tsx:149][src28] | Source-verified; literal. |
| SE-063 | game-suggestions-popup | `GameSuggestionsPopup; else view.kind === "loading"; if view.kind === "error"` | `!` | [game-suggestions-popup.tsx:150][src28] | Source-verified; literal. |
| SE-064 | game-suggestions-popup | `GameSuggestionsPopup; else view.kind === "loading"; else view.kind === "error"; if view.suggestions.length === 0` | `No hay coincidencias` | [game-suggestions-popup.tsx:154][src28] | Source-verified; literal. |
| SE-065 | game-suggestions-popup | `GameSuggestionsPopup; else view.kind === "loading"; else view.kind === "error"; else view.suggestions.length === 0` | `Sugerencias de juegos` | [game-suggestions-popup.tsx:156][src28] | Source-verified; accessible name/announcement. |
| SE-066 | game-suggestions-popup | `GameSuggestionsPopup` | `Ver todos los resultados para «{term}»` | [game-suggestions-popup.tsx:187][src28] | Source-verified; dynamic template/branch. |
| SE-067 | game-suggestions | `Accessible suggestion option` | `{title} · también conocido como {alias} · {platformNames, comma-separated} · {hiddenPlatformsLabel} · {yearOrRange}` | [game-suggestions.ts:57][src29] | Optional parts omitted when absent; no alias when it merely repeats the title. |
| SE-068 | game-suggestions | `suggestionAccessibleName; else suggestion.alias === null` | `también conocido como ${suggestion.alias}` | [game-suggestions.ts:61][src29] | Source-verified; dynamic template/branch. |
| SE-069 | search-page | `toFailureState; message` | `No se pudo leer el catálogo local. Inténtalo de nuevo más tarde.` | [search-page.tsx:23][src30] | Source-verified; literal. |

### Game details, information, release records and summary

| ID | Screen/component | State or trigger | Exact current text | Source (file) | Notes |
| --- | --- | --- | --- | --- | --- |
| GD-001 | game-details-content | `CommunityScore` | `Puntuación de la comunidad` | [game-details-content.tsx:61][src31] | Source-verified; literal. |
| GD-002 | game-details-content | `CommunityScore; if available === null` | `Nota no disponible` | [game-details-content.tsx:68][src31] | Source-verified; literal. |
| GD-003 | game-details-content | `CommunityScore; else available === null; if mean === null` | `Sin nota todavía` | [game-details-content.tsx:70][src31] | Source-verified; literal. |
| GD-004 | game-details-content | `CommunityScore; else available === null; else mean === null` | `Nota media: ${decimal(mean)} de 10` | [game-details-content.tsx:73][src31] | Source-verified; dynamic template/branch. |
| GD-005 | game-details-content | `CommunityScore; else available === null; else mean === null` | `/10` | [game-details-content.tsx:75][src31] | Source-verified; literal. |
| GD-006 | game-details-content | `CommunityScore; else available === null; else mean === null; else band === null` | `Temperatura:` | [game-details-content.tsx:79][src31] | Source-verified; literal. |
| GD-007 | game-details-content | `CommunityScore; if available === null` | `Las estadísticas no están disponibles temporalmente. Puedes seguir consultando el juego.` | [game-details-content.tsx:89][src31] | Source-verified; literal. |
| GD-008 | game-details-content | `CommunityScore; else available === null; if mean === null` | `Todavía no hay puntuaciones para este juego.` | [game-details-content.tsx:93][src31] | Source-verified; literal. |
| GD-009 | game-details-content | `CommunityScore; else available === null; else mean === null` | `Basada en {available.count.toLocaleString("es-ES")} {available.count === 1 ? "puntuación" : "puntuaciones"}` | [game-details-content.tsx:95][src31] | Source-verified; dynamic template/branch. |
| GD-010 | game-details-content | `CommunityScore; else available === null; else mean === null; if available.count === 1` | `puntuación` | [game-details-content.tsx:97][src31] | Source-verified; literal. |
| GD-011 | game-details-content | `Community count singular/plural` | `puntuación / puntuaciones` | [game-details-content.tsx:97][src31] | Template; source-verified. Synthetic examples are explained below. |
| GD-012 | game-details-content | `ExternalScore` | `Jugadores IGDB` | [game-details-content.tsx:114][src31] | Source-verified; literal. |
| GD-013 | game-details-content | `ExternalScore` | `Jugadores IGDB: fuente de la puntuación de ${title}` | [game-details-content.tsx:120][src31] | Source-verified; dynamic template/branch. |
| GD-014 | game-details-content | `ExternalScore` | `Nota media: ${decimal(score.value)} de 10` | [game-details-content.tsx:127][src31] | Source-verified; dynamic template/branch. |
| GD-015 | game-details-content | `ExternalScore` | `/10` | [game-details-content.tsx:129][src31] | Source-verified; literal. |
| GD-016 | game-details-content | `ExternalScore` | `Basada en {exactCount.format(score.count)} {score.count === 1 ? "valoración" : "valoraciones"}` | [game-details-content.tsx:133][src31] | Source-verified; dynamic template/branch. |
| GD-017 | game-details-content | `ExternalScore; if score.count === 1` | `valoración` | [game-details-content.tsx:135][src31] | Source-verified; literal. |
| GD-018 | game-details-content | `IGDB count singular/plural` | `valoración / valoraciones` | [game-details-content.tsx:135][src31] | Template; source-verified. Synthetic examples are explained below. |
| GD-019 | game-details-content | `GameDetailsContent` | `Ficha del catálogo` | [game-details-content.tsx:166][src31] | Source-verified; literal. |
| GD-020 | game-details-content | `Game heading; title split at punctuation for visual wordmark` | `{canonicalTitle}` | [game-details-content.tsx:167][src31] | Data-driven, unchanged title spelling; e.g. Juego sintético: Horizonte. |
| GD-021 | game-details-content | `GameDetailsContent; if game.aliases.length > 0` | `También conocido como {game.aliases.join(" · ")}` | [game-details-content.tsx:182][src31] | Source-verified; dynamic template/branch. |
| GD-022 | game-information | `GameInformation; allCredits; label` | `Desarrollador` | [game-information.tsx:48][src32] | Source-verified; literal. |
| GD-023 | game-information | `GameInformation; allCredits; label` | `Publisher` | [game-information.tsx:49][src32] | Source-verified; literal. |
| GD-024 | game-information | `GameInformation; allGroups; label` | `Géneros` | [game-information.tsx:54][src32] | Source-verified; literal. |
| GD-025 | game-information | `GameInformation; allGroups; label` | `Modos de juego` | [game-information.tsx:59][src32] | Source-verified; literal. |
| GD-026 | game-information | `GameInformation` | `Información del juego` | [game-information.tsx:68][src32] | Source-verified; literal. |
| GD-027 | game-information | `Known developer/publisher credits` | `{companyNames joined with Spanish conjunction}` | [game-information.tsx:85][src32] | Intl.ListFormat es; e.g. Estudio Uno y Estudio Dos. Omitted if unknown. |
| GD-028 | game-information | `Known genres and modes` | `{genreName}; {gameModeName}` | [game-information.tsx:96][src32] | Catalogue display names as stored; no automatic translation in React. |
| GD-029 | game-release-list | `sourceLine` | `${sources.length === 1 ? "Fuente" : "Fuentes"}: ${sources.join(", ")}` | [game-release-list.tsx:47][src33] | Source-verified; dynamic template/branch. |
| GD-030 | game-release-list | `Unique provenance source names in release order` | `Fuente: {sourceName} / Fuentes: {sourceNames, comma-separated}` | [game-release-list.tsx:47][src33] | Template; source-verified. Synthetic examples are explained below. |
| GD-031 | game-release-list | `ReleaseGroupRow` | `Lanzamiento` | [game-release-list.tsx:63][src33] | Source-verified; literal. |
| GD-032 | game-release-list | `ReleaseGroupRow` | `Estado` | [game-release-list.tsx:67][src33] | Source-verified; literal. |
| GD-033 | game-release-list | `ReleaseGroupRow` | `Región` | [game-release-list.tsx:75][src33] | Source-verified; literal. |
| GD-034 | game-release-list | `ReleaseGroupRow; else release.stage === "unknown"` | `Tipo de lanzamiento` | [game-release-list.tsx:84][src33] | Source-verified; literal. |
| GD-035 | game-release-list | `ReleaseGroupRow; if group.releases.length === 1` | `Plataforma` | [game-release-list.tsx:89][src33] | Source-verified; literal. |
| GD-036 | game-release-list | `ReleaseGroupRow; else group.releases.length === 1` | `Plataformas` | [game-release-list.tsx:89][src33] | Source-verified; literal. |
| GD-037 | game-release-list | `ReleaseGroupRow; if review \|\| verified; if review` | `Información pendiente de revisión` | [game-release-list.tsx:108][src33] | Source-verified; literal. |
| GD-038 | game-release-list | `ReleaseGroupRow; if review \|\| verified; if verified` | `Información verificada` | [game-release-list.tsx:109][src33] | Source-verified; literal. |
| GD-039 | game-release-list | `GameReleaseList` | `Fechas y plataformas` | [game-release-list.tsx:139][src33] | Source-verified; literal. |
| GD-040 | game-release-list | `GameReleaseList; if presented.length === 0` | `No hay lanzamientos comerciales registrados.` | [game-release-list.tsx:143][src33] | Source-verified; literal. |
| GD-041 | game-release-list | `GameReleaseList; if additional.length > 0` | `Otras fechas registradas ({additional.length})` | [game-release-list.tsx:150][src33] | Source-verified; dynamic template/branch. |
| GD-042 | game-release-list | `GameReleaseList; if additional.length > 0; if additional.some((release) => release.reviewStatus === "required")` | `Información pendiente de revisión` | [game-release-list.tsx:154][src33] | Source-verified; literal. |
| GD-043 | game-summary | `GameSummary` | `Resumen` | [game-summary.tsx:46][src34] | Source-verified; literal. |
| GD-044 | game-summary | `Editorial, sourced or machine-translated summary` | `{summary.text}` | [game-summary.tsx:55][src34] | Original language declared through lang; examples are synthetic, not an inventory of provider text. |
| GD-045 | game-summary | `GameSummary; if overflows \|\| expanded; if expanded` | `Mostrar menos` | [game-summary.tsx:66][src34] | Source-verified; literal. |
| GD-046 | game-summary | `GameSummary; if overflows \|\| expanded; else expanded` | `Leer más` | [game-summary.tsx:66][src34] | Source-verified; literal. |
| GD-047 | game-summary | `GameSummary; if "provenance" in summary; if "translation" in summary && summary.translation` | `Traducción automática de Gameómetro · Fuente del original: {summary.provenance.sourceName} {summary.translation.current ? null : " · Traducción pendiente de actualizar"}` | [game-summary.tsx:72][src34] | Source-verified; dynamic template/branch. |
| GD-048 | game-summary | `GameSummary; if "provenance" in summary; if "translation" in summary && summary.translation; else summary.translation.current` | `· Traducción pendiente de actualizar` | [game-summary.tsx:74][src34] | Source-verified; literal. |
| GD-049 | game-summary | `GameSummary; if "provenance" in summary; else "translation" in summary && summary.translation` | `` {language === null ? null : `Texto original en ${language} · `} Fuente: {summary.provenance.sourceName} `` | [game-summary.tsx:77][src34] | Source-verified; dynamic template/branch. |
| GD-050 | game-summary | `GameSummary; if "provenance" in summary; else "translation" in summary && summary.translation; else language === null` | `Texto original en ${language} ·` | [game-summary.tsx:78][src34] | Source-verified; dynamic template/branch. |
| GD-051 | game-details-page | `BackToReleases` | `← Volver a lanzamientos` | [game-details-page.tsx:13][src35] | Source-verified; literal. |
| GD-052 | game-details-page | `BackToReleases` | `←` | [game-details-page.tsx:14][src35] | Source-verified; literal. |
| GD-053 | game-details-page | `GameDetailsPage; if query.isPending` | `Detalle del juego` | [game-details-page.tsx:39][src35] | Source-verified; literal. |
| GD-054 | game-details-page | `GameDetailsPage; if query.isPending` | `Cargando el juego…` | [game-details-page.tsx:40][src35] | Source-verified; literal. |
| GD-055 | game-details-page | `GameDetailsPage; else query.isPending; if query.isError` | `Ficha del catálogo` | [game-details-page.tsx:48][src35] | Source-verified; literal. |
| GD-056 | game-details-page | `GameDetailsPage; else query.isPending; if query.isError; if missing` | `Juego no encontrado` | [game-details-page.tsx:51][src35] | Source-verified; literal. |
| GD-057 | game-details-page | `GameDetailsPage; else query.isPending; if query.isError; else missing; if notReady` | `El catálogo todavía no está disponible` | [game-details-page.tsx:53][src35] | Source-verified; literal. |
| GD-058 | game-details-page | `GameDetailsPage; else query.isPending; if query.isError; else missing; else notReady` | `No se pudo cargar el juego` | [game-details-page.tsx:54][src35] | Source-verified; literal. |
| GD-059 | game-details-page | `GameDetailsPage; else query.isPending; if query.isError; if missing` | `Este juego no forma parte del catálogo local disponible.` | [game-details-page.tsx:58][src35] | Source-verified; literal. |
| GD-060 | game-details-page | `GameDetailsPage; else query.isPending; if query.isError; else missing; if notReady` | `Aún no existe una publicación local válida. Inténtalo de nuevo más tarde.` | [game-details-page.tsx:60][src35] | Source-verified; literal. |
| GD-061 | game-details-page | `GameDetailsPage; else query.isPending; if query.isError; else missing; else notReady` | `No pudimos leer los datos locales. Inténtalo de nuevo más tarde.` | [game-details-page.tsx:61][src35] | Source-verified; literal. |
| GD-062 | game-details-page | `GameDetailsPage; else query.isPending; if query.isError; if !missing` | `Reintentar` | [game-details-page.tsx:65][src35] | Source-verified; literal. |
| GD-063 | game-details-page | `GameDetailsPage; else query.isPending; if query.isError; if error?.correlationId` | `Referencia para soporte: {error.correlationId}` | [game-details-page.tsx:78][src35] | Source-verified; dynamic template/branch. |

### Personal rating actions and shared keypad

| ID | Screen/component | State or trigger | Exact current text | Source (file) | Notes |
| --- | --- | --- | --- | --- | --- |
| RT-001 | game-rating-panel | `ineligibleReasons; ELIGIBLE_RELEASE_FOUND` | `Todavía no se puede puntuar este juego.` | [game-rating-panel.tsx:22][src36] | Source-verified; literal. Defensive fallback for contradictory eligibility input; not a valid normal eligible state. |
| RT-002 | game-rating-panel | `ineligibleReasons; NO_COMMERCIAL_RELEASE` | `Todavía no se puede puntuar: no hay un lanzamiento comercial registrado.` | [game-rating-panel.tsx:24][src36] | Source-verified; literal. |
| RT-003 | game-rating-panel | `ineligibleReasons; RELEASE_NOT_OCCURRED` | `Todavía no se puede puntuar: el lanzamiento aún no ha ocurrido.` | [game-rating-panel.tsx:26][src36] | Source-verified; literal. |
| RT-004 | game-rating-panel | `ineligibleReasons; RELEASE_CANCELLED` | `No se puede puntuar: los lanzamientos registrados están cancelados.` | [game-rating-panel.tsx:28][src36] | Source-verified; literal. |
| RT-005 | game-rating-panel | `ineligibleReasons; RELEASE_DATE_UNCERTAIN` | `Todavía no se puede puntuar: la fecha de lanzamiento es incierta.` | [game-rating-panel.tsx:30][src36] | Source-verified; literal. |
| RT-006 | game-rating-panel | `ineligibleReasons; RELEASE_REVIEW_REQUIRED` | `Todavía no se puede puntuar: la información del lanzamiento está pendiente de revisión.` | [game-rating-panel.tsx:32][src36] | Source-verified; literal. |
| RT-007 | game-rating-panel | `outcomeNotices; expired` | `La selección para puntuar caducó. Vuelve a elegir tu nota.` | [game-rating-panel.tsx:38][src36] | Source-verified; literal. |
| RT-008 | game-rating-panel | `outcomeNotices; cancelled` | `No se completó el inicio de sesión. Tu nota no se ha guardado.` | [game-rating-panel.tsx:39][src36] | Source-verified; literal. |
| RT-009 | game-rating-panel | `outcomeNotices; invalid` | `La nota seleccionada no era válida. Elige un valor del 1 al 10.` | [game-rating-panel.tsx:40][src36] | Source-verified; literal. |
| RT-010 | game-rating-panel | `failureMessages; authentication` | `Tu sesión ha caducado. Vuelve a elegir la nota para iniciar sesión de nuevo.` | [game-rating-panel.tsx:49][src36] | Source-verified; literal. |
| RT-011 | game-rating-panel | `failureMessages; csrf` | `No se pudo verificar la solicitud. Vuelve a intentarlo; si persiste, recarga la página.` | [game-rating-panel.tsx:50][src36] | Source-verified; literal. |
| RT-012 | game-rating-panel | `failureMessages; conflict` | `Tu nota cambió desde otra sesión. Se muestra la versión actual; revísala y vuelve a intentarlo.` | [game-rating-panel.tsx:52][src36] | Source-verified; literal. |
| RT-013 | game-rating-panel | `failureMessages; validation` | `La nota debe ser un número entero del 1 al 10.` | [game-rating-panel.tsx:53][src36] | Source-verified; literal. |
| RT-014 | game-rating-panel | `failureMessages; ineligible` | `Este juego ya no admite nuevas puntuaciones.` | [game-rating-panel.tsx:54][src36] | Source-verified; literal. |
| RT-015 | game-rating-panel | `failureMessages; "rate-limited"` | `Demasiadas solicitudes seguidas. Espera un momento y vuelve a intentarlo.` | [game-rating-panel.tsx:56][src36] | Source-verified; literal. |
| RT-016 | game-rating-panel | `failureMessages; unavailable` | `No se pudo guardar el cambio. Tu nota anterior se conserva; inténtalo de nuevo más tarde.` | [game-rating-panel.tsx:58][src36] | Source-verified; literal. |
| RT-017 | game-rating-panel | `failureMessages; ambiguous` | `No sabemos si el cambio se aplicó. Comprueba tu nota actual antes de volver a intentarlo.` | [game-rating-panel.tsx:60][src36] | Source-verified; literal. |
| RT-018 | game-rating-panel | `save; onSuccess; text` | `Puntuación guardada: ${outcome.rating.value}/10.` | [game-rating-panel.tsx:155][src36] | Source-verified; dynamic template/branch. |
| RT-019 | game-rating-panel | `remove; onSuccess; text` | `Puntuación eliminada.` | [game-rating-panel.tsx:224][src36] | Source-verified; literal. |
| RT-020 | game-rating-panel | `GameRatingPanel; status; else reason; if busy; if command.variables?.type === "delete"` | `Eliminando tu nota…` | [game-rating-panel.tsx:240][src36] | Source-verified; literal. |
| RT-021 | game-rating-panel | `GameRatingPanel; status; else reason; if busy; else command.variables?.type === "delete"` | `Guardando tu nota…` | [game-rating-panel.tsx:241][src36] | Source-verified; literal. |
| RT-022 | game-rating-panel | `GameRatingPanel; status; else reason; else busy; if checking` | `Comprobando tu nota…` | [game-rating-panel.tsx:243][src36] | Source-verified; literal. |
| RT-023 | game-rating-panel | `GameRatingPanel; status; else reason; else busy; else checking; if authenticated && personal.isError` | `No se pudo comprobar si ya tenías una nota. Puedes puntuar igualmente.` | [game-rating-panel.tsx:245][src36] | Source-verified; literal. |
| RT-024 | game-rating-panel | `GameRatingPanel; reading; if selected !== null` | `` Tu puntuación ${band ? `${thermalLabels[band]} ` : ""}${selected}/10 `` | [game-rating-panel.tsx:250][src36] | Source-verified; dynamic template/branch. |
| RT-025 | game-rating-panel | `GameRatingPanel; reading; else selected !== null; if checking` | `Tu puntuación: comprobando` | [game-rating-panel.tsx:252][src36] | Source-verified; literal. |
| RT-026 | game-rating-panel | `GameRatingPanel; reading; else selected !== null; else checking` | `Tu puntuación: sin nota` | [game-rating-panel.tsx:253][src36] | Source-verified; literal. |
| RT-027 | game-rating-panel | `GameRatingPanel; action; if !usable` | `No disponible` | [game-rating-panel.tsx:255][src36] | Source-verified; literal. |
| RT-028 | game-rating-panel | `GameRatingPanel; action; else !usable; if selected === null` | `Puntuar` | [game-rating-panel.tsx:257][src36] | Source-verified; literal. |
| RT-029 | game-rating-panel | `GameRatingPanel; action; else !usable; else selected === null; if eligible` | `Cambiar o eliminar` | [game-rating-panel.tsx:259][src36] | Source-verified; literal. |
| RT-030 | game-rating-panel | `GameRatingPanel; action; else !usable; else selected === null; else eligible` | `Eliminar` | [game-rating-panel.tsx:260][src36] | Source-verified; literal. |
| RT-031 | game-rating-panel | `GameRatingPanel` | `Tu puntuación` | [game-rating-panel.tsx:273][src36] | Source-verified; literal. |
| RT-032 | game-rating-panel | `Accessible rating trigger` | `{reading}. {action}` | [game-rating-panel.tsx:287][src36] | reading and action variants are inventoried in this component; e.g. Tu puntuación Caliente 7/10. Cambiar o eliminar. |
| RT-033 | game-rating-panel | `GameRatingPanel` | `Tu puntuación {temperature if available}` | [game-rating-panel.tsx:296][src36] | Source-verified; dynamic template/branch. |
| RT-034 | game-rating-panel | `GameRatingPanel; if selected !== null` | `{selected}/10` | [game-rating-panel.tsx:300][src36] | Source-verified; dynamic template/branch. |
| RT-035 | game-rating-panel | `GameRatingPanel; else selected !== null; if !usable` | `No disponible` | [game-rating-panel.tsx:303][src36] | Source-verified; literal. |
| RT-036 | game-rating-panel | `GameRatingPanel; else selected !== null; else !usable; else checking` | `Puntuar` | [game-rating-panel.tsx:303][src36] | Source-verified; literal. |
| RT-037 | game-rating-panel | `Personal rating still being checked; usable trigger` | `—` | [game-rating-panel.tsx:303][src36] | Template; source-verified. Synthetic examples are explained below. |
| RT-038 | game-rating-panel | `GameRatingPanel; if feedback?.tone === "error"; if feedback.error.kind === "ambiguous"` | `Comprobar mi nota` | [game-rating-panel.tsx:334][src36] | Source-verified; literal. |
| RT-039 | game-rating-panel | `GameRatingPanel; if feedback?.tone === "error"; if feedback.error.correlationId` | `Referencia para soporte: {feedback.error.correlationId}` | [game-rating-panel.tsx:339][src36] | Source-verified; dynamic template/branch. |
| RT-040 | game-rating-panel | `GameRatingPanel; if open` | `Tu puntuación de ${game.canonicalTitle}` | [game-rating-panel.tsx:348][src36] | Source-verified; dynamic template/branch. |
| RT-041 | game-rating-panel | `GameRatingPanel; if open` | `Selecciona una nota` | [game-rating-panel.tsx:355][src36] | Source-verified; literal. |
| RT-042 | game-rating-panel | `GameRatingPanel; if open; if persisted !== null` | `Eliminar puntuación` | [game-rating-panel.tsx:364][src36] | Source-verified; literal. |
| RT-043 | rating-keypad | `RatingKeypad` | `Nota del 1 al 10` | [rating-keypad.tsx:44][src37] | Source-verified; accessible name/announcement. |
| RT-044 | rating-keypad | `Keypad buttons; both rating editors` | `1; 2; 3; 4; 5; 6; 7; 8; 9; 10` | [rating-keypad.tsx:60][src37] | Template; source-verified. Synthetic examples are explained below. |
| RT-045 | rating-keypad | `RatingKeypad` | `1 · {thermalLabels.freeze}` | [rating-keypad.tsx:67][src37] | Source-verified; dynamic template/branch. |
| RT-046 | rating-keypad | `Scale-end labels; both editors` | `1 · Congelado / 10 · Ardiendo` | [rating-keypad.tsx:67][src37] | Template; source-verified. Synthetic examples are explained below. |
| RT-047 | rating-keypad | `RatingKeypad` | `10 · {thermalLabels.burn}` | [rating-keypad.tsx:68][src37] | Source-verified; dynamic template/branch. |

### Mis puntuaciones and its editor

| ID | Screen/component | State or trigger | Exact current text | Source (file) | Notes |
| --- | --- | --- | --- | --- | --- |
| MY-001 | my-rating-card | `failures; authentication` | `Tu sesión ha caducado. Puedes iniciar sesión al puntuar desde la ficha del juego.` | [my-rating-card.tsx:15][src38] | Source-verified; literal. |
| MY-002 | my-rating-card | `failures; csrf` | `No se pudo verificar la solicitud. Recarga la página antes de volver a intentarlo.` | [my-rating-card.tsx:16][src38] | Source-verified; literal. |
| MY-003 | my-rating-card | `failures; conflict` | `Tu puntuación cambió en otra sesión. Te mostramos la nota actual: revísala antes de volver a intentarlo.` | [my-rating-card.tsx:17][src38] | Source-verified; literal. |
| MY-004 | my-rating-card | `failures; validation` | `Elige una nota entera del 1 al 10.` | [my-rating-card.tsx:18][src38] | Source-verified; literal. |
| MY-005 | my-rating-card | `failures; ineligible` | `Este juego ya no admite cambios de nota. Puedes eliminar tu puntuación.` | [my-rating-card.tsx:19][src38] | Source-verified; literal. |
| MY-006 | my-rating-card | `failures; "rate-limited"` | `Espera un momento antes de volver a intentarlo.` | [my-rating-card.tsx:20][src38] | Source-verified; literal. |
| MY-007 | my-rating-card | `failures; unavailable` | `No se pudo aplicar el cambio. Tu puntuación anterior se conserva.` | [my-rating-card.tsx:21][src38] | Source-verified; literal. |
| MY-008 | my-rating-card | `failures; ambiguous` | `No sabemos si se aplicó el cambio. Comprobamos tu nota actual antes de permitir otro cambio.` | [my-rating-card.tsx:22][src38] | Source-verified; literal. |
| MY-009 | my-rating-card | `save; onSuccess` | `Puntuación de ${game.canonicalTitle} guardada: ${selected}/10.` | [my-rating-card.tsx:136][src38] | Source-verified; dynamic template/branch. |
| MY-010 | my-rating-card | `remove; onSuccess` | `Puntuación de ${game.canonicalTitle} eliminada.` | [my-rating-card.tsx:145][src38] | Source-verified; dynamic template/branch. |
| MY-011 | my-rating-card | `MyRatingCard` | `` Tu puntuación ${band ? `${thermalLabels[band]} ` : ""}${current}/10. Cambiar o eliminar `` | [my-rating-card.tsx:164][src38] | Source-verified; dynamic template/branch. |
| MY-012 | my-rating-card | `MyRatingCard` | `Tu puntuación` | [my-rating-card.tsx:168][src38] | Source-verified; literal. |
| MY-013 | my-rating-card | `MyRatingCard` | `{current}/10{temperature if available}` | [my-rating-card.tsx:169][src38] | Source-verified; dynamic template/branch. |
| MY-014 | my-rating-card | `MyRatingCard; if open` | `Tu puntuación de ${game.canonicalTitle}` | [my-rating-card.tsx:175][src38] | Source-verified; dynamic template/branch. |
| MY-015 | my-rating-card | `MyRatingCard; if open` | `{changed ? "Nueva nota" : "Nota actual"} {selected}/10 {pendingTemperature if available}` | [my-rating-card.tsx:180][src38] | Source-verified; dynamic template/branch. |
| MY-016 | my-rating-card | `MyRatingCard; if open; if changed` | `Nueva nota` | [my-rating-card.tsx:181][src38] | Source-verified; literal. |
| MY-017 | my-rating-card | `MyRatingCard; if open; else changed` | `Nota actual` | [my-rating-card.tsx:181][src38] | Source-verified; literal. |
| MY-018 | my-rating-card | `MyRatingCard; if open; if changed` | `Nota actual {current}/10 {temperature if available}` | [my-rating-card.tsx:185][src38] | Source-verified; dynamic template/branch. |
| MY-019 | my-rating-card | `MyRatingCard; if open; if changed` | `Nota actual` | [my-rating-card.tsx:186][src38] | Source-verified; literal. |
| MY-020 | my-rating-card | `MyRatingCard; if open; if busy; if command.variables?.type === "delete"` | `Eliminando puntuación…` | [my-rating-card.tsx:194][src38] | Source-verified; literal. |
| MY-021 | my-rating-card | `MyRatingCard; if open; if busy; else command.variables?.type === "delete"` | `Guardando cambio…` | [my-rating-card.tsx:194][src38] | Source-verified; literal. |
| MY-022 | my-rating-card | `MyRatingCard; if open; if failure; if failure.error.correlationId` | `Referencia: {failure.error.correlationId}` | [my-rating-card.tsx:198][src38] | Source-verified; dynamic template/branch. |
| MY-023 | my-rating-card | `MyRatingCard; if open` | `Cancelar` | [my-rating-card.tsx:201][src38] | Source-verified; literal. |
| MY-024 | my-rating-card | `MyRatingCard; if open` | `Guardar nota` | [my-rating-card.tsx:202][src38] | Source-verified; literal. |
| MY-025 | my-rating-card | `MyRatingCard; if open` | `Eliminar puntuación` | [my-rating-card.tsx:206][src38] | Source-verified; literal. |
| MY-026 | my-rating-card | `MyRatingCard; if open; if confirmingDelete` | `¿Eliminar tu nota de {current}/10? Se borrará de tu colección.` | [my-rating-card.tsx:211][src38] | Source-verified; dynamic template/branch. |
| MY-027 | my-rating-card | `MyRatingCard; if open; if confirmingDelete` | `Conservar` | [my-rating-card.tsx:212][src38] | Source-verified; literal. |
| MY-028 | my-rating-card | `MyRatingCard; if open; if confirmingDelete` | `Sí, eliminar` | [my-rating-card.tsx:214][src38] | Source-verified; literal. |
| MY-029 | my-rating-card | `MyRatingCard; if createdDate === updatedDate` | `Puntuado el {createdDate}` | [my-rating-card.tsx:223][src38] | Source-verified; dynamic template/branch. |
| MY-030 | my-rating-card | `Creation date; es-ES short date` | `Puntuado el {createdDate}` | [my-rating-card.tsx:223][src38] | Template; source-verified. Synthetic examples are explained below. |
| MY-031 | my-rating-card | `MyRatingCard; else createdDate === updatedDate` | `Actualizado el {updatedDate}` | [my-rating-card.tsx:224][src38] | Source-verified; dynamic template/branch. |
| MY-032 | my-rating-card | `Updated and creation dates when different` | `Actualizado el {updatedDate} · Puntuado el {createdDate}` | [my-rating-card.tsx:224][src38] | Template; source-verified. Synthetic examples are explained below. |
| MY-033 | my-rating-card | `MyRatingCard; else createdDate === updatedDate` | `·` | [my-rating-card.tsx:225][src38] | Source-verified; literal. |
| MY-034 | my-rating-card | `MyRatingCard; else createdDate === updatedDate` | `· Puntuado el {createdDate}` | [my-rating-card.tsx:225][src38] | Source-verified; dynamic template/branch. |
| MY-035 | my-ratings-page | `orders; label` | `Más recientes` | [my-ratings-page.tsx:34][src39] | Source-verified; literal. |
| MY-036 | my-ratings-page | `orders; label` | `Más antiguas` | [my-ratings-page.tsx:35][src39] | Source-verified; literal. |
| MY-037 | my-ratings-page | `orders; label` | `Título A–Z` | [my-ratings-page.tsx:36][src39] | Source-verified; literal. |
| MY-038 | my-ratings-page | `orders; label` | `Título Z–A` | [my-ratings-page.tsx:37][src39] | Source-verified; literal. |
| MY-039 | my-ratings-page | `orders; label` | `Nota más alta` | [my-ratings-page.tsx:38][src39] | Source-verified; literal. |
| MY-040 | my-ratings-page | `orders; label` | `Nota más baja` | [my-ratings-page.tsx:39][src39] | Source-verified; literal. |
| MY-041 | my-ratings-page | `MyRatingsPage` | `Tu colección de notas` | [my-ratings-page.tsx:77][src39] | Source-verified; literal. |
| MY-042 | my-ratings-page | `MyRatingsPage` | `Mis puntuaciones` | [my-ratings-page.tsx:78][src39] | Source-verified; tooltip. |
| MY-043 | my-ratings-page | `MyRatingsPage; if session.isPending` | `Comprobando sesión…` | [my-ratings-page.tsx:80][src39] | Source-verified; literal. |
| MY-044 | my-ratings-page | `MyRatingsPage; else session.isPending; if session.isError` | `Sesión` | [my-ratings-page.tsx:82][src39] | Source-verified; literal. |
| MY-045 | my-ratings-page | `MyRatingsPage; else session.isPending; if session.isError` | `No se pudo comprobar tu sesión.` | [my-ratings-page.tsx:83][src39] | Source-verified; literal. |
| MY-046 | my-ratings-page | `MyRatingsPage; else session.isPending; if session.isError` | `Reintentar` | [my-ratings-page.tsx:83][src39] | Source-verified; literal. |
| MY-047 | my-ratings-page | `MyRatingsPage; else session.isPending; else session.isError; if csrfToken === null \|\| query.error?.code === "AUTHENTICATION_REQUIRED"` | `Necesitas una sesión activa` | [my-ratings-page.tsx:85][src39] | Source-verified; literal. |
| MY-048 | my-ratings-page | `MyRatingsPage; else session.isPending; else session.isError; if csrfToken === null \|\| query.error?.code === "AUTHENTICATION_REQUIRED"` | `Inicia sesión para volver a tus puntuaciones.` | [my-ratings-page.tsx:86][src39] | Source-verified; literal. |
| MY-049 | my-ratings-page | `MyRatingsPage; else session.isPending; else session.isError; if csrfToken === null \|\| query.error?.code === "AUTHENTICATION_REQUIRED"` | `Iniciar sesión` | [my-ratings-page.tsx:87][src39] | Source-verified; literal. |
| MY-050 | my-ratings-page | `MyRatingsPage; else session.isPending; else session.isError; else csrfToken === null \|\| query.error?.code === "AUTHENTICATION_REQUIRED"` | `Buscar en mis puntuaciones` | [my-ratings-page.tsx:89][src39] | Source-verified; accessible name/announcement. |
| MY-051 | my-ratings-page | `MyRatingsPage; else session.isPending; else session.isError; else csrfToken === null \|\| query.error?.code === "AUTHENTICATION_REQUIRED"; if [...q].length > 100 \|\| (q !== "" && !/[\p{L}\p{N}]/u.test(q))` | `Escribe un título o alias de hasta 100 caracteres.` | [my-ratings-page.tsx:94][src39] | Source-verified; literal. |
| MY-052 | my-ratings-page | `MyRatingsPage; else session.isPending; else session.isError; else csrfToken === null \|\| query.error?.code === "AUTHENTICATION_REQUIRED"` | `Título o alias de tus juegos puntuados` | [my-ratings-page.tsx:103][src39] | Source-verified; literal. |
| MY-053 | my-ratings-page | `MyRatingsPage; else session.isPending; else session.isError; else csrfToken === null \|\| query.error?.code === "AUTHENTICATION_REQUIRED"` | `Título o alias` | [my-ratings-page.tsx:106][src39] | Source-verified; input placeholder. |
| MY-054 | my-ratings-page | `MyRatingsPage; else session.isPending; else session.isError; else csrfToken === null \|\| query.error?.code === "AUTHENTICATION_REQUIRED"` | `Buscar en mis puntuaciones` | [my-ratings-page.tsx:109][src39] | Source-verified; literal. |
| MY-055 | my-ratings-page | `MyRatingsPage; else session.isPending; else session.isError; else csrfToken === null \|\| query.error?.code === "AUTHENTICATION_REQUIRED"` | `en mis puntuaciones` | [my-ratings-page.tsx:110][src39] | Source-verified; literal. |
| MY-056 | my-ratings-page | `MyRatingsPage; else session.isPending; else session.isError; else csrfToken === null \|\| query.error?.code === "AUTHENTICATION_REQUIRED"` | `Orden` | [my-ratings-page.tsx:119][src39] | Source-verified; literal. |
| MY-057 | my-ratings-page | `MyRatingsPage; else session.isPending; else session.isError; else csrfToken === null \|\| query.error?.code === "AUTHENTICATION_REQUIRED"` | `Por página` | [my-ratings-page.tsx:132][src39] | Source-verified; literal. |
| MY-058 | my-ratings-page | `Page-size options` | `10; 20; 50` | [my-ratings-page.tsx:134][src39] | Template; source-verified. Synthetic examples are explained below. |
| MY-059 | my-ratings-page | `MyRatingsPage; else session.isPending; else session.isError; else csrfToken === null \|\| query.error?.code === "AUTHENTICATION_REQUIRED"` | `Resultados de mis puntuaciones` | [my-ratings-page.tsx:139][src39] | Source-verified; literal. |
| MY-060 | my-ratings-page | `MyRatingsPage; else session.isPending; else session.isError; else csrfToken === null \|\| query.error?.code === "AUTHENTICATION_REQUIRED"; if query.isFetching` | `Actualizando puntuaciones…` | [my-ratings-page.tsx:140][src39] | Source-verified; literal. |
| MY-061 | my-ratings-page | `MyRatingsPage; else session.isPending; else session.isError; else csrfToken === null \|\| query.error?.code === "AUTHENTICATION_REQUIRED"; else query.isFetching; if data` | `${data.page.totalItems} puntuaciones · Página ${data.page.number} de ${Math.max(data.page.totalPages, 1)}` | [my-ratings-page.tsx:141][src39] | Source-verified; dynamic template/branch. |
| MY-062 | my-ratings-page | `Collection count; includes current singular defect` | `{totalItems} puntuaciones · Página {number} de {max(totalPages, 1)}` | [my-ratings-page.tsx:141][src39] | Template; source-verified. Synthetic examples are explained below. |
| MY-063 | my-ratings-page | `MyRatingsPage; else session.isPending; else session.isError; else csrfToken === null \|\| query.error?.code === "AUTHENTICATION_REQUIRED"; if query.isError` | `Error de carga` | [my-ratings-page.tsx:143][src39] | Source-verified; literal. |
| MY-064 | my-ratings-page | `MyRatingsPage; else session.isPending; else session.isError; else csrfToken === null \|\| query.error?.code === "AUTHENTICATION_REQUIRED"; if query.isError` | `No se pudieron cargar tus puntuaciones. Inténtalo de nuevo.` | [my-ratings-page.tsx:144][src39] | Source-verified; literal. |
| MY-065 | my-ratings-page | `MyRatingsPage; else session.isPending; else session.isError; else csrfToken === null \|\| query.error?.code === "AUTHENTICATION_REQUIRED"; if query.isError` | `Reintentar carga` | [my-ratings-page.tsx:145][src39] | Source-verified; literal. |
| MY-066 | my-ratings-page | `MyRatingsPage; else session.isPending; else session.isError; else csrfToken === null \|\| query.error?.code === "AUTHENTICATION_REQUIRED"; else query.isPending; if data; if data.items.length === 0` | `Sin resultados` | [my-ratings-page.tsx:150][src39] | Source-verified; literal. |
| MY-067 | my-ratings-page | `MyRatingsPage; else session.isPending; else session.isError; else csrfToken === null \|\| query.error?.code === "AUTHENTICATION_REQUIRED"; else query.isPending; if data; if data.items.length === 0; if data.page.totalItems > 0` | `Esta página ya no tiene resultados` | [my-ratings-page.tsx:151][src39] | Source-verified; literal. |
| MY-068 | my-ratings-page | `MyRatingsPage; else session.isPending; else session.isError; else csrfToken === null \|\| query.error?.code === "AUTHENTICATION_REQUIRED"; else query.isPending; if data; if data.items.length === 0; else data.page.totalItems > 0; if params.q` | `No hay puntuaciones que coincidan con tu búsqueda` | [my-ratings-page.tsx:152][src39] | Source-verified; literal. |
| MY-069 | my-ratings-page | `MyRatingsPage; else session.isPending; else session.isError; else csrfToken === null \|\| query.error?.code === "AUTHENTICATION_REQUIRED"; else query.isPending; if data; if data.items.length === 0; else data.page.totalItems > 0; else params.q` | `Todavía no has puntuado ningún juego` | [my-ratings-page.tsx:152][src39] | Source-verified; literal. |
| MY-070 | my-ratings-page | `MyRatingsPage; else session.isPending; else session.isError; else csrfToken === null \|\| query.error?.code === "AUTHENTICATION_REQUIRED"; else query.isPending; if data; if data.items.length === 0; if params.q` | `Limpiar búsqueda` | [my-ratings-page.tsx:153][src39] | Source-verified; literal. |
| MY-071 | my-ratings-page | `MyRatingsPage; else session.isPending; else session.isError; else csrfToken === null \|\| query.error?.code === "AUTHENTICATION_REQUIRED"; else query.isPending; if data; if data.items.length === 0; else params.q` | `Explorar el catálogo` | [my-ratings-page.tsx:156][src39] | Source-verified; literal. |
| MY-072 | my-ratings-page | `MyRatingsPage; else session.isPending; else session.isError; else csrfToken === null \|\| query.error?.code === "AUTHENTICATION_REQUIRED"; else query.isPending; if data; if data.page.number > 1 \|\| data.page.number < data.page.totalPages` | `Paginación de mis puntuaciones` | [my-ratings-page.tsx:162][src39] | Source-verified; accessible name/announcement. |
| MY-073 | my-ratings-page | `MyRatingsPage; else session.isPending; else session.isError; else csrfToken === null \|\| query.error?.code === "AUTHENTICATION_REQUIRED"; else query.isPending; if data; if data.page.number > 1 \|\| data.page.number < data.page.totalPages; if data.page.number > 1` | `← Página anterior` | [my-ratings-page.tsx:163][src39] | Source-verified; literal. |
| MY-074 | my-ratings-page | `MyRatingsPage; else session.isPending; else session.isError; else csrfToken === null \|\| query.error?.code === "AUTHENTICATION_REQUIRED"; else query.isPending; if data; if data.page.number > 1 \|\| data.page.number < data.page.totalPages; if data.page.number > 1` | `←` | [my-ratings-page.tsx:165][src39] | Source-verified; literal. |
| MY-075 | my-ratings-page | `MyRatingsPage; else session.isPending; else session.isError; else csrfToken === null \|\| query.error?.code === "AUTHENTICATION_REQUIRED"; else query.isPending; if data; if data.page.number > 1 \|\| data.page.number < data.page.totalPages; if data.page.number < data.page.totalPages` | `Página siguiente →` | [my-ratings-page.tsx:167][src39] | Source-verified; literal. |
| MY-076 | my-ratings-page | `MyRatingsPage; else session.isPending; else session.isError; else csrfToken === null \|\| query.error?.code === "AUTHENTICATION_REQUIRED"; else query.isPending; if data; if data.page.number > 1 \|\| data.page.number < data.page.totalPages; if data.page.number < data.page.totalPages` | `→` | [my-ratings-page.tsx:169][src39] | Source-verified; literal. |

### Administration: access, synchronization requests, controls, runs and coverage

Route `/administracion/catalogo`; `tab=ejecuciones|cobertura`, optional `year`/`month`. Operator text may retain catalogue and operational vocabulary under #341. These actions were simulated; no real synchronization was requested, stopped or abandoned.

| ID | Screen/component | State or trigger | Exact current text | Source (file) | Notes |
| --- | --- | --- | --- | --- | --- |
| AD-001 | administration-format | `formatInstant; es-ES, Europe/Madrid` | `{day} {shortMonth} {year}, {hour}:{minute}` | [administration-format.ts:13][src40] | Locale-generated: for example 19 oct 2026, 12:30; exact separators follow Intl/ICU. |
| AD-002 | administration-format | `formatDay; calendar date, no time-zone shift` | `{day} {shortMonth} {year}` | [administration-format.ts:17][src40] | Template; source-verified. Synthetic examples are explained below. |
| AD-003 | administration-format | `formatInterval; a single date when both endpoints equal` | `{fromDate} – {toDate}` | [administration-format.ts:21][src40] | Template; source-verified. Synthetic examples are explained below. |
| AD-004 | administration-format | `formatMonth; e.g. Octubre` | `{CapitalizedMonth}` | [administration-format.ts:26][src40] | Template; source-verified. Synthetic examples are explained below. |
| AD-005 | administration-format | `Duration under 60 seconds` | `{seconds} s` | [administration-format.ts:36][src40] | Template; source-verified. Synthetic examples are explained below. |
| AD-006 | administration-format | `Duration under 60 minutes` | `{minutes} min {secondsRemainder} s` | [administration-format.ts:38][src40] | Template; source-verified. Synthetic examples are explained below. |
| AD-007 | administration-format | `formatDuration` | `${Math.floor(minutes / 60)} h ${minutes % 60} min` | [administration-format.ts:39][src40] | Source-verified; dynamic template/branch. |
| AD-008 | administration-format | `runReading; if run.outcomeCode === STOPPED; label` | `Detenida` | [administration-format.ts:57][src40] | Source-verified; literal. |
| AD-009 | administration-format | `runReading; if run.outcomeCode === STOPPED; explanation; if run.result === "partial"` | `Se detuvo a petición de un operador antes de leer todo el intervalo: conserva los juegos publicados y no registra cobertura.` | [administration-format.ts:59][src40] | Source-verified; literal. |
| AD-010 | administration-format | `runReading; if run.outcomeCode === STOPPED; explanation; else run.result === "partial"` | `Se detuvo a petición de un operador sin publicar ningún juego; no registra cobertura.` | [administration-format.ts:60][src40] | Source-verified; literal. |
| AD-011 | administration-format | `runReading; case "succeeded"; label` | `Completada` | [administration-format.ts:64][src40] | Source-verified; literal. |
| AD-012 | administration-format | `runReading; case "succeeded"; explanation` | `Leyó todo el intervalo sin juegos fallidos.` | [administration-format.ts:64][src40] | Source-verified; literal. |
| AD-013 | administration-format | `runReading; case "partial"; if run.outcomeCode === "SYNCHRONIZATION_INTERRUPTED"; label` | `Interrumpida` | [administration-format.ts:67][src40] | Source-verified; literal. |
| AD-014 | administration-format | `runReading; case "partial"; if run.outcomeCode === "SYNCHRONIZATION_INTERRUPTED"; explanation` | `Se detuvo antes de leer todas las páginas: publicó algunos juegos, pero no registra cobertura.` | [administration-format.ts:68][src40] | Source-verified; literal. |
| AD-015 | administration-format | `runReading; case "partial"; if run.outcomeCode === "SYNCHRONIZATION_COMPLETED_WITH_FAILURES"; label` | `Completa con juegos fallidos` | [administration-format.ts:71][src40] | Source-verified; literal. |
| AD-016 | administration-format | `runReading; case "partial"; if run.outcomeCode === "SYNCHRONIZATION_COMPLETED_WITH_FAILURES"; explanation` | `Leyó todo el intervalo; los juegos fallidos conservan su último estado válido.` | [administration-format.ts:72][src40] | Source-verified; literal. |
| AD-017 | administration-format | `runReading; case "partial"; label` | `Parcial` | [administration-format.ts:74][src40] | Source-verified; literal. |
| AD-018 | administration-format | `runReading; case "partial"; explanation` | `Publicó parte de los juegos del intervalo.` | [administration-format.ts:74][src40] | Source-verified; literal. |
| AD-019 | administration-format | `runReading; case "failed"; label` | `Fallida` | [administration-format.ts:76][src40] | Source-verified; literal. |
| AD-020 | administration-format | `runReading; case "failed"; explanation` | `No publicó ningún juego y no registra cobertura; se conserva el estado anterior.` | [administration-format.ts:77][src40] | Source-verified; literal. |
| AD-021 | administration-format | `runReading; case "abandoned"; if run.outcomeCode === ABANDONED_BY_OPERATOR; label` | `Abandonada por un operador` | [administration-format.ts:80][src40] | Source-verified; literal. |
| AD-022 | administration-format | `runReading; case "abandoned"; if run.outcomeCode === ABANDONED_BY_OPERATOR; explanation` | `Su proceso había dejado de dar señales y un operador la cerró: no puede publicar más juegos ni registra cobertura o contadores; lo ya publicado se conserva.` | [administration-format.ts:81][src40] | Source-verified; literal. |
| AD-023 | administration-format | `runReading; case "abandoned"; label` | `Abandonada` | [administration-format.ts:83][src40] | Source-verified; literal. |
| AD-024 | administration-format | `runReading; case "abandoned"; explanation` | `El proceso dejó de dar señales; no registra cobertura ni contadores.` | [administration-format.ts:84][src40] | Source-verified; literal. |
| AD-025 | administration-format | `coverageLabels; complete` | `Cubierto` | [administration-format.ts:89][src40] | Source-verified; literal. |
| AD-026 | administration-format | `coverageLabels; partial` | `Parcialmente cubierto` | [administration-format.ts:90][src40] | Source-verified; literal. |
| AD-027 | administration-format | `coverageLabels; never` | `Nunca cubierto` | [administration-format.ts:91][src40] | Source-verified; literal. |
| AD-028 | administration-format | `coverageLabels; unknown` | `Sin describir` | [administration-format.ts:92][src40] | Source-verified; literal. |
| AD-029 | administration-format | `coveredShare` | `${formatCount(summary.coveredDays)} de ${formatCount(summary.totalDays)} días` | [administration-format.ts:96][src40] | Source-verified; dynamic template/branch. |
| AD-030 | administration-format | `freshnessLabel; if summary.recent === null \|\| summary.oldestScan === null` | `Sin escaneos` | [administration-format.ts:101][src40] | Source-verified; literal. |
| AD-031 | administration-format | `freshnessLabel; if summary.recent` | `Reciente` | [administration-format.ts:102][src40] | Source-verified; literal. |
| AD-032 | administration-format | `freshnessLabel` | `Hace ${formatCount(days)} días` | [administration-format.ts:104][src40] | Source-verified; dynamic template/branch. |
| AD-033 | coverage-explorer | `CoverageCell; if summary.incomplete && summary.state !== "unknown"` | `(resumen incompleto)` | [coverage-explorer.tsx:31][src41] | Source-verified; literal. |
| AD-034 | coverage-explorer | `SummaryCells; if summary.failedGames` | `Con juegos fallidos` | [coverage-explorer.tsx:42][src41] | Source-verified; literal. |
| AD-035 | coverage-explorer | `SummaryHead` | `Cobertura` | [coverage-explorer.tsx:51][src41] | Source-verified; literal. |
| AD-036 | coverage-explorer | `SummaryHead` | `Días cubiertos` | [coverage-explorer.tsx:52][src41] | Source-verified; literal. |
| AD-037 | coverage-explorer | `SummaryHead` | `Escaneo completo más antiguo` | [coverage-explorer.tsx:53][src41] | Source-verified; literal. |
| AD-038 | coverage-explorer | `SummaryHead` | `Frescura` | [coverage-explorer.tsx:54][src41] | Source-verified; literal. |
| AD-039 | coverage-explorer | `Failure` | `Error de carga` | [coverage-explorer.tsx:61][src41] | Source-verified; literal. |
| AD-040 | coverage-explorer | `Failure` | `No se pudo leer la cobertura de este periodo.` | [coverage-explorer.tsx:62][src41] | Source-verified; literal. |
| AD-041 | coverage-explorer | `Failure` | `Reintentar` | [coverage-explorer.tsx:63][src41] | Source-verified; literal. |
| AD-042 | coverage-explorer | `Failure; if error?.correlationId` | `Referencia para soporte: {error.correlationId}` | [coverage-explorer.tsx:64][src41] | Source-verified; dynamic template/branch. |
| AD-043 | coverage-explorer | `CoverageExplorer` | `Décadas` | [coverage-explorer.tsx:99][src41] | Source-verified; accessible name/announcement. |
| AD-044 | coverage-explorer | `CoverageExplorer; if decade - 10 >= decadeOf(MIN_YEAR)` | `← {decade - 10}–{decade - 1}` | [coverage-explorer.tsx:100][src41] | Source-verified; dynamic template/branch. |
| AD-045 | coverage-explorer | `CoverageExplorer; if decade - 10 >= decadeOf(MIN_YEAR)` | `←` | [coverage-explorer.tsx:101][src41] | Source-verified; literal. |
| AD-046 | coverage-explorer | `CoverageExplorer` | `Década {decade}–{decade + 9}` | [coverage-explorer.tsx:103][src41] | Source-verified; dynamic template/branch. |
| AD-047 | coverage-explorer | `CoverageExplorer; if decade + 10 <= MAX_YEAR` | `{decade + 10}–{decade + 19} →` | [coverage-explorer.tsx:104][src41] | Source-verified; dynamic template/branch. |
| AD-048 | coverage-explorer | `CoverageExplorer; if decade + 10 <= MAX_YEAR` | `→` | [coverage-explorer.tsx:105][src41] | Source-verified; literal. |
| AD-049 | coverage-explorer | `CoverageExplorer; if decadeRead.isPending` | `Cargando la cobertura de la década…` | [coverage-explorer.tsx:109][src41] | Source-verified; literal. |
| AD-050 | coverage-explorer | `CoverageExplorer; else decadeRead.isPending; else decadeRead.isError` | `Cobertura por año` | [coverage-explorer.tsx:111][src41] | Source-verified; accessible name/announcement. |
| AD-051 | coverage-explorer | `CoverageExplorer; else decadeRead.isPending; else decadeRead.isError` | `Cobertura por año, {decade}–{decade + 9}` | [coverage-explorer.tsx:113][src41] | Source-verified; dynamic template/branch. |
| AD-052 | coverage-explorer | `CoverageExplorer; if decadeRead.data?.continuesFrom` | `La década tiene más intervalos de los que caben en una respuesta: el resumen se detiene el {formatDay(decadeRead.data.continuesFrom)}. Abre un año para verlo completo.` | [coverage-explorer.tsx:128][src41] | Source-verified; dynamic template/branch. |
| AD-053 | coverage-explorer | `CoverageExplorer` | `Meses de {year}` | [coverage-explorer.tsx:134][src41] | Source-verified; dynamic template/branch. |
| AD-054 | coverage-explorer | `CoverageExplorer; if yearData` | `Cobertura por mes de ${year}` | [coverage-explorer.tsx:135][src41] | Source-verified; dynamic template/branch. |
| AD-055 | coverage-explorer | `CoverageExplorer; if yearData` | `Cobertura por mes de {year}` | [coverage-explorer.tsx:137][src41] | Source-verified; dynamic template/branch. |
| AD-056 | coverage-explorer | `CoverageExplorer; else yearData; else yearRead.isError; else decadeRead.isError` | `Cargando los meses de {year}…` | [coverage-explorer.tsx:156][src41] | Source-verified; dynamic template/branch. |
| AD-057 | coverage-explorer | `CoverageExplorer; if month !== null && monthFrom && monthTo` | `Periodos de {formatMonth(year, month).toLowerCase()} de {year}` | [coverage-explorer.tsx:160][src41] | Source-verified; dynamic template/branch. |
| AD-058 | coverage-explorer | `CoverageExplorer; if month !== null && monthFrom && monthTo; if yearData; if period.status === "covered"` | `Cubierto` | [coverage-explorer.tsx:165][src41] | Source-verified; literal. |
| AD-059 | coverage-explorer | `CoverageExplorer; if month !== null && monthFrom && monthTo; if yearData; else period.status === "covered"` | `Nunca cubierto` | [coverage-explorer.tsx:165][src41] | Source-verified; literal. |
| AD-060 | coverage-explorer | `CoverageExplorer; if month !== null && monthFrom && monthTo; if yearData; if period.lastScan` | `` Escaneo completo terminado el {formatInstant(period.lastScan.scanCompletedAt)} {period.lastScan.failedGames > 0 ? ` · su ejecución tuvo ${period.lastScan.failedGames} juegos fallidos` : " · sin juegos fallidos"} `` | [coverage-explorer.tsx:166][src41] | Source-verified; dynamic template/branch. |
| AD-061 | coverage-explorer | `CoverageExplorer; if month !== null && monthFrom && monthTo; if yearData; if period.lastScan; if period.lastScan.failedGames > 0` | `· su ejecución tuvo ${period.lastScan.failedGames} juegos fallidos` | [coverage-explorer.tsx:169][src41] | Source-verified; dynamic template/branch. |
| AD-062 | coverage-explorer | `CoverageExplorer; if month !== null && monthFrom && monthTo; if yearData; if period.lastScan; else period.lastScan.failedGames > 0` | `· sin juegos fallidos` | [coverage-explorer.tsx:170][src41] | Source-verified; literal. |
| AD-063 | coverage-explorer | `CoverageExplorer; if month !== null && monthFrom && monthTo` | `Cerrar el detalle del mes` | [coverage-explorer.tsx:174][src41] | Source-verified; literal. |
| AD-064 | coverage-explorer | `CoverageExplorer` | `«Reciente» significa que el escaneo completo más antiguo del periodo tiene {RECENT_SCAN_DAYS} días o menos.` | [coverage-explorer.tsx:176][src41] | Source-verified; dynamic template/branch. |
| AD-065 | synchronization-control | `RejectionNotice; case "SYNCHRONIZATION_RUN_FINISHED"` | `Sin cambios` | [synchronization-control.tsx:42][src42] | Source-verified; literal. |
| AD-066 | synchronization-control | `RejectionNotice; case "SYNCHRONIZATION_RUN_FINISHED"` | `La ejecución ya había terminado` | [synchronization-control.tsx:43][src42] | Source-verified; literal. |
| AD-067 | synchronization-control | `RejectionNotice; case "SYNCHRONIZATION_RUN_FINISHED"` | `No se ha cambiado nada. Su resultado aparece en Ejecuciones recientes.` | [synchronization-control.tsx:44][src42] | Source-verified; literal. |
| AD-068 | synchronization-control | `RejectionNotice; case "SYNCHRONIZATION_RUN_NOT_FOUND"` | `Sin cambios` | [synchronization-control.tsx:48][src42] | Source-verified; literal. |
| AD-069 | synchronization-control | `RejectionNotice; case "SYNCHRONIZATION_RUN_NOT_FOUND"` | `La ejecución ya no está en el historial` | [synchronization-control.tsx:49][src42] | Source-verified; literal. |
| AD-070 | synchronization-control | `RejectionNotice; case "SYNCHRONIZATION_RUN_NOT_FOUND"` | `No se ha cambiado nada. El estado se ha actualizado con la ejecución actual, si la hay.` | [synchronization-control.tsx:50][src42] | Source-verified; literal. |
| AD-071 | synchronization-control | `RejectionNotice; case "SYNCHRONIZATION_RUN_NOT_STALE"` | `No se ha abandonado` | [synchronization-control.tsx:54][src42] | Source-verified; literal. |
| AD-072 | synchronization-control | `RejectionNotice; case "SYNCHRONIZATION_RUN_NOT_STALE"` | `La ejecución sigue dando señales` | [synchronization-control.tsx:55][src42] | Source-verified; literal. |
| AD-073 | synchronization-control | `RejectionNotice; case "SYNCHRONIZATION_RUN_NOT_STALE"` | `Su proceso ha dado señales dentro del plazo de abandono, así que sigue siendo suyo. Si quieres terminarla, solicita su detención.` | [synchronization-control.tsx:56][src42] | Source-verified; literal. |
| AD-074 | synchronization-control | `RejectionNotice; case "CSRF_VALIDATION_FAILED"` | `Sin cambios` | [synchronization-control.tsx:61][src42] | Source-verified; literal. |
| AD-075 | synchronization-control | `RejectionNotice; case "CSRF_VALIDATION_FAILED"` | `No se pudo verificar la orden` | [synchronization-control.tsx:62][src42] | Source-verified; literal. |
| AD-076 | synchronization-control | `RejectionNotice; case "CSRF_VALIDATION_FAILED"` | `Hemos renovado la verificación de tu sesión. Vuelve a intentarlo.` | [synchronization-control.tsx:63][src42] | Source-verified; literal. |
| AD-077 | synchronization-control | `RejectionNotice; case "AUTHENTICATION_REQUIRED"` | `Sesión` | [synchronization-control.tsx:67][src42] | Source-verified; literal. |
| AD-078 | synchronization-control | `RejectionNotice; case "AUTHENTICATION_REQUIRED"` | `Tu sesión ha terminado` | [synchronization-control.tsx:68][src42] | Source-verified; literal. |
| AD-079 | synchronization-control | `RejectionNotice; case "AUTHENTICATION_REQUIRED"` | `Inicia sesión de nuevo para controlar la sincronización.` | [synchronization-control.tsx:69][src42] | Source-verified; literal. |
| AD-080 | synchronization-control | `RejectionNotice; case "AUTHENTICATION_REQUIRED"` | `Iniciar sesión` | [synchronization-control.tsx:70][src42] | Source-verified; literal. |
| AD-081 | synchronization-control | `RejectionNotice; case "ACCESS_DENIED"` | `Acceso restringido` | [synchronization-control.tsx:74][src42] | Source-verified; literal. |
| AD-082 | synchronization-control | `RejectionNotice; case "ACCESS_DENIED"` | `Tu cuenta no puede controlar sincronizaciones` | [synchronization-control.tsx:75][src42] | Source-verified; literal. |
| AD-083 | synchronization-control | `RejectionNotice; case "ACCESS_DENIED"` | `Esta acción requiere el rol de operador del catálogo.` | [synchronization-control.tsx:76][src42] | Source-verified; literal. |
| AD-084 | synchronization-control | `RejectionNotice` | `Error` | [synchronization-control.tsx:80][src42] | Source-verified; literal. |
| AD-085 | synchronization-control | `RejectionNotice` | `No se pudo enviar la orden` | [synchronization-control.tsx:81][src42] | Source-verified; literal. |
| AD-086 | synchronization-control | `RejectionNotice` | `Consulta el estado actualizado antes de repetirla: puede no haberse aplicado.` | [synchronization-control.tsx:82][src42] | Source-verified; literal. |
| AD-087 | synchronization-control | `RejectionNotice; if error.correlationId` | `Referencia para soporte: {error.correlationId}` | [synchronization-control.tsx:83][src42] | Source-verified; dynamic template/branch. |
| AD-088 | synchronization-control | `OutcomeNotice; if command.action === "abandon"` | `Ejecución abandonada` | [synchronization-control.tsx:94][src42] | Source-verified; literal. |
| AD-089 | synchronization-control | `OutcomeNotice; if command.action === "abandon"` | `Abandonada y cerrada` | [synchronization-control.tsx:95][src42] | Source-verified; literal. |
| AD-090 | synchronization-control | `OutcomeNotice; if command.action === "abandon"` | `{interval}. Su proceso, si reaparece, ya no puede publicar juegos, registrar cobertura ni terminarla. Lo ya publicado se conserva y no se ha reintentado nada: ya puedes solicitar otra sincronización.` | [synchronization-control.tsx:96][src42] | Source-verified; dynamic template/branch. |
| AD-091 | synchronization-control | `OutcomeNotice; if status.currentRun?.runId === command.run.runId` | `Detención solicitada` | [synchronization-control.tsx:102][src42] | Source-verified; literal. |
| AD-092 | synchronization-control | `OutcomeNotice; if status.currentRun?.runId === command.run.runId` | `Se detendrá en su próximo punto seguro` | [synchronization-control.tsx:103][src42] | Source-verified; literal. |
| AD-093 | synchronization-control | `OutcomeNotice; if status.currentRun?.runId === command.run.runId` | `{interval}. Sigue en curso hasta terminar el juego o la página que procesa. El estado se consulta de nuevo cada pocos segundos; puedes salir de la página.` | [synchronization-control.tsx:104][src42] | Source-verified; dynamic template/branch. |
| AD-094 | synchronization-control | `OutcomeNotice; if finished; if stopped` | `Detención completada` | [synchronization-control.tsx:113][src42] | Source-verified; literal. |
| AD-095 | synchronization-control | `OutcomeNotice; if finished; else stopped` | `Terminó antes de detenerse` | [synchronization-control.tsx:113][src42] | Source-verified; literal. |
| AD-096 | synchronization-control | `OutcomeNotice; if finished` | `{interval}. {reading.explanation}` | [synchronization-control.tsx:115][src42] | Source-verified; dynamic template/branch. |
| AD-097 | synchronization-control | `OutcomeNotice` | `Detención solicitada` | [synchronization-control.tsx:119][src42] | Source-verified; literal. |
| AD-098 | synchronization-control | `OutcomeNotice` | `La ejecución ya no aparece en el historial` | [synchronization-control.tsx:120][src42] | Source-verified; literal. |
| AD-099 | synchronization-control | `OutcomeNotice` | `{interval}. El historial conservado es limitado; consulta la cobertura de esas fechas.` | [synchronization-control.tsx:121][src42] | Source-verified; dynamic template/branch. |
| AD-100 | synchronization-control | `RunControls; if run && (canStop \|\| canAbandon); if canStop` | `Detener sincronización` | [synchronization-control.tsx:183][src42] | Source-verified; literal. |
| AD-101 | synchronization-control | `RunControls; if run && (canStop \|\| canAbandon); if canAbandon` | `Abandonar ejecución` | [synchronization-control.tsx:188][src42] | Source-verified; literal. |
| AD-102 | synchronization-control | `RunControls; if run && open; if open === "stop"` | `¿Detener la sincronización de {interval}?` | [synchronization-control.tsx:198][src42] | Source-verified; dynamic template/branch. |
| AD-103 | synchronization-control | `RunControls; if run && open; if open === "stop"` | `Terminará en su próximo punto seguro, al acabar el juego o la página que procesa, no al instante. Lo ya publicado se conserva, el intervalo no quedará registrado como cubierto y nada se reintenta. La sincronización programada sigue su calendario.` | [synchronization-control.tsx:199][src42] | Source-verified; literal. |
| AD-104 | synchronization-control | `RunControls; if run && open; if open === "stop"` | `Seguir sincronizando` | [synchronization-control.tsx:205][src42] | Source-verified; literal. |
| AD-105 | synchronization-control | `RunControls; if run && open; if open === "stop"` | `Sí, detener` | [synchronization-control.tsx:206][src42] | Source-verified; literal. |
| AD-106 | synchronization-control | `RunControls; if run && open; else open === "stop"` | `¿Abandonar la ejecución de {interval}?` | [synchronization-control.tsx:210][src42] | Source-verified; dynamic template/branch. |
| AD-107 | synchronization-control | `RunControls; if run && open; else open === "stop"` | `Úsalo solo si su proceso ya no existe, por ejemplo tras reiniciar la aplicación: lleva más del plazo de abandono sin dar señales. Se cerrará como abandonada y su proceso, si reaparece, no podrá publicar más juegos, registrar cobertura ni terminarla. Lo ya publicado se conserva y no se deshace. Si su proceso da señales antes, el servidor no la abandonará.` | [synchronization-control.tsx:211][src42] | Source-verified; literal. |
| AD-108 | synchronization-control | `RunControls; if run && open; else open === "stop"` | `Conservar ejecución` | [synchronization-control.tsx:218][src42] | Source-verified; literal. |
| AD-109 | synchronization-control | `RunControls; if run && open; else open === "stop"` | `Sí, abandonar` | [synchronization-control.tsx:219][src42] | Source-verified; literal. |
| AD-110 | synchronization-control | `RunControls; if busy; if sent?.action === "abandon"` | `Abandonando la ejecución…` | [synchronization-control.tsx:225][src42] | Source-verified; literal. |
| AD-111 | synchronization-control | `RunControls; if busy; else sent?.action === "abandon"` | `Solicitando la detención…` | [synchronization-control.tsx:225][src42] | Source-verified; literal. |
| AD-112 | synchronization-request-state | `validateWindow; if !from` | `Indica la fecha de inicio.` | [synchronization-request-state.ts:44][src43] | Source-verified; literal. |
| AD-113 | synchronization-request-state | `validateWindow; else !from; if from < EARLIEST \|\| from > LATEST` | `Usa una fecha entre 1950 y 2099.` | [synchronization-request-state.ts:45][src43] | Source-verified; literal. |
| AD-114 | synchronization-request-state | `validateWindow; if !to` | `Indica la fecha final.` | [synchronization-request-state.ts:46][src43] | Source-verified; literal. |
| AD-115 | synchronization-request-state | `validateWindow; else !to; if to < EARLIEST \|\| to > LATEST` | `Usa una fecha entre 1950 y 2099.` | [synchronization-request-state.ts:47][src43] | Source-verified; literal. |
| AD-116 | synchronization-request-state | `validateWindow; else !to; else to < EARLIEST \|\| to > LATEST; if from && !errors.from && to < from` | `No puede ser anterior a la fecha de inicio.` | [synchronization-request-state.ts:48][src43] | Source-verified; literal. |
| AD-117 | synchronization-request-state | `validateWindow; else !to; else to < EARLIEST \|\| to > LATEST; else from && !errors.from && to < from; if from && !errors.from && dayNumber(to) - dayNumber(from) >= MAX_INTERVAL_DAYS` | `El intervalo admite como máximo ${MAX_INTERVAL_DAYS} días.` | [synchronization-request-state.ts:50][src43] | Source-verified; dynamic template/branch. |
| AD-118 | synchronization-request | `serverFieldError; message` | `Usa fechas entre 1950 y 2099, con la final no anterior a la inicial y como máximo ${MAX_INTERVAL_DAYS} días.` | [synchronization-request.tsx:17][src44] | Source-verified; dynamic template/branch. |
| AD-119 | synchronization-request | `RequestedRunNotice; case "running"` | `Solicitud aceptada` | [synchronization-request.tsx:27][src44] | Source-verified; literal. |
| AD-120 | synchronization-request | `RequestedRunNotice; case "running"; if reading.phase === "running"` | `Sincronización en curso` | [synchronization-request.tsx:28][src44] | Source-verified; literal. |
| AD-121 | synchronization-request | `RequestedRunNotice; case "running"; else reading.phase === "running"` | `Sincronización aceptada` | [synchronization-request.tsx:28][src44] | Source-verified; literal. |
| AD-122 | synchronization-request | `RequestedRunNotice; case "running"` | `{interval}. El estado se consulta de nuevo cada pocos segundos mientras dura; puedes salir de la página.` | [synchronization-request.tsx:29][src44] | Source-verified; dynamic template/branch. |
| AD-123 | synchronization-request | `RequestedRunNotice; case "running"; if reading.phase === "running" && reading.stalled` | `El proceso no ha dado señales dentro del plazo de abandono.` | [synchronization-request.tsx:30][src44] | Source-verified; literal. |
| AD-124 | synchronization-request | `RequestedRunNotice; case "finished"` | `Solicitud terminada` | [synchronization-request.tsx:37][src44] | Source-verified; literal. |
| AD-125 | synchronization-request | `RequestedRunNotice; case "finished"` | `{interval}. {result.explanation}` | [synchronization-request.tsx:39][src44] | Source-verified; dynamic template/branch. |
| AD-126 | synchronization-request | `RequestedRunNotice; case "finished"` | `Sus contadores aparecen en Ejecuciones recientes.` | [synchronization-request.tsx:40][src44] | Source-verified; literal. |
| AD-127 | synchronization-request | `RequestedRunNotice; case "missing"` | `Solicitud aceptada` | [synchronization-request.tsx:45][src44] | Source-verified; literal. |
| AD-128 | synchronization-request | `RequestedRunNotice; case "missing"` | `La ejecución ya no aparece en el historial` | [synchronization-request.tsx:46][src44] | Source-verified; literal. |
| AD-129 | synchronization-request | `RequestedRunNotice; case "missing"` | `{interval}. El historial conservado es limitado; consulta la cobertura de esas fechas.` | [synchronization-request.tsx:47][src44] | Source-verified; dynamic template/branch. |
| AD-130 | synchronization-request | `RejectionNotice; case "SYNCHRONIZATION_ALREADY_RUNNING"` | `No se ha iniciado` | [synchronization-request.tsx:77][src44] | Source-verified; literal. |
| AD-131 | synchronization-request | `RejectionNotice; case "SYNCHRONIZATION_ALREADY_RUNNING"` | `Ya hay una sincronización en curso` | [synchronization-request.tsx:78][src44] | Source-verified; literal. |
| AD-132 | synchronization-request | `RejectionNotice; case "SYNCHRONIZATION_ALREADY_RUNNING"` | `Solo se ejecuta una a la vez. Espera a que termine la actual y vuelve a solicitarla.` | [synchronization-request.tsx:79][src44] | Source-verified; literal. |
| AD-133 | synchronization-request | `RejectionNotice; case "SYNCHRONIZATION_DISABLED"` | `No se ha iniciado` | [synchronization-request.tsx:83][src44] | Source-verified; literal. |
| AD-134 | synchronization-request | `RejectionNotice; case "SYNCHRONIZATION_DISABLED"` | `La sincronización no está disponible` | [synchronization-request.tsx:84][src44] | Source-verified; literal. |
| AD-135 | synchronization-request | `RejectionNotice; case "SYNCHRONIZATION_DISABLED"` | `Este entorno no tiene acceso al proveedor del catálogo.` | [synchronization-request.tsx:85][src44] | Source-verified; literal. |
| AD-136 | synchronization-request | `RejectionNotice; case "CSRF_VALIDATION_FAILED"` | `No se ha iniciado` | [synchronization-request.tsx:89][src44] | Source-verified; literal. |
| AD-137 | synchronization-request | `RejectionNotice; case "CSRF_VALIDATION_FAILED"` | `No se pudo verificar la solicitud` | [synchronization-request.tsx:90][src44] | Source-verified; literal. |
| AD-138 | synchronization-request | `RejectionNotice; case "CSRF_VALIDATION_FAILED"` | `Hemos renovado la verificación de tu sesión. Vuelve a solicitarla.` | [synchronization-request.tsx:91][src44] | Source-verified; literal. |
| AD-139 | synchronization-request | `RejectionNotice; case "AUTHENTICATION_REQUIRED"` | `Sesión` | [synchronization-request.tsx:95][src44] | Source-verified; literal. |
| AD-140 | synchronization-request | `RejectionNotice; case "AUTHENTICATION_REQUIRED"` | `Tu sesión ha terminado` | [synchronization-request.tsx:96][src44] | Source-verified; literal. |
| AD-141 | synchronization-request | `RejectionNotice; case "AUTHENTICATION_REQUIRED"` | `Inicia sesión de nuevo para solicitar la sincronización.` | [synchronization-request.tsx:97][src44] | Source-verified; literal. |
| AD-142 | synchronization-request | `RejectionNotice; case "AUTHENTICATION_REQUIRED"` | `Iniciar sesión` | [synchronization-request.tsx:98][src44] | Source-verified; literal. |
| AD-143 | synchronization-request | `RejectionNotice; case "ACCESS_DENIED"` | `Acceso restringido` | [synchronization-request.tsx:102][src44] | Source-verified; literal. |
| AD-144 | synchronization-request | `RejectionNotice; case "ACCESS_DENIED"` | `Tu cuenta no puede solicitar sincronizaciones` | [synchronization-request.tsx:103][src44] | Source-verified; literal. |
| AD-145 | synchronization-request | `RejectionNotice; case "ACCESS_DENIED"` | `Esta acción requiere el rol de operador del catálogo.` | [synchronization-request.tsx:104][src44] | Source-verified; literal. |
| AD-146 | synchronization-request | `RejectionNotice` | `Error` | [synchronization-request.tsx:108][src44] | Source-verified; literal. |
| AD-147 | synchronization-request | `RejectionNotice` | `No se pudo solicitar la sincronización` | [synchronization-request.tsx:109][src44] | Source-verified; literal. |
| AD-148 | synchronization-request | `RejectionNotice` | `No se ha iniciado nada y los datos del catálogo no han cambiado. Inténtalo de nuevo en unos momentos.` | [synchronization-request.tsx:110][src44] | Source-verified; literal. |
| AD-149 | synchronization-request | `RejectionNotice; if error.correlationId` | `Referencia para soporte: {error.correlationId}` | [synchronization-request.tsx:111][src44] | Source-verified; dynamic template/branch. |
| AD-150 | synchronization-request | `SynchronizationRequest` | `Desde` | [synchronization-request.tsx:193][src44] | Source-verified; literal. |
| AD-151 | synchronization-request | `SynchronizationRequest` | `Hasta` | [synchronization-request.tsx:194][src44] | Source-verified; literal. |
| AD-152 | synchronization-request | `SynchronizationRequest; if command.isPending` | `Solicitando…` | [synchronization-request.tsx:196][src44] | Source-verified; literal. |
| AD-153 | synchronization-request | `SynchronizationRequest; else command.isPending` | `Solicitar sincronización` | [synchronization-request.tsx:196][src44] | Source-verified; literal. |
| AD-154 | synchronization-request | `SynchronizationRequest` | `Ambas fechas se incluyen. Como máximo {MAX_INTERVAL_DAYS} días, entre 1950 y 2099; solo se ejecuta una sincronización a la vez, la programada incluida.` | [synchronization-request.tsx:199][src44] | Source-verified; dynamic template/branch. |
| AD-155 | synchronization-request | `SynchronizationRequest; if running` | `Hay una sincronización en curso: podrás solicitar otra cuando termine.` | [synchronization-request.tsx:203][src44] | Source-verified; literal. |
| AD-156 | synchronization-runs | `currentState; if run.stopRequestedAt; if run.stalled` | `Detención solicitada, sin señales recientes` | [synchronization-runs.tsx:7][src45] | Source-verified; literal. |
| AD-157 | synchronization-runs | `currentState; if run.stopRequestedAt; else run.stalled` | `Detención solicitada` | [synchronization-runs.tsx:7][src45] | Source-verified; literal. |
| AD-158 | synchronization-runs | `currentState; if run.stalled` | `En curso, sin señales recientes` | [synchronization-runs.tsx:8][src45] | Source-verified; literal. |
| AD-159 | synchronization-runs | `currentState; else run.stalled` | `En curso` | [synchronization-runs.tsx:8][src45] | Source-verified; literal. |
| AD-160 | synchronization-runs | `CurrentRunPanel; if !run` | `No hay ninguna sincronización en curso.` | [synchronization-runs.tsx:14][src45] | Source-verified; literal. |
| AD-161 | synchronization-runs | `CurrentRunPanel` | `Intervalo` | [synchronization-runs.tsx:22][src45] | Source-verified; literal. |
| AD-162 | synchronization-runs | `CurrentRunPanel` | `Inicio` | [synchronization-runs.tsx:23][src45] | Source-verified; literal. |
| AD-163 | synchronization-runs | `CurrentRunPanel` | `Última señal` | [synchronization-runs.tsx:24][src45] | Source-verified; literal. |
| AD-164 | synchronization-runs | `CurrentRunPanel; if run.stopRequestedAt` | `Detención solicitada` | [synchronization-runs.tsx:25][src45] | Source-verified; literal. |
| AD-165 | synchronization-runs | `CurrentRunPanel; if run.stopRequestedAt` | `Sigue en curso hasta su próximo punto seguro, al terminar el juego o la página que procesa; entonces se detendrá y aparecerá en Ejecuciones recientes.` | [synchronization-runs.tsx:27][src45] | Source-verified; literal. |
| AD-166 | synchronization-runs | `CurrentRunPanel; if run.stalled` | `El proceso no ha dado señales dentro del plazo de abandono. Si ya no existe, puedes abandonar la ejecución; si no, la próxima sincronización que empiece la registrará como abandonada.` | [synchronization-runs.tsx:31][src45] | Source-verified; literal. |
| AD-167 | synchronization-runs | `StopNote; if run.outcomeCode === STOPPED \|\| run.result === "abandoned"` | `Detención solicitada el {requested}.` | [synchronization-runs.tsx:43][src45] | Source-verified; dynamic template/branch. |
| AD-168 | synchronization-runs | `StopNote` | `Se solicitó su detención el {requested}, pero terminó antes de llegar a un punto seguro.` | [synchronization-runs.tsx:45][src45] | Source-verified; dynamic template/branch. |
| AD-169 | synchronization-runs | `Counters; if !counters` | `Sin contadores: la ejecución no llegó a informarlos.` | [synchronization-runs.tsx:52][src45] | Source-verified; literal. |
| AD-170 | synchronization-runs | `Counters` | `Juegos` | [synchronization-runs.tsx:55][src45] | Source-verified; literal. |
| AD-171 | synchronization-runs | `Counters` | `{formatCount(counters.createdGames)} nuevos · {formatCount(counters.updatedGames)} actualizados · {formatCount(counters.unchangedGames)} sin cambios · {formatCount(counters.deferredGames)} aplazados · {formatCount(counters.failedGames)} fallidos` | [synchronization-runs.tsx:56][src45] | Source-verified; dynamic template/branch. |
| AD-172 | synchronization-runs | `Counters` | `Lanzamientos` | [synchronization-runs.tsx:63][src45] | Source-verified; literal. |
| AD-173 | synchronization-runs | `Counters` | `{formatCount(counters.createdReleases)} nuevos · {formatCount(counters.updatedReleases)} actualizados · {formatCount(counters.unchangedReleases)} sin cambios · {formatCount(counters.deletedReleases)} eliminados` | [synchronization-runs.tsx:64][src45] | Source-verified; dynamic template/branch. |
| AD-174 | synchronization-runs | `Counters` | `Peticiones al proveedor` | [synchronization-runs.tsx:70][src45] | Source-verified; literal. |
| AD-175 | synchronization-runs | `Counters` | `{formatCount(counters.providerRequests)} ({formatCount(counters.providerRetries)} reintentos)` | [synchronization-runs.tsx:71][src45] | Source-verified; dynamic template/branch. |
| AD-176 | synchronization-runs | `RecentRuns; if status.recentRuns.length === 0` | `Sin historial` | [synchronization-runs.tsx:80][src45] | Source-verified; literal. |
| AD-177 | synchronization-runs | `RecentRuns; if status.recentRuns.length === 0` | `Todavía no hay ejecuciones terminadas` | [synchronization-runs.tsx:81][src45] | Source-verified; literal. |
| AD-178 | synchronization-runs | `RecentRuns; if status.recentRuns.length === 0` | `Cuando termine una sincronización aparecerá aquí con su resultado y sus contadores.` | [synchronization-runs.tsx:82][src45] | Source-verified; literal. |
| AD-179 | synchronization-runs | `RecentRuns` | `, {formatInterval(run.from, run.to)}` | [synchronization-runs.tsx:94][src45] | Source-verified; dynamic template/branch. |
| AD-180 | synchronization-runs | `RecentRuns` | `{formatInstant(run.startedAt)} · {formatDuration(run.startedAt, run.completedAt)}` | [synchronization-runs.tsx:96][src45] | Source-verified; dynamic template/branch. |
| AD-181 | synchronization-runs | `RecentRuns` | `Intervalo` | [synchronization-runs.tsx:101][src45] | Source-verified; literal. |
| AD-182 | synchronization-runs | `RecentRuns` | `Código` | [synchronization-runs.tsx:102][src45] | Source-verified; literal. |
| AD-183 | synchronization-runs | `Operator diagnostic under Código` | `{run.outcomeCode}` | [synchronization-runs.tsx:102][src45] | Intentionally displayed operational code; never translate it or replace it with a player message. |
| AD-184 | catalogue-administration-page | `TABS; label` | `Ejecuciones` | [catalogue-administration-page.tsx:21][src46] | Source-verified; literal. |
| AD-185 | catalogue-administration-page | `TABS; label` | `Cobertura` | [catalogue-administration-page.tsx:22][src46] | Source-verified; literal. |
| AD-186 | catalogue-administration-page | `SignInRequired` | `Sesión` | [catalogue-administration-page.tsx:43][src46] | Source-verified; literal. |
| AD-187 | catalogue-administration-page | `SignInRequired` | `Necesitas una sesión activa` | [catalogue-administration-page.tsx:44][src46] | Source-verified; literal. |
| AD-188 | catalogue-administration-page | `SignInRequired` | `Inicia sesión con una cuenta de operador del catálogo para ver la sincronización.` | [catalogue-administration-page.tsx:45][src46] | Source-verified; literal. |
| AD-189 | catalogue-administration-page | `SignInRequired` | `Iniciar sesión` | [catalogue-administration-page.tsx:46][src46] | Source-verified; literal. |
| AD-190 | catalogue-administration-page | `AccessDenied` | `Acceso restringido` | [catalogue-administration-page.tsx:52][src46] | Source-verified; literal. |
| AD-191 | catalogue-administration-page | `AccessDenied` | `Tu cuenta no puede administrar el catálogo` | [catalogue-administration-page.tsx:53][src46] | Source-verified; literal. |
| AD-192 | catalogue-administration-page | `AccessDenied` | `Esta vista solo está disponible para cuentas con el rol de operador del catálogo. Si acabas de recibirlo, cierra la sesión y vuelve a entrar.` | [catalogue-administration-page.tsx:54][src46] | Source-verified; literal. |
| AD-193 | catalogue-administration-page | `AccessDenied` | `Volver al inicio` | [catalogue-administration-page.tsx:58][src46] | Source-verified; literal. |
| AD-194 | catalogue-administration-page | `PanelTabs` | `Secciones del panel` | [catalogue-administration-page.tsx:85][src46] | Source-verified; accessible name/announcement. |
| AD-195 | catalogue-administration-page | `CatalogueAdministrationPage` | `Panel de administrador` | [catalogue-administration-page.tsx:133][src46] | Source-verified; literal. |
| AD-196 | catalogue-administration-page | `CatalogueAdministrationPage` | `Sincronización y cobertura` | [catalogue-administration-page.tsx:134][src46] | Source-verified; tooltip. |
| AD-197 | catalogue-administration-page | `CatalogueAdministrationPage; if session.isPending` | `Comprobando sesión…` | [catalogue-administration-page.tsx:136][src46] | Source-verified; literal. |
| AD-198 | catalogue-administration-page | `CatalogueAdministrationPage; else session.isPending; if session.isError` | `Sesión` | [catalogue-administration-page.tsx:138][src46] | Source-verified; literal. |
| AD-199 | catalogue-administration-page | `CatalogueAdministrationPage; else session.isPending; if session.isError` | `No se pudo comprobar tu sesión.` | [catalogue-administration-page.tsx:139][src46] | Source-verified; literal. |
| AD-200 | catalogue-administration-page | `CatalogueAdministrationPage; else session.isPending; if session.isError` | `Reintentar` | [catalogue-administration-page.tsx:140][src46] | Source-verified; literal. |
| AD-201 | catalogue-administration-page | `CatalogueAdministrationPage; else session.isPending; else session.isError; else !authenticated \|\| code === "AUTHENTICATION_REQUIRED"; else code === "ACCESS_DENIED"; if status.isError` | `Error de carga` | [catalogue-administration-page.tsx:145][src46] | Source-verified; literal. |
| AD-202 | catalogue-administration-page | `CatalogueAdministrationPage; else session.isPending; else session.isError; else !authenticated \|\| code === "AUTHENTICATION_REQUIRED"; else code === "ACCESS_DENIED"; if status.isError` | `No se pudo leer el estado de la sincronización` | [catalogue-administration-page.tsx:146][src46] | Source-verified; literal. |
| AD-203 | catalogue-administration-page | `CatalogueAdministrationPage; else session.isPending; else session.isError; else !authenticated \|\| code === "AUTHENTICATION_REQUIRED"; else code === "ACCESS_DENIED"; if status.isError` | `Los datos del catálogo no han cambiado. Inténtalo de nuevo en unos momentos.` | [catalogue-administration-page.tsx:147][src46] | Source-verified; literal. |
| AD-204 | catalogue-administration-page | `CatalogueAdministrationPage; else session.isPending; else session.isError; else !authenticated \|\| code === "AUTHENTICATION_REQUIRED"; else code === "ACCESS_DENIED"; if status.isError` | `Reintentar` | [catalogue-administration-page.tsx:148][src46] | Source-verified; literal. |
| AD-205 | catalogue-administration-page | `CatalogueAdministrationPage; else session.isPending; else session.isError; else !authenticated \|\| code === "AUTHENTICATION_REQUIRED"; else code === "ACCESS_DENIED"; if status.isError; if status.error.correlationId` | `Referencia para soporte: {status.error.correlationId}` | [catalogue-administration-page.tsx:149][src46] | Source-verified; dynamic template/branch. |
| AD-206 | catalogue-administration-page | `CatalogueAdministrationPage; else session.isPending; else session.isError; else !authenticated \|\| code === "AUTHENTICATION_REQUIRED"; else code === "ACCESS_DENIED"; else status.isError; if status.isPending` | `Cargando el estado de la sincronización…` | [catalogue-administration-page.tsx:151][src46] | Source-verified; literal. |
| AD-207 | catalogue-administration-page | `CatalogueAdministrationPage; else session.isPending; else session.isError; else !authenticated \|\| code === "AUTHENTICATION_REQUIRED"; else code === "ACCESS_DENIED"; else status.isError; else status.isPending; if tab === "ejecuciones"; if csrfToken` | `Solicitar sincronización` | [catalogue-administration-page.tsx:158][src46] | Source-verified; literal. |
| AD-208 | catalogue-administration-page | `CatalogueAdministrationPage; else session.isPending; else session.isError; else !authenticated \|\| code === "AUTHENTICATION_REQUIRED"; else code === "ACCESS_DENIED"; else status.isError; else status.isPending; if tab === "ejecuciones"; if csrfToken` | `Sincroniza con el proveedor los juegos que se lanzan entre dos fechas, con la misma reconciliación que la sincronización programada. La solicitud responde en cuanto la ejecución empieza; su progreso y su resultado se leen de su estado.` | [catalogue-administration-page.tsx:159][src46] | Source-verified; literal. |
| AD-209 | catalogue-administration-page | `CatalogueAdministrationPage; else session.isPending; else session.isError; else !authenticated \|\| code === "AUTHENTICATION_REQUIRED"; else code === "ACCESS_DENIED"; else status.isError; else status.isPending; if tab === "ejecuciones"` | `Ejecución actual` | [catalogue-administration-page.tsx:169][src46] | Source-verified; literal. |
| AD-210 | catalogue-administration-page | `CatalogueAdministrationPage; else session.isPending; else session.isError; else !authenticated \|\| code === "AUTHENTICATION_REQUIRED"; else code === "ACCESS_DENIED"; else status.isError; else status.isPending; if tab === "ejecuciones"; if status.isFetching` | `Actualizando…` | [catalogue-administration-page.tsx:172][src46] | Source-verified; literal. |
| AD-211 | catalogue-administration-page | `CatalogueAdministrationPage; else session.isPending; else session.isError; else !authenticated \|\| code === "AUTHENTICATION_REQUIRED"; else code === "ACCESS_DENIED"; else status.isError; else status.isPending; if tab === "ejecuciones"; else status.isFetching` | `Actualizar estado` | [catalogue-administration-page.tsx:172][src46] | Source-verified; literal. |
| AD-212 | catalogue-administration-page | `CatalogueAdministrationPage; else session.isPending; else session.isError; else !authenticated \|\| code === "AUTHENTICATION_REQUIRED"; else code === "ACCESS_DENIED"; else status.isError; else status.isPending; if tab === "ejecuciones"` | `Ejecuciones recientes` | [catalogue-administration-page.tsx:179][src46] | Source-verified; literal. |
| AD-213 | catalogue-administration-page | `CatalogueAdministrationPage; else session.isPending; else session.isError; else !authenticated \|\| code === "AUTHENTICATION_REQUIRED"; else code === "ACCESS_DENIED"; else status.isError; else status.isPending; if tab === "ejecuciones"` | `Historial operativo limitado a las últimas ejecuciones conservadas. Una ejecución fallida, interrumpida o abandonada nunca cambia la cobertura ya registrada.` | [catalogue-administration-page.tsx:180][src46] | Source-verified; literal. |
| AD-214 | catalogue-administration-page | `CatalogueAdministrationPage; else session.isPending; else session.isError; else !authenticated \|\| code === "AUTHENTICATION_REQUIRED"; else code === "ACCESS_DENIED"; else status.isError; else status.isPending; else tab === "ejecuciones"` | `Cobertura histórica` | [catalogue-administration-page.tsx:187][src46] | Source-verified; literal. |
| AD-215 | catalogue-administration-page | `CatalogueAdministrationPage; else session.isPending; else session.isError; else !authenticated \|\| code === "AUTHENTICATION_REQUIRED"; else code === "ACCESS_DENIED"; else status.isError; else status.isPending; else tab === "ejecuciones"` | `Cubierto` | [catalogue-administration-page.tsx:190][src46] | Source-verified; literal. |
| AD-216 | catalogue-administration-page | `CatalogueAdministrationPage; else session.isPending; else session.isError; else !authenticated \|\| code === "AUTHENTICATION_REQUIRED"; else code === "ACCESS_DENIED"; else status.isError; else status.isPending; else tab === "ejecuciones"` | `Una sincronización leyó por completo esas fechas de lanzamiento en el momento indicado. No garantiza que el proveedor no cambie esos datos después.` | [catalogue-administration-page.tsx:191][src46] | Source-verified; literal. |
| AD-217 | catalogue-administration-page | `CatalogueAdministrationPage; else session.isPending; else session.isError; else !authenticated \|\| code === "AUTHENTICATION_REQUIRED"; else code === "ACCESS_DENIED"; else status.isError; else status.isPending; else tab === "ejecuciones"` | `Nunca cubierto` | [catalogue-administration-page.tsx:197][src46] | Source-verified; literal. |
| AD-218 | catalogue-administration-page | `CatalogueAdministrationPage; else session.isPending; else session.isError; else !authenticated \|\| code === "AUTHENTICATION_REQUIRED"; else code === "ACCESS_DENIED"; else status.isError; else status.isPending; else tab === "ejecuciones"` | `Ninguna sincronización completa ha recorrido esas fechas desde que se registra la cobertura. Las ejecuciones anteriores a ese registro no cuentan, aunque el catálogo ya tenga juegos de esas fechas.` | [catalogue-administration-page.tsx:198][src46] | Source-verified; literal. |
| AD-219 | catalogue-administration-page | `CatalogueAdministrationPage; else session.isPending; else session.isError; else !authenticated \|\| code === "AUTHENTICATION_REQUIRED"; else code === "ACCESS_DENIED"; else status.isError; else status.isPending; else tab === "ejecuciones"` | `Frescura` | [catalogue-administration-page.tsx:205][src46] | Source-verified; literal. |
| AD-220 | catalogue-administration-page | `CatalogueAdministrationPage; else session.isPending; else session.isError; else !authenticated \|\| code === "AUTHENTICATION_REQUIRED"; else code === "ACCESS_DENIED"; else status.isError; else status.isPending; else tab === "ejecuciones"` | `Antigüedad del escaneo completo más antiguo. Es una lectura de esta vista, no una caducidad.` | [catalogue-administration-page.tsx:206][src46] | Source-verified; literal. |

### Not-found routes

| ID | Screen/component | State or trigger | Exact current text | Source (file) | Notes |
| --- | --- | --- | --- | --- | --- |
| NF-001 | not-found-page | `NotFoundPage` | `Error 404` | [not-found-page.tsx:7][src47] | Source-verified; literal. |
| NF-002 | not-found-page | `NotFoundPage` | `Página no encontrada` | [not-found-page.tsx:8][src47] | Source-verified; literal. |
| NF-003 | not-found-page | `NotFoundPage` | `Esta ruta todavía no forma parte de la aplicación.` | [not-found-page.tsx:9][src47] | Source-verified; literal. |
| NF-004 | not-found-page | `NotFoundPage` | `Volver al inicio` | [not-found-page.tsx:10][src47] | Source-verified; literal. |

### Shared date/status, scores, images, platform/genre and selector patterns

| ID | Screen/component | State or trigger | Exact current text | Source (file) | Notes |
| --- | --- | --- | --- | --- | --- |
| SC-001 | fallback | `Standalone SVG title` | `Carátula no disponible` | [fallback.svg:2][src48] | Embedded img uses its HTML alt; SVG descriptions are not additional visible UI sentences. |
| SC-002 | fallback | `Standalone SVG desc` | `Marcador de Gameómetro para un juego sin carátula oficial.` | [fallback.svg:3][src48] | Embedded img uses its HTML alt; SVG descriptions are not additional visible UI sentences. |
| SC-003 | fallback | `Standalone SVG title` | `Imagen destacada no disponible` | [fallback.svg:2][src49] | Embedded img uses its HTML alt; SVG descriptions are not additional visible UI sentences. |
| SC-004 | fallback | `Standalone SVG desc` | `Marcador apaisado de Gameómetro para un juego sin imagen destacada.` | [fallback.svg:3][src49] | Embedded img uses its HTML alt; SVG descriptions are not additional visible UI sentences. |
| SC-005 | favicon | `Standalone SVG title` | `Gameómetro` | [favicon.svg:1][src50] | Embedded img uses its HTML alt; SVG descriptions are not additional visible UI sentences. |
| SC-006 | game-genres | `GameGenres; if labels` | `Géneros: ${labels}` | [game-genres.tsx:6][src51] | Source-verified; dynamic template/branch. |
| SC-007 | game-genres | `Discovery/search/ratings genre names` | `Géneros: {genreNames joined by comma}` | [game-genres.tsx:6][src51] | At most the supplied visible genres; labels are data. |
| SC-008 | platform-marks | `Platform tooltip/name and compact overflow` | `{platformName}; +{unseen}` | [platform-marks.tsx:27][src52] | Full known names announced; exact unknown remainder described by hiddenPlatformsLabel. |
| SC-009 | platform-marks | `PlatformMarks; else unseen === 0; if more !== undefined && more.count > 0` | `+{unseen}` | [platform-marks.tsx:38][src52] | Source-verified; dynamic template/branch. |
| SC-010 | platform-marks | `PlatformMarks; else unseen === 0; else more !== undefined && more.count > 0` | `+{unseen}` | [platform-marks.tsx:43][src52] | Source-verified; dynamic template/branch. |
| SC-011 | release-date | `Full month-name vocabulary` | `enero; febrero; marzo; abril; mayo; junio; julio; agosto; septiembre; octubre; noviembre; diciembre` | [release-date.ts:13][src53] | Template; source-verified. Synthetic examples are explained below. |
| SC-012 | release-date | `formatCalendarDay; if match === null \|\| month === null` | `Fecha no disponible` | [release-date.ts:40][src53] | Source-verified; literal. |
| SC-013 | release-date | `formatCalendarDay; else match === null \|\| month === null` | `${Number(match[3])} de ${month} de ${match[1]}` | [release-date.ts:41][src53] | Source-verified; dynamic template/branch. |
| SC-014 | release-date | `formatDayRange; if start === null \|\| end === null \|\| startMonth === null \|\| endMonth === null` | `Fecha no disponible` | [release-date.ts:54][src53] | Source-verified; literal. |
| SC-015 | release-date | `formatDayRange; last` | `${Number(endDay)} ${endMonth} ${endYear}` | [release-date.ts:58][src53] | Source-verified; dynamic template/branch. |
| SC-016 | release-date | `formatDayRange; if startYear !== endYear` | `${Number(startDay)} ${startMonth} ${startYear} – ${last}` | [release-date.ts:59][src53] | Source-verified; dynamic template/branch. |
| SC-017 | release-date | `formatDayRange; if startMonth !== endMonth` | `${Number(startDay)} ${startMonth} – ${last}` | [release-date.ts:60][src53] | Source-verified; dynamic template/branch. |
| SC-018 | release-date | `formatDayRange` | `${Number(startDay)}–${last}` | [release-date.ts:61][src53] | Source-verified; dynamic template/branch. |
| SC-019 | release-date | `Compact month-name vocabulary` | `ene; feb; mar; abr; may; jun; jul; ago; sep; oct; nov; dic` | [release-date.ts:65][src53] | Template; source-verified. Synthetic examples are explained below. |
| SC-020 | release-date | `quarterOrdinal: 1 and 3 use .er; 2 and 4 use .º` | `{quarter}.er / {quarter}.º` | [release-date.ts:88][src53] | Template; source-verified. Synthetic examples are explained below. |
| SC-021 | release-date | `formatReleaseDate; if releaseDate.precision === "unknown" \|\| value === null` | `Fecha por confirmar` | [release-date.ts:98][src53] | Source-verified; literal. |
| SC-022 | release-date | `formatReleaseDate; if releaseDate.precision === "month"; if match === null \|\| month === null` | `Fecha no disponible` | [release-date.ts:109][src53] | Source-verified; literal. |
| SC-023 | release-date | `formatReleaseDate; if releaseDate.precision === "month"; else match === null \|\| month === null` | `${month} de ${match[1]}` | [release-date.ts:110][src53] | Source-verified; dynamic template/branch. |
| SC-024 | release-date | `formatReleaseDate; if releaseDate.precision === "quarter"; if match === null` | `Fecha no disponible` | [release-date.ts:116][src53] | Source-verified; literal. |
| SC-025 | release-date | `formatReleaseDate; if releaseDate.precision === "quarter"; else match === null` | `${quarterOrdinal(match[2] ?? "")} trimestre de ${match[1]}` | [release-date.ts:117][src53] | Source-verified; dynamic template/branch. |
| SC-026 | release-date | `Year precision; full and compact formats` | `{year}` | [release-date.ts:120][src53] | Template; source-verified. Synthetic examples are explained below. |
| SC-027 | release-date | `formatReleaseDate` | `Fecha no disponible` | [release-date.ts:124][src53] | Source-verified; literal. |
| SC-028 | release-date | `formatReleaseDateShort; if releaseDate.precision === "unknown" \|\| value === null` | `Por confirmar` | [release-date.ts:136][src53] | Source-verified; literal. |
| SC-029 | release-date | `formatReleaseDateShort; if releaseDate.precision === "day"; if match === null \|\| month === null` | `Fecha no disponible` | [release-date.ts:143][src53] | Source-verified; literal. |
| SC-030 | release-date | `formatReleaseDateShort; if releaseDate.precision === "day"; else match === null \|\| month === null` | `${Number(match[3])} ${month} ${match[1]}` | [release-date.ts:144][src53] | Source-verified; dynamic template/branch. |
| SC-031 | release-date | `formatReleaseDateShort; if releaseDate.precision === "month"; if match === null \|\| month === null` | `Fecha no disponible` | [release-date.ts:150][src53] | Source-verified; literal. |
| SC-032 | release-date | `formatReleaseDateShort: month precision` | `{shortMonth} {year}` | [release-date.ts:150][src53] | Template; source-verified. Synthetic examples are explained below. |
| SC-033 | release-date | `formatReleaseDateShort; if releaseDate.precision === "quarter"; if match === null` | `Fecha no disponible` | [release-date.ts:155][src53] | Source-verified; literal. |
| SC-034 | release-date | `formatReleaseDateShort: quarter precision` | `T{quarter} {year}` | [release-date.ts:155][src53] | Template; source-verified. Synthetic examples are explained below. |
| SC-035 | release-date | `formatReleaseDateShort` | `Fecha no disponible` | [release-date.ts:162][src53] | Source-verified; literal. |
| SC-036 | release-labels | `releaseStages; full_release` | `Lanzamiento completo` | [release-labels.ts:7][src54] | Source-verified; literal. |
| SC-037 | release-labels | `releaseStages; early_access` | `Acceso anticipado` | [release-labels.ts:8][src54] | Source-verified; literal. |
| SC-038 | release-labels | `releaseStages; advance_access` | `Acceso previo` | [release-labels.ts:9][src54] | Source-verified; literal. |
| SC-039 | release-labels | `releaseStages; beta` | `Beta` | [release-labels.ts:10][src54] | Source-verified; literal. |
| SC-040 | release-labels | `releaseStages; alpha` | `Alfa` | [release-labels.ts:11][src54] | Source-verified; literal. |
| SC-041 | release-labels | `releaseStages; unknown` | `Tipo no especificado` | [release-labels.ts:12][src54] | Source-verified; literal. Defined vocabulary; game detail suppresses unknown stages. No current rendered usage found. |
| SC-042 | release-labels | `releaseStatuses; announced` | `Anunciado` | [release-labels.ts:17][src54] | Source-verified; literal. |
| SC-043 | release-labels | `releaseStatuses; scheduled` | `Programado` | [release-labels.ts:18][src54] | Source-verified; literal. |
| SC-044 | release-labels | `releaseStatuses; released` | `Publicado` | [release-labels.ts:19][src54] | Source-verified; literal. |
| SC-045 | release-labels | `releaseStatuses; delayed` | `Retrasado` | [release-labels.ts:20][src54] | Source-verified; literal. |
| SC-046 | release-labels | `releaseStatuses; cancelled` | `Cancelado` | [release-labels.ts:21][src54] | Source-verified; literal. |
| SC-047 | release-labels | `releaseStatuses; unknown` | `Estado sin confirmar` | [release-labels.ts:22][src54] | Source-verified; literal. |
| SC-048 | discovery-scores | `formatCompactCount; if layout === "featured" && count >= 1000` | `+${Math.floor(count / 1000)}K` | [discovery-scores.tsx:12][src55] | Source-verified; dynamic template/branch. |
| SC-049 | discovery-scores | `DiscoveryScores` | `Puntuaciones de ${title}` | [discovery-scores.tsx:27][src55] | Source-verified; dynamic template/branch. |
| SC-050 | discovery-scores | `DiscoveryScores; count` | `${exactCount.format(score.count)} ${score.count === 1 ? "valoración" : "valoraciones"}` | [discovery-scores.tsx:31][src55] | Source-verified; dynamic template/branch. |
| SC-051 | discovery-scores | `Discovery count tooltip and screen-reader text; currently both sources` | `{exactCount} valoración / {exactCount} valoraciones` | [discovery-scores.tsx:31][src55] | Uses external-count noun even for Gameómetro community. Game page uses puntuación/puntuaciones for community. |
| SC-052 | discovery-scores | `DiscoveryScores; else score.source === "gameometro"` | `Jugadores IGDB: fuente de la puntuación de ${title}` | [discovery-scores.tsx:38][src55] | Source-verified; dynamic template/branch. |
| SC-053 | discovery-scores | `DiscoveryScores; if score.source === "gameometro"` | `Gameómetro ·` | [discovery-scores.tsx:43][src55] | Source-verified; literal. |
| SC-054 | discovery-scores | `DiscoveryScores; if score.source === "gameometro"` | `Gameómetro · Comunidad` | [discovery-scores.tsx:43][src55] | Source-verified; literal. |
| SC-055 | discovery-scores | `DiscoveryScores; else score.source === "gameometro"` | `Jugadores IGDB` | [discovery-scores.tsx:43][src55] | Source-verified; literal. |
| SC-056 | discovery-scores | `DiscoveryScores; else band === null` | `·` | [discovery-scores.tsx:44][src55] | Source-verified; literal. |
| SC-057 | discovery-scores | `DiscoveryScores` | `${decimal.format(score.value)} de 10` | [discovery-scores.tsx:46][src55] | Source-verified; dynamic template/branch. |
| SC-058 | discovery-scores | `Accessible score; es-ES one decimal` | `{decimalScore} de 10` | [discovery-scores.tsx:46][src55] | Template; source-verified. Synthetic examples are explained below. |
| SC-059 | discovery-scores | `DiscoveryScores` | `/10` | [discovery-scores.tsx:47][src55] | Source-verified; literal. |
| SC-060 | discovery-scores | `DiscoveryScores; else layout === "hero"` | `({formatCompactCount(score.count, layout)})` | [discovery-scores.tsx:50][src55] | Source-verified; dynamic template/branch. |
| SC-061 | discovery-scores | `Visible compact count; exact count remains in tooltip and hidden text` | `({formattedCount})` | [discovery-scores.tsx:50][src55] | Below 10,000 exact es-ES grouping; otherwise Intl compact. Featured secondary cards use +floor(count/1000)K at 1,000+. |
| SC-062 | thermal-band | `thermalLabels; freeze` | `Congelado` | [thermal-band.ts:12][src56] | Source-verified; literal. |
| SC-063 | thermal-band | `thermalLabels; cold` | `Frío` | [thermal-band.ts:13][src56] | Source-verified; literal. |
| SC-064 | thermal-band | `thermalLabels; warm` | `Templado` | [thermal-band.ts:14][src56] | Source-verified; literal. |
| SC-065 | thermal-band | `thermalLabels; hot` | `Caliente` | [thermal-band.ts:15][src56] | Source-verified; literal. |
| SC-066 | thermal-band | `thermalLabels; burn` | `Ardiendo` | [thermal-band.ts:16][src56] | Source-verified; literal. |
| SC-067 | app-select | `Accessible name assembled from label and selected value` | `{label} {selectedOptionLabel}` | [app-select.tsx:126][src57] | Releases Periodo: plus 1/2/4 weeks; collection Orden/Por página plus selected option. No additional fixed error strings. |
| SC-068 | app-select | `AppSelect; if option.value === value` | `✓` | [app-select.tsx:153][src57] | Source-verified; literal. |
| SC-069 | catalogue-cover | `CatalogueCover; image; if failed` | `Carátula oficial no disponible` | [catalogue-cover.tsx:29][src58] | Source-verified; literal. |
| SC-070 | catalogue-cover | `Cover alt and caption` | `{cover.alternativeText}; Carátula: {attribution.label}` | [catalogue-cover.tsx:29][src58] | Release/search/ratings cards omit caption; game detail retains it. Stored and backend-provided copy included above. |
| SC-071 | catalogue-cover | `CatalogueCover; if caption; if attribution === null` | `Carátula oficial no disponible` | [catalogue-cover.tsx:55][src58] | Source-verified; literal. |
| SC-072 | catalogue-cover | `CatalogueCover; if caption; else attribution === null` | `Carátula: {attribution.label}` | [catalogue-cover.tsx:57][src58] | Source-verified; dynamic template/branch. |
| SC-073 | multi-select | `More than one selected filter` | `{selectedCount} seleccionadas` | [multi-select.tsx:45][src59] | Template; source-verified. Synthetic examples are explained below. |
| SC-074 | multi-select | `Closed filter trigger; no selection / one / many` | `{label}: {allLabelOrSelectedNameOrCount}` | [multi-select.tsx:165][src59] | Template; source-verified. Synthetic examples are explained below. |
| SC-075 | multi-select | `MultiSelect` | `{label}:` | [multi-select.tsx:167][src59] | Source-verified; dynamic template/branch. |

### Backend-generated display copy and data-backed surfaces

| ID | Screen/component | State or trigger | Exact current text | Source (file) | Notes |
| --- | --- | --- | --- | --- | --- |
| BE-001 | CatalogueCoverMapper | `Unavailable cover transport fallback` | `Carátula oficial no disponible` | [CatalogueCoverMapper.java:15][src60] | Template; source-verified. Synthetic examples are explained below. |
| BE-002 | JdbcCatalogueSynchronizationStore | `No source summary when importing a game` | `Todavía no hay un resumen curado para este juego.` | [JdbcCatalogueSynchronizationStore.java:808][src61] | Template; source-verified. Synthetic examples are explained below. |
| BE-003 | FeaturedReleaseService | `Featured landscape alternative text` | `Arte de {title} / Captura de {title}` | [FeaturedReleaseService.java:181][src62] | Template; source-verified. Synthetic examples are explained below. |
| BE-004 | FeaturedReleaseService | `Featured product fallback alternative text` | `Imagen destacada no disponible de {title}` | [FeaturedReleaseService.java:209][src62] | Template; source-verified. Synthetic examples are explained below. |
| BE-005 | FeaturedReleaseService | `Featured logo alternative text` | `{canonicalTitle}` | [FeaturedReleaseService.java:218][src62] | React secondary logo deliberately renders alt="" because the adjacent heading names it. |
| BE-006 | CoverSelectionPolicy | `Imported game without provider cover` | `Portada no disponible de {canonicalTitle}` | [CoverSelectionPolicy.java:48][src63] | Template; source-verified. Synthetic examples are explained below. |
| BE-007 | CoverSelectionPolicy | `Imported provider cover alternative text` | `Portada de {canonicalTitle}` | [CoverSelectionPolicy.java:53][src63] | Template; source-verified. Synthetic examples are explained below. |
| BE-008 | RegionLabel | `Blank or missing provider region descriptor` | `Región sin nombre` | [RegionLabel.java:27][src64] | Template; source-verified. Synthetic examples are explained below. |
| BE-009 | RegionLabel | `Approved acquisition-time region label map` | `Mundial; Europa; Norteamérica; Japón; Nueva Zelanda; Corea; Brasil` | [RegionLabel.java:34][src64] | Other descriptors retain readable original spelling or title-cased words; e.g. Middle East. Existing stored labels are not silently renamed. |

### Legal links and page furniture — local draft only

`legalPublicationApproved=false`. `/privacidad`, `/condiciones`, `/aviso-legal` and `/cookies` are development-review routes, not published product pages. The production footer has only the brand while unapproved. Full draft body text follows separately.

| ID | Screen/component | State or trigger | Exact current text | Source (file) | Notes |
| --- | --- | --- | --- | --- | --- |
| LE-001 | legal-links | `labels; privacidad` | `Privacidad` | [legal-links.tsx:7][src65] | Source-verified; literal. Development review only; publication disabled. |
| LE-002 | legal-links | `labels; condiciones` | `Condiciones de uso` | [legal-links.tsx:7][src65] | Source-verified; literal. Development review only; publication disabled. |
| LE-003 | legal-links | `labels; "aviso-legal"` | `Aviso legal` | [legal-links.tsx:7][src65] | Source-verified; literal. Development review only; publication disabled. |
| LE-004 | legal-links | `labels; cookies` | `Cookies` | [legal-links.tsx:7][src65] | Source-verified; literal. Development review only; publication disabled. |
| LE-005 | legal-links | `LegalLinks` | `Información legal` | [legal-links.tsx:13][src65] | Source-verified; accessible name/announcement. Development review only; publication disabled. |
| LE-006 | legal-links | `LegalLinks` | `Contacto` | [legal-links.tsx:15][src65] | Source-verified; literal. Development review only; publication disabled. |
| LE-007 | legal-links | `LegalLinks` | `Borradores para revisión` | [legal-links.tsx:16][src65] | Source-verified; literal. Development review only; publication disabled. |
| LE-008 | legal-pages | `LegalPage` | `Estado del texto` | [legal-pages.tsx:25][src66] | Source-verified; accessible name/announcement. Development review only; publication disabled. |
| LE-009 | legal-pages | `LegalPage` | `Borrador para revisión. No está en vigor. La publicación requiere aprobar los textos y verificar los proveedores y mecanismos operativos.` | [legal-pages.tsx:25][src66] | Source-verified; literal. Development review only; publication disabled. |
| LE-010 | legal-pages | `LegalPage` | `La publicación requiere aprobar los textos y verificar los proveedores y mecanismos operativos.` | [legal-pages.tsx:27][src66] | Source-verified; literal. Development review only; publication disabled. |
| LE-011 | legal-pages | `LegalPage; if block.kind === "table" && "rows" in block` | `Inventario de cookies` | [legal-pages.tsx:34][src66] | Source-verified; accessible name/announcement. Development review only; publication disabled. |
| LE-012 | legal-pages | `LegalPage; if block.kind === "table" && "rows" in block` | `Cookies de autenticación, seguridad e idioma` | [legal-pages.tsx:36][src66] | Source-verified; literal. Development review only; publication disabled. |
| LE-013 | legal-pages | `LegalPage` | `Volver a Gameómetro` | [legal-pages.tsx:46][src66] | Source-verified; literal. Development review only; publication disabled. |

### Legal draft bodies — not in force

The generated JSON is rendered verbatim with inline links/code; its [canonical legal source](../../legal/public-texts-es.md) owns these proposed notices. Link markup in the cells below records the source text; link labels are what the reader sees. These are intentionally review-only claims, not proof of operating policy or consent.

| ID | Screen/component | State or trigger | Exact current text | Source (file) | Notes |
| --- | --- | --- | --- | --- | --- |
| LD-001 | proposed-texts | `/privacidad: document title` | `Política de privacidad` | [proposed-texts.json:6][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-002 | proposed-texts | `/privacidad: block 1 (heading)` | `Responsable y contacto` | [proposed-texts.json:10][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-003 | proposed-texts | `/privacidad: block 2 (paragraph)` | `El responsable del tratamiento es Rubén Hernández López, persona física y titular de Gameómetro. Puedes contactar y ejercer tus derechos en [hola@gameometro.com](mailto:hola@gameometro.com).` | [proposed-texts.json:14][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-004 | proposed-texts | `/privacidad: block 3 (heading)` | `Datos, finalidades y bases jurídicas` | [proposed-texts.json:18][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-005 | proposed-texts | `/privacidad: block 4 (paragraph)` | `Puedes consultar el catálogo sin crear una cuenta. Al visitar la web, tu dispositivo transmite los datos técnicos necesarios para establecer la conexión, incluida la dirección IP. Las imágenes del catálogo se solicitan directamente al CDN de IGDB, como explicamos más abajo.` | [proposed-texts.json:22][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-006 | proposed-texts | `/privacidad: block 5 (paragraph)` | `Para crear y utilizar tu cuenta tratamos tu email, su estado de verificación, identificadores internos, fechas de la cuenta y la credencial protegida mediante un resumen criptográfico de la contraseña. No pedimos nombre, apellidos, fecha de nacimiento ni documento de identidad al registrarte. Debes verificar el email antes de usar la cuenta. El servicio está dirigido a mayores de 18 años, según tu declaración. Sin el email y los datos de autenticación no podemos ofrecerte una cuenta; puedes seguir consultando el catálogo.` | [proposed-texts.json:26][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-007 | proposed-texts | `/privacidad: block 6 (paragraph)` | `Guardamos tus puntuaciones, el juego al que corresponden y las fechas de creación y modificación. Tu lista personal no se publica. Cada puntuación contribuye a la media, el número de votos y la distribución pública del juego, sin mostrar tu identidad. No prometemos anonimato absoluto: las estadísticas de grupos pequeños pueden permitir inferencias. No hay perfiles públicos, publicidad ni venta de datos personales.` | [proposed-texts.json:30][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-008 | proposed-texts | `/privacidad: block 7 (paragraph)` | `La gestión de la cuenta, verificación, recuperación y puntuaciones se basa en la prestación del servicio que solicitas al aceptar las condiciones (artículo 6.1.b RGPD). Las sesiones incluyen identificadores, fechas de acceso, dirección IP y datos del navegador o dispositivo en Keycloak; el BFF mantiene la sesión y sus credenciales en el servidor. Se utilizan para autenticarte y evitar usos no autorizados.` | [proposed-texts.json:34][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-009 | proposed-texts | `/privacidad: block 8 (paragraph)` | `La seguridad, prevención de abusos y recuperación mediante copias se basan en el interés legítimo de proteger el servicio y sus usuarios (artículo 6.1.f RGPD), con acceso restringido y minimización. No se realizan decisiones individuales automatizadas con efectos jurídicos o similares ni perfiles comerciales. Las protecciones automáticas pueden bloquear temporalmente intentos de acceso; puedes pedir asistencia por email.` | [proposed-texts.json:38][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-010 | proposed-texts | `/privacidad: block 9 (paragraph)` | `Si nos escribes, tratamos tu dirección y el contenido necesario para responder. Las solicitudes de derechos y la gestión de brechas se atienden para cumplir las obligaciones de protección de datos (artículo 6.1.c RGPD). No envíes contraseñas, códigos de recuperación ni documentación personal innecesaria.` | [proposed-texts.json:42][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-011 | proposed-texts | `/privacidad: block 10 (heading)` | `Destinatarios y transferencias internacionales` | [proposed-texts.json:46][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-012 | proposed-texts | `/privacidad: block 11 (paragraph)` | `La identidad se gestiona con Keycloak y las puntuaciones con PostgreSQL, alojados bajo el control del titular. Los proyectos que desarrollan ese software no reciben tus datos simplemente por usar sus programas. Los proveedores del servicio son netcup para el alojamiento, OVHcloud para el dominio y el correo de contacto, y Mailjet para el envío de mensajes exclusivamente transaccionales de verificación y recuperación. Estos mensajes incluyen tu dirección, el contenido necesario y datos de entrega; no son boletines ni publicidad.` | [proposed-texts.json:50][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-013 | proposed-texts | `/privacidad: block 12 (paragraph)` | `Las copias cifradas y la información legal restringida se guardan en equipos y soportes administrados por el responsable, separados del servidor de producción. No se utilizan para elaborar perfiles ni para seguir prestando el servicio con datos que deban permanecer bloqueados. Los proveedores pueden tratar datos para sus propias obligaciones contractuales y legales, según su función.` | [proposed-texts.json:54][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-014 | proposed-texts | `/privacidad: block 13 (paragraph)` | `Mailjet forma parte de Sinch, cuya información contractual contempla subencargados fuera del Espacio Económico Europeo, incluido soporte en Estados Unidos. La ubicación europea de un centro de datos no significa que todos los accesos se produzcan allí. Puedes solicitar información sobre los destinatarios y las garantías de transferencia aplicables a tu caso en hola@gameometro.com y consultar la [información de protección de datos de Sinch](https://sinch.com/legal/terms-and-conditions/other-sinch-terms-conditions/data-processing-agreement/).` | [proposed-texts.json:58][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-015 | proposed-texts | `/privacidad: block 14 (paragraph)` | `` Al cargar carátulas e imágenes, tu navegador conecta directamente con `images.igdb.com`, servido mediante una red de distribución de contenidos. Esto comunica tu IP, datos del navegador y el origen `gameometro.com`; no enviamos al CDN tu email ni tus puntuaciones. Twitch informa de tratamiento de datos en Estados Unidos en su [política de privacidad](https://legal.twitch.com/en/legal/privacy-notice/). La información sobre estas conexiones forma parte de tu derecho de acceso y puedes plantear una oposición al tratamiento en el contacto indicado. `` | [proposed-texts.json:62][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-016 | proposed-texts | `/privacidad: block 15 (paragraph)` | `No se venden tus datos ni se comunican a otros destinatarios salvo las necesidades indicadas o una obligación legal. No solicitamos tu consentimiento para publicidad ni para tratamientos comerciales ajenos al servicio.` | [proposed-texts.json:66][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-017 | proposed-texts | `/privacidad: block 16 (heading)` | `Conservación` | [proposed-texts.json:70][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-018 | proposed-texts | `/privacidad: block 17 (paragraph)` | `Las cuentas verificadas y tus puntuaciones se conservan hasta que las elimines o solicites su supresión, salvo una obligación legal aplicable. La inactividad no provoca el borrado de una cuenta verificada. Puedes borrar cada puntuación desde el producto. Para eliminar la cuenta y todas sus puntuaciones, escribe al contacto anterior. Si no verificas el email, la cuenta se elimina al alcanzar 30 días desde su creación en la siguiente ejecución periódica de limpieza. Puedes volver a registrarte después; los enlaces de la cuenta eliminada dejarán de funcionar.` | [proposed-texts.json:74][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-019 | proposed-texts | `/privacidad: block 18 (paragraph)` | `Las sesiones tienen un límite de 30 minutos de inactividad y 8 horas de duración máxima. Los logs del servicio tendrán una conservación máxima de 7 días. Las copias de seguridad estarán cifradas y caducarán como máximo a los 35 días, incluidas las copias externas. Una restauración debe reaplicar los borrados antes de reabrir. Estos límites corresponden a la operación de Gameómetro. Mailjet publica una conservación de 90 días para estadísticas y campañas del plan Free, y mantiene contactos y plantillas durante la vida de la cuenta. No creamos listas de contactos para publicidad. Ese plazo no equivale al de todos los datos de entrega SMTP o del CDN; puedes pedir los plazos o criterios aplicables en el contacto indicado.` | [proposed-texts.json:78][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-020 | proposed-texts | `/privacidad: block 19 (paragraph)` | `El registro mínimo para impedir que una copia restaure datos borrados se conserva mientras existan copias capaces de recuperarlos, al menos 35 días tras completar el borrado; los borrados incompletos requieren resolución. El justificante de supresión y la documentación necesaria para acreditar la atención de derechos se conservan por separado del archivo de datos legalmente bloqueados y del registro técnico de borrados. Los datos que deban bloquearse permanecen fuera del uso ordinario durante un plazo inicial de tres años desde su bloqueo, con revisión cada doce meses. Si una responsabilidad u obligación aplicable exige más tiempo, se conservan únicamente los datos necesarios durante ese período, de forma documentada. Después se destruyen. En los casos ordinarios, el archivo bloqueado conserva solo los datos básicos necesarios de identidad y de la operación; no conserva tus puntuaciones individuales. Ante una reclamación o responsabilidad concreta, solo se reservan los datos adicionales pertinentes con su fundamento y plazo. Los tres años no son un plazo legal universal ni autorizan a conservar indiscriminadamente información personal.` | [proposed-texts.json:82][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-021 | proposed-texts | `/privacidad: block 20 (heading)` | `Derechos y reclamaciones` | [proposed-texts.json:86][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-022 | proposed-texts | `/privacidad: block 21 (paragraph)` | `Puedes solicitar acceso, rectificación, supresión, limitación, oposición y portabilidad cuando procedan, escribiendo a [hola@gameometro.com](mailto:hola@gameometro.com). Indica qué necesitas y el email de tu cuenta. Verificaremos tu identidad con medios proporcionados; no pedimos una copia del DNI por defecto. Si has perdido acceso al email, indícalo sin adjuntar documentación inicialmente. No entregaremos un export a una dirección alternativa sin verificar de forma suficiente su titularidad.` | [proposed-texts.json:90][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-023 | proposed-texts | `/privacidad: block 22 (paragraph)` | `Puedes consultar y modificar tus puntuaciones en «Mis puntuaciones». El cambio de email y la eliminación completa de la cuenta se gestionan administrativamente. El acceso puede incluir información de tus sesiones e IP; la exportación de datos se facilita en JSON. No incluye contraseñas ni secretos de autenticación.` | [proposed-texts.json:94][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-024 | proposed-texts | `/privacidad: block 23 (paragraph)` | `Responderemos sin dilación indebida y, como máximo, en un mes desde la recepción. Si la complejidad o el número de solicitudes lo requieren, podemos ampliar hasta dos meses adicionales, informándote dentro del primer mes del motivo. El ejercicio es gratuito, salvo las excepciones legales de solicitudes manifiestamente infundadas o excesivas, que deben justificarse. Puedes reclamar ante la [Agencia Española de Protección de Datos](https://www.aepd.es/), especialmente si no recibes una respuesta satisfactoria. La aceptación de las condiciones no supone renunciar a tus derechos.` | [proposed-texts.json:98][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-025 | proposed-texts | `/condiciones: document title` | `Condiciones de uso` | [proposed-texts.json:103][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-026 | proposed-texts | `/condiciones: block 1 (paragraph)` | `Gameómetro es un proyecto personal, gratuito y sin actividad económica gestionado por Rubén Hernández López, para descubrir videojuegos y conservar puntuaciones personales. No incluye anuncios, afiliación, patrocinios, donaciones, ventas ni promoción profesional. El contacto es [hola@gameometro.com](mailto:hola@gameometro.com).` | [proposed-texts.json:107][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-027 | proposed-texts | `/condiciones: block 2 (paragraph)` | `Para registrarte debes tener 18 años o más, declarar esa circunstancia, aceptar estas condiciones y verificar tu email. No aportes datos de otra persona. Protege tu contraseña y avísanos si sospechas un acceso indebido. La recuperación de contraseña se realiza mediante los mensajes del proveedor de identidad; nunca te pediremos tu contraseña por email.` | [proposed-texts.json:111][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-028 | proposed-texts | `/condiciones: block 3 (paragraph)` | `Puedes puntuar los juegos disponibles que admitan valoración, consultar, cambiar o borrar tus puntuaciones. Se permite una puntuación activa de 1 a 10 por cuenta y juego. Tu puntuación personal y los resultados agregados se presentan por separado. Las puntuaciones externas de IGDB se identifican como tales y no se mezclan con las de Gameómetro. No publiques material ilegal ni intentes acceder a cuentas ajenas, eludir protecciones o perjudicar la disponibilidad del servicio.` | [proposed-texts.json:115][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-029 | proposed-texts | `/condiciones: block 4 (paragraph)` | `El catálogo procede de fuentes externas, puede contener errores y no garantiza cobertura completa ni fechas definitivas. Los resúmenes traducidos automáticamente se identifican con su procedencia. Las imágenes y contenidos de terceros pertenecen a sus respectivos titulares; mostrarlos no concede al usuario derechos sobre ellos ni implica respaldo de IGDB, Twitch o las editoras. Las observaciones sobre derechos pueden enviarse al contacto anterior.` | [proposed-texts.json:119][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-030 | proposed-texts | `/condiciones: block 5 (paragraph)` | `El servicio se mantiene con dedicación limitada, sin promesa comercial de disponibilidad continua ni soporte inmediato. Puede interrumpirse por mantenimiento, seguridad o incidencias de proveedores, incluidas reclamaciones sobre contenidos. Podemos suspender temporalmente el acceso mientras se investiga y se decide una solución. Esto no excluye obligaciones legales ni responsabilidades que no puedan limitarse. Cuando sea posible, se informará de cambios relevantes y del cierre del servicio con oportunidad de solicitar los datos.` | [proposed-texts.json:123][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-031 | proposed-texts | `/condiciones: block 6 (paragraph)` | `Puedes dejar de usarlo en cualquier momento, borrar puntuaciones o solicitar la baja y el borrado de tu cuenta por email. Las reglas de conservación y derechos figuran en la política de privacidad. Ante abuso, se podrán aplicar restricciones proporcionadas; puedes solicitar revisión por email. Los cambios sustanciales de estas condiciones se comunicarán antes de aplicarse; si alteran lo aceptado se evaluará una nueva aceptación. No se limita el acceso a las autoridades o tribunales que resulten competentes según las normas aplicables.` | [proposed-texts.json:127][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-032 | proposed-texts | `/aviso-legal: document title` | `Aviso legal` | [proposed-texts.json:132][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-033 | proposed-texts | `/aviso-legal: block 1 (paragraph)` | `` Titular y responsable de Gameómetro (`gameometro.com`): Rubén Hernández López. Contacto: [hola@gameometro.com](mailto:hola@gameometro.com). `` | [proposed-texts.json:136][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-034 | proposed-texts | `/aviso-legal: block 2 (paragraph)` | `El proyecto es personal, gratuito y no desarrolla actividad económica: no incorpora anuncios, afiliación, patrocinios, donaciones, ventas ni promoción profesional.` | [proposed-texts.json:140][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-035 | proposed-texts | `/aviso-legal: block 3 (paragraph)` | `Gameómetro ofrece gratuitamente información sobre videojuegos y gestión de puntuaciones. Los contenidos propios y los de terceros conservan sus respectivos derechos. Consulta las condiciones de uso, la política de privacidad y la información sobre cookies del servicio. Para errores o reclamaciones utiliza el contacto indicado.` | [proposed-texts.json:144][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-036 | proposed-texts | `/cookies: document title` | `Cookies y almacenamiento del navegador` | [proposed-texts.json:149][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-037 | proposed-texts | `/cookies: block 1 (paragraph)` | `Utilizamos cookies para el acceso que solicitas, su seguridad y el idioma de los formularios. No utilizamos cookies de publicidad ni analítica. No añadimos un banner de consentimiento para estas cookies necesarias. Si se incorporan usos opcionales, se revisarán antes de activarlos.` | [proposed-texts.json:153][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-038 | proposed-texts | `/cookies: table row 1, cell 1` | `Cookie` | [proposed-texts.json:159][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-039 | proposed-texts | `/cookies: table row 1, cell 2` | `Host` | [proposed-texts.json:160][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-040 | proposed-texts | `/cookies: table row 1, cell 3` | `Finalidad` | [proposed-texts.json:161][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-041 | proposed-texts | `/cookies: table row 1, cell 4` | `Duración` | [proposed-texts.json:162][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-042 | proposed-texts | `/cookies: table row 2, cell 1` | `` `__Host-vgp_session` `` | [proposed-texts.json:165][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-043 | proposed-texts | `/cookies: table row 2, cell 2` | `` `gameometro.com` `` | [proposed-texts.json:166][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-044 | proposed-texts | `/cookies: table row 2, cell 3` | `Sesión del BFF; identificador opaco` | [proposed-texts.json:167][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-045 | proposed-texts | `/cookies: table row 2, cell 4` | `Sesión del navegador; servidor: 30 min de inactividad, máximo 8 h` | [proposed-texts.json:168][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-046 | proposed-texts | `/cookies: table row 3, cell 1` | `` `AUTH_SESSION_ID` `` | [proposed-texts.json:171][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-047 | proposed-texts | `/cookies: table row 3, cell 2` | `` `auth.gameometro.com` `` | [proposed-texts.json:172][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-048 | proposed-texts | `/cookies: table row 3, cell 3` | `Inicio de autenticación` | [proposed-texts.json:173][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-049 | proposed-texts | `/cookies: table row 3, cell 4` | `Sesión del navegador` | [proposed-texts.json:174][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-050 | proposed-texts | `/cookies: table row 4, cell 1` | `` `KC_AUTH_SESSION_HASH` `` | [proposed-texts.json:177][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-051 | proposed-texts | `/cookies: table row 4, cell 2` | `` `auth.gameometro.com` `` | [proposed-texts.json:172][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-052 | proposed-texts | `/cookies: table row 4, cell 3` | `Coordinación segura de la autenticación` | [proposed-texts.json:179][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-053 | proposed-texts | `/cookies: table row 4, cell 4` | `60 segundos` | [proposed-texts.json:180][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-054 | proposed-texts | `/cookies: table row 5, cell 1` | `` `KC_RESTART` `` | [proposed-texts.json:183][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-055 | proposed-texts | `/cookies: table row 5, cell 2` | `` `auth.gameometro.com` `` | [proposed-texts.json:172][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-056 | proposed-texts | `/cookies: table row 5, cell 3` | `Reanudar autenticación o recuperación interrumpida` | [proposed-texts.json:185][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-057 | proposed-texts | `/cookies: table row 5, cell 4` | `Sesión del navegador` | [proposed-texts.json:174][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-058 | proposed-texts | `/cookies: table row 6, cell 1` | `` `KEYCLOAK_LOCALE` `` | [proposed-texts.json:189][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-059 | proposed-texts | `/cookies: table row 6, cell 2` | `` `auth.gameometro.com` `` | [proposed-texts.json:172][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-060 | proposed-texts | `/cookies: table row 6, cell 3` | `Idioma de los formularios` | [proposed-texts.json:191][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-061 | proposed-texts | `/cookies: table row 6, cell 4` | `Sesión del navegador` | [proposed-texts.json:174][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-062 | proposed-texts | `/cookies: table row 7, cell 1` | `` `KEYCLOAK_IDENTITY` `` | [proposed-texts.json:195][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-063 | proposed-texts | `/cookies: table row 7, cell 2` | `` `auth.gameometro.com` `` | [proposed-texts.json:172][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-064 | proposed-texts | `/cookies: table row 7, cell 3` | `Identidad de la sesión de acceso único` | [proposed-texts.json:197][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-065 | proposed-texts | `/cookies: table row 7, cell 4` | `Sesión del navegador` | [proposed-texts.json:174][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-066 | proposed-texts | `/cookies: table row 8, cell 1` | `` `KEYCLOAK_SESSION` `` | [proposed-texts.json:201][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-067 | proposed-texts | `/cookies: table row 8, cell 2` | `` `auth.gameometro.com` `` | [proposed-texts.json:172][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-068 | proposed-texts | `/cookies: table row 8, cell 3` | `Estado de la sesión de acceso único` | [proposed-texts.json:203][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-069 | proposed-texts | `/cookies: table row 8, cell 4` | `Persistente: máximo 8 horas` | [proposed-texts.json:204][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-070 | proposed-texts | `/cookies: block 3 (paragraph)` | `` Todas utilizan `Secure` y sin atributo `Domain`. Las cookies de Keycloak se limitan a `/realms/gameometro/`; la del BFF, a `/`. `KC_AUTH_SESSION_HASH` y `KEYCLOAK_SESSION` son legibles por scripts del proveedor de identidad; las restantes usan `HttpOnly`. La cookie del BFF usa `SameSite=Lax` y las de Keycloak, `SameSite=None`. Son del mismo sitio, aunque sus hosts sean distintos. `` | [proposed-texts.json:210][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-071 | proposed-texts | `/cookies: block 4 (paragraph)` | `` La aplicación no necesita crear cookies para consultar el catálogo sin identificarte. No utiliza `localStorage`, `sessionStorage` ni IndexedDB para los recorridos de cuenta. Los tokens OAuth se mantienen en el servidor; el cierre de sesión puede incluir un ID token en la redirección al proveedor de identidad, sin almacenarlo de forma persistente en el navegador. Las conexiones con el CDN de imágenes se explican en la política de privacidad; esta información no las convierte en cookies de autenticación. `` | [proposed-texts.json:214][src67] | Source-verified; literal. Development review only; publication disabled. |
| LD-072 | proposed-texts | `/cookies: block 5 (paragraph)` | `Puedes bloquear o borrar cookies desde los ajustes del navegador; hacerlo puede impedir el registro, el acceso o la recuperación. La cookie de sesión del BFF y las de identidad autenticada se eliminan al cerrar sesión; algunas cookies auxiliares pueden permanecer hasta cerrar el navegador. La restauración de sesiones del navegador puede conservar cookies de sesión, pero no prolonga los límites del servidor.` | [proposed-texts.json:218][src67] | Source-verified; literal. Development review only; publication disabled. |

### Keycloak browser copy — separate owner: #290

[Issue #290](https://github.com/rubhern/videogame-platform/issues/290) owns hosted-screen visual work; no theme changes are proposed for implementation here. `parent=keycloak.v2` inherits base templates/messages from the branch-pinned Keycloak image. A disposable instance used that image, the current theme and a synthetic copy of the **local import** realm for login, invalid credentials, registration and recovery at 390/1320px. It had no real users, SMTP credentials or private-dev connection. The configured **aligned identity policy** comes from the production import through `configure-keycloak-identity`: email-only profile, verification and 18+ declaration. Those policies are inventoried separately; the old local-import username/name/surname fields are not evidence of deployed private-dev behavior. No deployed realm was inspected.

Message precedence is realm localization over custom theme over parent Spanish/English fallback. A defined custom key may be shadowed or unused; the table states that instead of assuming it is shown. `{0}`, `{1}`, `{2}`, `{3}` are Keycloak MessageFormat parameters; no actual email, action link or token is reproduced. The browser title is `Gameómetro` from `loginTitle`.

| ID | Screen/component | State or trigger | Exact current text | Source (file) | Notes |
| --- | --- | --- | --- | --- | --- |
| KC-001 | messages_es | `loginTitle` | `Gameómetro` | [messages_es.properties:1][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. |
| KC-002 | messages_es | `loginTitleHtml` | `Gameómetro` | [messages_es.properties:2][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. |
| KC-003 | messages_es | `loginAccountTitle` | `Bienvenido, Jugón` | [messages_es.properties:3][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. |
| KC-004 | messages_es | `registerTitle` | `Empieza tu historia.` | [messages_es.properties:4][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. |
| KC-005 | messages_es | `doLogin` | `Iniciar sesión` | [messages_es.properties:5][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. Parent templates use doLogIn (capital I), not this key; inherited doLogIn happens to have the same current wording. |
| KC-006 | messages_es | `doRegister` | `Crear cuenta` | [messages_es.properties:6][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. |
| KC-007 | messages_es | `noAccount` | `¿Es tu primera vez?` | [messages_es.properties:7][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. |
| KC-008 | messages_es | `emailForgotTitle` | `Recupera tu acceso` | [messages_es.properties:8][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. |
| KC-009 | messages_es | `updatePasswordTitle` | `Elige una nueva contraseña` | [messages_es.properties:9][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. |
| KC-010 | messages_es | `doSubmit` | `Continuar` | [messages_es.properties:10][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. |
| KC-011 | messages_es | `backToLogin` | `Volver a iniciar sesión` | [messages_es.properties:11][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. |
| KC-012 | messages_es | `gmClaim` | `Tu criterio.` | [messages_es.properties:12][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. Shared story/footer content; responsive presentation depends on inherited page layout. |
| KC-013 | messages_es | `gmClaimAccent` | `Tu historia.` | [messages_es.properties:13][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. Shared story/footer content; responsive presentation depends on inherited page layout. |
| KC-014 | messages_es | `gmLede` | `Puntúa del 1 al 10 lo que juegas y guarda tu historial de puntuaciones.` | [messages_es.properties:14][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. Shared story/footer content; responsive presentation depends on inherited page layout. |
| KC-015 | messages_es | `gmScaleTitle` | `Cada nota, una temperatura` | [messages_es.properties:15][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. Shared story/footer content; responsive presentation depends on inherited page layout. |
| KC-016 | messages_es | `gmScaleFreeze` | `1 · Congelado` | [messages_es.properties:16][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. Shared story/footer content; responsive presentation depends on inherited page layout. |
| KC-017 | messages_es | `gmScaleBurn` | `10 · Ardiendo` | [messages_es.properties:17][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. Shared story/footer content; responsive presentation depends on inherited page layout. |
| KC-018 | messages_es | `gmBackToCatalogue` | `Volver al catálogo` | [messages_es.properties:18][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. |
| KC-019 | messages_es | `passwordConfirm` | `Confirma la contraseña` | [messages_es.properties:19][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. |
| KC-020 | messages_es | `emailInstruction` | `Indica tu usuario. Si corresponde a una cuenta, te enviaremos a su email un enlace para crear una nueva contraseña.` | [messages_es.properties:20][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. Shadowed by aligned realm localization; local import uses theme wording. |
| KC-021 | messages_es | `emailSentMessage` | `Si el usuario corresponde a una cuenta, recibirás en breve un email con un enlace para crear una nueva contraseña.` | [messages_es.properties:21][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. Shadowed by aligned realm localization; local import uses theme wording. |
| KC-022 | messages_es | `resetPasswordMessage` | `Escribe tu nueva contraseña y confírmala.` | [messages_es.properties:22][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. |
| KC-023 | messages_es | `resetCredentialNotAllowedMessage` | `Este enlace no es válido para crear una nueva contraseña.` | [messages_es.properties:23][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. |
| KC-024 | messages_es | `expiredActionMessage` | `El enlace ha caducado o ya se ha usado. Vuelve a iniciar sesión o pide uno nuevo.` | [messages_es.properties:24][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. |
| KC-025 | messages_es | `expiredActionTokenNoSessionMessage` | `El enlace ha caducado o ya se ha usado. Pide uno nuevo desde ¿Has olvidado tu contraseña?` | [messages_es.properties:25][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. |
| KC-026 | messages_es | `expiredActionTokenSessionExistsMessage` | `El enlace ha caducado o ya se ha usado. Pide uno nuevo para continuar.` | [messages_es.properties:26][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. |
| KC-027 | messages_es | `missingUsernameMessage` | `Indica tu usuario.` | [messages_es.properties:27][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. Shadowed by aligned realm localization; local import uses theme wording. |
| KC-028 | messages_es | `missingPasswordMessage` | `Indica tu contraseña.` | [messages_es.properties:28][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. |
| KC-029 | messages_es | `missingEmailMessage` | `Indica tu email.` | [messages_es.properties:29][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. |
| KC-030 | messages_es | `missingFirstNameMessage` | `Indica tu nombre.` | [messages_es.properties:30][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. Legacy local profile only; aligned registration does not request these fields. |
| KC-031 | messages_es | `missingLastNameMessage` | `Indica tu apellido.` | [messages_es.properties:31][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. Legacy local profile only; aligned registration does not request these fields. |
| KC-032 | messages_es | `error-user-attribute-required` | `Completa este campo.` | [messages_es.properties:32][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. |
| KC-033 | messages_es | `emailVerifyTitle` | `Verifica tu email` | [messages_es.properties:33][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. |
| KC-034 | messages_es | `emailVerifyInstruction1` | `Te hemos enviado a {0} un enlace para verificar tu email.` | [messages_es.properties:34][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. |
| KC-035 | messages_es | `emailVerifyInstruction2` | `¿No te ha llegado?` | [messages_es.properties:35][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. Legacy resend branch; Keycloak 26.7 default email-verification view uses newer send/resend controls. |
| KC-036 | messages_es | `emailVerifyInstruction3` | `para enviarlo de nuevo.` | [messages_es.properties:36][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. Legacy resend branch; Keycloak 26.7 default email-verification view uses newer send/resend controls. |
| KC-037 | messages_es | `emailVerifyInstruction4` | `Te enviaremos a {0} un enlace para verificar tu email.` | [messages_es.properties:37][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. |
| KC-038 | messages_es | `emailVerifySend` | `Enviar el enlace` | [messages_es.properties:38][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. |
| KC-039 | messages_es | `emailVerifyResend` | `Volver a enviar el enlace` | [messages_es.properties:39][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. |
| KC-040 | messages_es | `verifyEmailMessage` | `Verifica tu email para activar tu cuenta.` | [messages_es.properties:40][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. |
| KC-041 | messages_es | `confirmEmailAddressVerification` | `Confirma que {0} es tu email.` | [messages_es.properties:41][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. |
| KC-042 | messages_es | `emailVerifiedMessage` | `Tu email está verificado.` | [messages_es.properties:42][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. |
| KC-043 | messages_es | `emailVerifiedAlreadyMessage` | `Tu email ya estaba verificado.` | [messages_es.properties:43][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. |
| KC-044 | messages_es | `invalidPasswordMinLengthMessage` | `La contraseña debe tener al menos {0} caracteres.` | [messages_es.properties:44][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. |
| KC-045 | messages_es | `invalidPasswordMaxLengthMessage` | `La contraseña puede tener como máximo {0} caracteres.` | [messages_es.properties:45][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. |
| KC-046 | messages_es | `invalidPasswordNotEmailMessage` | `La contraseña no puede ser tu email.` | [messages_es.properties:46][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. |
| KC-047 | messages_es | `confirmEmailAddressVerificationHeader` | `Verifica tu email` | [messages_es.properties:47][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. |
| KC-048 | messages_es | `emailVerifiedMessageHeader` | `Email verificado` | [messages_es.properties:48][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. |
| KC-049 | messages_es | `updatePasswordMessage` | `Elige la contraseña de tu cuenta para activarla.` | [messages_es.properties:49][src68] | Custom theme definition; inherited form/error/required-action selects the key. Runtime proof is limited to the coverage matrix. |

#### Aligned identity and legal-review localization

| ID | Screen/component | State or trigger | Exact current text | Source (file) | Notes |
| --- | --- | --- | --- | --- | --- |
| KR-001 | gameometro-realm | `Aligned realm localization: termsTitle` | `Antes de crear tu cuenta` | [gameometro-realm.json:195][src69] | Overrides theme/parent when managed identity alignment has been applied; public production remains unbuilt. |
| KR-002 | gameometro-realm | `Aligned realm localization: termsText` | `<p>Gameómetro es un servicio gratuito y sin fines comerciales para personas adultas. Para crear tu cuenta solo te pedimos tu email.</p>` | [gameometro-realm.json:196][src69] | Overrides theme/parent when managed identity alignment has been applied; public production remains unbuilt. |
| KR-003 | gameometro-realm | `Aligned realm localization: acceptTerms` | `Declaro que tengo 18 años o más y acepto las condiciones de uso de Gameómetro.` | [gameometro-realm.json:197][src69] | Overrides theme/parent when managed identity alignment has been applied; public production remains unbuilt. |
| KR-004 | gameometro-realm | `Aligned realm localization: termsAcceptanceRequired` | `Para crear tu cuenta, confirma que tienes 18 años o más y aceptas las condiciones de uso.` | [gameometro-realm.json:198][src69] | Overrides theme/parent when managed identity alignment has been applied; public production remains unbuilt. |
| KR-005 | gameometro-realm | `Aligned realm localization: emailInstruction` | `Indica tu email. Si corresponde a una cuenta, te enviaremos un enlace para crear una nueva contraseña.` | [gameometro-realm.json:199][src69] | Overrides theme/parent when managed identity alignment has been applied; public production remains unbuilt. |
| KR-006 | gameometro-realm | `Aligned realm localization: emailSentMessage` | `Si el email corresponde a una cuenta, recibirás en breve un enlace para crear una nueva contraseña.` | [gameometro-realm.json:200][src69] | Overrides theme/parent when managed identity alignment has been applied; public production remains unbuilt. |
| KR-007 | gameometro-realm | `Aligned realm localization: invalidPasswordNotContainsUsernameMessage` | `La contraseña no puede contener tu email.` | [gameometro-realm.json:201][src69] | Overrides theme/parent when managed identity alignment has been applied; public production remains unbuilt. |
| KR-008 | gameometro-realm | `Aligned realm localization: missingUsernameMessage` | `Indica tu email.` | [gameometro-realm.json:202][src69] | Overrides theme/parent when managed identity alignment has been applied; public production remains unbuilt. |
| KR-009 | gameometro-realm | `Aligned realm localization: invalidUserMessage` | `Email o contraseña incorrectos.` | [gameometro-realm.json:203][src69] | Overrides theme/parent when managed identity alignment has been applied; public production remains unbuilt. |
| KR-010 | gameometro-realm | `Aligned realm localization: accountTemporarilyDisabledMessage` | `Email o contraseña incorrectos.` | [gameometro-realm.json:204][src69] | Overrides theme/parent when managed identity alignment has been applied; public production remains unbuilt. |
| KR-011 | legal-review | `Review-only realm localization: termsText` | `<p>Responsable: Rubén Hernández López. Trataremos tu email, los datos necesarios para proteger tu cuenta y tus puntuaciones para prestarte el servicio que solicitas. La gestión de cuenta y puntuaciones se basa en la ejecución de las condiciones de uso; la seguridad, en nuestro interés legítimo, según se explica en la política de privacidad. Puedes ejercer tus derechos en <a href="mailto:hola@gameometro.com">hola@gameometro.com</a>. Consulta la política de privacidad para conocer destinatarios, conservación, transferencias y todos tus derechos.</p><p>Crear una cuenta requiere tener al menos 18 años y aceptar las condiciones de uso. La información de privacidad se facilita para que conozcas el tratamiento; no te pedimos que consientas tratamientos opcionales para utilizar tu cuenta.</p><p>Consulta la <a href="https://gameometro.com/privacidad">política de privacidad</a> y las <a href="https://gameometro.com/condiciones">condiciones de uso</a> antes de registrarte.</p>` | [legal-review.json:7][src70] | Unapproved legal-review overlay; not evidence of live registration wording. |

#### Inherited native messages and controls

The following are definitions from `org.keycloak.keycloak-themes-26.7.0.jar`, extracted from the cached official image specified by the audited Dockerfile. Scope includes the credential/registration/recovery/verification/update/logout/page-expired/error templates, their labels, user-profile validation and password-policy messages. Conditional policies/controls and native fallbacks are explicitly unverified; LDAP, social identity, organizations, OTP/WebAuthn setup and administrative-console localization are not implemented Gameómetro journeys. They are not claimed as reachable screens. Native server error text is selected by Keycloak; there is no React translation mapping for these messages.

| ID | Screen/component | State or trigger | Exact current text | Source (file) | Notes |
| --- | --- | --- | --- | --- | --- |
| KI-001 | Keycloak base/login/messages_es.properties | `acceptTerms` | `Estoy de acuerdo con los términos y condiciones` | [Keycloak base/login/messages_es.properties:255][src71] | Spanish parent; register-commons.ftl. Source-verified, not every message triggered. Shadowed by aligned realm localization. |
| KI-002 | Keycloak base/login/messages_es.properties | `accountDisabledMessage` | `La cuenta está desactivada, contacte con el administrador.` | [Keycloak base/login/messages_es.properties:117][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-003 | Keycloak base/login/messages_es.properties | `accountPasswordUpdatedMessage` | `Tu contraseña se ha actualizado.` | [Keycloak base/login/messages_es.properties:151][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-004 | Keycloak base/login/messages_es.properties | `accountPermanentlyDisabledMessage` | `Usuario o contraseña incorrectos.` | [Keycloak base/login/messages_es.properties:119][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-005 | Keycloak base/login/messages_es.properties | `accountTemporarilyDisabledMessage` | `Usuario o contraseña incorrectos.` | [Keycloak base/login/messages_es.properties:118][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. Shadowed by aligned realm localization. |
| KI-006 | Keycloak base/login/messages_es.properties | `accountUpdatedMessage` | `Tu cuenta se ha actualizado.` | [Keycloak base/login/messages_es.properties:150][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-007 | Keycloak base/login/messages_es.properties | `backToApplication` | `Volver a la aplicación` | [Keycloak base/login/messages_es.properties:199][src71] | Spanish parent; info.ftl, error.ftl, logout-confirm.ftl. Source-verified, not every message triggered. |
| KI-008 | Keycloak base/login/messages_es.properties | `clientNotFoundMessage` | `Cliente no encontrado.` | [Keycloak base/login/messages_es.properties:201][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-009 | Keycloak base/login/messages_es.properties | `code-clipboard-label` | `Mostrar contenido` | [Keycloak base/login/messages_es.properties:505][src71] | Spanish parent; field.ftl. Source-verified, not every message triggered. Conditional native feature; current minimal realm does not establish a reachable usage. |
| KI-010 | Keycloak base/login/messages_es.properties | `code-copy-failure` | `Fallo la copia del código al portapapel` | [Keycloak base/login/messages_es.properties:508][src71] | Spanish parent; field.ftl. Source-verified, not every message triggered. Conditional native feature; current minimal realm does not establish a reachable usage. |
| KI-011 | Keycloak base/login/messages_es.properties | `code-copy-label` | `Copiar al portapapel` | [Keycloak base/login/messages_es.properties:506][src71] | Spanish parent; field.ftl. Source-verified, not every message triggered. Conditional native feature; current minimal realm does not establish a reachable usage. |
| KI-012 | Keycloak base/login/messages_es.properties | `code-copy-success` | `Código copiado al portapapel` | [Keycloak base/login/messages_es.properties:507][src71] | Spanish parent; field.ftl. Source-verified, not every message triggered. Conditional native feature; current minimal realm does not establish a reachable usage. |
| KI-013 | Keycloak base/login/messages_es.properties | `doCancel` | `Cancelar` | [Keycloak base/login/messages_es.properties:4][src71] | Spanish parent; login-verify-email.ftl. Source-verified, not every message triggered. |
| KI-014 | Keycloak base/login/messages_es.properties | `doClickHere` | `Haz clic aquí` | [Keycloak base/login/messages_es.properties:12][src71] | Spanish parent; login-verify-email.ftl, login-page-expired.ftl. Source-verified, not every message triggered. |
| KI-015 | Keycloak base/login/messages_es.properties | `doContinue` | `Continuar` | [Keycloak base/login/messages_es.properties:8][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-016 | Keycloak base/login/messages_es.properties | `doForgotPassword` | `¿Has olvidado tu contraseña?` | [Keycloak base/login/messages_es.properties:11][src71] | Spanish parent; field.ftl. Source-verified, not every message triggered. |
| KI-017 | Keycloak base/login/messages_es.properties | `doLogIn` | `Iniciar sesión` | [Keycloak base/login/messages_es.properties:1][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-018 | Keycloak base/login/messages_es.properties | `doLogout` | `Cerrar sesión` | [Keycloak base/login/messages_es.properties:225][src71] | Spanish parent; logout-confirm.ftl. Source-verified, not every message triggered. |
| KI-019 | Keycloak base/login/messages_es.properties | `doNo` | `No` | [Keycloak base/login/messages_es.properties:7][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-020 | Keycloak base/login/messages_es.properties | `doSwitchOrganization` | `Cambiar organización` | [Keycloak base/login/messages_es.properties:519][src71] | Spanish parent; template.ftl. Source-verified, not every message triggered. Conditional native feature; current minimal realm does not establish a reachable usage. |
| KI-021 | Keycloak base/login/messages_es.properties | `doTryAnotherWay` | `Pruebe de otra manera` | [Keycloak base/login/messages_es.properties:229][src71] | Spanish parent; template.ftl. Source-verified, not every message triggered. Conditional native feature; current minimal realm does not establish a reachable usage. |
| KI-022 | Keycloak base/login/messages_es.properties | `doYes` | `Sí` | [Keycloak base/login/messages_es.properties:6][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-023 | Keycloak base/login/messages_es.properties | `email` | `Email` | [Keycloak base/login/messages_es.properties:50][src71] | Spanish parent; login.ftl, login-reset-password.ftl, template.ftl. Source-verified, not every message triggered. |
| KI-024 | Keycloak base/login/messages_es.properties | `emailExistsMessage` | `El email ya existe.` | [Keycloak base/login/messages_es.properties:137][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-025 | Keycloak base/login/messages_es.properties | `emailInstructionUsername` | `Ingrese su nombre de usuario y le enviaremos instrucciones sobre cómo crear una nueva contraseña.` | [Keycloak base/login/messages_es.properties:299][src71] | Spanish parent; login-reset-password.ftl. Source-verified, not every message triggered. |
| KI-026 | Keycloak base/login/messages_es.properties | `emailSendErrorMessage` | `Falló el envío del email, por favor inténtalo de nuevo más tarde.` | [Keycloak base/login/messages_es.properties:149][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-027 | Keycloak base/login/messages_es.properties | `error-empty` | `Especifique el valor.` | [Keycloak base/login/messages_es.properties:315][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-028 | Keycloak base/login/messages_es.properties | `error-invalid-blank` | `Especifique el valor.` | [Keycloak base/login/messages_es.properties:314][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-029 | Keycloak base/login/messages_es.properties | `error-invalid-date` | `Fecha inválida.` | [Keycloak base/login/messages_es.properties:329][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-030 | Keycloak base/login/messages_es.properties | `error-invalid-email` | `Dirección de correo electrónico no válida.` | [Keycloak base/login/messages_es.properties:319][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-031 | Keycloak base/login/messages_es.properties | `error-invalid-length` | `La longitud debe estar entre {1} y {2}.` | [Keycloak base/login/messages_es.properties:316][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-032 | Keycloak base/login/messages_es.properties | `error-invalid-length-too-long` | `La longitud máxima es {2}.` | [Keycloak base/login/messages_es.properties:318][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-033 | Keycloak base/login/messages_es.properties | `error-invalid-length-too-short` | `La longitud mínima es {1}.` | [Keycloak base/login/messages_es.properties:317][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-034 | Keycloak base/login/messages_es.properties | `error-invalid-multivalued-size` | `El atributo {0} debe tener al menos {1} y como máximo {2} {2,choice,0#valores\|1#valor\|1<valores}.` | [Keycloak base/login/messages_es.properties:472][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-035 | Keycloak base/login/messages_es.properties | `error-invalid-number` | `Número invalido.` | [Keycloak base/login/messages_es.properties:320][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-036 | Keycloak base/login/messages_es.properties | `error-invalid-uri` | `URL invalida.` | [Keycloak base/login/messages_es.properties:325][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-037 | Keycloak base/login/messages_es.properties | `error-invalid-uri-fragment` | `Fragmento de URL no válido.` | [Keycloak base/login/messages_es.properties:327][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-038 | Keycloak base/login/messages_es.properties | `error-invalid-uri-scheme` | `Esquema de URL no válido.` | [Keycloak base/login/messages_es.properties:326][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-039 | Keycloak base/login/messages_es.properties | `error-invalid-value` | `Valor no válido.` | [Keycloak base/login/messages_es.properties:313][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-040 | Keycloak base/login/messages_es.properties | `error-non-ascii-local-part-email` | `La parte local de la dirección debe contener únicamente caracteres ASCII.` | [Keycloak base/login/messages_es.properties:494][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-041 | Keycloak base/login/messages_es.properties | `error-number-out-of-range` | `El número debe estar entre {1} y {2}.` | [Keycloak base/login/messages_es.properties:321][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-042 | Keycloak base/login/messages_es.properties | `error-number-out-of-range-too-big` | `El número debe tener un valor máximo de {2}.` | [Keycloak base/login/messages_es.properties:323][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-043 | Keycloak base/login/messages_es.properties | `error-number-out-of-range-too-small` | `El número debe tener un valor mínimo de {1}.` | [Keycloak base/login/messages_es.properties:322][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-044 | Keycloak base/login/messages_es.properties | `error-pattern-no-match` | `Valor no válido.` | [Keycloak base/login/messages_es.properties:324][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-045 | Keycloak base/login/messages_es.properties | `error-person-name-invalid-character` | `El valor contiene carácter inválido.` | [Keycloak base/login/messages_es.properties:332][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-046 | Keycloak base/login/messages_es.properties | `error-reset-otp-missing-id` | `Elija una configuración OTP.` | [Keycloak base/login/messages_es.properties:333][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-047 | Keycloak base/login/messages_es.properties | `error-user-attribute-read-only` | `Este campo es de solo lectura.` | [Keycloak base/login/messages_es.properties:330][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-048 | Keycloak base/login/messages_es.properties | `error-username-invalid-character` | `El valor contiene carácter inválido.` | [Keycloak base/login/messages_es.properties:331][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-049 | Keycloak base/login/messages_es.properties | `errorTitle` | `Lo sentimos...` | [Keycloak base/login/messages_es.properties:30][src71] | Spanish parent; error.ftl. Source-verified, not every message triggered. |
| KI-050 | Keycloak base/login/messages_es.properties | `expiredCodeMessage` | `Se agotó el tiempo máximo para la identificación. Por favor identificate de nuevo.` | [Keycloak base/login/messages_es.properties:122][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-051 | Keycloak base/login/messages_es.properties | `firstName` | `Nombre` | [Keycloak base/login/messages_es.properties:45][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. Local-import profile variant; aligned profile uses email only. |
| KI-052 | Keycloak base/login/messages_es.properties | `hidePassword` | `Ocultar contraseña` | [Keycloak base/login/messages_es.properties:258][src71] | Spanish parent; field.ftl. Source-verified, not every message triggered. |
| KI-053 | Keycloak base/login/messages_es.properties | `internalServerError` | `Se ha producido un error interno del servidor` | [Keycloak base/login/messages_es.properties:213][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-054 | Keycloak base/login/messages_es.properties | `invalidAccessCodeMessage` | `Código de acceso no válido.` | [Keycloak base/login/messages_es.properties:185][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-055 | Keycloak base/login/messages_es.properties | `invalidCodeMessage` | `Ha ocurrido un error, por favor identificate de nuevo desde tu aplicación.` | [Keycloak base/login/messages_es.properties:187][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-056 | Keycloak base/login/messages_es.properties | `invalidEmailMessage` | `Email no válido.` | [Keycloak base/login/messages_es.properties:116][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-057 | Keycloak base/login/messages_es.properties | `invalidParameterMessage` | `Parámetro no válido: {0}` | [Keycloak base/login/messages_es.properties:202][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-058 | Keycloak base/login/messages_es.properties | `invalidPasswordConfirmMessage` | `La confirmación de contraseña no coincide.` | [Keycloak base/login/messages_es.properties:134][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. Actual policy decides reachability; definitions do not prove policy enabled. |
| KI-059 | Keycloak base/login/messages_es.properties | `invalidPasswordExistingMessage` | `La contraseña actual no es correcta.` | [Keycloak base/login/messages_es.properties:132][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. Actual policy decides reachability; definitions do not prove policy enabled. |
| KI-060 | Keycloak base/login/messages_es.properties | `invalidPasswordHistoryMessage` | `Contraseña incorrecta: no puede ser igual a ninguna de las últimas {0} contraseñas.` | [Keycloak base/login/messages_es.properties:160][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. Actual policy decides reachability; definitions do not prove policy enabled. |
| KI-061 | Keycloak base/login/messages_es.properties | `invalidPasswordMessage` | `Contraseña inválida.` | [Keycloak base/login/messages_es.properties:309][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. Actual policy decides reachability; definitions do not prove policy enabled. |
| KI-062 | Keycloak base/login/messages_es.properties | `invalidPasswordMinDigitsMessage` | `Contraseña incorrecta: debe contener al menos {0} caracteres numéricos.` | [Keycloak base/login/messages_es.properties:154][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. Actual policy decides reachability; definitions do not prove policy enabled. |
| KI-063 | Keycloak base/login/messages_es.properties | `invalidPasswordMinLowerCaseCharsMessage` | `Contraseña incorrecta: debe contener al menos {0} letras minúsculas.` | [Keycloak base/login/messages_es.properties:155][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. Actual policy decides reachability; definitions do not prove policy enabled. |
| KI-064 | Keycloak base/login/messages_es.properties | `invalidPasswordMinSpecialCharsMessage` | `Contraseña incorrecta: debe contener al menos {0} caracteres especiales.` | [Keycloak base/login/messages_es.properties:157][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. Actual policy decides reachability; definitions do not prove policy enabled. |
| KI-065 | Keycloak base/login/messages_es.properties | `invalidPasswordMinUpperCaseCharsMessage` | `Contraseña incorrecta: debe contener al menos {0} letras mayúsculas.` | [Keycloak base/login/messages_es.properties:156][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. Actual policy decides reachability; definitions do not prove policy enabled. |
| KI-066 | Keycloak base/login/messages_es.properties | `invalidPasswordNotContainsUsernameMessage` | `Contraseña no válida: no puede contener el nombre de usuario.` | [Keycloak base/login/messages_es.properties:463][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. Shadowed by aligned realm localization. |
| KI-067 | Keycloak base/login/messages_es.properties | `invalidPasswordNotUsernameMessage` | `Contraseña incorrecta: no puede ser igual al nombre de usuario.` | [Keycloak base/login/messages_es.properties:158][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. Actual policy decides reachability; definitions do not prove policy enabled. |
| KI-068 | Keycloak base/login/messages_es.properties | `invalidPasswordRegexPatternMessage` | `Contraseña incorrecta: no cumple la expresión regular.` | [Keycloak base/login/messages_es.properties:159][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. Actual policy decides reachability; definitions do not prove policy enabled. |
| KI-069 | Keycloak base/login/messages_es.properties | `invalidRedirectUriMessage` | `La URI de redirección no es correcta` | [Keycloak base/login/messages_es.properties:169][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-070 | Keycloak base/login/messages_es.properties | `invalidRequestMessage` | `Petición incorrecta` | [Keycloak base/login/messages_es.properties:164][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-071 | Keycloak base/login/messages_es.properties | `invalidUserMessage` | `Usuario o contraseña incorrectos.` | [Keycloak base/login/messages_es.properties:115][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. Shadowed by aligned realm localization. |
| KI-072 | Keycloak base/login/messages_es.properties | `invalidUsernameMessage` | `Nombre de usuario no válido.` | [Keycloak base/login/messages_es.properties:307][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-073 | Keycloak base/login/messages_es.properties | `invalidUsernameOrEmailMessage` | `Nombre de usuario o correo electrónico no válidos.` | [Keycloak base/login/messages_es.properties:308][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-074 | Keycloak base/login/messages_es.properties | `lastName` | `Apellido` | [Keycloak base/login/messages_es.properties:48][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. Local-import profile variant; aligned profile uses email only. |
| KI-075 | Keycloak base/login/messages_es.properties | `logoutConfirmHeader` | `¿Quiere cerrar sesión?` | [Keycloak base/login/messages_es.properties:224][src71] | Spanish parent; logout-confirm.ftl. Source-verified, not every message triggered. |
| KI-076 | Keycloak base/login/messages_es.properties | `logoutConfirmTitle` | `Cerrando sesión` | [Keycloak base/login/messages_es.properties:223][src71] | Spanish parent; logout-confirm.ftl. Source-verified, not every message triggered. |
| KI-077 | Keycloak base/login/messages_es.properties | `logoutOtherSessions` | `Cerrar sesión en otros dispositivos` | [Keycloak base/login/messages_es.properties:266][src71] | Spanish parent; password-commons.ftl. Source-verified, not every message triggered. |
| KI-078 | Keycloak base/login/messages_es.properties | `missingParameterMessage` | `Parámetros que faltan: {0}` | [Keycloak base/login/messages_es.properties:200][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-079 | Keycloak base/login/messages_es.properties | `noAccessMessage` | `Sin acceso` | [Keycloak base/login/messages_es.properties:152][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-080 | Keycloak base/login/messages_es.properties | `notMatchPasswordMessage` | `Las contraseñas no coinciden.` | [Keycloak base/login/messages_es.properties:131][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-081 | Keycloak base/login/messages_es.properties | `pageExpiredMsg1` | `Para reiniciar el proceso de inicio de sesión` | [Keycloak base/login/messages_es.properties:301][src71] | Spanish parent; login-page-expired.ftl. Source-verified, not every message triggered. |
| KI-082 | Keycloak base/login/messages_es.properties | `pageExpiredMsg2` | `Para continuar el proceso de inicio de sesión` | [Keycloak base/login/messages_es.properties:302][src71] | Spanish parent; login-page-expired.ftl. Source-verified, not every message triggered. |
| KI-083 | Keycloak base/login/messages_es.properties | `pageExpiredTitle` | `La página ha expirado` | [Keycloak base/login/messages_es.properties:300][src71] | Spanish parent; login-page-expired.ftl. Source-verified, not every message triggered. |
| KI-084 | Keycloak base/login/messages_es.properties | `password` | `Contraseña` | [Keycloak base/login/messages_es.properties:51][src71] | Spanish parent; login.ftl, register.ftl. Source-verified, not every message triggered. |
| KI-085 | Keycloak base/login/messages_es.properties | `passwordNew` | `Nueva contraseña` | [Keycloak base/login/messages_es.properties:53][src71] | Spanish parent; login-update-password.ftl. Source-verified, not every message triggered. |
| KI-086 | Keycloak base/login/messages_es.properties | `proceedWithAction` | `» Haz click aquí para proceder` | [Keycloak base/login/messages_es.properties:204][src71] | Spanish parent; info.ftl. Source-verified, not every message triggered. |
| KI-087 | Keycloak base/login/messages_es.properties | `registrationNotAllowedMessage` | `El registro no está permitido` | [Keycloak base/login/messages_es.properties:172][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-088 | Keycloak base/login/messages_es.properties | `rememberMe` | `Seguir conectado` | [Keycloak base/login/messages_es.properties:55][src71] | Spanish parent; login.ftl. Source-verified, not every message triggered. Conditional native feature; current minimal realm does not establish a reachable usage. |
| KI-089 | Keycloak base/login/messages_es.properties | `requiredAction.TERMS_AND_CONDITIONS` | `Términos y condiciones` | [Keycloak base/login/messages_es.properties:206][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-090 | Keycloak base/login/messages_es.properties | `requiredAction.UPDATE_PASSWORD` | `Actualización de contraseña` | [Keycloak base/login/messages_es.properties:207][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-091 | Keycloak base/login/messages_es.properties | `requiredAction.UPDATE_PROFILE` | `Actualización del perfil` | [Keycloak base/login/messages_es.properties:208][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-092 | Keycloak base/login/messages_es.properties | `requiredAction.VERIFY_EMAIL` | `Verificar correo electrónico` | [Keycloak base/login/messages_es.properties:209][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-093 | Keycloak base/login/messages_es.properties | `requiredFields` | `Campos requeridos` | [Keycloak base/login/messages_es.properties:306][src71] | Spanish parent; template.ftl. Source-verified, not every message triggered. |
| KI-094 | Keycloak base/login/messages_es.properties | `restartLoginTooltip` | `Reiniciar el inicio de sesión` | [Keycloak base/login/messages_es.properties:274][src71] | Spanish parent; template.ftl. Source-verified, not every message triggered. |
| KI-095 | Keycloak base/login/messages_es.properties | `sessionNotActiveMessage` | `La sesión no está activa.` | [Keycloak base/login/messages_es.properties:186][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-096 | Keycloak base/login/messages_es.properties | `showPassword` | `Mostrar contraseña` | [Keycloak base/login/messages_es.properties:259][src71] | Spanish parent; field.ftl. Source-verified, not every message triggered. |
| KI-097 | Keycloak base/login/messages_es.properties | `termsText` | `<p>Términos y condiciones a definir</p>` | [Keycloak base/login/messages_es.properties:38][src71] | Spanish parent; register-commons.ftl, terms.ftl. Source-verified, not every message triggered. Shadowed by aligned realm localization. |
| KI-098 | Keycloak base/login/messages_es.properties | `termsTitle` | `Términos y Condiciones` | [Keycloak base/login/messages_es.properties:37][src71] | Spanish parent; register-commons.ftl, terms.ftl. Source-verified, not every message triggered. Shadowed by aligned realm localization. |
| KI-099 | Keycloak base/login/messages_es.properties | `traceIdSupportMessage` | `Si se pone en contacto con el servicio de soporte, facilite el siguiente identificador de seguimiento: {0}` | [Keycloak base/login/messages_es.properties:522][src71] | Spanish parent; error.ftl. Source-verified, not every message triggered. |
| KI-100 | Keycloak base/login/messages_es.properties | `username` | `Usuario` | [Keycloak base/login/messages_es.properties:43][src71] | Spanish parent; login.ftl, login-reset-password.ftl, template.ftl. Source-verified, not every message triggered. Local-import profile variant; aligned profile uses email only. |
| KI-101 | Keycloak base/login/messages_es.properties | `usernameExistsMessage` | `El nombre de usuario ya existe.` | [Keycloak base/login/messages_es.properties:136][src71] | Spanish parent; server-side validation/required-action lookup. Source-verified, not every message triggered. |
| KI-102 | Keycloak base/login/messages_es.properties | `usernameOrEmail` | `Usuario o email` | [Keycloak base/login/messages_es.properties:44][src71] | Spanish parent; login.ftl, login-reset-password.ftl, template.ftl. Source-verified, not every message triggered. Conditional native feature; current minimal realm does not establish a reachable usage. |

### Transactional emails — separate owner: #323

[Issue #323](https://github.com/rubhern/videogame-platform/issues/323) owns received-mail copy, HTML/plain-text parity, sender/client presentation and approval. This phase records the existing templates and configuration only; it does not duplicate that issue's delivery/capture QA or claim that a message was sent. [#290](https://github.com/rubhern/videogame-platform/issues/290) remains the browser-theme boundary. Password reset is enabled in local/aligned policies; verification is disabled in the legacy local import and required in aligned identity. Native execute-actions mail is available to an authorized identity operator, but is not a React account feature. No game/news/rating email notification system was found.

For reset/verification: `{0}` is the generated action URL, `{1}` the legacy expiration argument, `{2}` the realm display name (`Gameómetro`), `{3}` the formatted expiry. The source contains literal `\n` line breaks for plain text; HTML tags below are template structure, not words shown as tags. Representative safe expansion: `tu cuenta de Gameómetro`, expiry `5 minutos`, link `[generated single-use action URL omitted]`; this is a synthetic example, not a captured email.

| ID | Screen/component | State or trigger | Exact current text | Source (file) | Notes |
| --- | --- | --- | --- | --- | --- |
| EM-001 | messages_es | `passwordResetSubject` | `Crea una nueva contraseña de Gameómetro` | [messages_es.properties:1][src72] | Custom email subject/body/HTML definition; no message delivery or client rendering verified in this audit. |
| EM-002 | messages_es | `passwordResetBody` | `Alguien ha pedido crear una nueva contraseña para tu cuenta de {2}. Si has sido tú, abre este enlace para elegirla:\n\n{0}\n\nEl enlace caduca en {3} y solo puede usarse una vez.\n\nSi no lo has pedido tú, ignora este mensaje: tu contraseña no cambiará.` | [messages_es.properties:2][src72] | Custom email subject/body/HTML definition; no message delivery or client rendering verified in this audit. |
| EM-003 | messages_es | `passwordResetBodyHtml` | `<p>Alguien ha pedido crear una nueva contraseña para tu cuenta de {2}. Si has sido tú, abre este enlace para elegirla:</p><p><a href="{0}">Crear una nueva contraseña</a></p><p>El enlace caduca en {3} y solo puede usarse una vez.</p><p>Si no lo has pedido tú, ignora este mensaje: tu contraseña no cambiará.</p>` | [messages_es.properties:3][src72] | Custom email subject/body/HTML definition; no message delivery or client rendering verified in this audit. |
| EM-004 | messages_es | `emailVerificationSubject` | `Verifica tu email de Gameómetro` | [messages_es.properties:4][src72] | Custom email subject/body/HTML definition; no message delivery or client rendering verified in this audit. |
| EM-005 | messages_es | `emailVerificationBody` | `Alguien ha creado una cuenta de {2} con este email. Si has sido tú, abre este enlace para verificarlo:\n\n{0}\n\nEl enlace caduca en {3}.\n\nSi no has sido tú, ignora este mensaje: sin verificar el email, nadie podrá entrar en esa cuenta.` | [messages_es.properties:5][src72] | Custom email subject/body/HTML definition; no message delivery or client rendering verified in this audit. |
| EM-006 | messages_es | `emailVerificationBodyHtml` | `<p>Alguien ha creado una cuenta de {2} con este email. Si has sido tú, abre este enlace para verificarlo:</p><p><a href="{0}">Verificar mi email</a></p><p>El enlace caduca en {3}.</p><p>Si no has sido tú, ignora este mensaje: sin verificar el email, nadie podrá entrar en esa cuenta.</p>` | [messages_es.properties:6][src72] | Custom email subject/body/HTML definition; no message delivery or client rendering verified in this audit. |

| ID | Screen/component | State or trigger | Exact current text | Source (file) | Notes |
| --- | --- | --- | --- | --- | --- |
| EN-001 | Keycloak base/email/messages_es.properties | `executeActionsSubject` | `Actualiza tu cuenta` | [Keycloak base/email/messages_es.properties:7][src73] | Inherited native action/expiry template; no sent example verified. Execute-actions requires an identity-operator trigger. |
| EN-002 | Keycloak base/email/messages_es.properties | `executeActionsBody` | `Su administrador ha solicitado que actualice su cuenta {2} realizando la(s) siguiente(s) acción(es): {3}. Haga clic en el enlace de abajo para comenzar este proceso.\n\n{0}\n\nEste enlace expirará en {4} minutos.\n\nSi no está al tanto que el administrador haya solicitado esto, simplemente ignore este mensaje y no se realizará ningún cambio.` | [Keycloak base/email/messages_es.properties:8][src73] | Inherited native action/expiry template; no sent example verified. Execute-actions requires an identity-operator trigger. |
| EN-003 | Keycloak base/email/messages_es.properties | `executeActionsBodyHtml` | `<p>Su administrador ha solicitado que actualice su cuenta {2} realizando la(s) siguiente(s) acción(es): {3}. Haga clic en el enlace de abajo para comenzar este proceso.</p><p><a href="{0}">Enlace para actualizar la cuenta</a></p><p>Este enlace caducará en {4}.</p><p>Si no sabe si su administrador ha solicitado esto, simplemente ignore este mensaje y no se realizará ningún cambio.</p>` | [Keycloak base/email/messages_es.properties:9][src73] | Inherited native action/expiry template; no sent example verified. Execute-actions requires an identity-operator trigger. |
| EN-004 | Keycloak base/email/messages_es.properties | `requiredAction.TERMS_AND_CONDITIONS` | `Términos y condiciones` | [Keycloak base/email/messages_es.properties:32][src73] | Inherited native action/expiry template; no sent example verified. Execute-actions requires an identity-operator trigger. |
| EN-005 | Keycloak base/email/messages_es.properties | `requiredAction.UPDATE_PASSWORD` | `Actualiza la contraseña` | [Keycloak base/email/messages_es.properties:33][src73] | Inherited native action/expiry template; no sent example verified. Execute-actions requires an identity-operator trigger. |
| EN-006 | Keycloak base/email/messages_es.properties | `requiredAction.UPDATE_PROFILE` | `Actualización del perfil` | [Keycloak base/email/messages_es.properties:34][src73] | Inherited native action/expiry template; no sent example verified. Execute-actions requires an identity-operator trigger. |
| EN-007 | Keycloak base/email/messages_es.properties | `requiredAction.VERIFY_EMAIL` | `Verificar correo electrónico` | [Keycloak base/email/messages_es.properties:35][src73] | Inherited native action/expiry template; no sent example verified. Execute-actions requires an identity-operator trigger. |
| EN-008 | Keycloak base/email/messages_es.properties | `linkExpirationFormatter.timePeriodUnit.seconds` | `{0,choice,0#segundos\|1#segundo\|1<segundos}` | [Keycloak base/email/messages_es.properties:42][src73] | Inherited native action/expiry template; no sent example verified. Execute-actions requires an identity-operator trigger. |
| EN-009 | Keycloak base/email/messages_es.properties | `linkExpirationFormatter.timePeriodUnit.minutes` | `{0,choice,0#minutos\|1#minuto\|1<minutos}` | [Keycloak base/email/messages_es.properties:43][src73] | Inherited native action/expiry template; no sent example verified. Execute-actions requires an identity-operator trigger. |
| EN-010 | Keycloak base/email/messages_es.properties | `linkExpirationFormatter.timePeriodUnit.hours` | `{0,choice,0#horas\|1#hora\|1<horas}` | [Keycloak base/email/messages_es.properties:44][src73] | Inherited native action/expiry template; no sent example verified. Execute-actions requires an identity-operator trigger. |
| EN-011 | Keycloak base/email/messages_es.properties | `linkExpirationFormatter.timePeriodUnit.days` | `{0,choice,0#días\|1#día\|1<días}` | [Keycloak base/email/messages_es.properties:45][src73] | Inherited native action/expiry template; no sent example verified. Execute-actions requires an identity-operator trigger. |
| EN-012 | videogame-platform-realm | `Realm display name substituted into emails` | `Gameómetro` | [videogame-platform-realm.json:3][src74] | Configuration definition, not a runtime sender capture. |
| EN-013 | videogame-platform-realm | `SMTP from` | `no-reply@gameometro.invalid` | [videogame-platform-realm.json:23][src74] | Local import uses synthetic sender; production uses configuration substitution. No actual address or credential resolved. |
| EN-014 | videogame-platform-realm | `SMTP fromDisplayName` | `Gameómetro` | [videogame-platform-realm.json:24][src74] | Local import uses synthetic sender; production uses configuration substitution. No actual address or credential resolved. |
| EN-015 | gameometro-realm | `Realm display name substituted into emails` | `Gameómetro` | [gameometro-realm.json:3][src69] | Configuration definition, not a runtime sender capture. |
| EN-016 | gameometro-realm | `SMTP from` | `${KEYCLOAK_SMTP_FROM}` | [gameometro-realm.json:55][src69] | Local import uses synthetic sender; production uses configuration substitution. No actual address or credential resolved. |
| EN-017 | gameometro-realm | `SMTP fromDisplayName` | `Gameómetro` | [gameometro-realm.json:56][src69] | Local import uses synthetic sender; production uses configuration substitution. No actual address or credential resolved. |

### API-to-UI mapping index — internal values are not Spanish copy

The API contract and transport modules retain machine-facing codes. The inventory records their displayed messages separately; do not translate codes, Java exceptions or HTTP fields. Backend Problem `title`/`detail` strings and frontend exceptions such as `The releases request failed.` are not forwarded verbatim by the reviewed React presentations.

| Read/action | Internal trigger | Displayed branch / inventory usage |
| --- | --- | --- |
| Recent/upcoming read | `CATALOGUE_NOT_READY` | Catalogue-preparation notice and retry (RL) |
| Recent/upcoming read | `PLATFORM_NOT_SUPPORTED` | `La plataforma solicitada no existe en el catálogo local.` plus unsupported-filter recovery |
| Recent/upcoming read | `REGION_NOT_SUPPORTED` | `La región solicitada no existe en el catálogo local.` plus unsupported-filter recovery |
| Recent/upcoming read | `FILTER_INVALID` | `Los filtros solicitados no son válidos.` plus unsupported-filter recovery |
| Featured explicit period | `FILTER_INVALID` | Canonical navigation back to current period, no new error text |
| Featured read | `CATALOGUE_NOT_READY`; other failures | Preparation notice; otherwise generic featured-load error (FT/RL) |
| Search read | `SEARCH_QUERY_INVALID`; `CATALOGUE_NOT_READY`; other failures | Invalid-query notice; preparation notice; otherwise generic search-load error (SE) |
| Game read | `GAME_NOT_FOUND`; `CATALOGUE_NOT_READY`; other failures | Missing game; preparation; generic game-load failure (GD) |
| Personal rating read | HTTP 404 | No existing rating, not an error message; current trigger offers Puntuar |
| Personal command | HTTP 401 → `authentication` | RT-010; MY-001 |
| Personal command | HTTP 403 → `csrf` | RT-011; MY-002; HTTP status determines the current UI kind |
| Personal command | HTTP 412 or 404 → `conflict` | RT-012; MY-003; reread current state |
| Personal command | HTTP 422 + `RATING_NOT_ELIGIBLE` / `RELEASE_DATA_REVIEW_REQUIRED` → `ineligible` | RT-014; MY-005 |
| Personal command | Other HTTP 422 → `validation` | RT-013; MY-004 |
| Personal command | HTTP 429 → `rate-limited` | RT-015; MY-006 |
| Personal command | Other response failure → `unavailable`; transport exception → `ambiguous` | RT-016/017; MY-007/008; these meanings must stay distinct |
| My ratings collection | `AUTHENTICATION_REQUIRED` | Sign-in notice; other failures give generic collection-load error |
| Administration read | `AUTHENTICATION_REQUIRED` / `ACCESS_DENIED` (also fallback from HTTP 401/403) | Session/role notices; other failures give load notice |
| Synchronization request | `SYNCHRONIZATION_RANGE_INVALID` + violation pointer `/from` or `/to` | Date-field error; does not display raw pointer or code |
| Synchronization request | `SYNCHRONIZATION_ALREADY_RUNNING`; `SYNCHRONIZATION_DISABLED` | “No se ha iniciado” + already-running/unavailable notice |
| Synchronization request/control | `CSRF_VALIDATION_FAILED`; `AUTHENTICATION_REQUIRED`; `ACCESS_DENIED` | Specific verification/session/role wording in AD; session is reread where implemented |
| Stop/abandon command | `SYNCHRONIZATION_RUN_FINISHED`; `SYNCHRONIZATION_RUN_NOT_FOUND`; `SYNCHRONIZATION_RUN_NOT_STALE` | Finished/missing/still-live notice; reread status |
| Operator retained run | `SYNCHRONIZATION_STOPPED`; `SYNCHRONIZATION_INTERRUPTED`; `SYNCHRONIZATION_COMPLETED_WITH_FAILURES`; `SYNCHRONIZATION_ABANDONED_BY_OPERATOR` | Operational label/explanation plus the unchanged code under Código; other result labels depend on `run.result` |
| BFF auth return | `rating-intent=expired|cancelled|invalid` | RT-007/008/009; these URL outcome markers are not error sentences |
| Ordinary non-rating auth failure | Authentication failure handler returns to safe target | No dedicated new React message found; do not invent one in the inventory |

Mapping sources: `frontend/src/pages/*-page.tsx`, `frontend/src/features/ratings/personal-rating-api.ts`, `frontend/src/features/catalogue-administration/administration-api.ts`, synchronization request/control presentation, and `backend/src/main/java/com/videogameplatform/identity/configuration/RatingIntentAuthenticationFailureHandler.java` at the audited revision. Source branch keys also appear beside individual inventory rows.

## Coverage matrix

Coverage codes: **S** = source/template inspected; **R** = rendered with synthetic data; **G** = runtime gap. An R entry validates the sampled journey, not every source row or real BFF/identity integration. All React routes retain the title `Gameómetro`.

| Surface / route | States and variants reviewed | Evidence | Remaining runtime gap |
| --- | --- | --- | --- |
| Global shell, every React route | Brand, skip link, release navigation, footer, route transition/loading | S + R, phone/desktop | Initial navigation/loading is transient; not every transition was separately captured |
| Anonymous account control | Iniciar sesión / Crear cuenta, phone popup, larger-screen inline entry | S + R; existing header tests at 620/667/834px | Real BFF sign-in navigation not exercised end to end |
| Authenticated/operator account | Mi cuenta, Mis puntuaciones, role-gated administration, logout pending/error | S + R; operator screens and menu states rendered | Four header browser cases fail at final `/auth/logout` navigation against Vite; logout completion is unverified here |
| Header search | Desktop field, phone dialog, close control, length validation, keyboard hint | S + R; suggestions tests at 390/1320px | Native IME/browser-specific behavior not investigated |
| Suggestions | Loading, result/alias/platform/year, empty/error, count announcement, Enter action | S; R for nonempty keyboard navigation | Empty/error announcements source-verified; not individually captured with every width |
| `/` weekly Destacados | Current/adjacent/past/future periods, bounds, cross-month week, reduced motion | S + R; four reference widths | Does not assert every historical period or provider input |
| `/?granularity=month` | Month title/selector, hero, other cards, long titles, score sources, image fallback | S + R; 320/390/834/1320px | Two keyboard activation cases fail at 390/1320px; visual presence and copy still observed |
| Destacados exceptional | Loading, popularity unavailable, no qualifying releases, generic error; catalogue not ready | S + R for first four via existing featured browser cases | Not-ready source-verified; popularity staleness modeled with no distinct rendered caption found |
| `/?view=recent` | Window/range/scale, period/platform/region selectors, card/overflow, result summary | S + R at 390/1320px, reference-width release tests | Multi-filter, multi-page and release-overflow permutations are source-verified, not all independently rendered |
| `/?view=upcoming` | Same shared controls, exact dates, approximate-date opt-in, today marker | S + R | Every precision/empty combination not independently rendered |
| Release exceptional states | Not-ready, generic error, invalid/unsupported platform/region, no results, exhausted page, background/selection refresh | S + R for not-ready/error/invalid filter | Remaining branches verified in source; catalogue fixtures do not prove backend errors |
| `/search` | Prompt, empty/invalid query, result/no result, aliases, year/range/unknown date | S + R; search results at all four reference widths | Empty catalogue and every API error not independently injected in browser |
| `/search?q=…&page=…` | Pagination, beyond last page, loading/refreshing, unavailable/not-ready | S + R for keyboard pages/detail and zero matches | Beyond-page and loading/error state combinations source-only |
| `/games/:gameId/:slug?` | Canonical/alias headings, covers, community/IGDB/personal scores, summary, facts, release groups, extra dates | S + R, 390/1320px | Long summary, all credits/modes, all provenance/translation/review permutations source-only |
| Game error states | Loading, missing game, not-ready, generic failure, reference/retry/return | S + R for missing game | Other failure branches source-verified |
| Community / IGDB scores | Available/empty/unavailable community, absent/valid IGDB, compact counts and thermal labels | S + R through score-bearing fixtures | Not every count threshold independently captured; invalid IGDB scores are omitted rather than printed as zero |
| Inline personal rating | Keypad, eligible/ineligible reasons, busy, auth intent notices, save/delete, conflict/ambiguous/rejected errors | S + R for ineligible, conflict, rejection/ambiguity | Anonymous pick → auth return → automatic save test fails at expected `Puntuación guardada: 8/10.`; no successful integration claim |
| `/mis-puntuaciones` | Anonymous/session loading/failure, search/sort/page size, empty/no match/exhausted, read failure | S + R for anonymous and authenticated empty; populated collection through existing tests | Every query/session error and singular count not individually rendered |
| Personal collection editor | Pending/current reading, save/cancel, confirmation/delete, dates, success/errors | S + R for touch update/delete, rereads and four reference widths | All error-kind variants source-verified, not all individually triggered |
| `/administracion/catalogo` access | Session loading/error, signed-out prompt, denied role, operator | S + R for denied and authorized; local session mocks | Real Keycloak authority/role changes not verified |
| Administration `tab=ejecuciones` | Request dates/limits; validation; accepted/running/stalled/finished/missing; current/history; counters/results | S + R for request and retained/stalled/history fixtures; 320/390/834/1320px | All server rejections, history eviction and real scheduled-run concurrency source-only |
| Synchronization controls | Stop/abandon confirmations, safe buttons, pending/outcome; terminal/missing/live/CSRF/session/role errors | S + R for stalled abandonment confirmation/command via mock | Stop outcomes and every rejection inspected in source; no real job operated |
| Administration `tab=cobertura` | Decade/year/month navigation, complete/partial/never/unknown, age, failed-game notes, truncation, read/loading errors | S + R for keyboard drill-down and tables at all widths | Response truncation and all failure combinations source-only |
| Shared dates/statuses | Day/month/quarter/year/unknown; invalid defensive dates; release stage/lifecycle/review/verification | S + sampled R | Defensive malformed data is not claimed as reachable valid API output |
| Shared selectors/platforms/genres | All/one/many selection; selected option labels; platform overflow; hidden names; marks | S + sampled R | No additional fixed strings in icon/stage/needle/hero-title primitives; decorative SVGs deliberately silent |
| `*` unmatched React path | 404 heading/explanation/return | S + R at 390/1320px | Does not establish proxy/server error-page behavior |
| `/privacidad`, `/condiciones`, `/aviso-legal`, `/cookies` | Full titles/headings/paragraphs/cookie table, legal links/contact/draft notice | S + R at 390/1320px in development | Intentionally unavailable in published build; not in force or approved public notices |
| Keycloak legacy local import | Username/password, invalid credentials, name/surname registration, username recovery | S + R at 390/1320px in disposable realm | Not the managed/aligned private-dev configuration; no actual private realm read |
| Keycloak aligned identity | Email sign-in, email-only registration with adult declaration, email recovery and generic unknown-account response | S + R at 390/1320px in second disposable realm | Verification, new-password, expired/used token, lockout and logout pages inspected as templates, not fully exercised |
| Keycloak transactional emails | Reset and verification custom subject/plain/HTML; native execute-actions/expiry; configured sender | S | No mail was sent/captured; sender, MIME/client rendering, actual substitutions and delivery remain #323 evidence gaps |
| BFF navigation `/auth/start`, `/login`, `/auth/logout`, `/auth/rating-intent/*` | Delegated entry/return/logout; no React credential forms or separate body-copy screens | S; hosted forms rendered separately | Full real BFF integration not verified; navigation failures are documented above |
| Other native account capabilities | No React profile, password form, account-delete/settings screen or account-console link exists in this route tree | S | Direct external Keycloak account-console access is not established as a Gameómetro journey; no hypothetical account UI inventoried |
| Non-product surfaces | Documentation portal, OpenAPI reference, Grafana, Mailpit, actuator/admin tooling | S boundary | Tool/operator documentation is outside Gameómetro product copy; not mixed into player-screen inventory |

### Runtime record and failed checks

The audit installed the committed npm dependencies with `--ignore-scripts`, served the current frontend through Vite on loopback, and ran existing Chromium specs against scripted network responses. No application source or test was edited. Command:

```bash
PLAYWRIGHT_BASE_URL=http://127.0.0.1:5173 npm --workspace frontend run test:e2e -- \
  tests/header.spec.ts tests/search-typeahead.spec.ts tests/search-results.spec.ts \
  tests/my-ratings.spec.ts tests/inline-rating.spec.ts \
  tests/catalogue-administration.spec.ts tests/featured-design.spec.ts \
  tests/identity-rollout.spec.ts --workers=4 --reporter=json
```

Result: **59 cases, 52 passed, 7 failed, no retries, no skipped cases**. The failing assertions were:

| Cases | Observed failure | Interpretation for this audit |
| --- | --- | --- |
| Header authenticated menu, 320/390/834/1320px | After logout the browser navigates toward `/auth/logout`; the Vite-only host has no BFF completion handler and the final “Mi cuenta is absent” assertion times out | Header/menu was exercised before the failure. Real logout completion remains unverified; this does not prove a production logout defect |
| Featured layout/contrast/keyboard, 390/1320px | Expected game URL after keyboard activation; actual URL remained `/?granularity=month` | Unresolved keyboard activation discrepancy. Do not claim the full interaction/a11y suite passes or diagnose the cause from copy alone |
| Inline anonymous pick/auth return/automatic save | Expected a status region containing `Puntuación guardada: 8/10.`; found none | Auth-return success announcement not verified. Other rejection/conflict/ineligible cases passed; no implementation fix made |

A separate temporary browser script captured **48** snapshots of body text, labels, placeholders, alternative text and titles: 20 React route/state samples at each of 390/1320px and four legacy-local Keycloak states at each width. Another captured **8** aligned-identity snapshots (login, registration, recovery, generic absent-account response at both widths). These are controlled render observations, not a full end-to-end server test. Captures stayed in temporary local files; no raw HTML containing action tokens or actual user data was added to the report. Native browser-required-field bubbles depend on browser/OS locale and are not application-owned translations.

## Terminology inconsistencies against the approved #341 glossary

These are facts about the audited older branch. The approved glossary is a comparison source; none of the suggestions below authorizes changes. Some discrepancies may already be resolved on newer `main`.

| Approved rule | Exact current wording and usages | Evidence / interpretation |
| --- | --- | --- |
| `TOPómetro`; `TOPómetro de la semana` / `TOPómetro del mes` | `Destacados`; `Lanzamientos semanales`; `Lanzamientos del mes`; `Otros lanzamientos destacados`; loading/error/ARIA variants containing `destacados` | Header, featured shell/selector and featured page rows. First-game labels `Lanzamiento de la semana` / `Lanzamiento del mes` already match |
| `Hype`, without a numerical value | `Sin datos de atención`; `…la señal de atención necesaria para destacarlos.` | Unranked featured state. No Hype count is exposed; preserve that property |
| No player-visible `Catálogo` / `catálogo local` | `Catálogo de juegos`, `Buscar en el catálogo`, `Ficha del catálogo`, not-ready and empty/error explanations; `Volver al catálogo` in Keycloak | Search/header/game/discovery/Keycloak inventories. Operator administration is an explicit exception; do not remove its operational vocabulary globally |
| `Lanzamiento`, avoid `Estreno` | `El catálogo local no tiene estrenos con día confirmado dentro de esta semana.` | Weekly no-qualifying-release explanation |
| `Portada`, avoid `Carátula` | `Carátula oficial no disponible`, `Carátula:`, `Vista previa de la carátula de {title}`, fallback SVG descriptions | Shared cover, backend mapper, local preview and SVG. Import policy already generates `Portada de {title}` / `Portada no disponible de {title}`; branch is internally mixed |
| Gameómetro `Puntuación`; external `Valoración` count | DiscoveryScores uses `{n} valoración/valoraciones` for **both** community and IGDB | Game page correctly uses `Basada en {n} puntuación/puntuaciones` for community and `valoración/valoraciones` for IGDB. Personal `nota` is allowed by #341 and is not automatically an inconsistency |
| Only special stages labeled; omit full/unknown stages | `Lanzamiento completo` rendered for full release in game release rows | `Tipo no especificado` is defined but unknown stage is already suppressed. Preserve `Acceso anticipado` versus `Acceso previo` |
| Navigation wording belongs to approved glossary; structure belongs to #285 | Separate `Recientes` / `Próximos` links alongside `Destacados` | This branch predates the combined navigation described in the approved glossary. Record the difference; do not redesign navigation in #286 |
| Spanish-first plain product language | `Publisher`; `alias`; `email`; `Jugón` | `Publisher` is an English UI label; the others are tone/context questions, not explicit glossary violations. IGDB/platform/game names retain source spelling |

The five thermal labels, `Gameómetro`, `Mis puntuaciones`, `Puntuar`, `Tu puntuación`, personal/community source separation and the special release-stage names are retained correctly in the sampled surfaces. No new brand name, score, release precision or provider status is proposed.

### Other source discrepancies recorded without changing their owners

The audited design guide describes the administration opening as `Administración del catálogo` over `Sincronización y cobertura`; the actual page prints `Panel de administrador` over `Sincronización y cobertura`. It also says a stale featured ranking keeps its observed-attention caption, whereas the current view model stores the staleness/observation fields but the featured presentation does not render such a caption. These are source/documentation differences for owner triage, not authority to alter copy or expose catalogue synchronization freshness. The executable strings above are the inventory evidence.

## Copy-quality findings

Facts identify a code branch or an observed rendering; editorial judgments and proposed wording are labeled as such.

| Finding | Evidence (fact) | Assessment / limit |
| --- | --- | --- |
| CQ-01: synchronization rejection overstates certainty | Generic request failure says `No se ha iniciado nada y los datos del catálogo no han cambiado. Inténtalo de nuevo en unos momentos.`; administration transport failures also enter the generic branch | A lost response can leave the outcome unknown. Saying nothing started is stronger than the available evidence. Specific pre-execution business rejections can retain their accurate “No se ha iniciado” wording |
| CQ-02: synchronization read failure implies no concurrent changes | Status-read failure says `Los datos del catálogo no han cambiado. Inténtalo de nuevo en unos momentos.` | A read failure does not prove the catalogue stayed unchanged while jobs run. Separate failure to read from operational outcome |
| CQ-03: no-result heading can overclaim absence | `No hay lanzamientos en {periodPhrase}`; `No hay coincidencias en el catálogo local`; game missing/not-ready variants | Local catalogue coverage is partial; weekly eligibility additionally requires a confirmed day. Explanations qualify this, but headings can be read without them. #341 already supplies honest brand-based language |
| CQ-04: infrastructure language in player recovery | `publicación local válida`, `proveedor externo`, `datos locales`, `catálogo local`, `señal de atención` | Correct architectural concepts are unnecessarily prominent in player-facing notices. Proposed simplification must retain incomplete-coverage and retry meaning |
| CQ-05: unsupported-filter heading plus kicker is repetitive | `Revisa la selección` / `Filtro no admitido`; search `Consulta no válida` / `La búsqueda no es válida`; error headings plus generic failure text | Similar ideas repeated at adjacent levels. Review the whole notice rather than shortening one isolated row |
| CQ-06: singular count and announcement grammar | Collection interpolates `{totalItems} puntuaciones`; suggestions interpolate `{suggestionCount} sugerencias disponibles` | Produces `1 puntuaciones` / `1 sugerencias disponibles`. Static template fact; a dedicated singular runtime capture was not made |
| CQ-07: three support-reference labels | `Referencia para soporte:` on most failures, `Referencia:` in personal collection editor, parent Keycloak fallback may use a different support sentence | Standardization candidate. A reference is useful, but the product should not imply a support service/channel beyond its approved contact policy |
| CQ-08: aliases described in three registers | Search prompt `título alternativo aprobado`, personal search `alias`, suggestion `También «{alias}»`, detail `También conocido como` | “Aprobado” exposes curation/process language. `Alias` may be less clear than a contextual “otro título”; #341 does not authorize a new domain name |
| CQ-09: ambiguous path absence sounds like a roadmap | `Esta ruta todavía no forma parte de la aplicación.` | “Todavía” suggests a planned feature; an invalid/old link does not establish one. This is an editorial problem, not a proposal to create a route |
| CQ-10: count compaction changes meaning/notation | Featured secondary counts use `+floor(n/1000)K`; other compact views use Spanish Intl notation and full counts in hidden text | `+1K` can mean “more than one thousand”, yet 1,000 itself qualifies. Clarify approximate notation; retain exact accessible count and provenance |
| CQ-11: different rating action expectations | Game-page `Selecciona una nota` saves on choice and closes; collection editor uses `Nueva nota`, `Cancelar`, `Guardar nota` and delete confirmation | Both behaviors are implemented and should be preserved. Whether inline wording needs a consequence hint is an owner decision; do not add a new confirmation automatically |
| CQ-12: operator instructions are long but contain real safeguards | Stop/abandon confirmation paragraphs explain committed data, coverage, noninstant stop, scheduler and fencing | Candidate for sentence-level editing, not deletion. These distinctions prevent destructive misunderstanding and must survive any shortening |
| CQ-13: current copy omits some feedback entirely | Session failure hides the global account entry; ordinary non-rating auth failure redirects to return target without a message; stale popularity state is stored but not visibly explained | Source facts, not missing strings invented for the inventory. Decide whether feedback is necessary before adding it. Catalogue freshness must remain internal under #267 |
| CQ-14: Keycloak tone varies by message owner | `Bienvenido, Jugón`, `Empieza tu historia.`, custom `Indica…` versus inherited `Por favor indique…`, `contacte con el administrador`, `Lo sentimos...` | Parent/theme/realm mix causes variable register. Coordinate a bounded review through #290/#323; no blanket override or inherited-template rewrite here |

## Accessibility concerns

| Concern | Evidence | Impact / proposal requiring review |
| --- | --- | --- |
| AC-01: identical browser titles across routes | `frontend/index.html` title is `Gameómetro`; no route-specific title assignment found; captured React samples all retained it | Tabs, history and screen-reader navigation do not identify the page. Candidate titles can append the route/game name, preserving brand and personal-data rules |
| AC-02: repeated generic control names | Multiple discovery counts have only `7,5 de 10` as their reading; repeated `Reintentar` relies on notice context | Context is supplied by nearby section/list headings; no claim that the controls are unlabeled. Reassess source/game context in screen-reader lists if users find it ambiguous |
| AC-03: source vocabulary announced incorrectly for community | Discovery exact-count hidden text/tooltip uses `valoración/valoraciones` for Gameómetro | The mismatch reaches assistive technology even if compact sighted counts are just numerals. Fixing only visible copy would leave it behind |
| AC-04: different live-region severity for not-ready | Release notice uses `role=status`; search and game notices use `role=alert`; featured not-ready uses a separate polite live status | A normal not-ready condition is announced with different urgency. Assess consistency without removing recovery or duplicating announcements |
| AC-05: mobile controls have good base names but hide visible words | Phone icons use `Buscar juegos`, `Acceder a tu cuenta`, `Mi cuenta`; dialog close `Cerrar búsqueda` | Preserve these labels during naming edits. The 320px header reflows navigation; text is not a reason to remove accessible context |
| AC-06: ambiguous keypad consequence | Group label `Nota del 1 al 10` does not itself explain immediate-save versus pending selection | Existing dialogs give context but the game-page prompt could state the consequence. This is a proposal; do not change keyboard mechanics or confirmation policy |
| AC-07: verification fragments depend on inherited layout | Custom `¿No te ha llegado?` / `para enviarlo de nuevo.` and inherited `Haz clic aquí` coexist with newer send/resend controls | Older conditional branch may form a vague link phrase. Runtime verification of those particular branches remains with hosted-screen review |
| AC-08: unresolved keyboard evidence | Two featured activation assertions failed; auth-return success announcement assertion failed | These are validation limitations, not proof of cause. Retain them for focused investigation before claiming full journey accessibility |
| AC-09: native validation/localization | Keycloak fields and native date inputs may use browser-generated messages, with English fallback for some parent keys | Source Spanish does not prove every native bubble/assistive label is Spanish on every platform. Parent fallback definitions are marked in KI rows |
| AC-10: decorative versus informative images | Featured art carries data-derived alt; secondary title logo and suggestion covers use `alt=""`; inline brand SVG is aria-hidden and header link names it | These are deliberate implementations, not automatically missing labels. Do not add duplicate spoken titles or call all imagery a cover |

## Prioritized improvement candidates — proposals for owner review

Priority describes the proposed phase-2 order, not permission to implement. Reconcile newer #341 changes first. Alternatives below preserve data, provenance, release precision, operational distinctions and API codes; they are examples for approval.

| Priority / candidate | Exact current wording | Issue identified | Suggested alternative | Rationale / boundary |
| --- | --- | --- | --- | --- |
| P1-01 | `No se ha iniciado nada y los datos del catálogo no han cambiado. Inténtalo de nuevo en unos momentos.` | Unknown transport outcome presented as definite nonexecution | `No se pudo confirmar si la sincronización ha empezado. Consulta el estado antes de volver a solicitarla.` | Avoid a misleading repeat action; keep certain precondition-rejection messages distinct |
| P1-02 | `Los datos del catálogo no han cambiado. Inténtalo de nuevo en unos momentos.` | Read failure asserts unchanged data | `No se pudo consultar el estado actual. Vuelve a intentarlo en unos momentos.` | A failed status read does not stop or roll back jobs |
| P1-03 | `{exactCount} valoración / {exactCount} valoraciones` for community discovery scores | Violates approved source-specific rating vocabulary | `{exactCount} puntuación / {exactCount} puntuaciones` for Gameómetro; keep `valoración/valoraciones` for IGDB | Apply to visible tooltip and hidden count; never merge sources |
| P1-04 | `No hay lanzamientos en {periodPhrase}` | Absolute heading for a bounded local selection | `Gameómetro no tiene lanzamientos para {periodPhrase}` | Matches approved brand/coverage framing; week explanation must still state confirmed-day condition |
| P2-01 | `Destacados`; `Lanzamientos semanales`; `Lanzamientos del mes`; `Sin datos de atención` | Older branch predates approved naming | `TOPómetro`; `TOPómetro de la semana`; `TOPómetro del mes`; `Sin datos de Hype` | Already glossary-approved, but reconcile delivered #341 work instead of duplicating it |
| P2-02 | `El catálogo todavía no está disponible`; `Aún no hay una publicación local válida del catálogo. No se consulta ningún proveedor externo para completarla.` | Player recovery explains implementation mechanics | `Los juegos todavía no están disponibles`; `No podemos mostrar los juegos en este momento. Vuelve a intentarlo más tarde.` | Owner must approve expected-unavailability wording; retain retry and avoid implying live provider fetch |
| P2-03 | `Escribe un título o un título alternativo aprobado en el buscador de la cabecera.` | Curation qualifier and instructions are verbose | `Busca un juego por su título en el buscador de arriba.` | Plain directional guidance; matching approved aliases remains supported without claiming arbitrary alias coverage |
| P2-04 | `Esta ruta todavía no forma parte de la aplicación.` | Implies planned future route | `No encontramos esta página. El enlace puede ser incorrecto o haber cambiado.` | Neutral unknown/old-link explanation; preserve return action |
| P2-05 | `{totalItems} puntuaciones · Página {number} de …`; `{suggestionCount} sugerencias disponibles. Usa las flechas para recorrerlas.` | Singular grammar | `1 puntuación · Página …`; `1 sugerencia disponible. Usa las flechas para seleccionarla.`; retain correct plurals | Keep exact totals and screen-reader instructions; no pagination redesign |
| P2-06 | `Carátula oficial no disponible`; `Carátula:` | Disagrees with approved Portada term | `Portada oficial no disponible`; `Portada:` | Cover only; do not rename artwork/screenshot/logo. Include backend alt and fallback SVG, subject to #341 reconciliation |
| P2-07 | Browser title `Gameómetro` everywhere | Route cannot be distinguished in tabs/history | `TOPómetro · Gameómetro`, `Mis puntuaciones · Gameómetro`, `{gameTitle} · Gameómetro` | Owner approves title pattern; avoid query/email/personal-rating values in metadata |
| P3-01 | `Publisher` | English fact label | `Distribuidor` (alternative `Editora`, subject to intended business role) | Needs terminology decision: these companies are the provider's publisher role, not a retailer or storefront |
| P3-02 | `Referencia:` versus `Referencia para soporte:` | Inconsistent diagnostic label | `Referencia del error: {reference}` | Owner approves one label and practical contact path; stable internal codes stay untouched |
| P3-03 | `+{floor(count / 1000)}K` | Ambiguous threshold/locale shorthand | Use the existing Spanish compact-number formatting, with the exact count in tooltip/accessible text | Requires agreement about approximation style; no change to source count or scoring |
| P3-04 | `Selecciona una nota` (inline immediate save) | Saving consequence is implicit | `Elige una nota para guardarla` | Evaluate in context; collection editor still uses pending value and explicit Guardar |
| P3-05 | `Bienvenido, Jugón` | Gendered/familiar brand address not in glossary | `Te damos la bienvenida` (alternative retain current expression) | Tone decision routed to #290; no automatic rewrite |

## Explicit owner decisions required

1. **Target revision for phase 2:** this older branch or a current branch containing #341? The approved terms are already decided, but their newer implementation must be reconciled before selecting remaining #286 work. No automatic branch update was made.
2. **Player notices:** approve the exact normal-unavailability, empty-search and 404 wording, including how to communicate partial coverage without infrastructure terminology or a false completeness claim.
3. **Operational certainty:** approve revising generic synchronization failure copy to acknowledge an unknown outcome; retain precise distinctions between accepted, running, stopped, interrupted, failed, abandoned and covered. Any behavior change beyond copy needs its own scoped work.
4. **Rating action guidance:** decide whether immediate inline saving needs a consequence hint; do not add a second confirmation or change the collection editor behavior under an editorial task.
5. **Count notation and diagnostic labels:** approve compact count style, singular forms and one support-reference pattern. No invented support availability or contact commitment.
6. **Publisher terminology:** choose a Spanish label that preserves the external company-role meaning, or retain the existing source term deliberately.
7. **Hosted identity tone:** decide the address/register for `Jugón`, welcome/recovery text and inherited formal messages through #290. #323 still owns actual sent-mail approval, expiry/action-link clarity, signature and sender review; this inventory alone is not approval.
8. **Titles and announcements:** approve route-title and announcement/severity patterns while retaining correct accessible names on mobile, accurate score provenance and silent decorative primitives.
9. **Legal and release gates:** no wording in the draft legal inventory becomes current by appearing in this report. Legal publication and identity/privacy operating assertions need the existing separate owner approvals; #286 does not pass a production gate.

## Validation, version impact and handoff

This is frozen research evidence, indexed by the research register and documentation portal. It changes no executable artifact or release content; **no Semantic Versioning bump** is warranted under the delivery lifecycle. The report records recommendations for review only. The issue stays open for phase 2; no commit, push, merge or deployment is authorized.

Validation completed: `bash scripts/validate-docs.sh` passed; `git diff --check` passed; `npm --prefix documentation run build` built and validated 93 HTML pages, internal links, anchors, assets and the local search index. Focused Chromium checks in the production preview confirmed the report appears under Research & history, opens from that sidebar entry, is returned by local search, and has no page-level horizontal overflow at 1320px and 390px. The report uses the same canonical Markdown source; no editable portal copy was added. Browser product evidence above is intentionally reported as 52 passes / 7 failures, not as a green product gate. Remaining gaps are contained in the coverage matrix; inherited message definitions, source branches and draft routes must not be mistaken for runtime acceptance or production publication.

## Source index

Repository source links below pin the audited revision. Keycloak parent links name the locally inspected, version-pinned upstream resource; the inherited bytes came from the cached official image, not a live private realm. The #341 comparison source is pinned separately near the start of the report.

[src1]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/index.html

[src2]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/app/account-control.tsx

[src3]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/app/app-shell.tsx

[src4]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/app/catalogue-search.tsx

[src5]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/app/route-loading.tsx

[src6]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/features/releases/featured-card.tsx

[src7]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/features/releases/featured-hero.tsx

[src8]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/features/releases/featured-period-selector.tsx

[src9]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/features/releases/featured-releases-shell.tsx

[src10]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/features/releases/featured-releases-view-model.ts

[src11]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/features/releases/month-dial.tsx

[src12]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/features/releases/period-timeline.ts

[src13]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/features/releases/release-card.tsx

[src14]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/features/releases/release-overflow.tsx

[src15]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/features/releases/release-window-scale.tsx

[src16]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/features/releases/releases-approximate-dates.tsx

[src17]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/features/releases/releases-filters.tsx

[src18]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/features/releases/releases-pagination.tsx

[src19]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/features/releases/releases-shell.tsx

[src20]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/features/releases/releases-view-model.ts

[src21]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/features/releases/releases-weeks.tsx

[src22]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/pages/featured-releases-page.tsx

[src23]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/pages/releases-page.tsx

[src24]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/features/search/game-search-card.tsx

[src25]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/features/search/game-search-pagination.tsx

[src26]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/features/search/game-search-shell.tsx

[src27]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/features/search/game-search-view-model.ts

[src28]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/features/search/game-suggestions-popup.tsx

[src29]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/features/search/game-suggestions.ts

[src30]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/pages/search-page.tsx

[src31]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/features/game-details/game-details-content.tsx

[src32]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/features/game-details/game-information.tsx

[src33]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/features/game-details/game-release-list.tsx

[src34]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/features/game-details/game-summary.tsx

[src35]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/pages/game-details-page.tsx

[src36]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/features/ratings/game-rating-panel.tsx

[src37]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/features/ratings/rating-keypad.tsx

[src38]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/features/ratings/my-rating-card.tsx

[src39]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/pages/my-ratings-page.tsx

[src40]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/features/catalogue-administration/administration-format.ts

[src41]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/features/catalogue-administration/coverage-explorer.tsx

[src42]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/features/catalogue-administration/synchronization-control.tsx

[src43]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/features/catalogue-administration/synchronization-request-state.ts

[src44]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/features/catalogue-administration/synchronization-request.tsx

[src45]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/features/catalogue-administration/synchronization-runs.tsx

[src46]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/pages/catalogue-administration-page.tsx

[src47]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/pages/not-found-page.tsx

[src48]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/public/assets/covers/fallback.svg

[src49]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/public/assets/featured/fallback.svg

[src50]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/public/favicon.svg

[src51]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/shared/catalogue/game-genres.tsx

[src52]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/shared/catalogue/platform-marks.tsx

[src53]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/shared/catalogue/release-date.ts

[src54]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/shared/catalogue/release-labels.ts

[src55]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/shared/score/discovery-scores.tsx

[src56]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/shared/score/thermal-band.ts

[src57]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/shared/ui/app-select.tsx

[src58]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/shared/ui/catalogue-cover.tsx

[src59]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/shared/ui/multi-select.tsx

[src60]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/backend/src/main/java/com/videogameplatform/api/delivery/catalogue/CatalogueCoverMapper.java

[src61]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/backend/src/main/java/com/videogameplatform/catalogue/adapter/persistence/synchronization/JdbcCatalogueSynchronizationStore.java

[src62]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/backend/src/main/java/com/videogameplatform/catalogue/application/releases/internal/FeaturedReleaseService.java

[src63]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/backend/src/main/java/com/videogameplatform/catalogue/application/synchronization/internal/CoverSelectionPolicy.java

[src64]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/backend/src/main/java/com/videogameplatform/catalogue/domain/RegionLabel.java

[src65]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/shared/legal/legal-links.tsx

[src66]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/shared/legal/legal-pages.tsx

[src67]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/frontend/src/shared/legal/proposed-texts.json

[src68]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/docker/keycloak/themes/gameometro/login/messages/messages_es.properties

[src69]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/deploy/public-production/keycloak/import/gameometro-realm.json

[src70]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/deploy/public-production/keycloak/legal-review.json

[src71]: https://github.com/keycloak/keycloak/blob/26.7.0/themes/src/main/resources/theme/base/login/messages/messages_es.properties

[src72]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/docker/keycloak/themes/gameometro/email/messages/messages_es.properties

[src73]: https://github.com/keycloak/keycloak/blob/26.7.0/themes/src/main/resources/theme/base/email/messages/messages_es.properties

[src74]: https://github.com/rubhern/videogame-platform/blob/1532f1ddb81e0060970dfdbc8112de577976e845/docker/keycloak/import/videogame-platform-realm.json
