import { GameMeter } from "../../shared/score/game-meter";
import { thermalBand, thermalLabels } from "../../shared/score/thermal-band";
import { readingCount, type RankingReading as Reading } from "./game-ranking-view-model";

const decimal = new Intl.NumberFormat("es-ES", { minimumFractionDigits: 1, maximumFractionDigits: 1 });

/**
 * The score that placed a game, as the instrument reads it: the /10 value, its source and the
 * exact count behind it, which TOPómetro never abbreviates so a one-vote mean states its sample as
 * plainly as its value. Gameómetro's reading keeps its G-meter and temperature; IGDB's keeps neutral
 * ink and its attribution mark, the only link besides the title.
 */
export function RankingReading({
  reading,
  title,
  variant,
}: {
  reading: Reading;
  title: string;
  variant: "podium" | "row";
}) {
  const band = reading.source === "gameometro" ? thermalBand(reading.value) : null;
  const value = decimal.format(reading.value);
  return (
    <div
      className={`ranking-reading ranking-reading-${variant}`}
      data-source={reading.source}
      data-thermal={band ?? undefined}
    >
      {reading.source === "gameometro" ? (
        <GameMeter className="ranking-reading-mark" value={reading.value} />
      ) : (
        <a
          aria-label={`Jugadores IGDB: fuente de la puntuación de ${title}`}
          className="ranking-reading-attribution"
          href={reading.attributionUrl}
          rel="noreferrer"
        >
          <img alt="" className="ranking-reading-mark" height="22" src="/assets/providers/igdb.svg" width="44" />
        </a>
      )}
      <p className="ranking-reading-source">
        <span className="ranking-reading-name">
          {reading.source === "gameometro" ? "Comunidad Gameómetro" : "Jugadores IGDB"}
        </span>
        {band === null ? null : (
          <>
            <span aria-hidden="true" className="ranking-reading-separator"> · </span>
            <b>{thermalLabels[band]}</b>
          </>
        )}
      </p>
      <p aria-label={`${value} de 10`} className="ranking-reading-value">
        <strong>{value}</strong>
        <span>/10</span>
      </p>
      <p className="ranking-reading-count">{readingCount(reading)}</p>
    </div>
  );
}
