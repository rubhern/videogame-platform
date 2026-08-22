import { ReleasesApiError } from "../features/releases/releases-api";
import {
  ReleasesShell,
  type ReleasesShellState,
} from "../features/releases/releases-shell";
import { useRecentReleasesQuery } from "../features/releases/use-recent-releases-query";

function errorMessage(error: unknown): string {
  if (error instanceof ReleasesApiError && error.code === "CATALOGUE_NOT_READY") {
    return "El catálogo local todavía no está disponible.";
  }
  return "Inténtalo de nuevo más tarde.";
}

export function ReleasesPage() {
  const query = useRecentReleasesQuery();
  let state: ReleasesShellState;

  if (query.isPending) {
    state = { status: "loading" };
  } else if (query.isError) {
    state = { status: "error", message: errorMessage(query.error) };
  } else if (query.data.length === 0) {
    state = { status: "empty" };
  } else {
    state = { status: "success", items: query.data };
  }

  return <ReleasesShell state={state} />;
}
