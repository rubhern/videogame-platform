import type { ReleasesQuery } from "./releases-api";

export type ReleaseView = ReleasesQuery["view"];

/**
 * Navigable release-discovery state.
 *
 * It lives in the URL so a filtered page stays shareable and survives browser
 * navigation. Values outside the contract shape fall back to the default instead of
 * reaching the API; well-formed unknown filter identifiers remain server-validated.
 */
export type ReleasesSearch = {
  view: ReleaseView;
  platformId: string | null;
  regionId: string | null;
  page: number;
  pageSize: number;
};

/** One full grid of cards: three columns by two rows on the widest supported layout. */
export const DEFAULT_PAGE_SIZE = 6;
const MAX_PAGE_SIZE = 100;
const MAX_FILTER_CODE_POINTS = 100;

const defaultSearch: ReleasesSearch = {
  view: "recent",
  platformId: null,
  regionId: null,
  page: 1,
  pageSize: DEFAULT_PAGE_SIZE,
};

function readView(value: string | null): ReleaseView {
  return value === "upcoming" || value === "recent" ? value : defaultSearch.view;
}

function readFilter(value: string | null): string | null {
  const trimmed = value?.trim() ?? "";
  return trimmed === "" || Array.from(trimmed).length > MAX_FILTER_CODE_POINTS ? null : trimmed;
}

function readBoundedInteger(value: string | null, fallback: number, maximum: number): number {
  if (value === null || !/^\d+$/.test(value)) {
    return fallback;
  }
  const parsed = Number(value);
  return parsed >= 1 && parsed <= maximum ? parsed : fallback;
}

export function readReleasesSearch(params: URLSearchParams): ReleasesSearch {
  return {
    view: readView(params.get("view")),
    platformId: readFilter(params.get("platformId")),
    regionId: readFilter(params.get("regionId")),
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
  if (search.platformId !== null) {
    params.set("platformId", search.platformId);
  }
  if (search.regionId !== null) {
    params.set("regionId", search.regionId);
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

export function toReleasesQuery(search: ReleasesSearch): ReleasesQuery {
  return {
    view: search.view,
    ...(search.platformId === null ? {} : { platformId: search.platformId }),
    ...(search.regionId === null ? {} : { regionId: search.regionId }),
    page: search.page,
    pageSize: search.pageSize,
  };
}

export function hasActiveFilters(search: ReleasesSearch): boolean {
  return search.platformId !== null || search.regionId !== null;
}
