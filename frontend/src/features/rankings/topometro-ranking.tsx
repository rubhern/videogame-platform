import { Link } from "react-router-dom";

import { PLATFORMS_PATH } from "../platforms/platform-context";
import { GameRankingApiError } from "./game-ranking-api";
import { toRankingView } from "./game-ranking-view-model";
import type { RankingSource } from "./ranking-search";
import { TopometroShell, type TopometroPlatform, type TopometroState } from "./topometro-shell";
import { useGameRankingQuery } from "./use-game-ranking-query";

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
 */
export function TopometroRanking({ source, platform }: { source: RankingSource; platform?: TopometroPlatform }) {
  const platformIds = platform?.context.platformIds ?? [];
  const query = useGameRankingQuery(source, platformIds);

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

  return (
    <TopometroShell
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
    />
  );
}
