import { useId, type CSSProperties } from "react";
import { Link } from "react-router-dom";
import type { RatingInsights } from "./rating-insights-api";
import { decimal, games, number, percent } from "./rating-insights-format";
import { platformIcon } from "../../shared/catalogue/taxonomy-icons";
import { thermalBand, thermalLabels } from "../../shared/score/thermal-band";
import { SelectIcon } from "../../shared/ui/select-icon";

const size = (value: number): CSSProperties => ({ ["--bar-size" as string]: `${value}%` });

type Slice = { key: "one" | "several" | "none"; label: string; count: number };

// A ring of circumference 100 (`pathLength`), drawn clockwise from twelve o'clock.
const RADIUS = 42;
const GAP = 1.2;

/**
 * The collection's one true partition: every rated game falls in exactly one slice — recorded on
 * one platform, on several, or on none — so the slices add up to the total, unlike the platforms.
 * Games without a platform stay an empty, outlined part of the ring rather than a texture the bars
 * use for something else; the legend states every count and share.
 */
function CoverageDonut({ total, without, several }: { total: number; without: number; several: number }) {
  // SVG `url(#…)` references need a plain identifier.
  const id = `coverage${useId().replace(/[^\w-]/g, "")}`;
  const recorded = total - without;
  const slices: Slice[] = [
    { key: "one", label: "En una plataforma", count: recorded - several },
    { key: "several", label: "En varias plataformas", count: several },
    { key: "none", label: "Sin plataforma indicada", count: without },
  ];
  const drawn = slices.filter(slice => slice.count > 0);
  const gap = drawn.length > 1 ? GAP : 0;
  let start = 0;
  return <figure className="insights-coverage">
    <div className="insights-donut">
      <svg viewBox="0 0 100 100" aria-hidden="true" focusable="false">
        <defs>
          <pattern id={`${id}-several`} width="4" height="4" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
            <rect width="4" height="4" className="insights-donut-several-base" /><rect width="1.4" height="4" className="insights-donut-several-stripe" />
          </pattern>
        </defs>
        <circle className="insights-donut-track" cx="50" cy="50" r={RADIUS} pathLength="100" />
        <circle className="insights-donut-rim" cx="50" cy="50" r={RADIUS + 7.5} />
        <circle className="insights-donut-rim" cx="50" cy="50" r={RADIUS - 7.5} />
        {drawn.map(slice => {
          const length = slice.count / total * 100;
          const offset = start;
          start += length;
          return <circle key={slice.key} className={`insights-donut-slice insights-donut-${slice.key}`} cx="50" cy="50" r={RADIUS}
            pathLength="100" transform="rotate(-90 50 50)"
            stroke={slice.key === "several" ? `url(#${id}-several)` : undefined}
            strokeDasharray={`${Math.max(length - gap, 0.4)} ${100 - Math.max(length - gap, 0.4)}`}
            strokeDashoffset={-(offset + gap / 2)} />;
        })}
      </svg>
      <p className="insights-donut-centre"><strong>{percent(recorded, total)}</strong><span>con plataforma</span></p>
    </div>
    <figcaption>
      <p className="insights-coverage-figure"><strong>{number(recorded)}</strong> <span>de {games(total)} con plataforma</span></p>
      <ul className="insights-donut-legend">
        {slices.map(slice => <li key={slice.key} data-slice={slice.key}>
          <span className={`insights-donut-swatch insights-donut-${slice.key}`} aria-hidden="true" />
          <span className="insights-donut-label">{slice.label}</span>{" "}
          <span className="insights-donut-value"><strong>{number(slice.count)}</strong> <small>{percent(slice.count, total)}</small></span>
        </li>)}
      </ul>
    </figcaption>
  </figure>;
}

/**
 * Where the player recorded playing: one bar per platform whose length counts its games, solid for
 * the games scored on that platform and outlined for those only marked as played, beside the mean
 * of those platform scores alone. Platforms overlap, so the coverage ring — the one partition of
 * the collection — sits apart from them.
 */
export function PlatformInsights({ platforms, total }: Pick<RatingInsights, "platforms" | "total">) {
  const { items, otherPlatforms, gamesWithoutPlatforms, gamesOnSeveralPlatforms } = platforms;
  const maximum = Math.max(...items.map(item => item.playedGames), 1);
  const anyScore = items.some(item => item.scoredGames > 0);
  return <section className="insights-platforms" aria-labelledby="platforms-title">
    <div className="insights-platforms-lead">
      <p className="insights-caption">Las plataformas que has indicado</p>
      <h2 id="platforms-title">Dónde has jugado</h2>
      {items.length === 0 ? null : <CoverageDonut total={total} without={gamesWithoutPlatforms} several={gamesOnSeveralPlatforms} />}
    </div>
    {items.length === 0 ? <div className="insights-inline-empty insights-platforms-empty">
      <p>Aún no has indicado en qué plataformas jugaste {total === 1 ? "tu juego" : `tus ${games(total)}`}.</p>
      <p>Al puntuar un juego, <strong>Puntuar por plataforma</strong> te deja marcarlas, con o sin una puntuación para cada una.</p>
      <Link className="insights-inline-link" to="/mis-puntuaciones">Ir a Mis puntuaciones</Link>
    </div> : <div className="insights-platforms-body">
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
    </div>}
  </section>;
}
