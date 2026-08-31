import { useSearchParams } from "react-router-dom";

import type { ReleasesApiError } from "../features/releases/releases-api";
import { readReleasesSearch } from "../features/releases/releases-search";
import {
  ReleasesShell,
  type ReleasesShellState,
} from "../features/releases/releases-shell";
import { useReleasesQuery } from "../features/releases/use-releases-query";

const unsupportedFilterMessages: Partial<Record<ReleasesApiError["code"], string>> = {
  PLATFORM_NOT_SUPPORTED: "La plataforma solicitada no existe en el catálogo local.",
  REGION_NOT_SUPPORTED: "La región solicitada no existe en el catálogo local.",
  FILTER_INVALID: "Los filtros solicitados no son válidos.",
};

function toFailureState(error: ReleasesApiError): ReleasesShellState {
  if (error.code === "CATALOGUE_NOT_READY") {
    return { status: "catalogue-not-ready" };
  }

  const unsupportedFilterMessage = unsupportedFilterMessages[error.code];
  if (unsupportedFilterMessage !== undefined) {
    return { status: "unsupported-filters", message: unsupportedFilterMessage };
  }

  return {
    status: "error",
    message: "No se pudo leer el catálogo local. Inténtalo de nuevo más tarde.",
    correlationId: error.correlationId,
  };
}

export function ReleasesPage() {
  const [searchParams] = useSearchParams();
  const search = readReleasesSearch(searchParams);
  const query = useReleasesQuery(search);

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
      onRetry={() => {
        void query.refetch();
      }}
      search={search}
      state={state}
    />
  );
}
