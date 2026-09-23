import type { ReleasesQuery } from "./releases-api";

export type ReleaseView = ReleasesQuery["view"];
export type ReleaseWeeks = NonNullable<ReleasesQuery["weeks"]>;

/**
 * Navigable release-discovery state.
 *
 * It lives in the URL so a filtered page stays shareable and survives browser
 * navigation. Platform and region are multi-select: several values inside one dimension
 * combine with OR and the two dimensions combine with AND. An empty array means no filter
 * for that dimension (`Todas`), which is distinct from selecting a concrete region such as
 * `Worldwide`. Values outside the contract shape fall back to the default instead of
 * reaching the API; well-formed unknown filter identifiers remain server-validated.
 */
export type ReleasesSearch = {
  view: ReleaseView;
  weeks: ReleaseWeeks;
  platformIds: string[];
  regionIds: string[];
  page: number;
  pageSize: number;
};

/** Both release windows show two rows of six on wide desktop. */
export const DEFAULT_PAGE_SIZE = 12;
const MAX_PAGE_SIZE = 100;
const MAX_FILTER_CODE_POINTS = 100;

const defaultSearch: ReleasesSearch = {
  view: "recent",
  weeks: 1,
  platformIds: [],
  regionIds: [],
  page: 1,
  pageSize: DEFAULT_PAGE_SIZE,
};

function readView(value: string | null): ReleaseView {
  return value === "upcoming" || value === "recent" ? value : defaultSearch.view;
}

function readWeeks(value: string | null): ReleaseWeeks {
  return value === "2" ? 2 : value === "4" ? 4 : 1;
}

/** Trims, drops blank and over-long values and de-duplicates while keeping first-seen order. */
function readFilters(values: string[]): string[] {
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
  const view = readView(params.get("view"));
  return {
    view,
    weeks: readWeeks(params.get("weeks")),
    platformIds: readFilters(params.getAll("platformIds")),
    regionIds: readFilters(params.getAll("regionIds")),
    page: readBoundedInteger(params.get("page"), defaultSearch.page, Number.MAX_SAFE_INTEGER),
    pageSize: readBoundedInteger(params.get("pageSize"), DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE),
  };
}

/** Serializes navigable state, omitting defaults so shared URLs stay readable. */
export function writeReleasesSearch(search: ReleasesSearch): URLSearchParams {
  const params = new URLSearchParams();
  if (search.view !== defaultSearch.view) {
    params.set("view", search.view);
  }
  params.set("weeks", String(search.weeks));
  for (const platformId of search.platformIds) {
    params.append("platformIds", platformId);
  }
  for (const regionId of search.regionIds) {
    params.append("regionIds", regionId);
  }
  if (search.page !== defaultSearch.page) {
    params.set("page", String(search.page));
  }
  if (search.pageSize !== DEFAULT_PAGE_SIZE) {
    params.set("pageSize", String(search.pageSize));
  }
  return params;
}

/** Builds the link target for a change that keeps the rest of the state intact. */
export function releasesSearchPath(search: ReleasesSearch, change: Partial<ReleasesSearch>): string {
  const params = writeReleasesSearch({ ...search, ...change });
  const query = params.toString();
  return query === "" ? "/" : `/?${query}`;
}

/** Adds or removes one value from a dimension, preserving order, for a multi-select toggle. */
export function toggleFilterValue(current: readonly string[], value: string): string[] {
  return current.includes(value)
    ? current.filter((existing) => existing !== value)
    : [...current, value];
}

export function toReleasesQuery(search: ReleasesSearch): ReleasesQuery {
  return {
    view: search.view,
    weeks: search.weeks,
    ...(search.platformIds.length === 0 ? {} : { platformIds: search.platformIds }),
    ...(search.regionIds.length === 0 ? {} : { regionIds: search.regionIds }),
    page: search.page,
    pageSize: search.pageSize,
  };
}

export function hasActiveFilters(search: ReleasesSearch): boolean {
  return search.platformIds.length > 0 || search.regionIds.length > 0;
}
