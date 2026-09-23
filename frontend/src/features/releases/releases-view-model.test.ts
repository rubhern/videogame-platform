import { describe, expect, it } from "vitest";

import type { components } from "../../shared/api/generated/schema";
import { toReleaseListItems, toReleasesViewModel } from "./releases-view-model";

const fallbackCover = {
  kind: "fallback",
  url: "/assets/covers/fallback.svg",
  alternativeText: "Portada no disponible de Pragmata",
  attribution: null,
} satisfies components["schemas"]["Cover"];

const provenance = {
  sourceKind: "product_curated",
  sourceName: "VideoGame Platform clickable prototype",
  sourceEntityType: "prototype_release",
} satisfies components["schemas"]["Provenance"];

function release(
  overrides: Partial<components["schemas"]["Release"]> = {},
): components["schemas"]["Release"] {
  return {
    releaseId: "40000000-0000-4000-8000-000000000006",
    gameId: "30000000-0000-4000-8000-000000000006",
    platform: { platformId: "playstation-5", name: "PlayStation 5" },
    region: { regionId: "worldwide", name: "Worldwide" },
    releaseDate: { precision: "day", value: "2026-09-24" },
    status: "released",
    provenance,
    lastSyncedAt: "2026-08-09T10:00:00Z",
    verificationLevel: "provider_only",
    reviewStatus: "not_required",
    freshnessStatus: "fresh",
    ...overrides,
  };
}

function page(
  releases: components["schemas"]["Release"][],
  overrides: Partial<components["schemas"]["ReleasePage"]> = {},
): components["schemas"]["ReleasePage"] {
  return {
    view: "recent",
    evaluatedOn: "2026-08-13",
    window: { from: "2026-02-13", to: "2026-08-13" },
    activeFilters: { platformIds: [], regionIds: [] },
    availableFilters: { platforms: [], regions: [] },
    items: [
      {
        gameId: "30000000-0000-4000-8000-000000000006",
        slug: "pragmata",
        canonicalTitle: "Pragmata",
        primaryCover: fallbackCover,
        releases,
      },
    ],
    page: { number: 1, size: 6, totalItems: 1, totalPages: 1 },
    ...overrides,
  };
}

describe("release group projection", () => {
  it("keeps one group with one platform for a single release", () => {
    const [item] = toReleaseListItems(page([release()]));

    expect(item?.releaseGroups).toHaveLength(1);
    expect(item?.releaseGroups[0]).toMatchObject({
      platforms: ["PlayStation 5"],
      region: "Mundial",
      releaseCount: 1,
    });
    expect(item?.hiddenReleaseCount).toBe(0);
  });

  it("joins platforms that share the same date and region into one group", () => {
    const [item] = toReleaseListItems(
      page([
        release({ releaseId: "r-ps5", platform: { platformId: "playstation-5", name: "PlayStation 5" } }),
        release({ releaseId: "r-pc", platform: { platformId: "windows-pc", name: "Windows PC" } }),
      ]),
    );

    expect(item?.releaseGroups).toHaveLength(1);
    expect(item?.releaseGroups[0]).toMatchObject({
      date: "24 de septiembre de 2026",
      region: "Mundial",
      platforms: ["PlayStation 5", "Windows PC"],
      releaseCount: 2,
    });
    expect(item?.hiddenReleaseCount).toBe(0);
  });

  it("separates groups by date or region and counts hidden releases, not groups", () => {
    const [item] = toReleaseListItems(
      page([
        release({ releaseId: "r-ps5", platform: { platformId: "playstation-5", name: "PlayStation 5" } }),
        release({ releaseId: "r-pc", platform: { platformId: "windows-pc", name: "Windows PC" } }),
        release({ releaseId: "r-xbox", platform: { platformId: "xbox-series", name: "Xbox Series X|S" } }),
        release({
          releaseId: "r-switch",
          platform: { platformId: "nintendo-switch-2", name: "Nintendo Switch 2" },
          region: { regionId: "europe", name: "Europe" },
          releaseDate: { precision: "quarter", value: "2026-Q4" },
        }),
      ]),
    );

    // First group holds the three worldwide platforms sharing the day; the European quarter is a
    // second group. Two groups, but four releases → one release hidden behind the primary group.
    expect(item?.releaseGroups).toHaveLength(2);
    expect(item?.releaseGroups[0]?.platforms).toEqual([
      "PlayStation 5",
      "Windows PC",
      "Xbox Series X|S",
    ]);
    expect(item?.releaseGroups[1]).toMatchObject({
      date: "4.º trimestre de 2026",
      region: "Europa",
      platforms: ["Nintendo Switch 2"],
      releaseCount: 1,
    });
    expect(item?.hiddenReleaseCount).toBe(1);
  });

  it("reports the hidden release count across several hidden groups", () => {
    const [item] = toReleaseListItems(
      page([
        release({ releaseId: "r-ps5" }),
        release({
          releaseId: "r-eu",
          region: { regionId: "europe", name: "Europe" },
          releaseDate: { precision: "quarter", value: "2026-Q4" },
        }),
        release({
          releaseId: "r-jp",
          region: { regionId: "japan", name: "Japan" },
          releaseDate: { precision: "year", value: "2027" },
        }),
      ]),
    );

    expect(item?.releaseGroups).toHaveLength(3);
    expect(item?.hiddenReleaseCount).toBe(2);
  });
});

const providerCover = {
  kind: "provider",
  url: "https://images.igdb.com/igdb/image/upload/t_cover_big_2x/coexample.webp",
  alternativeText: "Carátula de Pragmata",
  attribution: { label: "IGDB", sourceUrl: "https://www.igdb.com/games/pragmata" },
} satisfies components["schemas"]["Cover"];

const upcomingPage = page(
  [
    release({
      releaseId: "40000000-0000-4000-8000-000000000008",
      gameId: "30000000-0000-4000-8000-000000000008",
      platform: { platformId: "windows-pc", name: "Windows PC" },
      region: { regionId: "unknown", name: "Unknown" },
      releaseDate: { precision: "unknown", value: null },
      status: "announced",
      reviewStatus: "required",
      freshnessStatus: "stale",
    }),
  ],
  {
    view: "upcoming",
    window: { from: "2026-08-13", to: "2027-02-13" },
    activeFilters: { platformIds: ["windows-pc"], regionIds: [] },
    availableFilters: {
      platforms: [{ platformId: "windows-pc", name: "Windows PC" }],
      regions: [{ regionId: "unknown", name: "Unknown" }],
    },
    items: [
      {
        gameId: "30000000-0000-4000-8000-000000000008",
        slug: "the-witcher-iv",
        canonicalTitle: "The Witcher IV",
        primaryCover: providerCover,
        releases: [
          release({
            releaseId: "40000000-0000-4000-8000-000000000008",
            gameId: "30000000-0000-4000-8000-000000000008",
            platform: { platformId: "windows-pc", name: "Windows PC" },
            region: { regionId: "unknown", name: "Unknown" },
            releaseDate: { precision: "unknown", value: null },
            status: "announced",
            reviewStatus: "required",
            freshnessStatus: "stale",
          }),
        ],
      },
    ],
    page: { number: 2, size: 12, totalItems: 13, totalPages: 2 },
  },
);

describe("releases view model", () => {
  it("describes the evaluated window without inventing dates", () => {
    const model = toReleasesViewModel(upcomingPage);

    expect(model.title).toBe("Próximos lanzamientos");
    expect(model.windowDescription).toBe("Del 13 de agosto de 2026 al 13 de febrero de 2027");
    expect(model.items[0]?.releaseGroups[0]?.date).toBe("Fecha por confirmar");
    expect(model.items[0]?.releaseGroups[0]?.region).toBe("Sin región confirmada");
  });

  it("exposes the available filters and the active values the API confirmed", () => {
    const model = toReleasesViewModel(upcomingPage);

    expect(model.platforms).toEqual([{ id: "windows-pc", name: "Windows PC" }]);
    expect(model.regions).toEqual([{ id: "unknown", name: "Sin región confirmada" }]);
    expect(model.activePlatformIds).toEqual(["windows-pc"]);
    expect(model.activeRegionIds).toEqual([]);
    expect(model.page).toEqual({ number: 2, size: 12, totalItems: 13, totalPages: 2 });
  });

  it("keeps stale and review information explicit per group", () => {
    const model = toReleasesViewModel(upcomingPage);

    expect(model.staleItemCount).toBe(1);
    expect(model.items[0]?.isStale).toBe(true);
    expect(model.items[0]?.releaseGroups[0]?.review).toBe(true);
  });

  it("preserves provider cover attribution and the product-owned fallback", () => {
    expect(toReleasesViewModel(upcomingPage).items[0]?.cover).toEqual({
      kind: "provider",
      url: "https://images.igdb.com/igdb/image/upload/t_cover_big_2x/coexample.webp",
      alternativeText: "Carátula de Pragmata",
      attribution: { label: "IGDB", sourceUrl: "https://www.igdb.com/games/pragmata" },
    });
    expect(toReleasesViewModel(page([release()])).items[0]?.cover).toEqual({
      kind: "fallback",
      url: "/assets/covers/fallback.svg",
      alternativeText: "Portada no disponible de Pragmata",
    });
  });
});
