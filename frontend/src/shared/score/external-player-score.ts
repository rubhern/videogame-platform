import type { components } from "../api/generated/schema";

export type ExternalPlayerScore = { value: number; count: number; attributionUrl: string };

/** IGDB player sentiment on its /10 scale with a positive count; anything else has no score. */
export function toExternalPlayerScore(
  score: components["schemas"]["ExternalPlayerScore"] | undefined,
): ExternalPlayerScore | null {
  if (score?.source !== "igdb" || score.kind !== "player" || score.scale !== 10 ||
      !Number.isFinite(score.value) || score.value < 0 || score.value > 10 ||
      !Number.isInteger(score.count) || score.count <= 0) return null;
  return { value: score.value, count: score.count, attributionUrl: score.attribution.sourceUrl };
}
