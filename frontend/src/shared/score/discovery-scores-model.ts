import type { components } from "../api/generated/schema";
import { toExternalPlayerScore, type ExternalPlayerScore } from "./external-player-score";

export type DiscoveryScore =
  | { source: "gameometro"; value: number; count: number }
  | ({ source: "igdb" } & ExternalPlayerScore);

/** Preserve source identity and unknown state; absence never becomes a zero score. */
export function toDiscoveryScores(
  scores: components["schemas"]["DiscoveryScores"] | undefined,
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
  const external = toExternalPlayerScore(scores.external);
  if (external !== null) result.push({ source: "igdb", ...external });
  return result;
}
