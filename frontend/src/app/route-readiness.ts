import type { QueryClient } from "@tanstack/react-query";
import { redirect, replace, type LoaderFunctionArgs } from "react-router-dom";

import { gameDetailsQueryOptions } from "../features/game-details/game-details-api";
import { getRatingInsights } from "../features/ratings/rating-insights-api";
import { initialMyRatingsQuery, listMyRatings } from "../features/ratings/my-ratings-api";
import { FEATURED_PATH, readFeaturedSearch } from "../features/releases/featured-search";
import {
  legacyReleasesPath,
  readReleasesSearch,
  RELEASES_PATH,
} from "../features/releases/releases-search";
import { featuredReleasesQueryOptions } from "../features/releases/use-featured-releases-query";
import { releasesQueryOptions } from "../features/releases/use-releases-query";
import {
  CALENDAR_PATH,
  readCalendarSearch,
  toCalendarQuery,
} from "../features/releases/release-calendar-search";
import { releaseCalendarQueryOptions } from "../features/releases/use-release-calendar-query";
import { resolvePlatformFilter } from "../features/platform-preferences/interests";
import { hasSearchableQuery, readGameSearchParams } from "../features/search/game-search-params";
import { gameSearchQueryOptions } from "../features/search/use-game-search-query";
import { readPlatformRankingKey, readRankingSource, TOPOMETRO_PATH } from "../features/rankings/ranking-search";
import { gameRankingQueryOptions } from "../features/rankings/use-game-ranking-query";
import { getSession } from "../features/session/session-api";
import { SESSION_QUERY_KEY } from "../features/session/use-session";
import {
  explorerRequestSearch,
  featuredRequestPlatforms,
} from "../features/platform-preferences/discovery-requests";
import {
  loadInterests,
  PLATFORM_PREFERENCES_QUERY_KEY,
  platformListQueryOptions,
} from "../features/platform-preferences/use-platform-preferences";
import {
  featuredPlatforms,
  PLATFORMS_PATH,
  resolvePlatformContext,
} from "../features/platforms/platform-context";
import {
  readPlatformCatalogueKey,
  readPlatformCatalogueSearch,
} from "../features/platform-catalogue/platform-catalogue-search";
import { platformGamesQueryOptions } from "../features/platform-catalogue/use-platform-games-query";
import { readPlatformUpcomingKey } from "../features/upcoming-highlights/upcoming-highlights-path";
import { upcomingHighlightsQueryOptions } from "../features/upcoming-highlights/use-upcoming-highlights-query";
import type { PlatformPreferences } from "../features/platform-preferences/platform-preferences-api";
import { welcomePath } from "../features/platform-preferences/welcome-return";
import type { InterestsSwitch } from "../features/platform-preferences/interests-switch";

/**
 * A platform destination's context: a featured family by its key, any other platform through the
 * public directory, which is read either way for **Cambiar plataforma**.
 */
async function readPlatformContext(queryClient: QueryClient, key: string) {
  const directory = await queryClient.fetchQuery(platformListQueryOptions()).catch(() => undefined);
  const featured = featuredPlatforms.find(
    (platform) => platform.key === key || platform.platformIds.includes(key),
  );
  return featured ?? (directory === undefined ? null : resolvePlatformContext(key, directory));
}

/** Complete the destination's read before the router restores its entry's scroll offset.
 * TanStack Query still owns data/errors; scroll offsets stay in memory; browser storage stays empty.
 *
 * Release discovery first resolves the visitor's preferred platforms (#295), so its read is already
 * the personal one. A signed-in visitor who has never chosen is offered the first-run choice once
 * per document, on the way into discovery only: rating returns land on a game page and personal or
 * search pages never redirect. The destination travels with the offer and is restored after it.
 */
export function routeReadiness(queryClient: QueryClient, interestsSwitch: InterestsSwitch) {
  let welcomeOffered = false;
  return async ({ request, params }: LoaderFunctionArgs) => {
    const url = new URL(request.url);
    // Resolve the compatibility entry before hydration, so a pending destination keeps its
    // loading surface instead of mounting a redirect-only page with an empty outlet.
    if (url.pathname === "/") {
      return replace(legacyReleasesPath(url.searchParams) ?? `${FEATURED_PATH}${url.search}`);
    }
    if (url.pathname === FEATURED_PATH || url.pathname === RELEASES_PATH || url.pathname === CALENDAR_PATH) {
      const interests = await loadInterests(queryClient);
      const preferences = queryClient.getQueryData<PlatformPreferences>(PLATFORM_PREFERENCES_QUERY_KEY);
      if (!welcomeOffered && preferences?.configured === false) {
        welcomeOffered = true;
        return redirect(welcomePath(url.pathname + url.search));
      }
      if (url.pathname === FEATURED_PATH) {
        const search = readFeaturedSearch(url.searchParams);
        await queryClient.prefetchQuery(
          featuredReleasesQueryOptions(
            search,
            featuredRequestPlatforms(interestsSwitch.enabled(), interests),
          ),
        );
      } else if (url.pathname === CALENDAR_PATH) {
        const search = readCalendarSearch(url.searchParams);
        const { platformIds } = resolvePlatformFilter(
          search.platformIds,
          interestsSwitch.enabled(),
          interests,
        );
        await queryClient.prefetchQuery(releaseCalendarQueryOptions(toCalendarQuery(search, platformIds)));
      } else {
        await queryClient.prefetchQuery(
          releasesQueryOptions(
            explorerRequestSearch(
              readReleasesSearch(url.searchParams),
              interestsSwitch.enabled(),
              interests,
            ),
          ),
        );
      }
    } else if (url.pathname === TOPOMETRO_PATH) {
      // The global ranking never reads preferences or the session (#386). Pages already loaded in
      // this document are kept, so Back and the game page's way back restore their height.
      await queryClient
        .ensureInfiniteQueryData(gameRankingQueryOptions(readRankingSource(url.searchParams)))
        .catch(() => undefined);
    } else if (readPlatformCatalogueKey(url.pathname) !== null) {
      // A platform's catalogue (#389) reads the directory to resolve a platform outside the
      // featured four, then the page the URL names, so Back restores its height.
      const context = await readPlatformContext(queryClient, readPlatformCatalogueKey(url.pathname) ?? "");
      if (context !== null) {
        await queryClient
          .prefetchQuery(
            platformGamesQueryOptions(readPlatformCatalogueSearch(url.searchParams), context),
          )
          .catch(() => undefined);
      }
    } else if (readPlatformRankingKey(url.pathname) !== null) {
      // A platform's TOPómetro (#387) resolves its platform the same way; loaded pages are kept,
      // as on the global ranking, so Back and the game page's way back restore their height.
      const context = await readPlatformContext(queryClient, readPlatformRankingKey(url.pathname) ?? "");
      if (context !== null) {
        await queryClient
          .ensureInfiniteQueryData(
            gameRankingQueryOptions(readRankingSource(url.searchParams), context.platformIds),
          )
          .catch(() => undefined);
      }
    } else if (readPlatformUpcomingKey(url.pathname) !== null) {
      // A platform's upcoming highlights (#388) resolve their platform the same way, then read
      // the one bounded ranking, so Back and the game page's way back restore their height.
      const context = await readPlatformContext(queryClient, readPlatformUpcomingKey(url.pathname) ?? "");
      if (context !== null) {
        await queryClient.prefetchQuery(upcomingHighlightsQueryOptions(context)).catch(() => undefined);
      }
    } else if (url.pathname === PLATFORMS_PATH) {
      // The public directory (#287): Otras and its chosen card restore their height on Back.
      await queryClient.prefetchQuery(platformListQueryOptions());
    } else if (url.pathname === "/search") {
      const search = readGameSearchParams(url.searchParams);
      if (hasSearchableQuery(search)) await queryClient.prefetchQuery(gameSearchQueryOptions(search));
    } else if (params.gameId) {
      await queryClient.prefetchQuery(gameDetailsQueryOptions(params.gameId));
    } else if (url.pathname === "/mis-puntuaciones" || url.pathname === "/mis-puntuaciones/estadisticas") {
      const session = await queryClient.fetchQuery({
        queryKey: SESSION_QUERY_KEY,
        queryFn: ({ signal }) => getSession(undefined, signal),
      }).catch(() => null);
      if (session?.authenticated) {
        // gcTime: 0 is intentional. Do not create an unobserved personal query:
        // its collection timer can race the page's first subscription. Hand the
        // bounded, abortable read to the page, which seeds its observed query.
        if (url.pathname === "/mis-puntuaciones/estadisticas") {
          const data = await getRatingInsights(request.signal).catch(() => null);
          return data ? { data, sessionProof: session.csrfToken } : null;
        }
        return listMyRatings(initialMyRatingsQuery, request.signal).catch(() => null);
      }
    }
    return null;
  };
}
