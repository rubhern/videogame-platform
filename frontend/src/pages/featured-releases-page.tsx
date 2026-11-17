import { resolvePlatformContext } from "../features/platforms/platform-context";
import { useEffect } from "react";
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
import { useInterests, usePlatformList } from "../features/platform-preferences/use-platform-preferences";
import { useInterestsSwitch } from "../features/platform-preferences/interests-switch";

export function FeaturedReleasesPage() {
  const [searchParams] = useSearchParams();
  const search = readFeaturedSearch(searchParams);
  useEffect(() => {
    const previousTitle = document.title;
    document.title = `Destacados ${search.granularity === "week" ? "de la semana" : "del mes"} · Gameómetro`;
    return () => { document.title = previousTitle; };
  }, [search.granularity]);
  const interests = useInterests();
  const { enabled } = useInterestsSwitch();
  const directory = usePlatformList(search.platform !== undefined);
  const platform = search.platform === undefined ? null : resolvePlatformContext(search.platform, directory.data ?? []);
  const platformFilter = resolvePlatformFilter(platform?.platformIds ?? [], enabled, interests);
  const query = useFeaturedReleasesQuery(
    search,
    platformFilter.platformIds,
    interests.status !== "pending" && (search.platform === undefined || platform !== null || !directory.isPending),
  );

  // A URL the contract does not accept, malformed or contradictory, takes the canonical form of
  // what it reads as, so the address always names the period shown.
  if (search.platform !== undefined && platform === null && directory.isSuccess) {
    return <Navigate replace to={featuredSearchPath({ granularity: search.granularity, period: search.period })} />;
  }
  if (!isCanonicalFeaturedSearch(searchParams)) {
    return <Navigate replace to={featuredSearchPath(search)} />;
  }
  // A period outside the navigation bounds, which only the API's trusted date knows, returns to
  // the current period of the same granularity rather than presenting a new error state.
  if (search.period !== null && query.isError && query.error.code === "FILTER_INVALID") {
    return (
      <Navigate replace to={featuredSearchPath({ ...search, period: null })} />
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
      platform={platform ?? undefined}
      search={search}
      state={state}
    />
  );
}
