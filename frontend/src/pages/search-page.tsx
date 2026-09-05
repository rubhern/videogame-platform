import { useSearchParams } from "react-router-dom";

import type { GameSearchApiError } from "../features/search/game-search-api";
import {
  hasSearchableQuery,
  readGameSearchParams,
} from "../features/search/game-search-params";
import {
  GameSearchShell,
  type GameSearchShellState,
} from "../features/search/game-search-shell";
import { useGameSearchQuery } from "../features/search/use-game-search-query";

function toFailureState(error: GameSearchApiError): GameSearchShellState {
  if (error.code === "SEARCH_QUERY_INVALID") {
    return { status: "query-invalid" };
  }
  if (error.code === "CATALOGUE_NOT_READY") {
    return { status: "catalogue-not-ready" };
  }
  return {
    status: "error",
    message: "No se pudo leer el catálogo local. Inténtalo de nuevo más tarde.",
    correlationId: error.correlationId,
  };
}

export function SearchPage() {
  const [searchParams] = useSearchParams();
  const params = readGameSearchParams(searchParams);
  const query = useGameSearchQuery(params);

  let state: GameSearchShellState;
  if (params.query === "") {
    state = { status: "prompt" };
  } else if (!hasSearchableQuery(params)) {
    // The contract bound is rejected before a request the API would reject anyway.
    state = { status: "query-invalid" };
  } else if (query.isPending) {
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
    <GameSearchShell
      onRetry={() => {
        void query.refetch();
      }}
      params={params}
      state={state}
    />
  );
}
