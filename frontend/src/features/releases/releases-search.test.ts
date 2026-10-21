import { describe, expect, it } from "vitest";

import {
  DEFAULT_PAGE_SIZE,
  hasActiveFilters,
  legacyReleasesPath,
  rangeDays,
  rangeProblem,
  readReleasesSearch,
  releasesRangePath,
  releasesSearchPath,
  toggleFilterValue,
  toReleasesQuery,
  writeReleasesSearch,
} from "./releases-search";

function read(query: string) {
  return readReleasesSearch(new URLSearchParams(query));
}

describe("release explorer navigable state", () => {
  it("ignores the removed highlights filter in old URLs", () => {
    const search = read("from=2026-11-13&to=2026-11-13&sort=hype&featuredOnly=true");
    expect(toReleasesQuery(search)).not.toHaveProperty("featuredOnly");
    expect(writeReleasesSearch(search).has("featuredOnly")).toBe(false);
  });

  it("defaults to the current week, ordered by date, on the first page with no filters", () => {
    expect(read("")).toEqual({
      from: null,
      to: null,
      sort: "releaseDate",
      includeApproximateDates: false,
      platformIds: [],
      regionIds: [],
      page: 1,
      pageSize: DEFAULT_PAGE_SIZE,
    });
    expect(releasesSearchPath(read(""), { page: 1 })).toBe("/lanzamientos");
  });

  it("restores a shared range, order, approximate dates, filters and page", () => {
    expect(
      read(
        "from=2026-08-01&to=2026-08-31&sort=hype&includeApproximateDates=true&platformIds=platform-ps5&platformIds=platform-switch&regionIds=region-eu&page=3&pageSize=24",
      ),
    ).toEqual({
      from: "2026-08-01",
      to: "2026-08-31",
      sort: "hype",
      includeApproximateDates: true,
      platformIds: ["platform-ps5", "platform-switch"],
      regionIds: ["region-eu"],
      page: 3,
      pageSize: 24,
    });
  });

  it.each([
    ["only one bound", "from=2026-08-01"],
    ["only the other bound", "to=2026-08-31"],
    ["an end before the start", "from=2026-08-31&to=2026-08-01"],
    ["a day that does not exist", "from=2026-02-28&to=2026-02-30"],
    ["a malformed day", "from=1-8-2026&to=2026-08-31"],
    ["more than 92 days", "from=2026-08-01&to=2026-11-01"],
  ])("reads %s as the current week instead of reaching the API", (_, query) => {
    const search = read(query);
    expect(search.from).toBeNull();
    expect(search.to).toBeNull();
  });

  it("accepts single days, leap days, month ends, year ends and the widest range", () => {
    expect(read("from=2026-08-13&to=2026-08-13").from).toBe("2026-08-13");
    expect(read("from=2028-02-29&to=2028-02-29").to).toBe("2028-02-29");
    expect(read("from=2026-12-28&to=2027-01-03").to).toBe("2027-01-03");
    expect(read("from=2026-08-01&to=2026-10-31").to).toBe("2026-10-31");
    expect(rangeDays("2026-08-01", "2026-10-31")).toBe(92);
    expect(rangeDays("2026-12-28", "2027-01-03")).toBe(7);
  });

  it("explains why a typed range cannot be explored", () => {
    expect(rangeProblem("", "2026-08-31")).toBe("incomplete");
    expect(rangeProblem("2026-08-31", "2026-08-01")).toBe("reversed");
    expect(rangeProblem("2026-08-01", "2026-11-01")).toBe("too-long");
    expect(rangeProblem("2026-08-01", "2026-08-01")).toBeNull();
  });

  it("falls back to the date order for an unknown sort", () => {
    expect(read("sort=popularity").sort).toBe("releaseDate");
    expect(read("sort=rating").sort).toBe("rating");
    expect(read("sort=externalPlayerScore").sort).toBe("externalPlayerScore");
  });

  it("trims, drops over-long and de-duplicates repeated filter values", () => {
    expect(read("platformIds=%20%20").platformIds).toEqual([]);
    expect(read(`platformIds=${"x".repeat(101)}`).platformIds).toEqual([]);
    expect(read("platformIds=a&platformIds=%20a%20&platformIds=b").platformIds).toEqual(["a", "b"]);
  });

  it("serializes only what differs from the defaults", () => {
    const search = read("from=2026-08-01&to=2026-08-31&sort=rating&page=2&pageSize=24");
    expect(writeReleasesSearch(search).toString()).toBe(
      "from=2026-08-01&to=2026-08-31&sort=rating&page=2&pageSize=24",
    );
    expect(releasesSearchPath(search, { from: null, to: null, page: 1 })).toBe(
      "/lanzamientos?sort=rating&pageSize=24",
    );
  });

  it("opens the explorer on exactly one range from elsewhere", () => {
    expect(releasesRangePath("2026-08-01", "2026-08-31")).toBe(
      "/lanzamientos?from=2026-08-01&to=2026-08-31",
    );
  });

  it("lands former recent and upcoming links on the explorer with their filters", () => {
    expect(
      legacyReleasesPath(
        new URLSearchParams("view=upcoming&weeks=4&platformIds=platform-ps5&page=3&pageSize=24"),
      ),
    ).toBe("/lanzamientos?platformIds=platform-ps5&pageSize=24");
    expect(legacyReleasesPath(new URLSearchParams("view=recent"))).toBe("/lanzamientos");
    expect(legacyReleasesPath(new URLSearchParams("week=2026-W42"))).toBeNull();
    expect(legacyReleasesPath(new URLSearchParams(""))).toBeNull();
  });

  it("maps navigable state to the contract query, omitting what the API defaults", () => {
    expect(toReleasesQuery(read(""))).toEqual({ page: 1, pageSize: DEFAULT_PAGE_SIZE });
    expect(
      toReleasesQuery(
        read(
          "from=2026-08-01&to=2026-08-31&sort=hype&includeApproximateDates=true&regionIds=region-eu",
        ),
      ),
    ).toEqual({
      from: "2026-08-01",
      to: "2026-08-31",
      sort: "hype",
      includeApproximateDates: true,
      regionIds: ["region-eu"],
      page: 1,
      pageSize: DEFAULT_PAGE_SIZE,
    });
  });

  it("toggles one value of a multi-select dimension, preserving order", () => {
    expect(toggleFilterValue(["a", "b"], "c")).toEqual(["a", "b", "c"]);
    expect(toggleFilterValue(["a", "b", "c"], "b")).toEqual(["a", "c"]);
  });

  it("reports active filters only when a dimension has a value", () => {
    expect(hasActiveFilters(read(""))).toBe(false);
    expect(hasActiveFilters(read("regionIds=region-eu"))).toBe(true);
  });
});
