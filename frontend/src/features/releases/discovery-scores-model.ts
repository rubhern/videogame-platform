import type { ReleasePage } from "./releases-api";

export type DiscoveryScore =
  | { source: "gameometro"; value: number; count: number }
  | { source: "igdb"; value: number; count: number; attributionUrl: string };

/** Preserve source identity and unknown state; absence never becomes a zero score. */
export function toDiscoveryScores(
  scores: ReleasePage["items"][number]["scores"],
): DiscoveryScore[] {
  if (scores === undefined) return [];
  const result: DiscoveryScore[] = [];
  const community = scores.community;
  if (community.status === "available" && "mean" in community &&
      community.mean !== null && Number.isFinite(community.mean) &&
      community.mean >= 1 && community.mean <= 10 && Number.isInteger(community.count) &&
      community.count > 0) {
    result.push({ source: "gameometro", value: community.mean, count: community.count });
  }
  const external = scores.external;
  if (external?.source === "igdb" && external.kind === "player" && external.scale === 10 &&
      Number.isFinite(external.value) && external.value >= 0 && external.value <= 10 &&
      Number.isInteger(external.count) && external.count > 0) {
    result.push({ source: "igdb", value: external.value, count: external.count,
      attributionUrl: external.attribution.sourceUrl });
  }
  return result;
}
