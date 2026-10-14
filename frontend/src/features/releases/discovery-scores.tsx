import { GameMeter } from "../../shared/score/game-meter";
import { thermalBand, thermalLabels } from "../../shared/score/thermal-band";
import type { DiscoveryScore } from "./discovery-scores-model";

const decimal = new Intl.NumberFormat("es-ES", { minimumFractionDigits: 1, maximumFractionDigits: 1 });
const exactCount = new Intl.NumberFormat("es-ES", { useGrouping: "always" });
const compactCount = new Intl.NumberFormat("es-ES", { notation: "compact", maximumFractionDigits: 1 });

type ScoreLayout = "hero" | "compact" | "featured";

function formatCompactCount(count: number, layout: ScoreLayout): string {
  if (layout === "featured" && count >= 1000) return `+${Math.floor(count / 1000)}K`;
  return count < 10_000 ? exactCount.format(count) : compactCount.format(count);
}

/** Shared discovery reading: Gameómetro's existing thermal semantics, neutral external sentiment. */
export function DiscoveryScores({ scores, title, layout = "compact" }: {
  scores: DiscoveryScore[];
  title: string;
  layout?: ScoreLayout;
}) {
  if (scores.length === 0) return null;
  return (
    <ul aria-label={`Puntuaciones de ${title}`}
      className={`discovery-scores discovery-scores-${layout === "hero" ? "hero" : "compact"}${layout === "featured" ? " discovery-scores-featured" : ""}`}>
      {scores.map((score) => {
        const band = score.source === "gameometro" ? thermalBand(score.value) : null;
        const count = `${exactCount.format(score.count)} ${score.count === 1 ? "valoración" : "valoraciones"}`;
        return (
          <li className="discovery-score" data-source={score.source} data-thermal={band ?? undefined} key={score.source}>
            {score.source === "gameometro" ? (
              <GameMeter className="discovery-score-mark" value={score.value} />
            ) : (
              <a className="discovery-score-attribution" href={score.attributionUrl}
                aria-label={`Jugadores IGDB: fuente de la puntuación de ${title}`}>
                <img alt="" className="discovery-score-mark" height="22" src="/assets/providers/igdb.svg" width="44" />
              </a>
            )}
            <p className="discovery-score-source">
              {score.source === "gameometro" ? <><span className="sr-only">Gameómetro · </span>Comunidad</> : "Jugadores IGDB"}
              {band === null ? null : <><span aria-hidden="true"> · </span><b>{thermalLabels[band]}</b></>}
            </p>
            <p className="discovery-score-reading" aria-label={`${decimal.format(score.value)} de 10`}>
              <strong>{decimal.format(score.value)}</strong><span>/10</span>
            </p>
            <p className="discovery-score-count" title={count}>
              {layout === "hero" ? count : <><span aria-hidden="true">({formatCompactCount(score.count, layout)})</span><span className="sr-only">{count}</span></>}
            </p>
          </li>
        );
      })}
    </ul>
  );
}
