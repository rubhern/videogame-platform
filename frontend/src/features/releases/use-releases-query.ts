import { keepPreviousData, useQuery } from "@tanstack/react-query";

import { getReleases, type ReleasePage, type ReleasesApiError } from "./releases-api";
import { toReleasesQuery, type ReleasesSearch } from "./releases-search";
import { toReleasesViewModel, type ReleasesViewModel } from "./releases-view-model";

/**
 * Owns the server state for UC-001. The previous page stays rendered while the next
 * one loads so filters, the evaluated window and pagination remain operable instead
 * of collapsing into the initial loading state on every interaction.
 */
export function useReleasesQuery(search: ReleasesSearch) {
  return useQuery<ReleasePage, ReleasesApiError, ReleasesViewModel>({
    queryKey: ["releases", search],
    queryFn: () => getReleases(toReleasesQuery(search)),
    select: toReleasesViewModel,
    placeholderData: keepPreviousData,
  });
}
