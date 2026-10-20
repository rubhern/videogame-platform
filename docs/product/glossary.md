# Product glossary

- **Status:** Active

| Term | Definition |
|---|---|
| Game | Provider-independent video-game work; distinct from a platform/region release or edition |
| Release | Commercial release of a game for one platform and region, with explicit date precision/status |
| Presented release | The one release a discovery card or game-page selection shows for a platform when several are stored; the others stay stored and reachable |
| Availability | Subscription, promotion, or rotating-catalogue access; never a commercial release |
| Featured releases (`TOPómetro`) | Automatic selection of a calendar week's (Monday to Sunday) or month's releases by provider-observed attention; its first game is the period's featured release (`Lanzamiento de la semana`, `Lanzamiento del mes`). Never a quality judgement, award or editorial pick |
| Popularity signal (`Hype`) | A game's locally stored, provider-observed attention (currently IGDB Hypes) that ranks featured releases and can order the release explorer; its value is never shown and never invented |
| External player score | A source's own player mean on its declared 0–100 scale with the positive count of that source's ratings (currently IGDB players), stored locally with its source and check time; never a Gameómetro rating, never blended with one and never hidden for its age or sample size |
| Featured media | The context-selected landscape image and optional secondary-card logo a featured release presents, chosen from provider image metadata; a portrait cover is only shown whole, never stretched or cropped |
| Date precision | `day`, `month`, `quarter`, `year`, or `unknown`; never infer missing precision |
| Catalogue | Accepted visible games with product-owned identity; a provider result joins it only through the explicit import policy |
| Import policy | Product rule deciding which provider work types become a game; unsupported types are deferred, not failed |
| Cover reference | Provider image identifier or product asset reference used without making provider binaries product-owned storage |
| Verification / review / freshness | Independent axes: evidence level, unresolved ambiguity, and internal time-policy state |
| Rating | One active personal integer score from 1 to 10 for a released game; not a written review |
| Rating eligibility | Dated decision that a qualifying commercial release permits create/update; delete remains allowed |
| Aggregate rating | Arithmetic mean to one decimal, count, and distribution from active ratings; separate from personal rating |
| Score temperature | Presentation of a personal or aggregate score as one of five bands, from Congelado to Ardiendo; never a separate score and never a change to ratings or the aggregate |
| `Mis puntuaciones` | Authenticated view for finding, sorting, editing, and deleting the current user's ratings |
| Spanish-first | Product behaviour designed around Spanish language and regional/platform release clarity, not only translation |
| Learning MVP | Smallest complete journey that tests selected product and architecture learning goals; not product–market-fit evidence |
| Release stage | Product semantic type of a release date; see the [domain model](../architecture/domain/mvp-domain-model.md). Distinct from lifecycle status and editions. |
| Synthetic evidence | Fictional/rehearsal observations accepted only for an explicitly bounded internal decision |
| Vertical slice | Coherent capability across UI, application, data, security, testing, observability, and operation |
| Owner | Ruben Hernandez, the only person accountable for decisions; AI may assist but does not approve |

The [domain model](../architecture/domain/mvp-domain-model.md) owns normative domain
terms and invariants when this concise product glossary is insufficient.

## User-facing terms

Owner-approved Spanish labels for the concepts above (#341). They name what players read;
they never rename domain concepts, API fields, persistence, telemetry identifiers or URL
paths, and they never change a concept's meaning. The
[frontend design guidelines](../development/frontend-design.md) own where and how each label
is set on screen.

| Concept | Visible term | Use | Avoid |
|---|---|---|---|
| Brand | `Gameómetro` | Product name and the subject of honest, non-exhaustive statements (`Gameómetro no tiene lanzamientos…`) | — |
| Release | `Lanzamiento` / `Lanzamientos` | Every release, card, count and status; also the main navigation entry that opens the release explorer, titled `Explorar lanzamientos` (owner decision, #285), over any range of days (`Desde`, `Hasta`, `Esta semana`, `Ver todo el periodo`) | `Estreno`; `Recientes`/`Próximos` windows or sections; `Todos`, `Explorar` or a parent group as a navigation entry |
| Featured releases | `TOPómetro` | The weekly or monthly selection, past and future (`TOPómetro de la semana`, `TOPómetro del mes`); its first game keeps `Lanzamiento de la semana` / `Lanzamiento del mes`. The name never states the ranking criterion | `Destacados`; calling the selection the best or the most anticipated |
| Popularity signal | `Hype` | To explain why a selection exists or is missing (`Sin datos de Hype`) and as an order of the release explorer (`Ordenar por: Hype`, #285); never with a value | Presenting Hype as quality, a score or a Gameómetro metric |
| Catalogue | No visible term | Contextual words: `Juegos`, `Resultados`, `Ficha del juego` or the game's title | `Catálogo`, `catálogo local`, claims of exhaustive coverage. Operator-only administration may keep it |
| Cover | `Portada` | A game's actual cover image and its fallback (`Portada oficial no disponible`) | `Carátula`; calling artwork, screenshots or logos a cover |
| External player score count | `Valoraciones` | Number of a source's player ratings (`Basada en 1.234 valoraciones`), under the `Jugadores IGDB` source label, which also names the release explorer's order by that score (`Ordenar por: Jugadores IGDB`, #285) | Blending it with Gameómetro ratings |
| Rating | `Puntuación` / `Puntuaciones` | `Puntuación de la comunidad`, `Tu puntuación`, `Mis puntuaciones`, `Puntuar`; the chosen 1–10 value is also a `puntuación` (#286, superseding the earlier `nota` variant); the release explorer's community-mean order is `Ordenar por: Puntuación Gameómetro` (#285) | `Nota`; `Valoración` for a Gameómetro rating |
| Score temperature | `Congelado`, `Frío`, `Templado`, `Caliente`, `Ardiendo` | Unchanged bands presenting a numerical score | A separate rating or scale |
| Release stage | Only special stages: `Acceso anticipado` (Early Access), `Acceso previo` (Advance Access), `Beta`, `Alfa` | A full release and an unknown stage are not labelled | `Lanzamiento completo`; merging Early and Advance Access |
| Game page way back | `Volver al TOPómetro`, `Volver a lanzamientos`, `Volver a resultados`, `Volver a mis puntuaciones` | The game page's back action names the screen it was opened from (#285); without a known origin it is `Volver al TOPómetro` | A generic `Volver` or `Atrás` |
