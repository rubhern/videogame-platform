import type { ReleasesQuery } from "./releases-api";

export type ReleaseSort = NonNullable<ReleasesQuery["sort"]>;

/** How each order reads in **Ordenar por** and in the folded phone controls. */
export const releaseSortLabels: Record<ReleaseSort, string> = {
  releaseDate: "Fecha de lanzamiento",
  hype: "Hype",
  rating: "Puntuación Gameómetro",
  externalPlayerScore: "Jugadores IGDB",
};

/** The release explorer's own route (#285); Destacados has its own canonical route. */
export const RELEASES_PATH = "/lanzamientos";

/** The longest inclusive range the API explores in one request. */
export const MAX_RANGE_DAYS = 92;

/**
 * Navigable state of Explorar lanzamientos.
 *
 * It lives in the URL so a range, its order and its filters stay shareable and survive refreshes
 * and browser navigation. `from` and `to` are inclusive calendar days and travel together; without
 * them the API reads the current Monday-to-Sunday week in `Europe/Madrid`, because the browser
 * never decides "today". Platform and region are multi-select: several values inside one
 * dimension combine with OR and the two dimensions combine with AND. An empty array means no
 * filter for that dimension (`Todas`), which is distinct from selecting a concrete region such as
 * `Worldwide`. Values outside the contract shape fall back to the default instead of reaching the
 * API, and a range the API would reject reads as the current week; well-formed unknown filter
 * identifiers remain server-validated.
 *
 * Post-MVP (#295), platforms in the URL are a manual selection; without them a signed-in player's
 * preferred platforms may apply, which the document-wide switch in **Mi cuenta** decides. The URL
 * never carries the preferences themselves.
 */
export type ReleasesSearch = {
  from: string | null;
  to: string | null;
  sort: ReleaseSort;
  /** Admits month, quarter and year periods still to come; meaningless for a range in the past. */
  includeApproximateDates: boolean;
  platformIds: string[];
  regionIds: string[];
  page: number;
  pageSize: number;
};

/** Six columns of two rows on wide desktop. */
export const DEFAULT_PAGE_SIZE = 12;
const MAX_PAGE_SIZE = 100;
const MAX_FILTER_CODE_POINTS = 100;
const SORTS: readonly ReleaseSort[] = ["releaseDate", "hype", "rating", "externalPlayerScore"];
const DAY = /^(\d{4})-(\d{2})-(\d{2})$/;
const DAY_MS = 86_400_000;

export const defaultReleasesSearch: ReleasesSearch = {
  from: null,
  to: null,
  sort: "releaseDate",
  includeApproximateDates: false,
  platformIds: [],
  regionIds: [],
  page: 1,
  pageSize: DEFAULT_PAGE_SIZE,
};

/** Milliseconds of a real `YYYY-MM-DD` calendar day at UTC midnight, or null. */
export function calendarDay(value: string | null): number | null {
  if (value === null) return null;
  const match = DAY.exec(value);
  if (match === null) return null;
  const [, year, month, day] = match.map(Number) as [number, number, number, number];
  if (year < 1) return null;
  const time = Date.UTC(year, month - 1, day);
  const date = new Date(time);
  return date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day
    ? time
    : null;
}

/** The inclusive number of days from `from` to `to`, or null when either is not a calendar day. */
export function rangeDays(from: string, to: string): number | null {
  const start = calendarDay(from);
  const end = calendarDay(to);
  return start === null || end === null ? null : Math.round((end - start) / DAY_MS) + 1;
}

/** Why a candidate range cannot be explored, or null when the API accepts it. */
export type RangeProblem = "incomplete" | "reversed" | "too-long";

export function rangeProblem(from: string, to: string): RangeProblem | null {
  const days = rangeDays(from, to);
  if (days === null) return "incomplete";
  if (days < 1) return "reversed";
  return days > MAX_RANGE_DAYS ? "too-long" : null;
}

function readSort(value: string | null): ReleaseSort {
  return SORTS.find((sort) => sort === value) ?? defaultReleasesSearch.sort;
}

/** Trims, drops blank and over-long values and de-duplicates while keeping first-seen order. */
export function readFilters(values: string[]): string[] {
  const seen = new Set<string>();
  const result: string[] = [];
  for (const raw of values) {
    const trimmed = raw.trim();
    if (trimmed === "" || Array.from(trimmed).length > MAX_FILTER_CODE_POINTS || seen.has(trimmed)) {
      continue;
    }
    seen.add(trimmed);
    result.push(trimmed);
  }
  return result;
}

function readBoundedInteger(value: string | null, fallback: number, maximum: number): number {
  if (value === null || !/^\d+$/.test(value)) {
    return fallback;
  }
  const parsed = Number(value);
  return parsed >= 1 && parsed <= maximum ? parsed : fallback;
}

export function readReleasesSearch(params: URLSearchParams): ReleasesSearch {
  const from = params.get("from");
  const to = params.get("to");
  const validRange = from !== null && to !== null && rangeProblem(from, to) === null;
  return {
    from: validRange ? from : null,
    to: validRange ? to : null,
    sort: readSort(params.get("sort")),
    includeApproximateDates: params.get("includeApproximateDates") === "true",
    platformIds: readFilters(params.getAll("platformIds")),
    regionIds: readFilters(params.getAll("regionIds")),
    page: readBoundedInteger(params.get("page"), defaultReleasesSearch.page, Number.MAX_SAFE_INTEGER),
    pageSize: readBoundedInteger(params.get("pageSize"), DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE),
  };
}

/** Serializes navigable state, omitting defaults so shared URLs stay readable. */
export function writeReleasesSearch(search: ReleasesSearch): URLSearchParams {
  const params = new URLSearchParams();
  if (search.from !== null && search.to !== null) {
    params.set("from", search.from);
    params.set("to", search.to);
  }
  if (search.sort !== defaultReleasesSearch.sort) {
    params.set("sort", search.sort);
  }
  if (search.includeApproximateDates) {
    params.set("includeApproximateDates", "true");
  }
  for (const platformId of search.platformIds) {
    params.append("platformIds", platformId);
  }
  for (const regionId of search.regionIds) {
    params.append("regionIds", regionId);
  }
  if (search.page !== defaultReleasesSearch.page) {
    params.set("page", String(search.page));
  }
  if (search.pageSize !== DEFAULT_PAGE_SIZE) {
    params.set("pageSize", String(search.pageSize));
  }
  return params;
}

/** Builds the link target for a change that keeps the rest of the state intact. */
export function releasesSearchPath(search: ReleasesSearch, change: Partial<ReleasesSearch>): string {
  const query = writeReleasesSearch({ ...search, ...change }).toString();
  return query === "" ? RELEASES_PATH : `${RELEASES_PATH}?${query}`;
}

/** Explorar lanzamientos for one inclusive range, with every other choice at its default. */
export function releasesRangePath(from: string, to: string): string {
  return releasesSearchPath(defaultReleasesSearch, { from, to });
}

/**
 * Where a link from before #285 lands: the release explorer for the current week, keeping only
 * the filters it named. Its moving recent/upcoming horizon has no fixed range to carry.
 */
export function legacyReleasesPath(params: URLSearchParams): string | null {
  const view = params.get("view");
  if (view !== "recent" && view !== "upcoming") return null;
  const legacy = readReleasesSearch(params);
  return releasesSearchPath(defaultReleasesSearch, {
    platformIds: legacy.platformIds,
    regionIds: legacy.regionIds,
    pageSize: legacy.pageSize,
  });
}

/** Adds or removes one value from a dimension, preserving order, for a multi-select toggle. */
export function toggleFilterValue(current: readonly string[], value: string): string[] {
  return current.includes(value)
    ? current.filter((existing) => existing !== value)
    : [...current, value];
}

export function toReleasesQuery(search: ReleasesSearch): ReleasesQuery {
  return {
    ...(search.from === null || search.to === null ? {} : { from: search.from, to: search.to }),
    ...(search.sort === defaultReleasesSearch.sort ? {} : { sort: search.sort }),
    ...(search.includeApproximateDates ? { includeApproximateDates: true } : {}),
    ...(search.platformIds.length === 0 ? {} : { platformIds: search.platformIds }),
    ...(search.regionIds.length === 0 ? {} : { regionIds: search.regionIds }),
    page: search.page,
    pageSize: search.pageSize,
  };
}

export function hasActiveFilters(search: ReleasesSearch): boolean {
  return search.platformIds.length > 0 || search.regionIds.length > 0;
}
