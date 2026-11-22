import { GameStateButton } from "../library/game-state-button";
import { useGameStates } from "../library/use-game-states";
import { WishlistActions, WishlistButton } from "../wishlist/wishlist-controls";
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
import { CommunityPlatformScores } from "./community-platform-scores";
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
function CommunityScore({ statistics, eligible, title }: {
  statistics: GameDetails["ratingStatistics"];
  eligible: GameDetails["ratingEligibility"]["eligible"];
  title: string;
}) {
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
            <p className="game-reading-empty">Puntuación no disponible</p>
          ) : mean === null ? (
            <p className="game-reading-empty">Sin puntuación todavía</p>
          ) : (
            <>
              <p aria-label={`Puntuación media: ${decimal(mean)} de 10`} className="game-reading-value">
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
          Ahora no tenemos disponibles las estadísticas del juego. Pero puedes revisar todo lo demás
        </p>
      ) : mean === null ? (
        <p className="game-reading-description">
          {eligible === true ? "Sé el primero en puntuar este juego" : "Aún no se puede puntuar"}
        </p>
      ) : (
        <div className="game-reading-description">
          <p>
            Basada en <strong>{available.count.toLocaleString("es-ES")}</strong>{" "}
            {available.count === 1 ? "puntuación" : "puntuaciones"}
          </p>
          <CommunityPlatformScores platforms={available.platforms} title={title} />
        </div>
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

const PERSONAL_RATING_ID = "tu-puntuacion";

/** The player's private state for this game; finishing it offers the rating, never sets it. */
function GameDetailState({ game }: { game: GameDetails }) {
  const library = useGameStates([game.gameId]);
  const offer = game.ratingEligibility.eligible ? (
    <button className="text-link" type="button" onClick={() => {
      const target = document.getElementById(PERSONAL_RATING_ID);
      target?.scrollIntoView({ block: "center" });
      target?.focus({ preventScroll: true });
    }}>
      ¿Qué te ha parecido? Puntúalo
    </button>
  ) : undefined;
  return (
    <div className="game-detail-state">
      <GameStateButton
        csrfToken={library.csrfToken}
        gameId={game.gameId}
        ratingOffer={offer}
        released={game.releases.some((release) => release.status === "released")}
        state={library.states === null ? undefined : library.states.get(game.gameId) ?? null}
        title={game.canonicalTitle}
      />
      {library.failed ? (
        <p className="library-state-error" role="status">
          No se pudo comprobar su estado.{" "}
          <button className="text-link" type="button" onClick={library.retry}>Reintentar</button>
        </p>
      ) : null}
    </div>
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
  const wish = (
    <span className="game-detail-title-wish">
      <WishlistButton gameId={game.gameId} hero title={game.canonicalTitle} />
    </span>
  );
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
        <WishlistActions batches={[[game.gameId]]}>
          <h1 aria-label={game.canonicalTitle} className={`game-detail-title${titleScale}`}>
            <span className="game-wordmark-main">
              {wordmark.main}
              {wordmark.sub === null ? wish : (
                <span className="game-wordmark-delimiter">{wordmark.delimiter}</span>
              )}
            </span>
            {wordmark.sub === null ? null : (
              <>
                {" "}
                <span className={`game-wordmark-sub${subtitleScale}`}>{wordmark.sub}{wish}</span>
              </>
            )}
          </h1>
        </WishlistActions>
        {game.aliases.length > 0 ? (
          <p className="game-detail-aliases">
            También conocido como <span>{game.aliases.join(" · ")}</span>
          </p>
        ) : null}
      </header>

      <div className="game-artwork">
        <CatalogueCover cover={coverForPresentation(game)} />
        <GameDetailState game={game} />
      </div>

      {/* The ratings belong to the whole game. The community's is the product's own reading and
          leads; IGDB's supporting reading sits beside it and the visitor's own follows. IGDB's is
          omitted, never invented, without a score. */}
      <div className="game-scores" data-sources={externalScore === null ? 1 : 2}>
        <CommunityScore
          eligible={game.ratingEligibility.eligible}
          statistics={game.ratingStatistics}
          title={game.canonicalTitle}
        />
        <div className="game-scores-aside">
          {externalScore === null ? null : (
            <ExternalScore score={externalScore} title={game.canonicalTitle} />
          )}
          <div className="game-personal-rating" id={PERSONAL_RATING_ID} tabIndex={-1}>
            <GameRatingPanel game={game} />
          </div>
        </div>
      </div>

      <GameSummary summary={game.summary} />

      <GameInformation game={game} />
    </div>
  );
}
