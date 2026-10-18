import type { GameDetails } from "./game-details-api";

type Release = GameDetails["releases"][number];

/** Releases that read alike on the page: one shared context, stated once, and their platforms. */
export type ReleaseGroup = { key: string; releases: readonly [Release, ...Release[]] };

/**
 * Everything a release row states besides its platform: the date at its own precision, the
 * region, the status, the stage and the review and verification notes. Two releases share a group
 * only when all of these are identical, so a group never implies a value one of its releases
 * lacks.
 */
function contextKey(release: Release) {
  const date = release.releaseDate;
  return JSON.stringify([
    date.precision,
    "value" in date ? date.value : null,
    release.region.regionId,
    release.status,
    release.stage,
    release.reviewStatus,
    release.verificationLevel,
  ]);
}

/**
 * The first day a release's date can mean, comparable as text: the day itself, or the first day
 * of its month, quarter or year. An unknown date has none.
 */
function periodStart(release: Release): string | null {
  const date = release.releaseDate;
  const value = "value" in date ? date.value : null;
  if (value === null) return null;
  if (date.precision === "quarter") {
    const [year, quarter] = value.split("-Q");
    return `${year}-${String((Number(quarter) - 1) * 3 + 1).padStart(2, "0")}-01`;
  }
  return date.precision === "month" ? `${value}-01` : date.precision === "year" ? `${value}-01-01` : value;
}

/**
 * Groups releases by their shared context for presentation only, so the ledger reads as a
 * calendar: every release stays whole and appears exactly once, groups run from the earliest
 * period they can mean to the latest with unknown dates last, groups that start together keep the
 * order in which their context first appears, and releases keep the given order inside their
 * group. Nothing is merged, rewritten or dropped.
 */
export function groupByContext(releases: readonly Release[]): ReleaseGroup[] {
  const groups = new Map<string, [Release, ...Release[]]>();
  for (const release of releases) {
    const key = contextKey(release);
    const group = groups.get(key);
    if (group) group.push(release);
    else groups.set(key, [release]);
  }
  return [...groups]
    .map(([key, members]) => ({ key, releases: members, start: periodStart(members[0]) }))
    .sort((a, b) =>
      a.start === b.start ? 0 : a.start === null ? 1 : b.start === null ? -1 : a.start < b.start ? -1 : 1,
    )
    .map(({ key, releases: members }) => ({ key, releases: members }));
}
