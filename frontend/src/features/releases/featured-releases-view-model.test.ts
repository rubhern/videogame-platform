import { describe, expect, it } from "vitest";

import type { components } from "../../shared/api/generated/schema";
import {
  formatMonthLabel,
  formatMonthPhrase,
  formatRequestedPeriodLabel,
  formatWeekPhrase,
  formatWeekRange,
  isoWeekWindow,
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
    verificationLevel: "provider_only",
    reviewStatus: "not_required",
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
    granularity: "month",
    month: "2026-10",
    evaluatedOn: "2026-10-03",
    window: { from: "2026-10-01", to: "2026-10-31" },
    navigation: { previous: "2026-09", next: "2026-11" },
    selection: {
      status: "ranked",
      popularityFreshness: "fresh",
      popularityObservedAt: "2026-10-02T05:00:00Z",
    },
    items: [item("a"), item("b"), item("c")],
    ...overrides,
  };
}

function month(value: string, to: string, overrides: Partial<FeaturedReleases> = {}) {
  return response({ month: value, window: { from: `${value}-01`, to }, ...overrides });
}

function week(value: string, from: string, to: string, overrides: Partial<FeaturedReleases> = {}) {
  const { evaluatedOn, selection, items } = response();
  return {
    evaluatedOn,
    selection,
    items,
    granularity: "week",
    week: value,
    window: { from, to },
    navigation: {},
    ...overrides,
  } satisfies FeaturedReleases;
}

describe("featured releases view model", () => {
  it("names the represented month and its neighbours from the API's navigation", () => {
    expect(formatMonthLabel("2026-10")).toBe("Octubre 2026");
    expect(formatMonthPhrase("2026-01")).toBe("enero de 2026");
    expect(formatMonthLabel("0001-12")).toBe("Diciembre 1");

    const model = toFeaturedReleasesViewModel(
      month("2027-02", "2027-02-28", { navigation: { previous: "2027-01" } }),
    );
    expect(model.granularity).toBe("month");
    expect(model.period).toBe("2027-02");
    expect(model.periodLabel).toBe("Febrero 2027");
    expect(model.periodPhrase).toBe("febrero de 2027");
    expect(model.isCurrent).toBe(false);
    expect(model.previous).toEqual({ period: "2027-01", phrase: "enero de 2027" });
    // The API's last month inside the forward horizon offers no next step.
    expect(model.next).toBeNull();
    expect(toFeaturedReleasesViewModel(response()).isCurrent).toBe(true);
  });

  it("names a week by its exact days, across months and years", () => {
    expect(formatWeekPhrase("2026-10-12", "2026-10-18")).toBe(
      "la semana del 12 al 18 de octubre de 2026",
    );
    expect(formatWeekRange("2026-09-28", "2026-10-04")).toBe(
      "del 28 de septiembre al 4 de octubre de 2026",
    );
    expect(formatWeekRange("2026-12-28", "2027-01-03")).toBe(
      "del 28 de diciembre de 2026 al 3 de enero de 2027",
    );

    const model = toFeaturedReleasesViewModel(
      week("2026-W53", "2026-12-28", "2027-01-03", {
        navigation: { previous: "2026-W52", next: "2027-W01" },
      }),
    );
    expect(model.granularity).toBe("week");
    expect(model.period).toBe("2026-W53");
    expect(model.periodLabel).toBe("28 dic 2026 – 3 ene 2027");
    expect(model.periodPhrase).toBe("la semana del 28 de diciembre de 2026 al 3 de enero de 2027");
    // A neighbouring week is the API's own window moved by seven days.
    expect(model.previous).toEqual({
      period: "2026-W52",
      phrase: "la semana del 21 al 27 de diciembre de 2026",
    });
    expect(model.next).toEqual({ period: "2027-W01", phrase: "la semana del 4 al 10 de enero de 2027" });
  });

  it("knows whether today lies inside the represented week", () => {
    expect(
      toFeaturedReleasesViewModel(week("2026-W40", "2026-09-28", "2026-10-04")).isCurrent,
    ).toBe(true);
    expect(
      toFeaturedReleasesViewModel(week("2026-W41", "2026-10-05", "2026-10-11")).isCurrent,
    ).toBe(false);
  });

  it("labels a URL-requested period before its response arrives", () => {
    expect(isoWeekWindow("2026-W42")).toEqual({ from: "2026-10-12", to: "2026-10-18" });
    expect(isoWeekWindow("2026-W01")).toEqual({ from: "2025-12-29", to: "2026-01-04" });
    expect(isoWeekWindow("2026-W53")).toEqual({ from: "2026-12-28", to: "2027-01-03" });
    expect(isoWeekWindow("2020-W53")).toEqual({ from: "2020-12-28", to: "2021-01-03" });
    expect(isoWeekWindow("2025-W53")).toBeNull();
    expect(formatRequestedPeriodLabel("week", "2026-W42")).toBe("12–18 oct 2026");
    expect(formatRequestedPeriodLabel("month", "2026-10")).toBe("Octubre 2026");
  });

  it("reads the period's progress from the window and evaluated date the same response states", () => {
    expect(toFeaturedReleasesViewModel(response()).timeline).toMatchObject({
      granularity: "month",
      days: 31,
      phase: "current",
      today: 3,
    });
    expect(
      toFeaturedReleasesViewModel(month("2026-02", "2026-02-28")).timeline,
    ).toMatchObject({ days: 28, phase: "past", today: null });
    expect(
      toFeaturedReleasesViewModel(month("2026-11", "2026-11-30")).timeline?.phase,
    ).toBe("future");
    expect(
      toFeaturedReleasesViewModel(week("2026-W40", "2026-09-28", "2026-10-04")).timeline,
    ).toMatchObject({ granularity: "week", days: 7, phase: "current", today: 6 });
  });

  it("marks each game's first presented release at the precision the contract states", () => {
    const model = toFeaturedReleasesViewModel(
      response({
        items: [
          item("a", [release({ releaseDate: { precision: "day", value: "2026-10-29" } })]),
          item("b", [release({ releaseDate: { precision: "month", value: "2026-10" } })]),
          item("c", [release({ releaseDate: { precision: "unknown", value: null } })]),
        ],
      }),
    );
    expect(model.lead?.mark).toEqual({ kind: "day", day: 29 });
    // A month-precision release covers the whole month: no day is invented for it.
    expect(model.others.map((other) => other.mark)).toEqual([
      { kind: "span", open: false },
      { kind: "unknown" },
    ]);
  });

  it("marks a week's release on its weekday and never assigns a coarser date to a day", () => {
    const model = toFeaturedReleasesViewModel(
      week("2026-W40", "2026-09-28", "2026-10-04", {
        items: [
          item("a", [release({ releaseDate: { precision: "day", value: "2026-10-01" } })]),
          item("b", [release({ releaseDate: { precision: "month", value: "2026-10" } })]),
          item("c", [release({ releaseDate: { precision: "day", value: "2026-10-05" } })]),
        ],
      }),
    );
    // Thursday 1 October is the week's fourth division.
    expect(model.lead?.mark).toEqual({ kind: "day", day: 4 });
    expect(model.others.map((other) => other.mark)).toEqual([
      { kind: "span", open: true },
      { kind: "after" },
    ]);
  });

  it("splits the ranking into the period's featured release and the others in order", () => {
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

  it("omits normal lifecycle/stage labels and retains only exceptional release notices", () => {
    for (const status of ["announced", "scheduled", "released", "unknown"] as const) {
      expect(toFeaturedReleasesViewModel(response({ items: [item("a", [release({ status, stage: "full_release" })])] })).lead?.facts).toEqual([]);
    }
    for (const [status, label] of [["delayed", "Retrasado"], ["cancelled", "Cancelado"]] as const) {
      expect(toFeaturedReleasesViewModel(response({ items: [item("a", [release({ status })])] })).lead?.facts).toEqual([label]);
    }
    const uncertain = toFeaturedReleasesViewModel(response({ items: [item("a", [release({
      releaseDate: { precision: "unknown", value: null }, reviewStatus: "required", stage: "early_access",
    })])] }));
    expect(uncertain.lead?.facts).toEqual(["Fecha por confirmar", "Fecha pendiente de revisión"]);
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
