import { describe, expect, it } from "vitest";

import type { UpcomingHighlightsResponse } from "./upcoming-highlights-api";
import { toUpcomingHighlightsViewModel } from "./upcoming-highlights-view-model";

type Item = UpcomingHighlightsResponse["items"][number];
type ReleaseDate = Item["platformRelease"]["releaseDate"];

const ARTWORK = {
  kind: "artwork",
  presentation: "fill",
  url: "https://images.igdb.com/igdb/image/upload/t_1080p/ar1.webp",
  compactUrl: "https://images.igdb.com/igdb/image/upload/t_720p/ar1.webp",
  alternativeText: "Arte",
  attribution: { label: "IGDB", sourceUrl: "https://www.igdb.com/games/a" },
} as const;

const FALLBACK = {
  kind: "fallback",
  presentation: "fill",
  url: "/assets/featured/fallback.svg",
  compactUrl: "/assets/featured/fallback.svg",
  alternativeText: "Imagen destacada no disponible",
  attribution: null,
} as const;

const PROVIDER_COVER = {
  kind: "provider",
  url: "https://images.igdb.com/igdb/image/upload/t_cover_big_2x/co1.webp",
  alternativeText: "Portada",
  attribution: { label: "IGDB", sourceUrl: "https://www.igdb.com/games/a" },
} as const;

function item(position: number, releaseDate: ReleaseDate, extra: Partial<Item> = {}): Item {
  return {
    position,
    gameId: `g${position}`,
    slug: `game-${position}`,
    canonicalTitle: `Juego ${position}`,
    primaryCover: PROVIDER_COVER,
    featuredImage: ARTWORK,
    genres: [{ genreId: "adventure", name: "Aventura" }],
    platformRelease: {
      platform: { platformId: "ps5", name: "PlayStation 5" },
      region: { regionId: "world", name: "Mundial" },
      releaseDate,
      status: "scheduled",
    },
    ...extra,
  } as Item;
}

function response(items: Item[], status: UpcomingHighlightsResponse["selection"]["status"] = "ranked") {
  return {
    platforms: [{ platformId: "ps5", name: "PlayStation 5" }],
    evaluatedOn: "2026-10-27",
    window: { from: "2026-10-27", to: "2027-04-26" },
    selection: { status },
    items,
  } as UpcomingHighlightsResponse;
}

describe("toUpcomingHighlightsViewModel", () => {
  it("reads the API's horizon in the instrument's two voices", () => {
    const model = toUpcomingHighlightsViewModel(response([], "no_qualifying_releases"));

    expect(model.windowLabel).toBe("27 oct 2026 – 26 abr 2027");
    expect(model.windowPhrase).toBe("Del 27 de octubre de 2026 al 26 de abril de 2027");
    expect(model.selection).toBe("no-qualifying-releases");
    expect(model.lead).toBeNull();
    expect(model.others).toEqual([]);
  });

  it("keeps each date at its precision and counts down only what it can", () => {
    const model = toUpcomingHighlightsViewModel(
      response([
        item(1, { precision: "day", value: "2027-02-12" }),
        item(2, { precision: "day", value: "2026-10-28" }),
        item(3, { precision: "month", value: "2026-10" }),
        item(4, { precision: "quarter", value: "2026-Q4" }),
        item(5, { precision: "quarter", value: "2027-Q1" }),
        item(6, { precision: "month", value: "2027-03" }),
      ]),
    );

    expect(model.lead?.when).toEqual({
      precision: "day",
      short: "12 feb 2027",
      full: "12 de febrero de 2027",
      countdown: "Faltan 108 días",
    });
    expect(model.others.map((entry) => [entry.when.short, entry.when.countdown])).toEqual([
      ["28 oct 2026", "Mañana"],
      ["oct 2026", "Este mes"],
      ["T4 2026", "Este trimestre"],
      // A future month or quarter allows no honest distance: no day is ever invented.
      ["T1 2027", null],
      ["mar 2027", null],
    ]);
  });

  it("unrolls the horizon's months and lands every arrival without inventing a day", () => {
    const model = toUpcomingHighlightsViewModel(
      response([
        item(1, { precision: "day", value: "2026-10-27" }),
        item(2, { precision: "quarter", value: "2026-Q4" }),
        item(3, { precision: "month", value: "2027-04" }),
      ]),
    );
    const horizon = model.horizon;
    if (horizon === null) throw new Error("A valid window has a horizon");

    expect(horizon.months.map((month) => month.key)).toEqual([
      "2026-10", "2026-11", "2026-12", "2027-01", "2027-02", "2027-03", "2027-04",
    ]);
    // October holds five days of the horizon: too narrow for its name, so November names the year.
    expect(horizon.months[0]).toMatchObject({ start: 0, labelled: false, year: null });
    expect(horizon.months[1]).toMatchObject({ label: "nov", labelled: true, year: "2026" });
    expect(horizon.months[3]).toMatchObject({ label: "ene", year: "2027" });
    expect(horizon.months.at(-1)?.end).toBe(1);

    const [today, quarter, april] = horizon.marks;
    expect(today).toEqual({ position: 1, kind: "day", at: 0.5 / 182 });
    // The quarter spans from today to its end; April is clipped at the horizon's last day.
    expect(quarter).toMatchObject({ position: 2, kind: "span", start: 0 });
    expect(quarter?.kind === "span" ? quarter.end : null).toBeCloseTo(66 / 182);
    expect(april).toMatchObject({ position: 3, kind: "span", end: 1 });
  });

  it("gives the lead its landscape media and the others their card steps, lighting only real art", () => {
    const model = toUpcomingHighlightsViewModel(
      response([
        item(1, { precision: "day", value: "2027-01-10" }, { featuredImage: FALLBACK }),
        item(2, { precision: "day", value: "2027-01-11" }),
        item(3, { precision: "day", value: "2027-01-12" }, {
          featuredImage: FALLBACK,
          primaryCover: {
            kind: "fallback",
            url: "/assets/covers/fallback.svg",
            alternativeText: "Portada oficial no disponible",
          } as Item["primaryCover"],
        }),
      ]),
    );

    // Without landscape media the lead uses the designed fallback, its cover only as a last resort.
    expect(model.lead?.art.map((art) => art.kind)).toEqual(["fallback", "cover"]);
    expect(model.others[0]?.art.map((art) => art.kind)).toEqual(["artwork", "cover", "fallback"]);
    expect(model.others[1]?.art.map((art) => art.kind)).toEqual(["fallback"]);
    // The lead's cover may light the stage; a product fallback never does.
    expect(model.lights).toEqual([PROVIDER_COVER.url, PROVIDER_COVER.url]);
    expect(model.lead?.path).toBe("/games/g1/game-1");
  });

  it("names an unranked horizon apart from an empty one", () => {
    expect(toUpcomingHighlightsViewModel(response([], "popularity_unavailable")).selection)
      .toBe("popularity-unavailable");
  });
});
