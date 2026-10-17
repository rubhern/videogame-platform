import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect } from "react";

import {
  getFeaturedReleases,
  type FeaturedReleasesQuery,
  type FeaturedReleasesResponse,
} from "./featured-releases-api";
import {
  toFeaturedReleasesViewModel,
  type FeaturedReleasesViewModel,
} from "./featured-releases-view-model";
import type { FeaturedSearch } from "./featured-search";
import { msUntilNextMadridDay } from "./period-timeline";
import type { ReleasesApiError } from "./releases-api";

/** The request names the granularity and, for a period other than the current one, the period. */
function toQuery(search: FeaturedSearch): FeaturedReleasesQuery {
  if (search.period === null) return { granularity: search.granularity };
  return search.granularity === "week" ? { week: search.period } : { month: search.period };
}

/** Read again a few seconds past midnight, so the server's own clock has reached the new day. */
const NEW_DAY_GRACE_MS = 5_000;

/**
 * Owns the server state for UC-010. The previous period stays rendered while another loads, so
 * the period selector stays in place instead of collapsing into the initial loading state.
 *
 * The temporal G reads "today" from the response's evaluated date. While the page stays open
 * across midnight in `Europe/Madrid`, the featured releases are read again, so the scale (and the
 * landing route's period) follow the API's new day. The browser's clock only times that read; the
 * new response's evaluated date remains the authority.
 */
export function useFeaturedReleasesQuery(search: FeaturedSearch) {
  const queryClient = useQueryClient();

  useEffect(() => {
    let timer: ReturnType<typeof setTimeout>;
    const scheduleNextDay = () => {
      timer = setTimeout(() => {
        void queryClient.invalidateQueries({ queryKey: ["featured-releases"] });
        scheduleNextDay();
      }, msUntilNextMadridDay(new Date()) + NEW_DAY_GRACE_MS);
    };
    scheduleNextDay();
    return () => clearTimeout(timer);
  }, [queryClient]);

  return useQuery<FeaturedReleasesResponse, ReleasesApiError, FeaturedReleasesViewModel>({
    queryKey: ["featured-releases", search.granularity, search.period],
    queryFn: () => getFeaturedReleases(toQuery(search)),
    select: toFeaturedReleasesViewModel,
    placeholderData: keepPreviousData,
  });
}
