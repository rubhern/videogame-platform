import { Navigate, useSearchParams } from "react-router-dom";

import {
  FeaturedReleasesShell,
  type FeaturedShellState,
} from "../features/releases/featured-releases-shell";
import {
  featuredSearchPath,
  isCanonicalFeaturedSearch,
  readFeaturedSearch,
} from "../features/releases/featured-search";
import { useFeaturedReleasesQuery } from "../features/releases/use-featured-releases-query";
import { resolvePlatformFilter } from "../features/platform-preferences/interests";
import { useInterests } from "../features/platform-preferences/use-platform-preferences";
import { useInterestsSwitch } from "../features/platform-preferences/interests-switch";

export function FeaturedReleasesPage() {
  const [searchParams] = useSearchParams();
  const search = readFeaturedSearch(searchParams);
  // TOPómetro has no manual platform choice: preferences are on or off in Mi cuenta (#295). The
  // restriction reaches the API, which ranks the restricted candidates; nothing filters the six.
  const interests = useInterests();
  const { enabled } = useInterestsSwitch();
  const platformFilter = resolvePlatformFilter([], enabled, interests);
  const query = useFeaturedReleasesQuery(
    search,
    platformFilter.platformIds,
    interests.status !== "pending",
  );

  // A URL the contract does not accept, malformed or contradictory, takes the canonical form of
  // what it reads as, so the address always names the period shown.
  if (!isCanonicalFeaturedSearch(searchParams)) {
    return <Navigate replace to={featuredSearchPath(search)} />;
  }
  // A period outside the navigation bounds, which only the API's trusted date knows, returns to
  // the current period of the same granularity rather than presenting a new error state.
  if (search.period !== null && query.isError && query.error.code === "FILTER_INVALID") {
    return (
      <Navigate replace to={featuredSearchPath({ granularity: search.granularity, period: null })} />
    );
  }

  let state: FeaturedShellState;
  if (query.isPending) {
    state = { status: "loading" };
  } else if (query.isError) {
    state =
      query.error.code === "CATALOGUE_NOT_READY"
        ? { status: "catalogue-not-ready" }
        : {
            status: "error",
            message: "A Gameómetro le está costando un poco. Prueba más tarde",
            correlationId: query.error.correlationId,
          };
  } else {
    state = {
      status: "ready",
      model: query.data,
      isRefreshing: query.isFetching,
      isPlaceholderData: query.isPlaceholderData,
    };
  }

  return (
    <FeaturedReleasesShell
      interestsMode={platformFilter.mode}
      onRetry={() => {
        void query.refetch();
      }}
      search={search}
      state={state}
    />
  );
}
