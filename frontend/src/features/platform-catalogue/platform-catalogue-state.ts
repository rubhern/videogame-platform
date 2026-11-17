import type { PlatformCatalogueViewModel } from "./platform-catalogue-view-model";
import type { PlatformGamesApiError } from "./platform-games-api";

/** What the catalogue shows: its read, or the failure the API reported in product terms. */
export type PlatformCatalogueState =
  | { status: "loading" }
  | {
      status: "ready";
      model: PlatformCatalogueViewModel;
      isRefreshing: boolean;
      isPlaceholderData: boolean;
    }
  | { status: "catalogue-not-ready" }
  | { status: "invalid-search" }
  | { status: "unsupported-filters" }
  | { status: "error"; correlationId: string | null };

export function toPlatformCatalogueFailure(error: PlatformGamesApiError): PlatformCatalogueState {
  switch (error.code) {
    case "CATALOGUE_NOT_READY":
      return { status: "catalogue-not-ready" };
    case "SEARCH_QUERY_INVALID":
      return { status: "invalid-search" };
    case "FILTER_INVALID":
    case "PLATFORM_NOT_SUPPORTED":
    case "SORT_INVALID":
      return { status: "unsupported-filters" };
    default:
      return { status: "error", correlationId: error.correlationId };
  }
}
