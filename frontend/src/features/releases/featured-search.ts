/**
 * Navigable state of the featured releases (#151, #329): the calendar week or month they
 * represent. It lives in the URL so a period stays shareable and survives browser navigation.
 * No period means the current week or month, which the API derives in `Europe/Madrid`; the browser
 * never decides "today", and the API alone states which periods may be offered. A value outside
 * the contract shape never reaches the API: the page returns to the current period instead, as it
 * does when the API rejects a period outside its navigation bounds.
 */
export type FeaturedGranularity = "week" | "month";

/**
 * The granularity Destacados opens on (owner decision, #329). It is the only place that decides
 * it: the landing route, the switch and the URL canonical form all follow this value.
 */
export const DEFAULT_FEATURED_GRANULARITY: FeaturedGranularity = "week";

export type FeaturedSearch = {
  granularity: FeaturedGranularity;
  /** `YYYY-Www` for a week, `YYYY-MM` for a month, or null for the current period. */
  period: string | null;
};

const MONTH = /^(?!0000)\d{4}-(0[1-9]|1[0-2])$/;
const WEEK = /^(?!0000)\d{4}-W(0[1-9]|[1-4]\d|5[0-3])$/;

function readGranularity(value: string | null): FeaturedGranularity | null {
  return value === "week" || value === "month" ? value : null;
}

/**
 * Reads the URL's period. A period names its own granularity; `granularity` alone selects the
 * current week or month. Anything else, malformed or contradictory, reads as the default.
 */
export function readFeaturedSearch(params: URLSearchParams): FeaturedSearch {
  const week = params.get("week");
  const month = params.get("month");
  const named = readGranularity(params.get("granularity"));
  if (week !== null && month === null && WEEK.test(week) && named !== "month") {
    return { granularity: "week", period: week };
  }
  if (month !== null && week === null && MONTH.test(month) && named !== "week") {
    return { granularity: "month", period: month };
  }
  if (week === null && month === null && named !== null) {
    return { granularity: named, period: null };
  }
  return { granularity: DEFAULT_FEATURED_GRANULARITY, period: null };
}

/**
 * The canonical route of a featured state: the landing route for the current period of the
 * default granularity, `?granularity=` for the other one, and the period alone otherwise.
 */
export function featuredSearchPath(search: FeaturedSearch): string {
  if (search.period !== null) {
    const key = search.granularity === "week" ? "week" : "month";
    return `/?${new URLSearchParams({ [key]: search.period }).toString()}`;
  }
  return search.granularity === DEFAULT_FEATURED_GRANULARITY
    ? "/"
    : `/?${new URLSearchParams({ granularity: search.granularity }).toString()}`;
}

/** Whether the URL already is the canonical route of what it reads as. */
export function isCanonicalFeaturedSearch(params: URLSearchParams): boolean {
  const canonical = featuredSearchPath(readFeaturedSearch(params));
  const current = params.toString();
  return canonical === (current === "" ? "/" : `/?${current}`);
}
