import { useQuery } from "@tanstack/react-query";

import {
  getReleases,
  type ReleasePage,
  type ReleasesApiError,
  type ReleasesQuery,
} from "./releases-api";
import { toReleaseListItems, type ReleaseListItem } from "./releases-view-model";

const recentReleasesQuery = {
  view: "recent",
  page: 1,
  pageSize: 6,
} satisfies ReleasesQuery;

export function useRecentReleasesQuery() {
  return useQuery<ReleasePage, ReleasesApiError, ReleaseListItem[]>({
    queryKey: ["releases", recentReleasesQuery],
    queryFn: () => getReleases(recentReleasesQuery),
    select: toReleaseListItems,
  });
}
