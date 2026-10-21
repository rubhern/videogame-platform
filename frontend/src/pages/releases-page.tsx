import { useLocation, useSearchParams } from "react-router-dom";

import { readDayFrame } from "../features/releases/day-bar";

import { toFailureState } from "../features/releases/releases-failure";
import { readReleasesSearch } from "../features/releases/releases-search";
import {
  ReleasesShell,
  type ReleasesShellState,
} from "../features/releases/releases-shell";
import { useReleasesQuery } from "../features/releases/use-releases-query";
import { resolvePlatformFilter } from "../features/platform-preferences/interests";
import { useInterests } from "../features/platform-preferences/use-platform-preferences";
import { useInterestsSwitch } from "../features/platform-preferences/interests-switch";
import { explorerRequestSearch } from "../features/platform-preferences/discovery-requests";

export function ReleasesPage() {
  const [searchParams] = useSearchParams();
  const location = useLocation();
  const search = readReleasesSearch(searchParams);
  // Preferred platforms resolve in the browser into the explicit filter the public read carries,
  // and the read waits for them, so a first view never shows every platform and then narrows.
  const interests = useInterests();
  const { enabled } = useInterestsSwitch();
  const platformFilter = resolvePlatformFilter(search.platformIds, enabled, interests);
  const query = useReleasesQuery(
    explorerRequestSearch(search, enabled, interests),
    interests.status !== "pending",
  );

  let state: ReleasesShellState;
  if (query.isPending) {
    state = { status: "loading" };
  } else if (query.isError) {
    state = toFailureState(query.error);
  } else {
    state = {
      status: "ready",
      model: query.data,
      isRefreshing: query.isFetching,
      isPlaceholderData: query.isPlaceholderData,
    };
  }

  return (
    <ReleasesShell
      interests={interests}
      keptFrame={readDayFrame(location.state)}
      platformFilter={platformFilter}
      onRetry={() => {
        void query.refetch();
      }}
      search={search}
      state={state}
    />
  );
}
