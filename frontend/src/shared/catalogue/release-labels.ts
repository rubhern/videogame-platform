import type { components } from "../api/generated/schema";

type Release = components["schemas"]["Release"];

/** Spanish names of the product release stages, shared by every surface that states one. */
export const releaseStages: Record<Release["stage"], string> = {
  full_release: "Lanzamiento completo",
  early_access: "Acceso anticipado",
  advance_access: "Acceso previo",
  beta: "Beta",
  alpha: "Alfa",
  unknown: "Tipo no especificado",
};

/** Spanish names of the release lifecycle statuses, shared with the game page. */
export const releaseStatuses: Record<Release["status"], string> = {
  announced: "Anunciado",
  scheduled: "Programado",
  released: "Publicado",
  delayed: "Retrasado",
  cancelled: "Cancelado",
  unknown: "Estado sin confirmar",
};
