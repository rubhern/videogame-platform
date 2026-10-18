import type { QueryClient } from "@tanstack/react-query";
import type { LoaderFunctionArgs } from "react-router-dom";

import { gameDetailsQueryOptions } from "../features/game-details/game-details-api";
import { initialMyRatingsQuery, listMyRatings } from "../features/ratings/my-ratings-api";
import { readFeaturedSearch } from "../features/releases/featured-search";
import { readDiscoveryView, readReleasesSearch } from "../features/releases/releases-search";
import { featuredReleasesQueryOptions } from "../features/releases/use-featured-releases-query";
import { releasesQueryOptions } from "../features/releases/use-releases-query";
import { hasSearchableQuery, readGameSearchParams } from "../features/search/game-search-params";
import { gameSearchQueryOptions } from "../features/search/use-game-search-query";
import { getSession } from "../features/session/session-api";
import { SESSION_QUERY_KEY } from "../features/session/use-session";

/** Complete the destination's read before the router restores its entry's scroll offset.
 * TanStack Query still owns data/errors; scroll offsets stay in memory; browser storage stays empty.
 */
export function routeReadiness(queryClient: QueryClient) {
  return async ({ request, params }: LoaderFunctionArgs) => {
    const url = new URL(request.url);
    if (url.pathname === "/") {
      await (readDiscoveryView(url.searchParams) === "featured"
        ? queryClient.prefetchQuery(featuredReleasesQueryOptions(readFeaturedSearch(url.searchParams)))
        : queryClient.prefetchQuery(releasesQueryOptions(readReleasesSearch(url.searchParams))));
    } else if (url.pathname === "/search") {
      const search = readGameSearchParams(url.searchParams);
      if (hasSearchableQuery(search)) await queryClient.prefetchQuery(gameSearchQueryOptions(search));
    } else if (params.gameId) {
      await queryClient.prefetchQuery(gameDetailsQueryOptions(params.gameId));
    } else if (url.pathname === "/mis-puntuaciones") {
      const session = await queryClient.fetchQuery({
        queryKey: SESSION_QUERY_KEY,
        queryFn: ({ signal }) => getSession(undefined, signal),
      }).catch(() => null);
      if (session?.authenticated) {
        // gcTime: 0 is intentional. Do not create an unobserved personal query:
        // its collection timer can race the page's first subscription. Hand the
        // bounded, abortable read to the page, which seeds its observed query.
        return listMyRatings(initialMyRatingsQuery, request.signal).catch(() => null);
      }
    }
    return null;
  };
}
