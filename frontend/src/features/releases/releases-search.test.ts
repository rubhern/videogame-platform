import { describe, expect, it } from "vitest";

import {
  DEFAULT_PAGE_SIZE,
  hasActiveFilters,
  readReleasesSearch,
  releasesSearchPath,
  toReleasesQuery,
  writeReleasesSearch,
} from "./releases-search";

function read(query: string) {
  return readReleasesSearch(new URLSearchParams(query));
}

describe("releases navigable state", () => {
  it("defaults to the recent first page", () => {
    expect(read("")).toEqual({
      view: "recent",
      platformId: null,
      regionId: null,
      page: 1,
      pageSize: DEFAULT_PAGE_SIZE,
    });
  });

  it("restores a shared filtered page", () => {
    expect(read("view=upcoming&platformId=platform-ps5&regionId=region-eu&page=3&pageSize=24")).toEqual(
      {
        view: "upcoming",
        platformId: "platform-ps5",
        regionId: "region-eu",
        page: 3,
        pageSize: 24,
      },
    );
  });

  it("replaces values the contract cannot accept instead of forwarding them", () => {
    expect(read("view=sideways&page=0&pageSize=500")).toMatchObject({
      view: "recent",
      page: 1,
      pageSize: DEFAULT_PAGE_SIZE,
    });
    expect(read("page=-2&pageSize=abc")).toMatchObject({ page: 1, pageSize: DEFAULT_PAGE_SIZE });
    expect(read("platformId=%20%20")).toMatchObject({ platformId: null });
    expect(read(`platformId=${"x".repeat(101)}`)).toMatchObject({ platformId: null });
  });

  it("omits defaults so a shared URL stays readable", () => {
    expect(writeReleasesSearch(read("")).toString()).toBe("");
    expect(
      writeReleasesSearch(read("view=upcoming&platformId=platform-ps5&page=2")).toString(),
    ).toBe("view=upcoming&platformId=platform-ps5&page=2");
  });

  it("returns to the first page when a filter or window changes", () => {
    const current = read("view=upcoming&platformId=platform-ps5&page=4");

    expect(releasesSearchPath(current, { regionId: "region-eu", page: 1 })).toBe(
      "/?view=upcoming&platformId=platform-ps5&regionId=region-eu",
    );
    expect(releasesSearchPath(current, { platformId: null, regionId: null, page: 1 })).toBe(
      "/?view=upcoming",
    );
    expect(releasesSearchPath(read(""), { view: "recent", page: 1 })).toBe("/");
  });

  it("sends only contract parameters to the API", () => {
    expect(toReleasesQuery(read("view=upcoming&page=2"))).toEqual({
      view: "upcoming",
      page: 2,
      pageSize: DEFAULT_PAGE_SIZE,
    });
    expect(toReleasesQuery(read("platformId=platform-ps5"))).toEqual({
      view: "recent",
      platformId: "platform-ps5",
      page: 1,
      pageSize: DEFAULT_PAGE_SIZE,
    });
  });

  it("reports whether any filter is active", () => {
    expect(hasActiveFilters(read(""))).toBe(false);
    expect(hasActiveFilters(read("regionId=region-eu"))).toBe(true);
  });
});
