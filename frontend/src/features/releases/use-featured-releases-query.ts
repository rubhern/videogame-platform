import { keepPreviousData, useQuery } from "@tanstack/react-query";

import {
  getFeaturedReleases,
  type FeaturedReleasesResponse,
} from "./featured-releases-api";
import {
  toFeaturedReleasesViewModel,
  type FeaturedReleasesViewModel,
} from "./featured-releases-view-model";
import type { FeaturedSearch } from "./featured-search";
import type { ReleasesApiError } from "./releases-api";

/**
 * Owns the server state for UC-010. The previous month stays rendered while another loads, so
 * the month selector stays operable instead of collapsing into the initial loading state.
 */
export function useFeaturedReleasesQuery(search: FeaturedSearch) {
  return useQuery<FeaturedReleasesResponse, ReleasesApiError, FeaturedReleasesViewModel>({
    queryKey: ["featured-releases", search.month],
    queryFn: () => getFeaturedReleases(search.month === null ? {} : { month: search.month }),
    select: toFeaturedReleasesViewModel,
    placeholderData: keepPreviousData,
  });
}
