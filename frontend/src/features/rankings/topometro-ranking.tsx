import { useQueryClient } from "@tanstack/react-query";
import { useRef, useState } from "react";
import { Link } from "react-router-dom";

import { trackEvent } from "../analytics/events";
import { PLATFORMS_PATH } from "../platforms/platform-context";
import { useSession } from "../session/use-session";
import { GAME_RANKING_KEY, GameRankingApiError } from "./game-ranking-api";
import type { GameDetails } from "../game-details/game-details-api";
import { PlatformRatingDialog } from "../ratings/platform-rating-dialog";
import { toRankingView, type RankedGame } from "./game-ranking-view-model";
import { QuickRatePop } from "./quick-rate-pop";
import { QuickRateSheet } from "./quick-rate-sheet";
import { QuickRateStamp } from "./quick-rate-stamp";
import { rankingPath, type RankingSource } from "./ranking-search";
import { TopometroShell, type TopometroPlatform, type TopometroState } from "./topometro-shell";
import { useGameRankingQuery } from "./use-game-ranking-query";
import { useListRatingResume } from "./use-list-rating-resume";
import { useListPersonalRatings } from "./use-ranking-personal-ratings";

function correlationId(error: unknown): string | null {
  return error instanceof GameRankingApiError ? error.correlationId : null;
}

/** A platform that is not on offer, by a stale or invented link; it never becomes a platform. */
export function PlatformRankingUnavailable() {
  return (
    <section aria-labelledby="topometro-title" className="hero-page topometro-page" data-scope="platform">
      <div className="page-container topometro-section">
        <h1 className="page-title" id="topometro-title">TOPómetro</h1>
        <div className="notice notice-empty ranking-notice" role="status">
          <p className="notice-kicker">Plataforma no disponible</p>
          <h2>Esa plataforma no tiene juegos en Gameómetro ahora mismo</h2>
          <Link className="button button-primary" to={PLATFORMS_PATH}>
            Ver todas las plataformas
          </Link>
        </div>
      </div>
    </section>
  );
}

/**
 * One TOPómetro, global or on a platform (#386, #387): the source's ranked sequence read page by
 * page and mapped to the shell's states. The platform scope is the server's; nothing here filters.
 *
 * <p>Every ranked game also carries the player's own mark, read for each loaded page in one
 * request (#574). The mark opens a one-press score card; from it, or from **Puntuar varios**,
 * the run sheet rates the list game after game, and either hands a game over to the platform
 * sheet. The ranking is public and never varies on the session: the marks are layered on top of
 * it. Votes cast in the run sheet refresh the ranking once, when it closes, so places never shift
 * under the player while rating.
 */

type Rating =
  | { mode: "pop"; game: RankedGame; anchor: HTMLElement | null }
  /** The run holds the list as it was when it opened, so a refreshed page never reorders it. */
  | { mode: "run"; startGameId: string; games: readonly RankedGame[] }
  | { mode: "platforms"; gameId: string; details: GameDetails };
export function TopometroRanking({ source, platform }: { source: RankingSource; platform?: TopometroPlatform }) {
  const platformIds = platform?.context.platformIds ?? [];
  const query = useGameRankingQuery(source, platformIds);
  const queryClient = useQueryClient();
  const session = useSession();
  const csrfToken = session.data?.authenticated === true ? session.data.csrfToken : null;
  const pages = (query.data?.pages ?? []).map((page) => page.items.map((item) => item.gameId));
  const personalOf = useListPersonalRatings(pages, csrfToken !== null);
  const stamps = useRef(new Map<string, HTMLButtonElement>());
  const [rating, setRating] = useState<Rating | null>(null);
  // A visitor who rates here signs in and comes back to this same list.
  const listPath = rankingPath(source, platform?.context.key);
  const loadedGames = (query.data?.pages ?? []).flatMap((page) => page.items);
  const resume = useListRatingResume({
    csrfToken,
    sessionPending: session.isPending,
    titleOf: (gameId) => loadedGames.find((item) => item.gameId === gameId)?.canonicalTitle ?? null,
  });

  if (
    platform !== undefined &&
    query.error instanceof GameRankingApiError &&
    query.error.code === "PLATFORM_NOT_SUPPORTED"
  ) {
    return <PlatformRankingUnavailable />;
  }

  let state: TopometroState;
  if (query.isPending) {
    state = { status: "loading" };
  } else if (query.isError && query.data === undefined) {
    state = query.error instanceof GameRankingApiError && query.error.code === "CATALOGUE_NOT_READY"
      ? { status: "catalogue-not-ready" }
      : { status: "error", correlationId: correlationId(query.error) };
  } else {
    const last = query.data?.pages.at(-1)?.page;
    state = {
      status: "ready",
      view: toRankingView(query.data?.pages ?? [], source, platform === undefined ? undefined : { platformIds }),
      next: query.hasNextPage && last !== undefined
        ? { from: last.number * last.size + 1, to: Math.min((last.number + 1) * last.size, last.totalItems) }
        : null,
      loadingMore: query.isFetchingNextPage,
      moreFailed: query.isFetchNextPageError ? { correlationId: correlationId(query.error) } : null,
    };
  }

  const games = state.status === "ready" ? [...state.view.podium, ...state.view.rows] : [];

  /** Back on the list at the game's own stamp, the ranking read again if a vote changed it. */
  function returnTo(gameId: string, changed: boolean) {
    setRating(null);
    stamps.current.get(gameId)?.focus();
    if (changed) void queryClient.invalidateQueries({ queryKey: GAME_RANKING_KEY });
  }

  function startRun(startGameId?: string) {
    // Without a game, the run starts at the first one the player has not rated yet.
    const firstOpen = games.find((game) => {
      const own = personalOf(game.gameId);
      return !(own.status === "known" && own.rating !== null);
    });
    const start = startGameId ?? firstOpen?.gameId ?? games[0]?.gameId;
    if (start !== undefined) setRating({ mode: "run", startGameId: start, games });
  }

  const runControl =
    games.length === 0 ? null : (
      <button
        className="quick-rate-run"
        onClick={() => {
          trackEvent("quick_rating", { list: "topometro", action: "run" });
          startRun();
        }}
        type="button"
      >
        <span aria-hidden="true" className="quick-rate-run-mark" />
        Puntuar varios
      </button>
    );

  return (
    <>
      <TopometroShell
        renderStamp={(game, variant) => (
          <QuickRateStamp
            onOpen={() => {
              trackEvent("quick_rating", { list: "topometro", action: "open" });
              setRating({ mode: "pop", game, anchor: stamps.current.get(game.gameId) ?? null });
            }}
            ref={(element) => {
              if (element === null) stamps.current.delete(game.gameId);
              else stamps.current.set(game.gameId, element);
            }}
            state={personalOf(game.gameId)}
            title={game.title}
            variant={variant}
          />
        )}
        onLoadMore={() => {
          // A page already being read is never cancelled and requested again.
          void query.fetchNextPage({ cancelRefetch: false });
        }}
        onRetry={() => {
          void query.refetch();
        }}
        platform={platform}
        source={source}
        state={state}
        notice={
          resume.notice === null ? null : (
            <p className="quick-rate-notice" data-tone={resume.notice.tone} role="status">
              {resume.notice.text}
            </p>
          )
        }
        tools={runControl}
      />
      {rating?.mode === "pop" ? (
        <QuickRatePop
          anchor={rating.anchor}
          csrfToken={csrfToken}
          game={rating.game}
          onClose={(changed) => returnTo(rating.game.gameId, changed)}
          onPlatforms={(details) => setRating({ mode: "platforms", gameId: rating.game.gameId, details })}
          onRun={() => startRun(rating.game.gameId)}
          returnTo={listPath}
          state={personalOf(rating.game.gameId)}
        />
      ) : null}
      {rating?.mode === "run" ? (
        <QuickRateSheet
          csrfToken={csrfToken}
          games={rating.games}
          onClose={returnTo}
          personalOf={personalOf}
          returnTo={listPath}
          startGameId={rating.startGameId}
        />
      ) : null}
      {rating?.mode === "platforms" ? (
        <PlatformRatingDialog
          csrfToken={csrfToken}
          game={rating.details}
          // The platform command reads the ranking again itself once it saves.
          onClose={() => returnTo(rating.gameId, false)}
          rating={(() => {
            const own = personalOf(rating.gameId);
            return own.status === "known" ? own.rating : null;
          })()}
          returnTo={listPath}
        />
      ) : null}
      {resume.recovered === null ? null : (
        <PlatformRatingDialog
          csrfToken={csrfToken}
          game={resume.recovered.details}
          onClose={() => {
            const { gameId } = resume.recovered?.details ?? { gameId: "" };
            resume.clearRecovered();
            stamps.current.get(gameId)?.focus();
          }}
          rating={resume.recovered.rating}
          recoveredPlatforms={resume.recovered.platforms}
          returnTo={listPath}
        />
      )}
    </>
  );
}
