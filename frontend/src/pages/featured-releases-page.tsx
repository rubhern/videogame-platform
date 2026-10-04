import { Navigate, useSearchParams } from "react-router-dom";

import {
  FeaturedReleasesShell,
  type FeaturedShellState,
} from "../features/releases/featured-releases-shell";
import { readFeaturedSearch } from "../features/releases/featured-search";
import { useFeaturedReleasesQuery } from "../features/releases/use-featured-releases-query";

export function FeaturedReleasesPage() {
  const [searchParams] = useSearchParams();
  const search = readFeaturedSearch(searchParams);
  const query = useFeaturedReleasesQuery(search);

  // A requested month the contract does not accept, malformed or outside the current calendar
  // year (which only the API's trusted date knows), returns to the landing route, the current
  // month, rather than presenting another year's selection or a new error state.
  if (
    searchParams.has("month") &&
    (search.month === null || (query.isError && query.error.code === "FILTER_INVALID"))
  ) {
    return <Navigate replace to="/" />;
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
            message: "No se pudo leer el catálogo local. Inténtalo de nuevo más tarde.",
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
      onRetry={() => {
        void query.refetch();
      }}
      search={search}
      state={state}
    />
  );
}
