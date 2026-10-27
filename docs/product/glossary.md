# Product glossary

- **Status:** Active

| Term | Definition |
|---|---|
| Game | Provider-independent video-game work; distinct from a platform/region release or edition |
| Release | Commercial release of a game for one platform and region, with explicit date precision/status |
| Presented release | The one release a discovery card or game-page selection shows for a platform when several are stored; the others stay stored and reachable |
| Availability | Subscription, promotion, or rotating-catalogue access; never a commercial release |
| Featured releases (`Destacados`) | Automatic selection of a calendar week's (Monday to Sunday) or month's releases by provider-observed attention (IGDB Hypes); its first game is the period's featured release (`Lanzamiento de la semana`, `Lanzamiento del mes`). Never a quality judgement, award or editorial pick |
| Popularity signal (`Hype`) | A game's locally stored, provider-observed attention (currently IGDB Hypes) that ranks featured releases and can order the release explorer; its value is never shown and never invented |
| External player score | A source's own player mean on its declared 0–100 scale with the positive count of that source's ratings (currently IGDB players), stored locally with its source and check time; never a Gameómetro rating, never blended with one and never hidden for its age or sample size |
| Featured media | The context-selected landscape image and optional secondary-card logo a featured release presents, chosen from provider image metadata; a portrait cover is only shown whole, never stretched or cropped |
| Date precision | `day`, `month`, `quarter`, `year`, or `unknown`; never infer missing precision |
| Catalogue | Accepted visible games with product-owned identity; a provider result joins it only through the explicit import policy |
| Import policy | Product rule deciding which provider work types become a game; unsupported types are deferred, not failed |
| Cover reference | Provider image identifier or product asset reference used without making provider binaries product-owned storage |
| Verification / review / freshness | Independent axes: evidence level, unresolved ambiguity, and internal time-policy state |
| Rating | One active personal score for a released game: an integer from 1 to 10 or, post-MVP (#359), integer scores per platform played whose mean is its one community vote; not a written review |
| Played platform | A platform a user says they played a rated game on, optionally with their own score there (#359); never inferred and never an ownership claim |
| Rating eligibility | Dated decision that a qualifying commercial release permits create/update; delete remains allowed |
| Aggregate rating | Arithmetic mean to one decimal, count, and distribution of one contribution per active rating; separate from personal rating. Per-platform readings average only that platform's scores and never count voters (#359) |
| Score temperature | Presentation of a personal or aggregate score as one of five bands, from Congelado to Ardiendo; never a separate score and never a change to ratings or the aggregate |
| `Mis puntuaciones` | Authenticated view for finding, sorting, editing, and deleting the current user's ratings |
| `Tus estadísticas` | Private descriptive view of the current user's recorded ratings; selected statistics and sample rules belong to [UC-015](../architecture/application/mvp-use-cases.md#personal-rating-statistics-uc-015-post-mvp-254) |
| Spanish-first | Product behaviour designed around Spanish language and regional/platform release clarity, not only translation |
| Learning MVP | Smallest complete journey that tests selected product and architecture learning goals; not product–market-fit evidence |
| Release stage | Product semantic type of a release date; see the [domain model](../architecture/domain/mvp-domain-model.md). Distinct from lifecycle status and editions. |
| Synthetic evidence | Fictional/rehearsal observations accepted only for an explicitly bounded internal decision |
| Vertical slice | Coherent capability across UI, application, data, security, testing, observability, and operation |
| Owner | Ruben Hernandez, the only person accountable for decisions; AI may assist but does not approve |

The [domain model](../architecture/domain/mvp-domain-model.md) owns normative domain
terms and invariants when this concise product glossary is insufficient.

## User-facing terms

Owner-approved Spanish labels for the concepts above (#341; Destacados/TOPómetro distinction #385). They name what players read;
they never rename domain concepts, API fields, persistence, telemetry identifiers or URL
paths, and they never change a concept's meaning. The
[frontend design guidelines](../development/frontend-design.md) own where and how each label
is set on screen.

| Concept | Visible term | Use | Avoid |
|---|---|---|---|
| Brand | `Gameómetro` | Product name and the subject of honest, non-exhaustive statements (`Gameómetro no tiene lanzamientos…`) | — |
| Release | `Lanzamiento` / `Lanzamientos` | Every release, card, count and status; also the main navigation disclosure containing `Destacados`, `Explorar` and `Calendario`, in that order (#385); `Explorar` opens the release explorer, titled `Explorar lanzamientos` (#285), over any range of days (`Desde`, `Hasta`, `Esta semana`, `Ver todo el periodo`); its second view is the `Calendario de destacados` (#250), reached through `Lista | Calendario`, where a day from today on counts `N lanzamientos` (one per highlighted game; `UC-016` owns eligibility) and `Ver todos los lanzamientos del día` opens the full explorer on that day, keeping the platform/region context | `Estreno`; `Recientes`/`Próximos` windows or sections (the `Próximos` release state inside `Explorar catálogo`, #389, is a filter, not a window); `Todos` as a header destination |
| Featured releases | `Destacados` | The weekly or monthly selection, past and future (`Destacados de la semana`, `Destacados del mes`); its first game keeps `Lanzamiento de la semana` / `Lanzamiento del mes`. Attention, never a judgement of game quality | `TOPómetro`; calling the selection the best or the most anticipated |
| Player-score ranking (#386) | `TOPómetro` | Best released games by players’ ratings, one source at a time: `Jugadores IGDB` (default) or `Comunidad Gameómetro`, never mixed; a top-level navigation entry beside `Lanzamientos`; IGDB players' scores take part from 50 ratings, community scores from the first; its places 1–3 are the podium (`Podio`) and the rest follow as a ledger, continued only by `Cargar más juegos` | Using Hypes, release attention or preferred platforms as an implicit personal ranking; calling Destacados the best games |
| Platforms section (#287) | `Plataformas` | The third top-level navigation entry, after `TOPómetro` (#358), and its index: four featured platforms, then `Otras plataformas`, the owner's selection of other platforms Gameómetro has games for. Platforms keep the catalogue's own names (`Windows PC`). Every platform offers the same three destinations: `Próximos destacados` (#388, its most anticipated releases by Hypes), `TOPómetro` (#387, its player-score ranking of the games released there, by each game's global score, live for every platform) and `Explorar catálogo` (#389, its games, live for every platform); one not yet available reads `Próximamente`. Inside `Explorar catálogo` the release state reads `Todos`, `Ya lanzados` and `Próximos` (owner request, #389), a game still to come carries `Próximo` (or `Retrasado`), the platform search reads `Buscar en <plataforma>`, the folded filters `Filtros` with `Año de lanzamiento` and `Géneros`, the switch `Cambiar plataforma`, and a game page returns with `Volver al catálogo` | `Consolas`; `Otros` as if it were a platform; confusing it with the personal `Mis plataformas`; `Mejor valorados` for Próximos destacados |
| Compact navigation (#358) | `Menú` | The button that opens the primary sections and the account on narrow screens; its account dock reads `Tu cuenta` without a session and `Mi cuenta` with one | `Más`, `Opciones`, or an icon without an accessible name |
| Popularity signal | `Hype` | To explain why a selection exists or is missing (`Sin datos de Hype`) and as an order of the release explorer (`Ordenar por: Hype`, #285); never with a value | Presenting Hype as quality, a score or a Gameómetro metric |
| Catalogue | No visible term | Contextual words: `Juegos`, `Resultados`, `Ficha del juego` or the game's title. The one exception is the owner-named platform destination `Explorar catálogo` (#287, #389) | `Catálogo`, `catálogo local`, claims of exhaustive coverage. Operator-only administration may keep it |
| Cover | `Portada` | A game's actual cover image and its fallback (`Portada oficial no disponible`) | `Carátula`; calling artwork, screenshots or logos a cover |
| External player score count | `Valoraciones` | Number of a source's player ratings (`Basada en 1.234 valoraciones`), under the `Jugadores IGDB` source label, which also names the release explorer's order by that score (`Ordenar por: Jugadores IGDB`, #285) | Blending it with Gameómetro ratings |
| Rating | `Puntuación` / `Puntuaciones` | `Puntuación de la comunidad`, `Tu puntuación`, `Mis puntuaciones`, `Puntuar`; the chosen 1–10 value is also a `puntuación` (#286, superseding the earlier `nota` variant); the release explorer's community-mean order is `Ordenar por: Puntuación Gameómetro` (#285) | `Nota`; `Valoración` for a Gameómetro rating |
| Preferred platforms | `Mis plataformas` | The saved choice of platforms, first offered by the welcome **¿A qué juegas?**, and the `Mi cuenta` row whose checkbox applies or sets it aside on Destacados, Explorar lanzamientos and the calendar (#295, #250); `Ver todas las plataformas` beside an empty view | `Favoritos`, `Perfil`, `Mis consolas`, `Para ti`, `Recomendados` or anything implying ownership or recommendations |
| Rating by platform | `Puntuar por plataforma`, `Editar plataformas`, `Tu puntuación general`, `Media de 2 plataformas`; the community's readings open from `Por plataforma` (#359) | Asking where a game was played and, optionally, its score there; the personal reading names its source only when it is a platform mean | `Consola`, `Versión`, `Voto` or showing the arithmetic; presenting platform counts as voters |
| Personal rating statistics | `Tus estadísticas` | Entry from `Mis puntuaciones` and `Mi cuenta`; descriptive personal readings (#254), including `Dónde has jugado` for the platforms the player recorded (#363) | Personality claims, inferred platforms, ownership, playing time, a favourite platform or score-history claims |
| Score temperature | `Congelado`, `Frío`, `Templado`, `Caliente`, `Ardiendo` | Unchanged bands presenting a numerical score | A separate rating or scale |
| Release stage | Only special stages: `Acceso anticipado` (Early Access), `Acceso previo` (Advance Access), `Beta`, `Alfa` | A full release and an unknown stage are not labelled | `Lanzamiento completo`; merging Early and Advance Access |
| Game page way back | `Volver a Destacados`, `Volver a lanzamientos`, `Volver al calendario`, `Volver al TOPómetro`, `Volver a resultados`, `Volver a mis puntuaciones`, `Volver a tus estadísticas` | The game page's back action names the screen it was opened from (#285); without a known origin it is `Volver a Destacados` | A generic `Volver` or `Atrás` |
