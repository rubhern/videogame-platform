import type { components } from "../shared/api/generated/schema";
type GameDetails = components["schemas"]["GameDetails"];

export function gameDetailsFixture(): GameDetails {
  return {
    gameId: "30000000-0000-4000-8000-000000000005",
    slug: "resident-evil-requiem",
    canonicalTitle: "Resident Evil Requiem",
    aliases: ["RE Requiem"],
    summary: { kind: "editorial", text: "Resumen de prueba del catálogo.", language: "es" },
    primaryCover: {
      kind: "fallback",
      url: "/assets/covers/fallback.svg",
      alternativeText: "Carátula oficial no disponible",
      attribution: null,
    },
    releases: [
      {
        releaseId: "release-one",
        gameId: "30000000-0000-4000-8000-000000000005",
        platform: { platformId: "playstation-5", name: "PlayStation 5" },
        region: { regionId: "europe", name: "Europe" },
        releaseDate: { precision: "day", value: "2026-02-27" },
        status: "released",
        provenance: {
          sourceKind: "official_source",
          sourceName: "Publisher",
          sourceEntityType: "release",
        },
        lastSyncedAt: "2026-08-09T10:00:00Z",
        lastVerifiedAt: "2026-08-09T10:00:00Z",
        verificationLevel: "verified",
        reviewStatus: "not_required",
        freshnessStatus: "fresh",
      },
    ],
    ratingEligibility: {
      eligible: true,
      reason: "ELIGIBLE_RELEASE_FOUND",
      evaluatedOn: "2026-08-13",
    },
    ratingStatistics: {
      status: "available",
      mean: null,
      count: 0,
      distribution: {
        "1": 0,
        "2": 0,
        "3": 0,
        "4": 0,
        "5": 0,
        "6": 0,
        "7": 0,
        "8": 0,
        "9": 0,
        "10": 0,
      },
    },
  };
}
