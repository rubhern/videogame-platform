import { infiniteQueryOptions, useInfiniteQuery } from "@tanstack/react-query";

import { GAME_RANKING_KEY, rankGames } from "./game-ranking-api";
import { API_RANKING_SOURCE, type RankingSource } from "./ranking-search";

/** One request's worth: the podium plus seventeen places on the first page. */
export const RANKING_PAGE_SIZE = 20;

/**
 * Each source, globally or on one platform scope, is its own sequence and cache entry, so
 * switching source or platform starts again at its podium and never shows another ranking in its
 * place. The server ranks the scope; pages continue by number until the API's counted last page.
 */
export function gameRankingQueryOptions(source: RankingSource, platformIds: readonly string[] = []) {
  const scoped = platformIds.length > 0;
  return infiniteQueryOptions({
    queryKey: scoped
      ? [...GAME_RANKING_KEY, source, { platformIds: [...platformIds] }]
      : [...GAME_RANKING_KEY, source],
    queryFn: ({ pageParam, signal }) =>
      rankGames(
        {
          source: API_RANKING_SOURCE[source],
          ...(scoped ? { platformIds: [...platformIds] } : {}),
          page: pageParam,
          pageSize: RANKING_PAGE_SIZE,
        },
        undefined,
        signal,
      ),
    initialPageParam: 1,
    getNextPageParam: (last) =>
      last.page.number < last.page.totalPages ? last.page.number + 1 : undefined,
    // A return from a game page shortly after reuses the loaded pages and their height as they
    // were; a rating command invalidates them.
    staleTime: 60_000,
  });
}

/** Failures surface as `GameRankingApiError`; anything else is an unexpected client fault. */
export function useGameRankingQuery(source: RankingSource, platformIds: readonly string[] = []) {
  return useInfiniteQuery(gameRankingQueryOptions(source, platformIds));
}
