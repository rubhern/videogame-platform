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

const sameGameTwoReleases = {
  view: "recent",
  evaluatedOn: "2026-08-13",
  window: { from: "2026-02-13", to: "2026-08-13" },
  activeFilters: { platformId: null, regionId: null },
  availableFilters: { platforms: [], regions: [] },
  items: [
    {
      gameId: "30000000-0000-4000-8000-000000000006",
      slug: "pragmata",
      canonicalTitle: "Pragmata",
      primaryCover: fallbackCover,
      release: {
        releaseId: "40000000-0000-4000-8000-000000000006",
        gameId: "30000000-0000-4000-8000-000000000006",
        platform: { platformId: "playstation-5", name: "PlayStation 5" },
        region: { regionId: "europe", name: "Europe" },
        releaseDate: { precision: "day", value: "2026-04-24" },
        status: "released",
        provenance,
        lastSyncedAt: "2026-08-09T10:00:00Z",
        verificationLevel: "provider_only",
        reviewStatus: "not_required",
        freshnessStatus: "fresh",
      },
    },
    {
      gameId: "30000000-0000-4000-8000-000000000006",
      slug: "pragmata",
      canonicalTitle: "Pragmata",
      primaryCover: fallbackCover,
      release: {
        releaseId: "40000000-0000-4000-8000-000000000007",
        gameId: "30000000-0000-4000-8000-000000000006",
        platform: { platformId: "windows-pc", name: "Windows PC" },
        region: { regionId: "worldwide", name: "Worldwide" },
        releaseDate: { precision: "day", value: "2026-04-25" },
        status: "released",
        provenance,
        lastSyncedAt: "2026-08-09T10:00:00Z",
        verificationLevel: "provider_only",
        reviewStatus: "not_required",
        freshnessStatus: "fresh",
      },
    },
  ],
  page: { number: 1, size: 6, totalItems: 2, totalPages: 1 },
} satisfies components["schemas"]["ReleasePage"];

describe("release list projection", () => {
  it("identifies each row by its own releaseId when one game has several releases", () => {
    const items = toReleaseListItems(sameGameTwoReleases);

    expect(items.map((item) => item.releaseId)).toEqual([
      "40000000-0000-4000-8000-000000000006",
      "40000000-0000-4000-8000-000000000007",
    ]);
    expect(new Set(items.map((item) => item.releaseId)).size).toBe(items.length);
    expect(items.map((item) => item.gameId)).toEqual([
      "30000000-0000-4000-8000-000000000006",
      "30000000-0000-4000-8000-000000000006",
    ]);
  });

  it("keeps the platform, region and date presentation of each release distinct", () => {
    const [europeanRelease, worldwideRelease] = toReleaseListItems(sameGameTwoReleases);

    expect(europeanRelease).toMatchObject({
      title: "Pragmata",
      slug: "pragmata",
      date: "24 de abril de 2026",
      platform: "PlayStation 5",
      region: "Europe",
    });
    expect(worldwideRelease).toMatchObject({
      title: "Pragmata",
      slug: "pragmata",
      date: "25 de abril de 2026",
      platform: "Windows PC",
      region: "Worldwide",
    });
  });
});

const providerCover = {
  kind: "provider",
  url: "https://images.igdb.com/igdb/image/upload/t_cover_big/coexample.webp",
  alternativeText: "Carátula de Pragmata",
  attribution: { label: "IGDB", sourceUrl: "https://www.igdb.com/games/pragmata" },
} satisfies components["schemas"]["Cover"];

const upcomingPage = {
  view: "upcoming",
  evaluatedOn: "2026-08-13",
  window: { from: "2026-08-13", to: "2027-02-13" },
  activeFilters: { platformId: "windows-pc", regionId: null },
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
      release: {
        releaseId: "40000000-0000-4000-8000-000000000008",
        gameId: "30000000-0000-4000-8000-000000000008",
        platform: { platformId: "windows-pc", name: "Windows PC" },
        region: { regionId: "unknown", name: "Unknown" },
        releaseDate: { precision: "unknown", value: null },
        status: "announced",
        provenance,
        lastSyncedAt: "2026-07-01T10:00:00Z",
        verificationLevel: "provider_only",
        reviewStatus: "required",
        freshnessStatus: "stale",
      },
    },
  ],
  page: { number: 2, size: 12, totalItems: 13, totalPages: 2 },
} satisfies components["schemas"]["ReleasePage"];

describe("releases view model", () => {
  it("describes the evaluated window without inventing dates", () => {
    const model = toReleasesViewModel(upcomingPage);

    expect(model.title).toBe("Próximos lanzamientos");
    expect(model.windowDescription).toBe("Del 13 de agosto de 2026 al 13 de febrero de 2027");
    expect(model.evaluatedOnDescription).toBe("Ventana evaluada el 13 de agosto de 2026");
    expect(model.items[0]?.date).toBe("Fecha por confirmar");
  });

  it("exposes the available filters and the active values the API confirmed", () => {
    const model = toReleasesViewModel(upcomingPage);

    expect(model.platforms).toEqual([{ id: "windows-pc", name: "Windows PC" }]);
    expect(model.regions).toEqual([{ id: "unknown", name: "Unknown" }]);
    expect(model.activePlatformId).toBe("windows-pc");
    expect(model.activeRegionId).toBeNull();
    expect(model.page).toEqual({ number: 2, size: 12, totalItems: 13, totalPages: 2 });
  });

  it("keeps stale, review and status information explicit", () => {
    const model = toReleasesViewModel(upcomingPage);

    expect(model.staleItemCount).toBe(1);
    expect(model.items[0]).toMatchObject({
      status: "Anunciado",
      isStale: true,
      freshness: "Datos locales desactualizados",
      review: "Información pendiente de revisión",
    });
  });

  it("preserves provider cover attribution and the product-owned fallback", () => {
    expect(toReleasesViewModel(upcomingPage).items[0]?.cover).toEqual({
      kind: "provider",
      url: "https://images.igdb.com/igdb/image/upload/t_cover_big/coexample.webp",
      alternativeText: "Carátula de Pragmata",
      attribution: { label: "IGDB", sourceUrl: "https://www.igdb.com/games/pragmata" },
    });
    expect(toReleasesViewModel(sameGameTwoReleases).items[0]?.cover).toEqual({
      kind: "fallback",
      url: "/assets/covers/fallback.svg",
      alternativeText: "Portada no disponible de Pragmata",
    });
  });
});
