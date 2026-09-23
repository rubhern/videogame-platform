import { describe, expect, it } from "vitest";

import {
  DEFAULT_PAGE_SIZE,
  hasActiveFilters,
  readReleasesSearch,
  releasesSearchPath,
  toggleFilterValue,
  toReleasesQuery,
  writeReleasesSearch,
} from "./releases-search";

function read(query: string) {
  return readReleasesSearch(new URLSearchParams(query));
}

describe("releases navigable state", () => {
  it("defaults to the recent first page with no filters", () => {
    expect(read("")).toEqual({
      view: "recent",
      weeks: 1,
      platformIds: [],
      regionIds: [],
      page: 1,
      pageSize: DEFAULT_PAGE_SIZE,
    });
  });

  it("uses twelve releases for both windows and preserves explicit page sizes", () => {
    expect(read("view=upcoming").pageSize).toBe(DEFAULT_PAGE_SIZE);
    expect(releasesSearchPath(read(""), { view: "upcoming", page: 1 })).toBe("/?view=upcoming&weeks=1");
    expect(releasesSearchPath(read("view=upcoming"), { view: "recent", page: 1 })).toBe("/?weeks=1");
    expect(releasesSearchPath(read("?pageSize=24"), { view: "upcoming", page: 1 })).toBe(
      "/?view=upcoming&weeks=1&pageSize=24",
    );
  });

  it("restores a shared multi-select filtered page", () => {
    expect(
      read(
        "view=upcoming&platformIds=platform-ps5&platformIds=platform-switch&regionIds=region-eu&page=3&pageSize=24",
      ),
    ).toEqual({
      view: "upcoming",
      weeks: 1,
      platformIds: ["platform-ps5", "platform-switch"],
      regionIds: ["region-eu"],
      page: 3,
      pageSize: 24,
    });
  });

  it("trims, drops over-long and de-duplicates repeated filter values", () => {
    expect(read("platformIds=%20%20").platformIds).toEqual([]);
    expect(read(`platformIds=${"x".repeat(101)}`).platformIds).toEqual([]);
    expect(read("platformIds=a&platformIds=a&platformIds=b").platformIds).toEqual(["a", "b"]);
  });

  it("replaces values the contract cannot accept instead of forwarding them", () => {
    expect(read("view=sideways&weeks=3&page=0&pageSize=500")).toMatchObject({
      view: "recent",
      weeks: 1,
      page: 1,
      pageSize: DEFAULT_PAGE_SIZE,
    });
    expect(read("page=-2&pageSize=abc")).toMatchObject({ page: 1, pageSize: DEFAULT_PAGE_SIZE });
    expect(read("weeks=2").weeks).toBe(2);
    expect(read("weeks=4").weeks).toBe(4);
  });

  it("omits defaults and repeats each selected value so a shared URL stays readable", () => {
    expect(writeReleasesSearch(read("")).toString()).toBe("weeks=1");
    expect(
      writeReleasesSearch(
        read("view=upcoming&platformIds=platform-ps5&platformIds=platform-switch&page=2"),
      ).toString(),
    ).toBe("view=upcoming&weeks=1&platformIds=platform-ps5&platformIds=platform-switch&page=2");
  });

  it("returns to the first page when a filter or window changes", () => {
    const current = read("view=upcoming&platformIds=platform-ps5&page=4");

    expect(releasesSearchPath(current, { regionIds: ["region-eu"], page: 1 })).toBe(
      "/?view=upcoming&weeks=1&platformIds=platform-ps5&regionIds=region-eu",
    );
    expect(releasesSearchPath(current, { platformIds: [], regionIds: [], page: 1 })).toBe(
      "/?view=upcoming&weeks=1",
    );
    expect(releasesSearchPath(current, { weeks: 4, page: 1 })).toBe(
      "/?view=upcoming&weeks=4&platformIds=platform-ps5",
    );
  });

  it("toggles a value within a dimension, preserving the other values", () => {
    expect(toggleFilterValue(["a"], "b")).toEqual(["a", "b"]);
    expect(toggleFilterValue(["a", "b"], "a")).toEqual(["b"]);
  });

  it("sends only contract parameters to the API and omits empty dimensions", () => {
    expect(toReleasesQuery(read("view=upcoming&page=2"))).toEqual({
      view: "upcoming",
      weeks: 1,
      page: 2,
      pageSize: DEFAULT_PAGE_SIZE,
    });
    expect(toReleasesQuery(read("platformIds=platform-ps5&platformIds=platform-switch"))).toEqual({
      view: "recent",
      weeks: 1,
      platformIds: ["platform-ps5", "platform-switch"],
      page: 1,
      pageSize: DEFAULT_PAGE_SIZE,
    });
  });

  it("reports whether any filter is active", () => {
    expect(hasActiveFilters(read(""))).toBe(false);
    expect(hasActiveFilters(read("regionIds=region-eu"))).toBe(true);
  });
});
