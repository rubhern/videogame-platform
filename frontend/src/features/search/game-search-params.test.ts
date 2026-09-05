import { describe, expect, it } from "vitest";

import {
  DEFAULT_PAGE_SIZE,
  gameSearchPath,
  hasSearchableQuery,
  MAX_QUERY_CODE_POINTS,
  readGameSearchParams,
  toGameSearchQuery,
  writeGameSearchParams,
} from "./game-search-params";

function read(query: string) {
  return readGameSearchParams(new URLSearchParams(query));
}

describe("catalogue search navigable state", () => {
  it("defaults to no query and the first page", () => {
    expect(read("")).toEqual({ query: "", page: 1, pageSize: DEFAULT_PAGE_SIZE });
  });

  it("restores a shared result page", () => {
    expect(read("q=resident+evil&page=3&pageSize=24")).toEqual({
      query: "resident evil",
      page: 3,
      pageSize: 24,
    });
  });

  it("trims the shared query so equivalent links address the same search", () => {
    expect(read("q=%20%20resident%20evil%20%20").query).toEqual("resident evil");
  });

  it("replaces pagination values the contract cannot accept instead of forwarding them", () => {
    expect(read("q=evil&page=0&pageSize=500")).toMatchObject({
      page: 1,
      pageSize: DEFAULT_PAGE_SIZE,
    });
    expect(read("q=evil&page=2147483648").page).toBe(1);
  });

  it("omits defaults so a shared link stays readable", () => {
    expect(writeGameSearchParams({ query: "evil", page: 1, pageSize: DEFAULT_PAGE_SIZE }).toString())
      .toEqual("q=evil");
    expect(gameSearchPath({ query: "", page: 1, pageSize: DEFAULT_PAGE_SIZE })).toEqual("/search");
  });

  it("keeps the rest of the state when one value changes", () => {
    const params = { query: "evil", page: 2, pageSize: DEFAULT_PAGE_SIZE };

    expect(gameSearchPath(params, { page: 3 })).toEqual("/search?q=evil&page=3");
    expect(gameSearchPath(params, { query: "resident", page: 1 })).toEqual("/search?q=resident");
  });

  it("treats a blank query as nothing to search rather than an invalid one", () => {
    expect(hasSearchableQuery(read(""))).toBe(false);
    expect(hasSearchableQuery(read("q=%20%20"))).toBe(false);
  });

  it("bounds the query by Unicode code points, not by UTF-16 units", () => {
    const withinBound = { query: "🎮".repeat(MAX_QUERY_CODE_POINTS), page: 1, pageSize: 6 };
    const beyondBound = { query: "🎮".repeat(MAX_QUERY_CODE_POINTS + 1), page: 1, pageSize: 6 };

    expect(hasSearchableQuery(withinBound)).toBe(true);
    expect(hasSearchableQuery(beyondBound)).toBe(false);
  });

  it("maps navigable state onto the reviewed query parameters", () => {
    expect(toGameSearchQuery({ query: "resident evil", page: 2, pageSize: 6 })).toEqual({
      q: "resident evil",
      page: 2,
      pageSize: 6,
    });
  });
});
