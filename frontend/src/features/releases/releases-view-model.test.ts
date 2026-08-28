import { describe, expect, it } from "vitest";

import type { components } from "../../shared/api/generated/schema";
import { toReleaseListItems } from "./releases-view-model";

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
