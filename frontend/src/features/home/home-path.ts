import { FEATURED_PATH, readFeaturedSearch, type FeaturedSearch } from "../releases/featured-search";
import { readDiscoveryPlatformKey } from "../platforms/platform-context";
import { calendarDay, legacyReleasesPath } from "../releases/releases-search";

/** The home (#382): Gameómetro's landing page and the brand link's destination. */
export const HOME_PATH = "/";

export function readHomeSearch(params: URLSearchParams) {
  const period = params.get("inicio");
  const featured = readFeaturedSearch(new URLSearchParams(
    period === "mes" ? { granularity: "month" } :
      period === null ? {} : { [period.includes("-W") ? "week" : "month"]: period },
  ));
  const selected = params.get("fecha");
  return { featured, selected: calendarDay(selected) === null ? null : selected, platformKey: readDiscoveryPlatformKey(params) };
}

export function homeSearchPath(
  featured: FeaturedSearch,
  selected: string | null,
  platform: string | undefined,
): string {
  const params = new URLSearchParams();
  const period = featured.period ?? (featured.granularity === "month" ? "mes" : null);
  if (period !== null) params.set("inicio", period);
  if (selected !== null) params.set("fecha", selected);
  if (platform !== undefined) params.set("plataforma", platform);
  const query = params.toString();
  return query === "" ? HOME_PATH : HOME_PATH + "?" + query;
}

/** The ISO week containing an API-supplied calendar day, including across year boundaries. */
export function isoWeekForDay(day: string): string | null {
  const start = calendarDay(day);
  if (start === null) return null;
  const thursday = new Date(start);
  thursday.setUTCDate(thursday.getUTCDate() + 3 - (thursday.getUTCDay() + 6) % 7);
  const year = thursday.getUTCFullYear();
  const week = Math.ceil(((thursday.getTime() - Date.UTC(year, 0, 1)) / 86_400_000 + 1) / 7);
  return String(year).padStart(4, "0") + "-W" + String(week).padStart(2, "0");
}

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
