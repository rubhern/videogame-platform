import { infiniteQueryOptions, useInfiniteQuery } from "@tanstack/react-query";

import { GAME_RANKING_KEY, rankGames } from "./game-ranking-api";
import { API_RANKING_SOURCE, type RankingSource } from "./ranking-search";

/** One request's worth: the podium plus seventeen places on the first page. */
export const RANKING_PAGE_SIZE = 20;

/**
 * Each source is its own sequence and cache entry, so switching source starts again at its podium
 * and never shows the other ranking in its place. Pages continue by number until the API's
 * counted last page.
 */
export function gameRankingQueryOptions(source: RankingSource) {
  return infiniteQueryOptions({
    queryKey: [...GAME_RANKING_KEY, source],
    queryFn: ({ pageParam, signal }) =>
      rankGames(
        { source: API_RANKING_SOURCE[source], page: pageParam, pageSize: RANKING_PAGE_SIZE },
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
export function useGameRankingQuery(source: RankingSource) {
  return useInfiniteQuery(gameRankingQueryOptions(source));
}
