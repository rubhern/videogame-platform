import type { RatingCommandError } from "../ratings/personal-rating-api";

/** What a failed general score from a list tells the player, by failure (#574). */
export const quickRateFailures: Record<RatingCommandError["kind"], string> = {
  authentication: "Tu sesión ha caducado. Vuelve a elegir tu puntuación para iniciar sesión de nuevo.",
  csrf: "No se pudo verificar la solicitud. Vuelve a intentarlo; si persiste, recarga la página.",
  conflict: "Tu puntuación de este juego cambió en otra sesión. Ya ves la actual; revísala antes de cambiarla.",
  validation: "La puntuación debe ser un número entero del 1 al 10.",
  platform: "Este juego tiene puntuaciones por plataforma. Cámbialas desde sus plataformas.",
  ineligible: "Este juego ya no admite nuevas puntuaciones.",
  "rate-limited": "Demasiadas puntuaciones seguidas. Espera un momento y vuelve a intentarlo.",
  unavailable: "No se pudo guardar. Tu puntuación anterior se conserva; inténtalo más tarde.",
  ambiguous: "No sabemos si tu puntuación se guardó. Ya la estamos comprobando antes de que repitas.",
};
