import type { GameDetails } from "../game-details/game-details-api";
import type { PersonalRating } from "./personal-rating-api";

/**
 * A personal score as read: a whole score keeps its integer (`9`), a platform mean its one
 * decimal (`8,5`). The API already rounds the contribution half up to one decimal.
 */
export function formatPersonalScore(score: number): string {
  return Number.isInteger(score)
    ? String(score)
    : score.toLocaleString("es-ES", { minimumFractionDigits: 1, maximumFractionDigits: 1 });
}

/** The platforms a rating scores, in the API's order. */
export function scoredPlatforms(rating: Pick<PersonalRating, "platforms">) {
  return rating.platforms.filter(
    (entry): entry is typeof entry & { value: number } => entry.value !== null,
  );
}

/**
 * The short line that explains where a personal score comes from, or null when it is a plain
 * score: `Media de 2 plataformas`, never the arithmetic itself.
 */
export function contributionSource(rating: Pick<PersonalRating, "mode" | "platforms">): string | null {
  if (rating.mode !== "platforms") return null;
  const count = scoredPlatforms(rating).length;
  return count === 1 ? "Nota de 1 plataforma" : `Media de ${count} plataformas`;
}

export type PlatformOption = { platformId: string; name: string };

/**
 * The platforms a rating may name: the game's release platforms in the page's order, then any
 * platform already saved on the rating that the game no longer releases on, so a saved choice is
 * never lost when catalogue evidence changes.
 */
export function platformOptions(
  game: Pick<GameDetails, "releases">,
  rating: Pick<PersonalRating, "platforms"> | null,
): PlatformOption[] {
  const options = new Map<string, PlatformOption>();
  for (const release of game.releases) {
    if (!options.has(release.platform.platformId)) {
      options.set(release.platform.platformId, { ...release.platform });
    }
  }
  for (const entry of rating?.platforms ?? []) {
    if (!options.has(entry.platform.platformId)) {
      options.set(entry.platform.platformId, { ...entry.platform });
    }
  }
  return [...options.values()];
}
