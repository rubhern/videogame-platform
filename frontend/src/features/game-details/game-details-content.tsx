import type { CSSProperties, PointerEvent } from "react";

import { GameMeter } from "../../shared/score/game-meter";
import { thermalBand, thermalLabels } from "../../shared/score/thermal-band";
import { CatalogueCover } from "../../shared/ui/catalogue-cover";
import { CinematicStage } from "../../shared/ui/cinematic-stage";
import { GameRatingPanel } from "../ratings/game-rating-panel";
import type { GameDetails } from "./game-details-api";
import { GameInformation } from "./game-information";
import { GameSummary } from "./game-summary";

/** Long canonical titles step down so the hero keeps its shape: one size, then a smaller one. */
const LONG_TITLE = 28;
const LONGEST_TITLE = 64;

function coverForPresentation(game: GameDetails) {
  const cover = game.primaryCover;
  if ("attribution" in cover && cover.attribution) {
    return {
      ...cover,
      kind: "provider" as const,
      attribution: cover.attribution,
    };
  }
  return {
    kind: "fallback" as const,
    url: cover.url,
    alternativeText: cover.alternativeText,
  };
}

// The cover leans toward a mouse pointer and catches its light. Only style properties change, so
// nothing re-renders; touch, pen and reduced motion keep the cover still (see game-details.css).
function tiltCover(event: PointerEvent<HTMLDivElement>) {
  if (event.pointerType !== "mouse") return;
  const box = event.currentTarget.getBoundingClientRect();
  const x = (event.clientX - box.left) / box.width - 0.5;
  const y = (event.clientY - box.top) / box.height - 0.5;
  const { style } = event.currentTarget;
  style.setProperty("--tilt-x", `${(-y * 9).toFixed(2)}deg`);
  style.setProperty("--tilt-y", `${(x * 11).toFixed(2)}deg`);
  style.setProperty("--glare-x", `${((x + 0.5) * 100).toFixed(1)}%`);
  style.setProperty("--glare-y", `${((y + 0.5) * 100).toFixed(1)}%`);
}

function releaseCover(event: PointerEvent<HTMLDivElement>) {
  const { style } = event.currentTarget;
  for (const property of ["--tilt-x", "--tilt-y", "--glare-x", "--glare-y"]) {
    style.removeProperty(property);
  }
}

/**
 * The community reading, the most prominent score on the page: the G-meter beside the mean, its
 * temperature in words and the count. The empty reading uses a quieter, compact treatment.
 */
function CommunityScore({ statistics }: { statistics: GameDetails["ratingStatistics"] }) {
  const available =
    statistics.status === "available" && "count" in statistics ? statistics : null;
  const mean = available !== null && available.count > 0 ? (available.mean ?? null) : null;
  // The temperature interprets the mean the page shows: the contract already rounds it.
  const band = mean === null ? null : thermalBand(mean);
  const shown = mean?.toLocaleString("es-ES", {
    minimumFractionDigits: 1,
    maximumFractionDigits: 1,
  });

  return (
    <section
      aria-labelledby="statistics-title"
      className="game-community-score"
      data-state={available === null ? "unavailable" : mean === null ? "empty" : "rated"}
      data-thermal={band ?? undefined}
    >
      <h2 className="game-score-label" id="statistics-title">
        Puntuación de la comunidad
      </h2>
      <GameMeter className="game-score-meter" value={mean} />
      <div className="game-score-body">
        {available === null ? (
          <div role="status">
            <p className="game-score-empty">Nota no disponible</p>
            <p className="game-score-description">
              Las estadísticas no están disponibles temporalmente. Puedes seguir consultando el
              juego.
            </p>
          </div>
        ) : mean === null ? (
          <>
            <p className="game-score-empty">Sin nota todavía</p>
            <p className="game-score-description">Todavía no hay puntuaciones para este juego.</p>
          </>
        ) : (
          <>
            <div className="game-score-reading">
              <p aria-label={`Nota media: ${shown} de 10`} className="game-score-value">
                {shown}
                <span>/ 10</span>
              </p>
              {band === null ? null : (
                <p className="thermal-tag">
                  <span className="sr-only">Temperatura: </span>
                  {thermalLabels[band]}
                </p>
              )}
            </div>
            <p className="game-score-description">
              Basada en <strong>{available.count.toLocaleString("es-ES")}</strong>{" "}
              {available.count === 1 ? "puntuación" : "puntuaciones"}
            </p>
          </>
        )}
      </div>
    </section>
  );
}

/**
 * The cover and its readings form the game's identity column. The title and summary lead the
 * editorial column, followed by the facts and calendar. Source order keeps the title first on
 * phones and keyboard navigation follows the same reading order at every width.
 */
export function GameDetailsContent({ game }: { game: GameDetails }) {
  const titleScale =
    game.canonicalTitle.length > LONGEST_TITLE
      ? " game-detail-title-longest"
      : game.canonicalTitle.length > LONG_TITLE
        ? " game-detail-title-long"
        : "";

  return (
    <div className="game-detail-layout">
      {/* The stage is lit by the cover this page already shows: no extra request, no new asset,
          and nothing readable depends on it. */}
      <CinematicStage coverUrl={game.primaryCover.url} variant="cover" />

      <header className="game-detail-heading">
        <p className="eyebrow eyebrow-dot">Ficha del catálogo</p>
        <h1 className={`game-detail-title${titleScale}`}>{game.canonicalTitle}</h1>
        {game.aliases.length > 0 ? (
          <p className="game-detail-aliases">
            También conocido como <span>{game.aliases.join(" · ")}</span>
          </p>
        ) : null}
      </header>

      <div className="game-detail-identity">
        <div
          className="game-artwork"
          onPointerLeave={releaseCover}
          onPointerMove={tiltCover}
          style={{ "--cover-art": `url(${JSON.stringify(game.primaryCover.url)})` } as CSSProperties}
        >
          <CatalogueCover cover={coverForPresentation(game)} />
        </div>
        {/* The ratings belong to the whole game and stay immediately below its cover. */}
        <div className="game-scores">
          <CommunityScore statistics={game.ratingStatistics} />
          <GameRatingPanel game={game} />
        </div>
      </div>

      <GameSummary summary={game.summary} />

      <GameInformation game={game} />
    </div>
  );
}
