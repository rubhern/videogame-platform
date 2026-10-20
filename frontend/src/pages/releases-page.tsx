import { useLocation, useSearchParams } from "react-router-dom";

import { readDayFrame } from "../features/releases/day-bar";

import type { ReleasesApiError } from "../features/releases/releases-api";
import { readReleasesSearch } from "../features/releases/releases-search";
import {
  ReleasesShell,
  type ReleasesShellState,
} from "../features/releases/releases-shell";
import { useReleasesQuery } from "../features/releases/use-releases-query";

const unsupportedFilterMessages: Partial<Record<ReleasesApiError["code"], string>> = {
  PLATFORM_NOT_SUPPORTED: "¿Qué plataforma es esa? A Gameómetro no le suena de nada",
  REGION_NOT_SUPPORTED: "¿Qué región es esa? A Gameómetro no le suena de nada",
  FILTER_INVALID: "Filtros inválidos, o al menos Gameómetro no los entiende",
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
    message: "A Gameómetro le está costando un poco. Prueba más tarde",
    correlationId: error.correlationId,
  };
}

export function ReleasesPage() {
  const [searchParams] = useSearchParams();
  const location = useLocation();
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
      keptFrame={readDayFrame(location.state)}
      onRetry={() => {
        void query.refetch();
      }}
      search={search}
      state={state}
    />
  );
}
