import type { ReleasesApiError } from "./releases-api";

/** How a failed release read presents itself; shared by the explorer and the calendar (#250). */
export type ReleasesFailureState =
  | { status: "catalogue-not-ready" }
  | { status: "unsupported-filters"; message: string }
  | { status: "error"; message: string; correlationId: string | null };

const unsupportedFilterMessages: Partial<Record<ReleasesApiError["code"], string>> = {
  PLATFORM_NOT_SUPPORTED: "¿Qué plataforma es esa? A Gameómetro no le suena de nada",
  REGION_NOT_SUPPORTED: "¿Qué región es esa? A Gameómetro no le suena de nada",
  FILTER_INVALID: "Filtros inválidos, o al menos Gameómetro no los entiende",
};

export function toFailureState(error: ReleasesApiError): ReleasesFailureState {
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
