import { useQuery } from "@tanstack/react-query";

import type { PlatformContext } from "../platforms/platform-context";
import {
  getPlatformUpcomingHighlights,
  UPCOMING_HIGHLIGHTS_KEY,
  type UpcomingHighlightsApiError,
  type UpcomingHighlightsResponse,
} from "./upcoming-highlights-api";
import {
  toUpcomingHighlightsViewModel,
  type UpcomingHighlightsViewModel,
} from "./upcoming-highlights-view-model";

export function upcomingHighlightsQueryOptions(context: Pick<PlatformContext, "platformIds">) {
  const platformIds = [...context.platformIds];
  return {
    queryKey: [...UPCOMING_HIGHLIGHTS_KEY, platformIds],
    queryFn: ({ signal }: { signal: AbortSignal }) =>
      getPlatformUpcomingHighlights(platformIds, undefined, signal),
  };
}

/** Owns the server state for UC-020; another platform is another read, never the previous one. */
export function useUpcomingHighlightsQuery(context: Pick<PlatformContext, "platformIds">) {
  return useQuery<UpcomingHighlightsResponse, UpcomingHighlightsApiError, UpcomingHighlightsViewModel>({
    ...upcomingHighlightsQueryOptions(context),
    refetchOnMount: false,
    select: toUpcomingHighlightsViewModel,
  });
}
