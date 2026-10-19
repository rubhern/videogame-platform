import { toWordmark } from "../../shared/catalogue/title-wordmark";
import {
  toExternalPlayerScore,
  type ExternalPlayerScore,
} from "../../shared/score/external-player-score";
import { GameMeter } from "../../shared/score/game-meter";
import { thermalBand, thermalLabels } from "../../shared/score/thermal-band";
import { CatalogueCover } from "../../shared/ui/catalogue-cover";
import { StageLight } from "../../shared/ui/stage-light";
import { GameRatingPanel } from "../ratings/game-rating-panel";
import type { GameDetails } from "./game-details-api";
import { GameInformation } from "./game-information";
import { GameSummary } from "./game-summary";

/**
 * The wordmark's name steps down so the hero keeps its shape: one size, then a smaller one. Only
 * the name decides it, so a short name keeps its full size above a long subtitle, which steps
 * down on its own.
 */
const LONG_NAME = 20;
const LONGEST_NAME = 34;
const LONG_SUBTITLE = 40;

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

const decimal = (value: number) =>
  value.toLocaleString("es-ES", { minimumFractionDigits: 1, maximumFractionDigits: 1 });

/**
 * The community reading, the most prominent score on the page: the G-meter beside the mean, its
 * temperature in words and the count. The empty reading is quieter and smaller.
 */
function CommunityScore({ statistics }: { statistics: GameDetails["ratingStatistics"] }) {
  const available =
    statistics.status === "available" && "count" in statistics ? statistics : null;
  const mean = available !== null && available.count > 0 ? (available.mean ?? null) : null;
  // The temperature interprets the mean the page shows: the contract already rounds it.
  const band = mean === null ? null : thermalBand(mean);

  return (
    <section
      aria-labelledby="statistics-title"
      className="game-reading game-community-score"
      data-state={available === null ? "unavailable" : mean === null ? "empty" : "rated"}
      data-thermal={band ?? undefined}
    >
      <h2 className="game-reading-label" id="statistics-title">
        Puntuación de la comunidad
      </h2>
      <div className="game-reading-figure">
        <GameMeter className="game-reading-meter" value={mean} />
        <div className="game-reading-body">
          {available === null ? (
            <p className="game-reading-empty">Nota no disponible</p>
          ) : mean === null ? (
            <p className="game-reading-empty">Sin nota todavía</p>
          ) : (
            <>
              <p aria-label={`Nota media: ${decimal(mean)} de 10`} className="game-reading-value">
                {decimal(mean)}
                <span>/10</span>
              </p>
              {band === null ? null : (
                <p className="game-reading-band">
                  <span className="sr-only">Temperatura: </span>
                  {thermalLabels[band]}
                </p>
              )}
            </>
          )}
        </div>
      </div>
      {/* The count stays as readable as the mean, so a reading from one vote says so plainly. */}
      {available === null ? (
        <p className="game-reading-description" role="status">
          Las estadísticas no están disponibles temporalmente. Puedes seguir consultando el juego.
        </p>
      ) : mean === null ? (
        <p className="game-reading-description">Todavía no hay puntuaciones para este juego.</p>
      ) : (
        <p className="game-reading-description">
          Basada en <strong>{available.count.toLocaleString("es-ES")}</strong>{" "}
          {available.count === 1 ? "puntuación" : "puntuaciones"}
        </p>
      )}
    </section>
  );
}

const exactCount = new Intl.NumberFormat("es-ES", { useGrouping: "always" });

/**
 * The IGDB players' reading supports the community's: the provider's mark, linked to this game's
 * source work, in the meter's place, then the mean on its /10 scale and the exact count, all at a
 * smaller size. It is a separate source with no Gameómetro temperature.
 */
function ExternalScore({ score, title }: { score: ExternalPlayerScore; title: string }) {
  return (
    <section aria-labelledby="external-score-title" className="game-reading game-external-score">
      <h2 className="game-reading-label" id="external-score-title">
        Jugadores IGDB
      </h2>
      <div className="game-reading-figure">
        <div className="game-reading-meter game-external-score-mark">
          <a
            aria-label={`Jugadores IGDB: fuente de la puntuación de ${title}`}
            href={score.attributionUrl}
          >
            <img alt="" height="41" src="/assets/providers/igdb.svg" width="82" />
          </a>
        </div>
        <div className="game-reading-body">
          <p aria-label={`Nota media: ${decimal(score.value)} de 10`} className="game-reading-value">
            {decimal(score.value)}
            <span>/10</span>
          </p>
        </div>
      </div>
      <p className="game-reading-description">
        Basada en <strong>{exactCount.format(score.count)}</strong>{" "}
        {score.count === 1 ? "valoración" : "valoraciones"}
      </p>
    </section>
  );
}

/**
 * The game's page as a stage lit by its own cover. The canonical title is set as Gameómetro's
 * wordmark, the cover stands beside it, and the readings follow, the community's leading; the
 * summary and the information close the page. Source order is the phone's reading order —
 * title, cover, readings, summary, information — so keyboard navigation follows it everywhere.
 */
export function GameDetailsContent({ game }: { game: GameDetails }) {
  const externalScore = toExternalPlayerScore(game.externalScore);
  const wordmark = toWordmark(game.canonicalTitle);
  const titleScale =
    wordmark.main.length > LONGEST_NAME
      ? " game-detail-title-longest"
      : wordmark.main.length > LONG_NAME
        ? " game-detail-title-long"
        : "";
  const subtitleScale =
    wordmark.sub !== null && wordmark.sub.length > LONG_SUBTITLE ? " game-wordmark-sub-long" : "";

  return (
    <div className="game-detail-layout">
      {/* The stage is lit by the cover this page already shows: no extra request, no new asset,
          and nothing readable depends on it. */}
      <StageLight images={[game.primaryCover.url]} />

      <header className="game-detail-heading">
        <p className="eyebrow">Ficha del juego</p>
        <h1 className={`game-detail-title${titleScale}`}>
          <span className="game-wordmark-main">
            {wordmark.main}
            {wordmark.sub === null ? null : (
              <span className="game-wordmark-delimiter">{wordmark.delimiter}</span>
            )}
          </span>
          {wordmark.sub === null ? null : (
            <>
              {" "}
              <span className={`game-wordmark-sub${subtitleScale}`}>{wordmark.sub}</span>
            </>
          )}
        </h1>
        {game.aliases.length > 0 ? (
          <p className="game-detail-aliases">
            También conocido como <span>{game.aliases.join(" · ")}</span>
          </p>
        ) : null}
      </header>

      <div className="game-artwork">
        <CatalogueCover cover={coverForPresentation(game)} />
      </div>

      {/* The ratings belong to the whole game. The community's is the product's own reading and
          leads; IGDB's supporting reading sits beside it and the visitor's own follows. IGDB's is
          omitted, never invented, without a score. */}
      <div className="game-scores" data-sources={externalScore === null ? 1 : 2}>
        <CommunityScore statistics={game.ratingStatistics} />
        <div className="game-scores-aside">
          {externalScore === null ? null : (
            <ExternalScore score={externalScore} title={game.canonicalTitle} />
          )}
          <GameRatingPanel game={game} />
        </div>
      </div>

      <GameSummary summary={game.summary} />

      <GameInformation game={game} />
    </div>
  );
}
