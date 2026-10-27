import { describe, expect, it } from "vitest";

import {
  advancedFilterCount,
  defaultPlatformCatalogueSearch,
  effectiveSort,
  isPlatformCataloguePath,
  platformCataloguePath,
  readPlatformCatalogueKey,
  readPlatformCatalogueSearch,
  toPlatformGamesQuery,
  writePlatformCatalogueSearch,
  yearRangeLabel,
} from "./platform-catalogue-search";

const read = (query: string) => readPlatformCatalogueSearch(new URLSearchParams(query));

describe("platform catalogue URL state", () => {
  it("reads every choice and writes it back, omitting defaults", () => {
    const search = read(
      "q=%20zelda%20&releaseState=released&sort=title_desc&fromYear=2020&toYear=2026&genreIds=adventure&genreIds=adventure&genreIds=%20rpg%20&page=3",
    );

    expect(search).toEqual({
      q: "zelda",
      state: "released",
      sort: "title_desc",
      fromYear: 2020,
      toYear: 2026,
      genreIds: ["adventure", "rpg"],
      page: 3,
    });
    expect(writePlatformCatalogueSearch(search).toString()).toBe(
      "q=zelda&releaseState=released&sort=title_desc&fromYear=2020&toYear=2026&genreIds=adventure&genreIds=rpg&page=3",
    );
    expect(writePlatformCatalogueSearch(defaultPlatformCatalogueSearch).toString()).toBe("");
  });

  it("falls back to defaults for values outside the contract and reads a reversed range the right way round", () => {
    expect(read("releaseState=soon&sort=hype&fromYear=0&toYear=10000&page=-2&q=%20%20")).toEqual(
      defaultPlatformCatalogueSearch,
    );
    expect(read(`q=${"a".repeat(101)}`).q).toBeNull();
    const reversed = read("fromYear=2027&toYear=2020");
    expect([reversed.fromYear, reversed.toYear]).toEqual([2020, 2027]);
  });

  it("orders the latest first by default and the soonest first among the games still to come", () => {
    expect(effectiveSort(defaultPlatformCatalogueSearch)).toBe("release_date_desc");
    expect(effectiveSort({ ...defaultPlatformCatalogueSearch, state: "released" })).toBe("release_date_desc");
    expect(effectiveSort({ ...defaultPlatformCatalogueSearch, state: "upcoming" })).toBe("release_date_asc");
    // A chosen order wins, and the state's own default never reaches the URL.
    expect(effectiveSort({ ...defaultPlatformCatalogueSearch, state: "upcoming", sort: "title_asc" })).toBe("title_asc");
    expect(writePlatformCatalogueSearch({ ...defaultPlatformCatalogueSearch, state: "upcoming", sort: "release_date_asc" }).toString())
      .toBe("releaseState=upcoming");
  });

  it("builds shareable paths per platform key and reads them back", () => {
    expect(platformCataloguePath("nintendo-switch-2")).toBe("/plataformas/nintendo-switch-2/catalogo");
    expect(platformCataloguePath("ps4-id", defaultPlatformCatalogueSearch, { state: "upcoming", page: 2 }))
      .toBe("/plataformas/ps4-id/catalogo?releaseState=upcoming&page=2");
    expect(readPlatformCatalogueKey("/plataformas/windows-pc/catalogo")).toBe("windows-pc");
    expect(readPlatformCatalogueKey("/plataformas/windows-pc")).toBeNull();
    expect(readPlatformCatalogueKey("/plataformas/a/b/catalogo")).toBeNull();
    expect(isPlatformCataloguePath("/plataformas/14700e95-9202-420b-9a03-2ec2ce6250c7/catalogo")).toBe(true);
    expect(isPlatformCataloguePath("/plataformas")).toBe(false);
  });

  it("sends the platform family, the effective order and only the active filters", () => {
    expect(toPlatformGamesQuery(defaultPlatformCatalogueSearch, { platformIds: ["p1", "p2"] })).toEqual({
      platformIds: ["p1", "p2"],
      sort: "release_date_desc",
      page: 1,
      pageSize: 24,
    });
    expect(
      toPlatformGamesQuery(
        { ...defaultPlatformCatalogueSearch, q: "mario", state: "upcoming", toYear: 2027, genreIds: ["platform"] },
        { platformIds: ["p1"] },
      ),
    ).toEqual({
      platformIds: ["p1"],
      q: "mario",
      releaseState: "upcoming",
      toYear: 2027,
      genreIds: ["platform"],
      sort: "release_date_asc",
      page: 1,
      pageSize: 24,
    });
  });

  it("names the year range and counts the advanced filters", () => {
    expect(yearRangeLabel(2026, null)).toBe("Desde 2026");
    expect(yearRangeLabel(null, 2027)).toBe("Hasta 2027");
    expect(yearRangeLabel(2025, 2027)).toBe("2025–2027");
    expect(yearRangeLabel(2026, 2026)).toBe("2026");
    expect(yearRangeLabel(null, null)).toBeNull();
    expect(advancedFilterCount({ ...defaultPlatformCatalogueSearch, fromYear: 2020, toYear: 2021, genreIds: ["a", "b"] }))
      .toBe(3);
  });
});
