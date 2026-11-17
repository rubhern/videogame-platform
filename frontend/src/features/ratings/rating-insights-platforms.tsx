import { useId, type CSSProperties } from "react";
import { Link } from "react-router-dom";
import type { RatingInsights } from "./rating-insights-api";
import { decimal, games, number } from "./rating-insights-format";
import { platformIcon } from "../../shared/catalogue/taxonomy-icons";
import { thermalBand, thermalLabels } from "../../shared/score/thermal-band";
import { platformDistribution } from "./platform-distribution";
import { SelectIcon } from "../../shared/ui/select-icon";

const size = (value: number): CSSProperties => ({ ["--bar-size" as string]: `${value}%` });

const RADIUS = 42;
const percentage = new Intl.NumberFormat("es-ES", { maximumFractionDigits: 1 });

function PlatformDonut({ distribution }: { distribution: NonNullable<RatingInsights["platforms"]["distribution"]> }) {
  const id = `platform${useId().replace(/[^\w-]/g, "")}`;
  const { total, rows } = platformDistribution(distribution);
  return <figure className="insights-coverage" data-empty={total === 0} aria-label="Distribución de plataformas indicadas">
    {total > 0 ? <div className="insights-donut" aria-hidden="true">
      <svg viewBox="0 0 100 100" focusable="false">
        <defs>
          {rows.map((row, index) => <pattern key={row.key} id={`${id}-${row.key}`} width={3 + index} height={3 + index}
            patternUnits="userSpaceOnUse" patternTransform={`rotate(${index % 2 === 0 ? 45 : -45})`}>
            <rect width="10" height="10" className={`insights-donut-pattern-base insights-category-${row.key}`} />
            {index > 0 ? <rect width={index === 4 ? 2 : 1} height={index === 4 ? 2 : 10} className="insights-donut-pattern-mark" /> : null}
          </pattern>)}
        </defs>
        <circle className="insights-donut-track" cx="50" cy="50" r={RADIUS} pathLength="100" />
        {rows.map((row, index) => {
          if (row.count === 0) return null;
          const length = row.count / total * 100;
          const offset = rows.slice(0, index).reduce((sum, previous) => sum + previous.count, 0) / total * 100;
          return <circle key={row.key} className="insights-donut-slice" cx="50" cy="50" r={RADIUS}
            pathLength="100" transform="rotate(-90 50 50)" stroke={`url(#${id}-${row.key})`}
            strokeDasharray={`${length} ${100 - length}`} strokeDashoffset={-offset} />;
        })}
        <circle className="insights-donut-rim" cx="50" cy="50" r={RADIUS + 7.5} />
        <circle className="insights-donut-rim" cx="50" cy="50" r={RADIUS - 7.5} />
      </svg>
      <p className="insights-donut-centre"><strong>{number(total)}</strong><span>selecciones de plataforma</span></p>
    </div> : null}
    <figcaption>
      <p className="insights-coverage-figure">Distribución de plataformas indicadas</p>
      <ul className="insights-donut-legend">
        {rows.map(row => <li key={row.key} data-slice={row.key}>
          <span className={`insights-donut-swatch insights-category-${row.key}`} aria-hidden="true" />
          <span className="insights-donut-label">{row.label}</span>{" "}
          <span className="insights-donut-value"><strong>{number(row.count)}</strong>{" "}<small>{percentage.format(row.percentage)} %</small></span>
        </li>)}
      </ul>
      <p className="insights-note">{number(total)} selecciones en total. Cada juego cuenta una vez por plataforma indicada, con o sin puntuación. Los porcentajes reparten estas selecciones, no los juegos.</p>
    </figcaption>
  </figure>;
}

/** Fixed association distribution first; personal platform scores remain secondary. */
export function PlatformInsights({ platforms, total }: Pick<RatingInsights, "platforms" | "total">) {
  const { items, otherPlatforms, gamesWithoutPlatforms, distribution } = platforms;
  const maximum = Math.max(...items.map(item => item.playedGames), 1);
  const anyScore = items.some(item => item.scoredGames > 0);
  return <section className="insights-platforms" aria-labelledby="platforms-title">
    <div className="insights-platforms-lead">
      <p className="insights-caption">Las plataformas que has indicado</p>
      <h2 id="platforms-title">Dónde has jugado</h2>
      {distribution ? <PlatformDonut distribution={distribution} /> : <p className="insights-note">La distribución de plataformas no está disponible.</p>}
      <p className="insights-note insights-platforms-missing">{games(gamesWithoutPlatforms)} sin plataforma indicada; no {gamesWithoutPlatforms === 1 ? "entra" : "entran"} en el gráfico.</p>
    </div>
    {items.length === 0 ? <div className="insights-inline-empty insights-platforms-empty">
      <p>Aún no has indicado en qué plataformas jugaste {total === 1 ? "tu juego" : `tus ${games(total)}`}.</p>
      <p>Al puntuar un juego, <strong>Puntuar por plataforma</strong> te deja marcarlas, con o sin una puntuación para cada una.</p>
      <Link className="insights-inline-link" to="/mis-puntuaciones">Ir a Mis puntuaciones</Link>
    </div> : <details className="insights-platforms-body">
      <summary>Tus puntuaciones por plataforma</summary>
      <ul className="insights-platform-key" aria-label="Cómo leer las barras">
        <li><span className="insights-key-swatch" aria-hidden="true" /> Con tu puntuación en esa plataforma</li>
        <li><span className="insights-key-swatch insights-key-played" aria-hidden="true" /> Solo marcada como jugada</li>
      </ul>
      <ol className="insights-platform-list">
        {items.map(item => {
          const band = item.mean === null ? null : thermalBand(item.mean);
          const icon = platformIcon(item.platform.platformId, item.platform.name);
          return <li key={item.platform.platformId}>
            <p className="insights-platform-name">
              <span className="insights-platform-mark" aria-hidden="true"><SelectIcon name={icon} /></span>
              <span>{item.platform.name}</span>
            </p>
            <span className="insights-platform-track" aria-hidden="true">
              <span className="insights-platform-played" style={size(item.playedGames / maximum * 100)}>
                <span style={size(item.scoredGames / item.playedGames * 100)} />
              </span>
            </span>
            <p className="insights-platform-counts">
              <span>{games(item.playedGames)}<span className="sr-only">,</span></span>{" "}
              <span>{item.scoredGames === 0 ? "ninguno con puntuación" : `${number(item.scoredGames)} con puntuación`}</span>
            </p>
            <p className="insights-platform-mean" data-thermal={band ?? undefined}>
              {item.mean === null ? <span className="insights-platform-unscored">Sin puntuar</span> : <>
                <span className="sr-only">Tu media en {item.platform.name}: </span>
                <strong>{decimal(item.mean)}</strong><small> /10</small>{" "}
                <span className="insights-platform-band">{band ? thermalLabels[band] : ""}</span>
              </>}
            </p>
          </li>;
        })}
      </ol>
      {otherPlatforms > 0 ? <p className="insights-note">{otherPlatforms === 1 ? "Y 1 plataforma más" : `Y ${number(otherPlatforms)} plataformas más`} fuera de esta lista.</p> : null}
      <p className="insights-note">{anyScore
        ? "Cada media usa solo las puntuaciones que diste en esa plataforma, nunca tu puntuación general del juego."
        : "Todavía no has puntuado ningún juego por plataforma: aquí solo ves dónde jugaste."} Un juego cuenta en cada plataforma indicada, así que las cifras se solapan.</p>
    </details>}
  </section>;
}
