import { WISHLIST_KEY } from "../wishlist/wishlist-api";
import { keepPreviousData, useQuery } from "@tanstack/react-query";

import {
  getReleaseCalendar,
  type ReleaseCalendar,
  type ReleaseCalendarQuery,
} from "./release-calendar-api";
import { toReleaseCalendarModel, type ReleaseCalendarModel } from "./release-calendar-model";
import type { ReleasesApiError } from "./releases-api";

export function releaseCalendarQueryOptions(query: ReleaseCalendarQuery, scope?: number) {
  const wishedOnly = scope !== undefined;
  return {
    queryKey: wishedOnly ? [...WISHLIST_KEY, "release-calendar", scope, query] : ["release-calendar", query],
    queryFn: ({ signal }: { signal: AbortSignal }) => getReleaseCalendar(query, undefined, wishedOnly, signal),
    ...(wishedOnly ? { gcTime: 0, retry: false } : {}),
  };
}

/**
 * Owns the server state of the release calendar (#250). The previous month stays in the cache
 * while the next one loads so the controls stay operable; the page shows placeholders rather than
 * presenting it as the new month.
 */
export function useReleaseCalendarQuery(query: ReleaseCalendarQuery, enabled = true, scope?: number) {
  return useQuery<ReleaseCalendar, ReleasesApiError, ReleaseCalendarModel>({
    ...releaseCalendarQueryOptions(query, scope),
    enabled,
    refetchOnMount: false,
    select: toReleaseCalendarModel,
    placeholderData: (previous, query) =>
      (scope === undefined ? query?.queryKey[0] === "release-calendar" : query?.queryKey[3] === scope)
        ? keepPreviousData(previous) : undefined,
  });
}
