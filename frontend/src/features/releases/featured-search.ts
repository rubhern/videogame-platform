/**
 * Navigable state of the featured releases (#151): the calendar month they represent. It lives in
 * the URL so a month stays shareable and survives browser navigation. No month means the current
 * calendar month, which the API derives in `Europe/Madrid`; the browser never decides "today".
 * A value outside the contract shape never reaches the API: the page returns to the landing
 * route, as it does when the API rejects a month outside the current calendar year.
 */
export type FeaturedSearch = {
  /** `YYYY-MM`, or null for the current month. */
  month: string | null;
};

const MONTH = /^(?!0000)(\d{4})-(0[1-9]|1[0-2])$/;

export function readFeaturedSearch(params: URLSearchParams): FeaturedSearch {
  const month = params.get("month");
  return { month: month !== null && MONTH.test(month) ? month : null };
}

/** The landing route represents the current month, so it carries no month of its own. */
export function featuredSearchPath(month: string | null): string {
  return month === null ? "/" : `/?${new URLSearchParams({ month }).toString()}`;
}

/** The adjacent calendar month, or null past the contract's bounds (0001-01 to 9999-12). */
export function shiftMonth(month: string, offset: -1 | 1): string | null {
  const match = MONTH.exec(month);
  if (match === null) {
    return null;
  }
  const index = Number(match[1]) * 12 + Number(match[2]) - 1 + offset;
  const year = Math.floor(index / 12);
  if (year < 1 || year > 9999) {
    return null;
  }
  return `${String(year).padStart(4, "0")}-${String((index % 12) + 1).padStart(2, "0")}`;
}
