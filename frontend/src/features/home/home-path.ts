import { FEATURED_PATH, type FeaturedSearch } from "../releases/featured-search";
import { legacyReleasesPath } from "../releases/releases-search";

/** The home (#382): Gameómetro's landing page and the brand link's destination. */
export const HOME_PATH = "/";

/**
 * The home presents Destacados's current week (#329) whatever granularity Destacados opens on: the
 * same read, so the same lead, the same order and the same cache entry as the destination.
 */
export const HOME_FEATURED_SEARCH: FeaturedSearch = { granularity: "week", period: null };

/** Featured-period parameters the landing carried while it was Destacados (#151, #329). */
const FEATURED_PARAMETERS = ["week", "month", "granularity"] as const;

/**
 * Where a former landing link now lives. `view=recent|upcoming` reached the release lists and opens
 * the explorer (#285); an explicit featured period stays on Destacados (#385), which takes the
 * canonical form of what it reads. No former featured link ever becomes a ranking. Anything else is
 * the home itself, so null.
 */
export function legacyHomeRedirect(params: URLSearchParams): string | null {
  const releases = legacyReleasesPath(params);
  if (releases !== null) return releases;
  return FEATURED_PARAMETERS.some((name) => params.has(name)) ? `${FEATURED_PATH}?${params.toString()}` : null;
}
