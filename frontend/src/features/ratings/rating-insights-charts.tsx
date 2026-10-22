import { useState, type CSSProperties } from "react";
import type { GenreInsight, RatingInsights } from "./rating-insights-api";
import { THERMAL_BANDS, thermalBand, thermalLabels } from "../../shared/score/thermal-band";

import { compact, decimal, games, number, percent } from "./rating-insights-format";

const size = (property: string, value: number): CSSProperties => ({ [property]: `${value}%` });

/**
 * Ten real buckets on one shared grid: every pair of columns sits over its temperature with that
 * band's count, and a fire needle marks the exact mean between the rounded columns. Every value
 * remains readable without colour, a pointer or SVG support.
 */
export function RatingDistribution({ distribution, total, mean }: Pick<RatingInsights, "distribution" | "total" | "mean">) {
  const [selected, setSelected] = useState<number | null>(null);
  const max = Math.max(...distribution, 1);
  const count = selected === null ? 0 : distribution[selected - 1] ?? 0;
  return <section className="insights-distribution" aria-labelledby="distribution-title">
    <div className="insights-section-head">
      <div><p className="insights-caption">De Congelado a Ardiendo</p><h2 id="distribution-title">Así puntúas</h2></div>
      <p>Cada juego, en su temperatura.<br />Toca una puntuación para acercarte.</p>
    </div>
    <div className="insights-scale">
      {mean === null ? null : <div className="insights-mean-needle" aria-hidden="true" style={{ ...size("--at", (mean - 0.5) / 10 * 100), ["--shift" as string]: mean < 1.8 ? "-12%" : mean > 9.2 ? "-88%" : "-50%" }}>
        <span className="insights-mean-label">Media {decimal(mean)}</span>
      </div>}
      <div className="insights-histogram" role="group" aria-label="Distribución de tus puntuaciones">
        {distribution.map((bucket, index) => {
          const value = index + 1;
          return <button type="button" key={value} data-thermal={thermalBand(value) ?? undefined}
            aria-pressed={selected === value} data-empty={bucket === 0 || undefined}
            aria-label={`${value} de 10: ${games(bucket)}, ${percent(bucket, total)}`}
            onClick={() => setSelected(selected === value ? null : value)} className="insights-bin">
            <span className="insights-bin-track" aria-hidden="true">
              <span className="insights-bin-fill" style={size("--bar-size", bucket / max * 100)}><span>{compact(bucket)}</span></span>
            </span>
            <span className="insights-bin-label" aria-hidden="true">{value}</span>
          </button>;
        })}
      </div>
      <ul className="insights-bands" aria-label="Tus cinco temperaturas">
        {THERMAL_BANDS.map((band, index) => {
          const bandCount = (distribution[index * 2] ?? 0) + (distribution[index * 2 + 1] ?? 0);
          return <li key={band} data-thermal={band}>
            <span className="insights-band-name">{thermalLabels[band]}<span className="sr-only">, de {index * 2 + 1} a {index * 2 + 2}:</span></span>{" "}
            <span className="insights-band-count"><strong>{number(bandCount)}</strong> <small>{percent(bandCount, total)}</small></span>
          </li>;
        })}
      </ul>
    </div>
    <p className="insights-chart-readout" role="status">{selected === null
      ? mean === null ? "" : `La aguja marca tu media exacta, ${decimal(mean)}. Cada columna reúne los juegos cuya puntuación se redondea a ese número.`
      : `${selected} de 10 · ${thermalLabels[thermalBand(selected) ?? "warm"]} · ${games(count)} · ${percent(count, total)} de tu colección`}</p>
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
            <div className="insights-genre-label"><span>{genre.genre.name}</span><span>{games(genre.count)}</span></div>
            <div className="insights-genre-measure">
              <span className="insights-genre-track" aria-hidden="true"><span style={size("--bar-size", (best ? genre.mean : genre.count) / maximum * 100)} /></span>
              <span className="insights-genre-mean" data-thermal={thermalBand(genre.mean) ?? undefined}>
                <strong>{decimal(genre.mean)}</strong><span className="sr-only"> de 10, {thermalLabels[thermalBand(genre.mean) ?? "warm"]}</span><small aria-hidden="true"> /10</small>
              </span>
            </div>
          </li>)}
        </ol>}
    </div>
    <p className="insights-note">Un juego cuenta en cada uno de sus géneros: los grupos se solapan. {number(total - gamesWithoutGenres)} de {number(total)} juegos con género{gamesWithoutGenres > 0 ? `; ${number(gamesWithoutGenres)} sin género en el catálogo.` : "."}</p>
  </section>;
}

const monthName = (month: string, form: "short" | "long") => new Intl.DateTimeFormat("es-ES", {
  month: form, timeZone: "UTC",
}).format(new Date(`${month}-01T12:00:00Z`)).replace(".", "");
const fullMonth = (month: string) => new Intl.DateTimeFormat("es-ES", {
  month: "long", year: "numeric", timeZone: "UTC",
}).format(new Date(`${month}-01T12:00:00Z`));

/**
 * Twelve counts in ice, one row on wide screens and two half-years on phones. Activity never
 * receives a score temperature; the first month and every January name their year.
 */
export function RatingActivity({ activity, olderRatings }: Pick<RatingInsights, "activity" | "olderRatings">) {
  const max = Math.max(...activity.map(month => month.count), 1);
  const first = activity[0];
  const last = activity.at(-1);
  const recent = activity.reduce((sum, month) => sum + month.count, 0);
  return <section className="insights-activity" aria-labelledby="activity-title">
    <p className="insights-caption">Últimos doce meses</p>
    <h2 id="activity-title">Puntuación a puntuación</h2>
    <p className="insights-activity-total"><strong>{number(recent)}</strong> {recent === 1 ? "puntuación que conservas" : "puntuaciones que conservas"}</p>
    {first && last ? <p className="insights-note">De {fullMonth(first.month)} a {fullMonth(last.month)}</p> : null}
    <ol className="insights-activity-chart" aria-hidden="true">
      {activity.map((month, index) => <li className="insights-month" key={month.month} data-empty={month.count === 0 || undefined}>
        <span className="insights-month-count">{compact(month.count)}</span>
        <span className="insights-month-track"><span style={size("--bar-size", month.count / max * 100)} /></span>
        <span className="insights-month-name">{monthName(month.month, "short")}{index === 0 || month.month.endsWith("-01") ? <small>{month.month.slice(2, 4)}</small> : null}</span>
      </li>)}
    </ol>
    <details className="insights-method"><summary>Ver meses y recuentos</summary>
      <table><caption>Puntuaciones que conservas por mes de creación</caption><thead><tr><th scope="col">Mes</th><th scope="col">Juegos</th></tr></thead>
        <tbody>{activity.map(month => <tr key={month.month}><th scope="row">{fullMonth(month.month)}</th><td>{number(month.count)}</td></tr>)}</tbody></table>
    </details>
    <p className="insights-note">Por el mes en que puntuaste cada juego por primera vez. Cambiar la puntuación no lo mueve de mes; eliminarla lo retira del recuento. No indica cuándo jugaste.{olderRatings > 0 ? ` Otras ${number(olderRatings)} puntuaciones son anteriores a este periodo.` : ""}</p>
  </section>;
}
