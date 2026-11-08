import { PLATFORMS_PATH, type PlatformContext } from "../platforms/platform-context";
import type { PlatformGamesQuery, PlatformGamesSort } from "./platform-games-api";

/** The destination's own segment under a platform (#389). */
const CATALOGUE_SEGMENT = "catalogo";

/** `/plataformas/<key>/catalogo`, where the key is a featured family's or a platform's own ID. */
const CATALOGUE_ROUTE = new RegExp(`^${PLATFORMS_PATH}/([^/]+)/${CATALOGUE_SEGMENT}$`);

export const PLATFORM_CATALOGUE_ROUTE = `${PLATFORMS_PATH.slice(1)}/:platformKey/${CATALOGUE_SEGMENT}`;

/** Four columns of the wide grid, six rows; two, three and four columns all divide it. */
export const PLATFORM_GAMES_PAGE_SIZE = 24;

export type CatalogueReleaseState = "all" | "released" | "upcoming";

/**
 * Navigable state of Explorar catálogo. It lives in the URL, next to the platform in the path, so
 * a platform's selection is shareable and survives refreshes, history and a return from a game
 * page. A null order reads as the release state's own default; values outside the contract shape
 * fall back to their defaults instead of reaching the API, while unknown genre identifiers stay
 * server-validated.
 */
export type PlatformCatalogueSearch = {
  q: string | null;
  state: CatalogueReleaseState;
  sort: PlatformGamesSort | null;
  fromYear: number | null;
  toYear: number | null;
  genreIds: string[];
  page: number;
};

export const defaultPlatformCatalogueSearch: PlatformCatalogueSearch = {
  q: null,
  state: "all",
  sort: null,
  fromYear: null,
  toYear: null,
  genreIds: [],
  page: 1,
};

/** How each order reads in **Ordenar por**. */
export const platformGamesSortLabels: Record<PlatformGamesSort, string> = {
  release_date_desc: "Fecha (descendente)",
  release_date_asc: "Fecha (ascendente)",
  title_asc: "Título (A-Z)",
  title_desc: "Título (Z-A)",
  community_score: "Puntuación Gameómetro",
  external_player_score: "Jugadores IGDB",
};

const SORTS = Object.keys(platformGamesSortLabels) as PlatformGamesSort[];
const MAX_QUERY_CODE_POINTS = 100;
const MAX_FILTER_CODE_POINTS = 100;
const MAX_GENRES = 30;

/**
 * Without a chosen order the latest dates lead, except among the games still to come, where the
 * soonest is the useful first answer.
 */
export function effectiveSort(search: PlatformCatalogueSearch): PlatformGamesSort {
  return search.sort ?? (search.state === "upcoming" ? "release_date_asc" : "release_date_desc");
}

function readYear(value: string | null): number | null {
  if (value === null || !/^\d{1,4}$/.test(value)) return null;
  const year = Number(value);
  return year >= 1 && year <= 9999 ? year : null;
}

function readPage(value: string | null): number {
  if (value === null || !/^\d+$/.test(value)) return 1;
  const page = Number(value);
  return page >= 1 && page <= Number.MAX_SAFE_INTEGER ? page : 1;
}

function readQuery(value: string | null): string | null {
  const trimmed = value?.trim() ?? "";
  if (trimmed === "" || Array.from(trimmed).length > MAX_QUERY_CODE_POINTS) return null;
  return trimmed;
}

function readGenres(values: string[]): string[] {
  const seen = new Set<string>();
  for (const raw of values) {
    const value = raw.trim();
    if (value !== "" && Array.from(value).length <= MAX_FILTER_CODE_POINTS) seen.add(value);
  }
  return [...seen].slice(0, MAX_GENRES);
}

export function readPlatformCatalogueSearch(params: URLSearchParams): PlatformCatalogueSearch {
  const state = params.get("releaseState");
  const sort = params.get("sort");
  let fromYear = readYear(params.get("fromYear"));
  let toYear = readYear(params.get("toYear"));
  // A reversed range in a shared link still names a range: read it the right way round.
  if (fromYear !== null && toYear !== null && fromYear > toYear) {
    [fromYear, toYear] = [toYear, fromYear];
  }
  return {
    q: readQuery(params.get("q")),
    state: state === "released" || state === "upcoming" ? state : "all",
    sort: SORTS.find((candidate) => candidate === sort) ?? null,
    fromYear,
    toYear,
    genreIds: readGenres(params.getAll("genreIds")),
    page: readPage(params.get("page")),
  };
}

/** Serializes navigable state, omitting defaults so shared URLs stay readable. */
export function writePlatformCatalogueSearch(search: PlatformCatalogueSearch): URLSearchParams {
  const params = new URLSearchParams();
  if (search.q !== null) params.set("q", search.q);
  if (search.state !== "all") params.set("releaseState", search.state);
  if (search.sort !== null && search.sort !== effectiveSort({ ...search, sort: null })) {
    params.set("sort", search.sort);
  }
  if (search.fromYear !== null) params.set("fromYear", String(search.fromYear));
  if (search.toYear !== null) params.set("toYear", String(search.toYear));
  for (const genreId of search.genreIds) params.append("genreIds", genreId);
  if (search.page !== 1) params.set("page", String(search.page));
  return params;
}

/** The catalogue of a platform, optionally with a selection; every key reads by its own path. */
export function platformCataloguePath(
  platformKey: string,
  search: PlatformCatalogueSearch = defaultPlatformCatalogueSearch,
  change: Partial<PlatformCatalogueSearch> = {},
): string {
  const next = { ...search, ...change };
  // A default order stays implicit, so changing the release state keeps its own default order.
  const query = writePlatformCatalogueSearch(next).toString();
  const path = `${PLATFORMS_PATH}/${encodeURIComponent(platformKey)}/${CATALOGUE_SEGMENT}`;
  return query === "" ? path : `${path}?${query}`;
}

/** The platform key a pathname names, or null for any other path. */
export function readPlatformCatalogueKey(pathname: string): string | null {
  const match = CATALOGUE_ROUTE.exec(pathname);
  if (match?.[1] === undefined) return null;
  try {
    return decodeURIComponent(match[1]);
  } catch {
    return null;
  }
}

/** Whether a path is a platform catalogue, for a game page's way back. */
export function isPlatformCataloguePath(pathname: string): boolean {
  return readPlatformCatalogueKey(pathname) !== null;
}

/** Whether anything narrows the platform's games beyond the release state. */
export function hasNarrowingFilters(search: PlatformCatalogueSearch): boolean {
  return (
    search.q !== null ||
    search.fromYear !== null ||
    search.toYear !== null ||
    search.genreIds.length > 0
  );
}

export function toPlatformGamesQuery(
  search: PlatformCatalogueSearch,
  context: Pick<PlatformContext, "platformIds">,
): PlatformGamesQuery {
  return {
    platformIds: [...context.platformIds],
    ...(search.q === null ? {} : { q: search.q }),
    ...(search.state === "all" ? {} : { releaseState: search.state }),
    ...(search.fromYear === null ? {} : { fromYear: search.fromYear }),
    ...(search.toYear === null ? {} : { toYear: search.toYear }),
    ...(search.genreIds.length === 0 ? {} : { genreIds: search.genreIds }),
    sort: effectiveSort(search),
    page: search.page,
    pageSize: PLATFORM_GAMES_PAGE_SIZE,
  };
}

/** The year range as it reads on a chip: `2026`, `Desde 2026`, `Hasta 2027` or `2026–2027`. */
export function yearRangeLabel(fromYear: number | null, toYear: number | null): string | null {
  if (fromYear !== null && toYear !== null) {
    return fromYear === toYear ? String(fromYear) : `${fromYear}–${toYear}`;
  }
  if (fromYear !== null) return `Desde ${fromYear}`;
  if (toYear !== null) return `Hasta ${toYear}`;
  return null;
}

/** How many advanced filters are active: the year range counts once, each genre once. */
export function advancedFilterCount(search: PlatformCatalogueSearch): number {
  return (search.fromYear !== null || search.toYear !== null ? 1 : 0) + search.genreIds.length;
}
