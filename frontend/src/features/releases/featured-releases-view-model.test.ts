import { describe, expect, it } from "vitest";

import type { components } from "../../shared/api/generated/schema";
import {
  adjacentMonths,
  formatMonthLabel,
  formatMonthPhrase,
  toFeaturedReleasesViewModel,
  toWordmark,
} from "./featured-releases-view-model";

type FeaturedReleases = components["schemas"]["FeaturedReleases"];
type FeaturedReleaseItem = components["schemas"]["FeaturedReleaseItem"];
type Release = components["schemas"]["Release"];

function release(overrides: Partial<Release> = {}): Release {
  return {
    releaseId: "release-1",
    gameId: "game-1",
    platform: { platformId: "10000000-0000-4000-8000-000000000001", name: "PlayStation 5" },
    region: { regionId: "20000000-0000-4000-8000-000000000001", name: "Mundial" },
    releaseDate: { precision: "day", value: "2026-10-15" },
    status: "scheduled",
    stage: "unknown",
    provenance: { sourceKind: "external_provider", sourceName: "IGDB", sourceEntityType: "release_date" },
    lastSyncedAt: "2026-10-02T05:00:00Z",
    verificationLevel: "provider_only",
    reviewStatus: "not_required",
    freshnessStatus: "fresh",
    ...overrides,
  };
}

const attribution = { label: "IGDB", sourceUrl: "https://www.igdb.com/games/example" };

function item(
  gameId: string,
  releases: Release[] = [release({ gameId })],
  overrides: Partial<FeaturedReleaseItem> = {},
): FeaturedReleaseItem {
  return {
    gameId,
    slug: `slug-${gameId}`,
    canonicalTitle: `Title ${gameId}`,
    primaryCover: {
      kind: "fallback",
      url: "/assets/covers/fallback.svg",
      alternativeText: "Carátula oficial no disponible",
      attribution: null,
    },
    featuredImage: {
      kind: "fallback",
      presentation: "fill",
      url: "/assets/featured/fallback.svg",
      compactUrl: "/assets/featured/fallback.svg",
      alternativeText: `Imagen destacada no disponible de Title ${gameId}`,
      attribution: null,
    },
    releases,
    ...overrides,
  };
}

function response(overrides: Partial<FeaturedReleases> = {}): FeaturedReleases {
  return {
    month: "2026-10",
    evaluatedOn: "2026-10-03",
    window: { from: "2026-10-01", to: "2026-10-31" },
    selection: {
      status: "ranked",
      popularityFreshness: "fresh",
      popularityObservedAt: "2026-10-02T05:00:00Z",
    },
    items: [item("a"), item("b"), item("c")],
    ...overrides,
  };
}

describe("featured releases view model", () => {
  it("steps only between months of the evaluated month's calendar year", () => {
    expect(adjacentMonths("2026-06", "2026-10")).toEqual({ previous: "2026-05", next: "2026-07" });
    expect(adjacentMonths("2026-01", "2026-10")).toEqual({ previous: null, next: "2026-02" });
    expect(adjacentMonths("2026-12", "2026-10")).toEqual({ previous: "2026-11", next: null });
    // A month of another year offers no step at all.
    expect(adjacentMonths("2025-06", "2026-10")).toEqual({ previous: null, next: null });
  });

  it("names the represented month and the evaluated one", () => {
    expect(formatMonthLabel("2026-10")).toBe("Octubre 2026");
    expect(formatMonthPhrase("2026-01")).toBe("enero de 2026");
    expect(formatMonthLabel("0001-12")).toBe("Diciembre 1");

    const model = toFeaturedReleasesViewModel(response({ month: "2027-02" }));
    expect(model.monthLabel).toBe("Febrero 2027");
    expect(model.monthPhrase).toBe("febrero de 2027");
    expect(model.currentMonth).toBe("2026-10");
  });

  it("splits the ranking into the month's featured release and the others in order", () => {
    const model = toFeaturedReleasesViewModel(response());

    expect(model.lead?.gameId).toBe("a");
    expect(model.others.map((other) => other.gameId)).toEqual(["b", "c"]);
    expect(model.lead?.releaseGroups[0]).toMatchObject({
      shortDate: "15 oct 2026",
      platforms: ["PlayStation 5"],
      platformIds: ["10000000-0000-4000-8000-000000000001"],
      region: "Mundial",
    });
  });

  it("states the lead's lifecycle and only a known stage, in the product vocabulary", () => {
    const unknownStage = toFeaturedReleasesViewModel(response());
    const earlyAccess = toFeaturedReleasesViewModel(
      response({ items: [item("a", [release({ status: "released", stage: "early_access" })])] }),
    );

    expect(unknownStage.lead?.facts).toEqual(["Programado"]);
    expect(earlyAccess.lead?.facts).toEqual(["Publicado", "Acceso anticipado"]);
  });

  it("never leads the hero with a cover: landscape art, the designed fallback, then the cover", () => {
    const model = toFeaturedReleasesViewModel(
      response({
        items: [
          item("a", undefined, {
            primaryCover: {
              kind: "provider",
              url: "https://images.igdb.com/igdb/image/upload/t_cover_big_2x/coa.webp",
              alternativeText: "Carátula de Title a",
              attribution,
            },
            featuredImage: {
              kind: "artwork",
              presentation: "fill",
              url: "https://images.igdb.com/igdb/image/upload/t_1080p/ara.webp",
              compactUrl: "https://images.igdb.com/igdb/image/upload/t_720p/ara.webp",
              alternativeText: "Arte de Title a",
              attribution,
            },
            logo: {
              url: "https://images.igdb.com/igdb/image/upload/t_logo_med_2x/loa.png",
              alternativeText: "Title a",
              attribution,
            },
          }),
          item("b", undefined, {
            featuredImage: {
              kind: "cover",
              presentation: "contain",
              url: "https://images.igdb.com/igdb/image/upload/t_cover_big_2x/cob.webp",
              compactUrl: "https://images.igdb.com/igdb/image/upload/t_cover_big_2x/cob.webp",
              alternativeText: "Carátula de Title b",
              attribution,
            },
          }),
          item("c"),
        ],
      }),
    );

    // The hero tries its artwork, then the designed fallback; the cover is only the last resort.
    expect(model.lead?.art.map((step) => [step.kind, step.presentation])).toEqual([
      ["artwork", "fill"],
      ["fallback", "fill"],
      ["cover", "contain"],
    ]);
    expect(model.lead?.art[0]?.compactUrl).toContain("t_720p/ara.webp");
    expect(model.lead?.lightUrl).toBe("https://images.igdb.com/igdb/image/upload/t_720p/ara.webp");
    expect(model.lead?.logo).toEqual({
      url: "https://images.igdb.com/igdb/image/upload/t_logo_med_2x/loa.png",
      alt: "Title a",
    });
    // A card tries its image, then the cover shown whole, then the product fallback; a cover is
    // never tried twice, and the product fallback needs no further step.
    expect(model.others[0]?.art.map((step) => step.kind)).toEqual(["cover", "fallback"]);
    expect(model.others[1]?.art.map((step) => step.kind)).toEqual(["fallback"]);
    expect(model.others[0]?.logo).toBeNull();
  });

  it("lights a hero without landscape media with its own cover, which it never presents first", () => {
    const cover = {
      kind: "provider",
      url: "https://images.igdb.com/igdb/image/upload/t_cover_big_2x/coa.webp",
      alternativeText: "Carátula de Title a",
      attribution,
    } as const;
    const designed = toFeaturedReleasesViewModel(
      response({ items: [item("a", undefined, { primaryCover: cover })] }),
    );
    // Even a response that offers the cover as the hero's image keeps the designed fallback first.
    const offered = toFeaturedReleasesViewModel(
      response({
        items: [
          item("a", undefined, {
            primaryCover: cover,
            featuredImage: {
              kind: "cover",
              presentation: "contain",
              url: cover.url,
              compactUrl: cover.url,
              alternativeText: cover.alternativeText,
              attribution,
            },
          }),
        ],
      }),
    );
    const bare = toFeaturedReleasesViewModel(response());

    for (const model of [designed, offered]) {
      expect(model.lead?.art.map((step) => step.kind)).toEqual(["fallback", "cover"]);
      expect(model.lead?.lightUrl).toBe(cover.url);
    }
    expect(bare.lead?.art.map((step) => step.kind)).toEqual(["fallback"]);
    expect(bare.lead?.lightUrl).toBe("/assets/featured/fallback.svg");
  });

  it("sets the canonical title as a wordmark, splitting only an explicit subtitle", () => {
    expect(toWordmark("Onimusha: Way of the Sword")).toEqual({
      main: "Onimusha",
      delimiter: ":",
      sub: "Way of the Sword",
    });
    expect(toWordmark("Foo - Bar: Baz")).toEqual({ main: "Foo", delimiter: " -", sub: "Bar: Baz" });
    expect(toWordmark("Marvel's Wolverine")).toEqual({
      main: "Marvel's Wolverine",
      delimiter: "",
      sub: null,
    });
    // A colon inside a word or a hyphenated word is part of the name, never a subtitle.
    expect(toWordmark("Steins;Gate Re:Boot").sub).toBeNull();
    expect(toWordmark("Spider-Man 2").sub).toBeNull();
    expect(toWordmark("Title:").sub).toBeNull();

    const model = toFeaturedReleasesViewModel(
      response({ items: [item("a", undefined, { canonicalTitle: "Dune: Awakening" })] }),
    );
    expect(model.lead?.wordmark).toEqual({ main: "Dune", delimiter: ":", sub: "Awakening" });
  });

  it("keeps a stale ranking usable and says how current its attention evidence is", () => {
    const stale = toFeaturedReleasesViewModel(
      response({
        selection: {
          status: "ranked",
          popularityFreshness: "stale",
          popularityObservedAt: "2026-09-02T05:00:00Z",
        },
      }),
    );

    expect(stale.selection).toEqual({ status: "ranked", stale: true, observedOn: "2 sep 2026" });
    expect(stale.lead).not.toBeNull();
  });

  it("tells an unranked month apart from a month without qualifying releases", () => {
    const unranked = toFeaturedReleasesViewModel(
      response({ selection: { status: "popularity_unavailable" }, items: [] }),
    );
    const empty = toFeaturedReleasesViewModel(
      response({ selection: { status: "no_qualifying_releases" }, items: [] }),
    );

    expect(unranked.selection).toEqual({ status: "popularity-unavailable" });
    expect(unranked.lead).toBeNull();
    expect(unranked.others).toEqual([]);
    expect(empty.selection).toEqual({ status: "no-qualifying-releases" });
  });
});
