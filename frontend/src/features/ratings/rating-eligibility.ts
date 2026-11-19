import type { GameDetails } from "../game-details/game-details-api";

export const ineligibleReasons: Record<GameDetails["ratingEligibility"]["reason"], string> = {
  ELIGIBLE_RELEASE_FOUND: "Todavía no se puede puntuar este juego.",
  NO_COMMERCIAL_RELEASE: "Puntuación no disponible: Aún no tenemos un lanzamiento",
  RELEASE_NOT_OCCURRED: "Puntuación no disponible: Aún no ha salido",
  RELEASE_CANCELLED: "Puntuación no disponible: Juego cancelado",
  RELEASE_DATE_UNCERTAIN: "Puntuación no disponible: Fecha incierta",
  RELEASE_REVIEW_REQUIRED: "Puntuación no disponible: El juego necesita revisión",
};
