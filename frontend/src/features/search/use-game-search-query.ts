import { keepPreviousData, useQuery } from "@tanstack/react-query";

import {
  searchGames,
  type GameSearchApiError,
  type GameSearchPage,
} from "./game-search-api";
import {
  hasSearchableQuery,
  toGameSearchQuery,
  type GameSearchParams,
} from "./game-search-params";
import { toGameSearchViewModel, type GameSearchViewModel } from "./game-search-view-model";

/**
 * Owns the server state for UC-002.
 *
 * No request is sent until the visitor has actually supplied a searchable query, and the
 * previous data stays cached, but the shell hides placeholder results while the new URL loads.
 */
export function useGameSearchQuery(params: GameSearchParams) {
  return useQuery<GameSearchPage, GameSearchApiError, GameSearchViewModel>({
    queryKey: ["game-search", params],
    queryFn: () => searchGames(toGameSearchQuery(params)),
    select: toGameSearchViewModel,
    enabled: hasSearchableQuery(params),
    placeholderData: keepPreviousData,
  });
}
