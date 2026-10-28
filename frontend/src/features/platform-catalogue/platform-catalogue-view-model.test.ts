import { describe, expect, it } from "vitest";

import type { PlatformGamesPage } from "./platform-games-api";
import { toPlatformCatalogueViewModel } from "./platform-catalogue-view-model";

type Item = PlatformGamesPage["items"][number];
type PlatformRelease = Item["platformRelease"];

function item(gameId: string, release: Partial<PlatformRelease>, extra: Partial<Item> = {}): Item {
  return {
    gameId,
    slug: gameId,
    canonicalTitle: `Juego ${gameId}`,
    primaryCover: { kind: "fallback", url: "/assets/covers/fallback.svg", alternativeText: "Portada", attribution: null },
    genres: [{ genreId: "adventure", name: "Aventura" }],
    platformRelease: {
      platform: { platformId: "switch2", name: "Nintendo Switch 2" },
      region: { regionId: "world", name: "Mundial" },
      releaseDate: { precision: "day", value: "2026-03-05" },
      status: "released",
      ...release,
    } as PlatformRelease,
    scores: { community: { status: "available", mean: null, count: 0 } },
    ...extra,
  } as Item;
}

function page(items: Item[], filters: Partial<PlatformGamesPage["availableFilters"]> = {}): PlatformGamesPage {
  return {
    platforms: [{ platformId: "switch2", name: "Nintendo Switch 2" }],
    availableFilters: { genres: [{ genreId: "adventure", name: "Aventura" }], ...filters },
    items,
    page: { number: 1, size: 24, totalItems: items.length, totalPages: 1 },
  } as PlatformGamesPage;
}

describe("toPlatformCatalogueViewModel", () => {
  it("reads each game's state from its presented release, keeping the date's precision", () => {
    const model = toPlatformCatalogueViewModel(
      page([
        item("out", {}),
        item("soon", { status: "scheduled", releaseDate: { precision: "quarter", value: "2027-Q1" } }),
        item("tba", { status: "announced", releaseDate: { precision: "unknown", value: null } } as Partial<PlatformRelease>),
        item("late", { status: "delayed", releaseDate: { precision: "month", value: "2026-07" } }),
      ], { earliestYear: 2026, latestYear: 2027 }),
    );

    expect(model.items.map((game) => [game.state, game.shortDate, game.date])).toEqual([
      ["released", "5 mar 2026", "5 de marzo de 2026"],
      ["upcoming", "T1 2027", "1.er trimestre de 2027"],
      ["undated", "Por confirmar", "Fecha por confirmar"],
      ["delayed", "jul 2026", "julio de 2026"],
    ]);
    expect(model.years).toEqual({ earliest: 2026, latest: 2027 });
    expect(model.genres).toEqual([{ id: "adventure", name: "Aventura" }]);
  });

  it("keeps an explaining alias only when it differs from the title, and no years without a known one", () => {
    const model = toPlatformCatalogueViewModel(
      page([
        item("a", {}, { matchedAlias: "Otro nombre" }),
        item("b", {}, { canonicalTitle: "Mismo", matchedAlias: "Mismo" }),
      ]),
    );

    expect(model.items.map((game) => game.matchedAlias)).toEqual(["Otro nombre", null]);
    expect(model.years).toBeNull();
    // Without a usable reading there is no score to show, never a zero.
    expect(model.items[0]?.scores).toEqual([]);
  });
});
