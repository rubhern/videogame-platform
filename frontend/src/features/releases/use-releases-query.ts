import { keepPreviousData, useQuery } from "@tanstack/react-query";

import { WISHLIST_KEY } from "../wishlist/wishlist-api";
import { getReleases, type ReleasePage, type ReleasesApiError } from "./releases-api";
import { toReleasesQuery, type ReleasesSearch } from "./releases-search";
import { toReleasesViewModel, type ReleasesViewModel } from "./releases-view-model";

export function releasesQueryOptions(search: ReleasesSearch, scope?: number) {
  return {
    queryKey: search.wishedOnly ? [...WISHLIST_KEY, "releases", scope, search] : ["releases", search],
    queryFn: ({ signal }: { signal: AbortSignal }) =>
      getReleases(toReleasesQuery(search), undefined, search.wishedOnly, signal),
    ...(search.wishedOnly ? { gcTime: 0, retry: false } : {}),
  };
}

/**
 * Owns the server state for UC-001. The previous page stays rendered while the next
 * one loads so filters, the evaluated window and pagination remain operable instead
 * of collapsing into the initial loading state on every interaction.
 */
export function useReleasesQuery(search: ReleasesSearch, enabled = true, scope?: number) {
  return useQuery<ReleasePage, ReleasesApiError, ReleasesViewModel>({
    ...releasesQueryOptions(search, scope),
    enabled,
    refetchOnMount: false,
    select: toReleasesViewModel,
    placeholderData: (previous, query) =>
      (search.wishedOnly ? scope !== undefined && query?.queryKey[3] === scope : query?.queryKey[0] === "releases")
        ? keepPreviousData(previous) : undefined,
  });
}
