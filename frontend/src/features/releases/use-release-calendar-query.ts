import { keepPreviousData, useQuery } from "@tanstack/react-query";

import {
  getReleaseCalendar,
  type ReleaseCalendar,
  type ReleaseCalendarQuery,
} from "./release-calendar-api";
import { toReleaseCalendarModel, type ReleaseCalendarModel } from "./release-calendar-model";
import type { ReleasesApiError } from "./releases-api";

export function releaseCalendarQueryOptions(query: ReleaseCalendarQuery) {
  return {
    queryKey: ["release-calendar", query],
    queryFn: () => getReleaseCalendar(query),
  };
}

/**
 * Owns the server state of the release calendar (#250). The previous month stays in the cache
 * while the next one loads so the controls stay operable; the page shows placeholders rather than
 * presenting it as the new month.
 */
export function useReleaseCalendarQuery(query: ReleaseCalendarQuery, enabled = true) {
  return useQuery<ReleaseCalendar, ReleasesApiError, ReleaseCalendarModel>({
    ...releaseCalendarQueryOptions(query),
    enabled,
    refetchOnMount: false,
    select: toReleaseCalendarModel,
    placeholderData: keepPreviousData,
  });
}
