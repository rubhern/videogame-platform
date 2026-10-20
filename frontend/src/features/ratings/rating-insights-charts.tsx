import { useState, type CSSProperties } from "react";
import type { GenreInsight, RatingInsights } from "./rating-insights-api";
import { THERMAL_BANDS, thermalBand, thermalLabels } from "../../shared/score/thermal-band";

import { number, decimal } from "./rating-insights-format";

const percent = (part: number, total: number) => new Intl.NumberFormat("es-ES", { style: "percent", maximumFractionDigits: 1 }).format(part / total);
const size = (property: string, value: number): CSSProperties => ({ [property]: `${value}%` });

/** Ten real buckets. Every value remains readable without colour, a pointer or SVG support. */
export function RatingDistribution({ distribution, total }: Pick<RatingInsights, "distribution" | "total">) {
  const [selected, setSelected] = useState<number | null>(null);
  const max = Math.max(...distribution, 1);
  return <section className="insights-distribution" aria-labelledby="distribution-title">
    <div className="insights-section-head">
      <div><p className="insights-caption">De Congelado a Ardiendo</p><h2 id="distribution-title">Así puntúas</h2></div>
      <p>Cada juego, en su temperatura.<br />Elige una puntuación para acercarte.</p>
    </div>
    <div className="insights-histogram" role="group" aria-label="Distribución de tus puntuaciones">
      {distribution.map((count, index) => {
        const value = index + 1;
        return <button type="button" key={value} data-thermal={thermalBand(value) ?? undefined}
          aria-pressed={selected === value}
          aria-label={`${value} de 10: ${number(count)} ${count === 1 ? "juego" : "juegos"}, ${percent(count, total)}`}
          onClick={() => setSelected(value)} className="insights-bin">
          <span className="insights-bin-track" aria-hidden="true">
            <span className="insights-bin-fill" style={size("--bar-size", count / max * 100)}><span>{number(count)}</span></span>
          </span>
          <span className="insights-bin-label" aria-hidden="true">{value}</span>
        </button>;
      })}
    </div>
    <p className="insights-chart-readout" role="status">{selected === null
      ? "Del 1 al 10. Todas tus puntuaciones, sin mezclar con las de nadie más."
      : `${selected} de 10 · ${thermalLabels[thermalBand(selected) ?? "warm"]} · ${number(distribution[selected - 1] ?? 0)} ${(distribution[selected - 1] ?? 0) === 1 ? "juego" : "juegos"} · ${percent(distribution[selected - 1] ?? 0, total)} de tu colección`}</p>
    <ul className="insights-thermal-summary" aria-label="Tus cinco temperaturas">
      {THERMAL_BANDS.map((band, index) => {
        const count = (distribution[index * 2] ?? 0) + (distribution[index * 2 + 1] ?? 0);
        return <li key={band} data-thermal={band}>
          <span className="insights-thermal-line" aria-hidden="true"><span style={size("--bar-size", count / total * 100)} /></span>
          <span className="insights-thermal-name">{thermalLabels[band]}</span>
          <span className="insights-thermal-count">{number(count)} <small>{percent(count, total)}</small></span>
          <span className="insights-caption">{index * 2 + 1}–{index * 2 + 2} de 10</span>
        </li>;
      })}
    </ul>
  </section>;
}

/** Independent overlapping genre groups: bar length measures count OR score, always labelled. */
export function GenreInsights({ mostRatedGenres, bestRatedGenres, gamesWithoutGenres, total }: Pick<RatingInsights,
  "mostRatedGenres" | "bestRatedGenres" | "gamesWithoutGenres" | "total">) {
  const [best, setBest] = useState(false);
  const genres = best ? bestRatedGenres : mostRatedGenres;
  const maximum = best ? 10 : Math.max(...genres.map(genre => genre.count), 1);
  return <section className="insights-genres" aria-labelledby="genres-title">
    <p className="insights-caption">Los mundos que has puntuado</p>
    <h2 id="genres-title">Tu mapa de géneros</h2>
    <div className="insights-switch" role="group" aria-label="Orden de géneros">
      <button type="button" aria-pressed={!best} onClick={() => setBest(false)}>Más puntuados</button>
      <button type="button" aria-pressed={best} onClick={() => setBest(true)}>Mejor puntuados</button>
    </div>
    <p className="insights-note" id="genre-sample">{best ? "Hasta seis géneros por media. Mínimo tres juegos por género." : "Hasta seis géneros por número de juegos. La media siempre lleva su muestra."}</p>
    <div aria-live="polite">
      {genres.length === 0 ? <p className="insights-inline-empty">{best
        ? "Aún no hay géneros con tres juegos puntuados. Tu mapa crecerá contigo."
        : "Estos juegos aún no tienen géneros en el catálogo."}</p>
        : <ol className="insights-genre-list" aria-describedby="genre-sample">
          {genres.map((genre: GenreInsight) => <li key={genre.genre.genreId} data-thermal={best ? thermalBand(genre.mean) ?? undefined : undefined}>
            <div className="insights-genre-label"><span>{genre.genre.name}</span><span>{number(genre.count)} {genre.count === 1 ? "juego" : "juegos"}</span></div>
            <div className="insights-genre-measure">
              <span className="insights-genre-track" aria-hidden="true"><span style={size("--bar-size", (best ? genre.mean : genre.count) / maximum * 100)} /></span>
              <span className="insights-genre-mean" data-thermal={thermalBand(genre.mean) ?? undefined}>
                <strong>{decimal(genre.mean)}</strong><span className="sr-only"> de 10, {thermalLabels[thermalBand(genre.mean) ?? "warm"]}</span><small aria-hidden="true"> /10</small>
              </span>
            </div>
          </li>)}
        </ol>}
    </div>
    <p className="insights-note">Un juego cuenta en cada uno de sus géneros: los grupos se solapan.</p>
    <p className="insights-note">{number(total - gamesWithoutGenres)} de {number(total)} juegos con género. {gamesWithoutGenres > 0 ? `${number(gamesWithoutGenres)} sin género en el catálogo.` : "Todos tienen género en el catálogo."}</p>
  </section>;
}

const monthLabel = (month: string, full = false) => new Intl.DateTimeFormat("es-ES", {
  month: full ? "long" : "short", year: full ? "numeric" : "2-digit", timeZone: "UTC",
}).format(new Date(`${month}-01T12:00:00Z`));

/** Bars are counts, in ice. Activity never receives a score temperature. */
export function RatingActivity({ activity, olderRatings }: Pick<RatingInsights, "activity" | "olderRatings">) {
  const max = Math.max(...activity.map(month => month.count), 1);
  const first = activity[0];
  const last = activity.at(-1);
  const recent = activity.reduce((sum, month) => sum + month.count, 0);
  return <section className="insights-activity" aria-labelledby="activity-title">
    <p className="insights-caption">Últimos doce meses</p>
    <h2 id="activity-title">Puntuación a puntuación</h2>
    <p className="insights-activity-total"><strong>{number(recent)}</strong> {recent === 1 ? "puntuación que conservas" : "puntuaciones que conservas"}</p>
    {first && last ? <p className="insights-note">{monthLabel(first.month, true)} – {monthLabel(last.month, true)}</p> : null}
    <div className="insights-activity-chart" aria-hidden="true">
      {activity.map(month => <div className="insights-month" key={month.month}>
        <span>{number(month.count)}</span>
        <span className="insights-month-track"><span style={size("--bar-size", month.count / max * 100)} /></span>
        <span>{monthLabel(month.month).split(" ")[0]}</span>
      </div>)}
    </div>
    <details className="insights-method"><summary>Ver meses y recuentos</summary>
      <table><caption>Puntuaciones que conservas por mes de creación</caption><thead><tr><th scope="col">Mes</th><th scope="col">Juegos</th></tr></thead>
        <tbody>{activity.map(month => <tr key={month.month}><th scope="row">{monthLabel(month.month, true)}</th><td>{number(month.count)}</td></tr>)}</tbody></table>
    </details>
    <p className="insights-note">Por el mes en que puntuaste cada juego por primera vez. Cambiar la puntuación no lo mueve de mes; eliminarla lo retira del recuento. No indica cuándo jugaste.</p>
    {olderRatings > 0 ? <p className="insights-note">Otras {number(olderRatings)} puntuaciones son anteriores a este periodo.</p> : null}
  </section>;
}
