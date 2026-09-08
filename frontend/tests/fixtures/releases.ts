import type { components } from "../../src/shared/api/generated/schema";

type ReleasePage = components["schemas"]["ReleasePage"];

export const pragmata: ReleasePage["items"][number] = {
  gameId: "30000000-0000-4000-8000-000000000006",
  slug: "pragmata",
  canonicalTitle: "Pragmata",
  primaryCover: {
    kind: "fallback",
    url: "/assets/covers/fallback.svg",
    alternativeText: "Portada no disponible de Pragmata",
    attribution: null,
  },
  release: {
    releaseId: "40000000-0000-4000-8000-000000000006",
    gameId: "30000000-0000-4000-8000-000000000006",
    platform: { platformId: "windows-pc", name: "Windows PC" },
    region: { regionId: "worldwide", name: "Worldwide" },
    releaseDate: { precision: "quarter", value: "2026-Q2" },
    status: "released",
    provenance: {
      sourceKind: "product_curated",
      sourceName: "VideoGame Platform clickable prototype",
      sourceEntityType: "prototype_release",
    },
    lastSyncedAt: "2026-08-09T10:00:00Z",
    verificationLevel: "provider_only",
    reviewStatus: "not_required",
    freshnessStatus: "fresh",
  },
};

export function releasePage(overrides: Partial<ReleasePage> = {}): ReleasePage {
  return {
    view: "recent",
    evaluatedOn: "2026-08-13",
    window: { from: "2026-02-13", to: "2026-08-13" },
    activeFilters: { platformId: null, regionId: null },
    availableFilters: {
      platforms: [
        { platformId: "playstation-5", name: "PlayStation 5" },
        { platformId: "windows-pc", name: "Windows PC" },
      ],
      regions: [{ regionId: "worldwide", name: "Worldwide" }],
    },
    items: [pragmata],
    page: { number: 1, size: 6, totalItems: 1, totalPages: 1 },
    ...overrides,
  };
}
