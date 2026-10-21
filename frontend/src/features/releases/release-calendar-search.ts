import type { ReleaseCalendarQuery } from "./release-calendar-api";
import {
  RELEASES_PATH,
  calendarDay,
  defaultReleasesSearch,
  readFilters,
  releasesSearchPath,
  type ReleasesSearch,
} from "./releases-search";

/** The release calendar's own route (#250), inside **Lanzamientos** beside the explorer. */
export const CALENDAR_PATH = `${RELEASES_PATH}/calendario`;

/** How many games the day under the calendar presents before **Ver todos**. */
export const DAY_PREVIEW_SIZE = 3;

const MONTH = /^(\d{4})-(0[1-9]|1[0-2])$/;

/**
 * Navigable state of the release calendar, in the URL so a month, a chosen day and the filters
 * stay shareable and survive refreshes and browser navigation.
 *
 * Without `month` the API serves the current month in `Europe/Madrid`, because the browser never
 * decides "today". `day` is the chosen day; it implies its month when `month` is absent and is
 * dropped when it lies outside a named month. Platform and region follow the release explorer's
 * multi-select semantics, and preferred platforms apply exactly as they do there (#295).
 */
export type CalendarSearch = {
  month: string | null;
  day: string | null;
  platformIds: string[];
  regionIds: string[];
};

export const defaultCalendarSearch: CalendarSearch = {
  month: null,
  day: null,
  platformIds: [],
  regionIds: [],
};

function readMonth(value: string | null): string | null {
  return value !== null && MONTH.test(value) && value.slice(0, 4) !== "0000" ? value : null;
}

function readDay(value: string | null): string | null {
  return value !== null && calendarDay(value) !== null ? value : null;
}

export function readCalendarSearch(params: URLSearchParams): CalendarSearch {
  const day = readDay(params.get("day"));
  const named = readMonth(params.get("month"));
  const month = named ?? (day === null ? null : day.slice(0, 7));
  return {
    month,
    day: day !== null && month !== null && day.startsWith(`${month}-`) ? day : null,
    platformIds: readFilters(params.getAll("platformIds")),
    regionIds: readFilters(params.getAll("regionIds")),
  };
}

/** Serializes navigable state, omitting defaults so shared URLs stay readable. */
export function writeCalendarSearch(search: CalendarSearch): URLSearchParams {
  const params = new URLSearchParams();
  if (search.month !== null) params.set("month", search.month);
  if (search.day !== null) params.set("day", search.day);
  for (const platformId of search.platformIds) params.append("platformIds", platformId);
  for (const regionId of search.regionIds) params.append("regionIds", regionId);
  return params;
}

/** The link target for a change that keeps the rest of the calendar's state. */
export function calendarSearchPath(search: CalendarSearch, change: Partial<CalendarSearch>): string {
  const query = writeCalendarSearch({ ...search, ...change }).toString();
  return query === "" ? CALENDAR_PATH : `${CALENDAR_PATH}?${query}`;
}

/** The public read: the month as named and the platforms already resolved with preferences. */
export function toCalendarQuery(
  search: CalendarSearch,
  platformIds: readonly string[],
): ReleaseCalendarQuery {
  return {
    ...(search.month === null ? {} : { month: search.month }),
    ...(platformIds.length === 0 ? {} : { platformIds: [...platformIds] }),
    ...(search.regionIds.length === 0 ? {} : { regionIds: search.regionIds }),
  };
}

/**
 * The explorer's request for the day's leading games: that one day, exact dates only, ordered by
 * Hype with the explorer's fallback and tie-breakers. The highlighted count caps this bounded
 * first page, since every highlighted game precedes lower or missing Hype. No list filter exists.
 */
export function dayPreviewSearch(search: CalendarSearch, day: string, highlightedCount: number): ReleasesSearch {
  return {
    ...defaultReleasesSearch,
    from: day,
    to: day,
    sort: "hype",
    platformIds: search.platformIds,
    regionIds: search.regionIds,
    pageSize: Math.max(1, Math.min(DAY_PREVIEW_SIZE, highlightedCount)),
  };
}

/** **Ver todos los lanzamientos del día**: the explorer on that day, in the preview's order. */
export function dayExplorerPath(search: CalendarSearch, day: string): string {
  return releasesSearchPath(defaultReleasesSearch, {
    from: day,
    to: day,
    sort: "hype",
    platformIds: search.platformIds,
    regionIds: search.regionIds,
  });
}

/**
 * The calendar from the explorer: its filters and the month its range starts in, unless that
 * month has gone by, when the calendar opens on the current month.
 */
export function calendarFromExplorerPath(
  search: Pick<ReleasesSearch, "platformIds" | "regionIds">,
  rangeFrom: string | null,
  evaluatedOn: string | null,
): string {
  const month =
    rangeFrom === null || evaluatedOn === null || rangeFrom.slice(0, 7) <= evaluatedOn.slice(0, 7)
      ? null
      : rangeFrom.slice(0, 7);
  return calendarSearchPath(defaultCalendarSearch, {
    month,
    platformIds: search.platformIds,
    regionIds: search.regionIds,
  });
}
