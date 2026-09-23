import { keepPreviousData, useQuery } from "@tanstack/react-query";

import { searchGames, type GameSearchApiError, type GameSearchPage } from "./game-search-api";
import { toGameSearchQuery, type GameSearchParams } from "./game-search-params";
import { isSuggestionTerm, SUGGESTION_LIMIT, toGameSuggestions, type GameSuggestions } from "./game-suggestions";

/**
 * Owns the typeahead server state for #156.
 *
 * The key is the exact first page of the full search, so a response can only ever render for
 * the term that requested it. Consuming the abort signal lets TanStack Query cancel a request
 * once a newer term supersedes it. The previous page is kept only as a placeholder so the
 * popup can size its loading rows; the popup never renders it as results.
 */
export function useGameSuggestionsQuery(term: string, enabled: boolean) {
  const params: GameSearchParams = { query: term, page: 1, pageSize: SUGGESTION_LIMIT };
  return useQuery<GameSearchPage, GameSearchApiError, GameSuggestions>({
    queryKey: ["game-search", params],
    queryFn: ({ signal }) => searchGames(toGameSearchQuery(params), undefined, signal),
    select: toGameSuggestions,
    enabled: enabled && isSuggestionTerm(term),
    placeholderData: keepPreviousData,
  });
}
