import { keepPreviousData, useQuery } from "@tanstack/react-query";

import type { PlatformContext } from "../platforms/platform-context";
import {
  getPlatformGames,
  PLATFORM_GAMES_KEY,
  type PlatformGamesApiError,
  type PlatformGamesPage,
} from "./platform-games-api";
import { toPlatformGamesQuery, type PlatformCatalogueSearch } from "./platform-catalogue-search";
import {
  toPlatformCatalogueViewModel,
  type PlatformCatalogueViewModel,
} from "./platform-catalogue-view-model";

export function platformGamesQueryOptions(
  search: PlatformCatalogueSearch,
  context: Pick<PlatformContext, "platformIds">,
) {
  const query = toPlatformGamesQuery(search, context);
  return {
    queryKey: [...PLATFORM_GAMES_KEY, query],
    queryFn: ({ signal }: { signal: AbortSignal }) => getPlatformGames(query, undefined, signal),
  };
}

/**
 * Owns the server state for UC-019. The previous page stays rendered while the next one loads, so
 * the controls stay operable instead of collapsing into the first loading state on every choice.
 */
export function usePlatformGamesQuery(
  search: PlatformCatalogueSearch,
  context: Pick<PlatformContext, "platformIds"> | null,
) {
  return useQuery<PlatformGamesPage, PlatformGamesApiError, PlatformCatalogueViewModel>({
    ...platformGamesQueryOptions(search, context ?? { platformIds: [] }),
    enabled: context !== null,
    refetchOnMount: false,
    select: toPlatformCatalogueViewModel,
    placeholderData: keepPreviousData,
  });
}
