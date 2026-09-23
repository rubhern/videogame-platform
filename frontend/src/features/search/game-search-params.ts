import type { GameSearchQuery } from "./game-search-api";

/**
 * Navigable catalogue-search state.
 *
 * The query lives in the URL so a result page stays shareable and survives browser
 * navigation. A blank query is a valid navigable state: it means the visitor has not
 * searched yet, and no request is sent for it.
 */
export type GameSearchParams = {
  query: string;
  page: number;
  pageSize: number;
};

/** One full grid of cards: three columns by two rows on the widest supported layout. */
export const DEFAULT_PAGE_SIZE = 6;
const MAX_PAGE_SIZE = 100;
// The generated Spring delivery currently represents page as a signed Java Integer.
const MAX_PAGE_NUMBER = 2_147_483_647;

/** The reviewed contract bounds the query by Unicode code points, not by UTF-16 units. */
export const MAX_QUERY_CODE_POINTS = 100;

const defaults: GameSearchParams = { query: "", page: 1, pageSize: DEFAULT_PAGE_SIZE };

function readBoundedInteger(value: string | null, fallback: number, maximum: number): number {
  if (value === null || !/^\d+$/.test(value)) {
    return fallback;
  }
  const parsed = Number(value);
  return parsed >= 1 && parsed <= maximum ? parsed : fallback;
}

export function readGameSearchParams(params: URLSearchParams): GameSearchParams {
  return {
    query: params.get("q")?.trim() ?? defaults.query,
    page: readBoundedInteger(params.get("page"), defaults.page, MAX_PAGE_NUMBER),
    pageSize: readBoundedInteger(params.get("pageSize"), DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE),
  };
}

/** Serializes navigable state, omitting defaults so shared URLs stay readable. */
export function writeGameSearchParams(params: GameSearchParams): URLSearchParams {
  const result = new URLSearchParams();
  if (params.query !== "") {
    result.set("q", params.query);
  }
  if (params.page !== defaults.page) {
    result.set("page", String(params.page));
  }
  if (params.pageSize !== DEFAULT_PAGE_SIZE) {
    result.set("pageSize", String(params.pageSize));
  }
  return result;
}

/** Builds the link target for a change that keeps the rest of the state intact. */
export function gameSearchPath(
  params: GameSearchParams,
  change: Partial<GameSearchParams> = {},
): string {
  const query = writeGameSearchParams({ ...params, ...change }).toString();
  return query === "" ? "/search" : `/search?${query}`;
}

export function countCodePoints(value: string): number {
  return Array.from(value).length;
}

export function hasSearchableQuery(params: GameSearchParams): boolean {
  return params.query !== "" && countCodePoints(params.query) <= MAX_QUERY_CODE_POINTS;
}

export function toGameSearchQuery(params: GameSearchParams): GameSearchQuery {
  return { q: params.query, page: params.page, pageSize: params.pageSize };
}
