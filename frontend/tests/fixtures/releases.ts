import type { components } from "../../src/shared/api/generated/schema";

type ReleasePage = components["schemas"]["ReleasePage"];

export const pragmataRelease: components["schemas"]["Release"] = {
  releaseId: "40000000-0000-4000-8000-000000000006",
  stage: "unknown",
  gameId: "30000000-0000-4000-8000-000000000006",
  platform: { platformId: "windows-pc", name: "Windows PC" },
  region: { regionId: "worldwide", name: "Mundial" },
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
};

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
  releases: [pragmataRelease],
};

export function releasePage(overrides: Partial<ReleasePage> = {}): ReleasePage {
  return {
    view: "recent",
    evaluatedOn: "2026-08-13",
    window: { from: "2026-02-13", to: "2026-08-13" },
    activeFilters: { platformIds: [], regionIds: [] },
    availableFilters: {
      platforms: [
        { platformId: "playstation-5", name: "PlayStation 5" },
        { platformId: "windows-pc", name: "Windows PC" },
      ],
      regions: [{ regionId: "worldwide", name: "Mundial" }],
    },
    items: [pragmata],
    page: { number: 1, size: 6, totalItems: 1, totalPages: 1 },
    ...overrides,
  };
}

type FeaturedReleases = components["schemas"]["FeaturedReleases"];
type FeaturedReleaseItem = components["schemas"]["FeaturedReleaseItem"];
type FeaturedMedia = Partial<Pick<FeaturedReleaseItem, "primaryCover" | "featuredImage" | "logo">>;

const igdb = { label: "IGDB", sourceUrl: "https://www.igdb.com/games/featured-fixture" };

/** A fixture IGDB CDN URL; browser specs serve these from generated images, never the network. */
export function cdn(size: string, id: string, extension = "webp"): string {
  return `https://images.igdb.com/igdb/image/upload/${size}/${id}.${extension}`;
}

/** A wide artwork or screenshot the frames may crop to fill. */
export function landscapeMedia(kind: "artwork" | "screenshot", id: string, title: string): FeaturedMedia {
  return {
    featuredImage: {
      kind,
      presentation: "fill",
      url: cdn("t_1080p", id),
      compactUrl: cdn("t_screenshot_big", id),
      alternativeText: `${kind === "artwork" ? "Arte" : "Captura"} de ${title}`,
      attribution: igdb,
    },
  };
}

/** A transparent title logo. */
export function titleLogo(id: string, title: string): FeaturedMedia {
  return { logo: { url: cdn("t_logo_med_2x", id, "png"), alternativeText: title, attribution: igdb } };
}

/** No usable artwork or screenshot: the provider cover, shown whole. */
export function coverMedia(id: string, title: string): FeaturedMedia {
  const url = cdn("t_cover_big_2x", id);
  return {
    primaryCover: { kind: "provider", url, alternativeText: `Carátula de ${title}`, attribution: igdb },
    featuredImage: {
      kind: "cover",
      presentation: "contain",
      url,
      compactUrl: url,
      alternativeText: `Carátula de ${title}`,
      attribution: igdb,
    },
  };
}

/** One featured game whose presented release falls on `day`, optionally at month precision. */
export function featuredItem(
  number: number,
  title: string,
  releaseDate: components["schemas"]["ReleaseDate"],
  platforms: Array<[string, string]> = [["10000000-0000-4000-8000-000000000001", "PlayStation 5"]],
  media: FeaturedMedia = {},
): FeaturedReleaseItem {
  const gameId = `30000000-0000-4000-8000-${String(900 + number).padStart(12, "0")}`;
  return {
    gameId,
    slug: title.toLowerCase().normalize("NFD").replace(/[^a-z0-9]+/g, "-").replace(/(^-|-$)/g, ""),
    canonicalTitle: title,
    primaryCover: {
      kind: "fallback",
      url: "/assets/covers/fallback.svg",
      alternativeText: `Portada no disponible de ${title}`,
      attribution: null,
    },
    featuredImage: {
      kind: "fallback",
      presentation: "fill",
      url: "/assets/featured/fallback.svg",
      compactUrl: "/assets/featured/fallback.svg",
      alternativeText: `Imagen destacada no disponible de ${title}`,
      attribution: null,
    },
    ...media,
    releases: platforms.map(([platformId, name], index) => ({
      ...pragmataRelease,
      releaseId: `40000000-0000-4000-8000-${String(900 + number * 10 + index).padStart(12, "0")}`,
      gameId,
      platform: { platformId, name },
      region: { regionId: "20000000-0000-4000-8000-000000000001", name: "Mundial" },
      releaseDate,
      status: "scheduled",
    })),
  };
}

/**
 * A ranked month: a long-titled featured release over several platforms with its artwork and
 * logo, then five more covering every media path: artwork, a screenshot with its logo, a provider
 * cover shown whole, the product fallback, and artwork again.
 */
export function featuredReleases(overrides: Partial<FeaturedReleases> = {}): FeaturedReleases {
  const lead = "Una aventura extraordinariamente larga: más allá del horizonte";
  return {
    month: "2026-08",
    evaluatedOn: "2026-08-13",
    window: { from: "2026-08-01", to: "2026-08-31" },
    selection: {
      status: "ranked",
      popularityFreshness: "fresh",
      popularityObservedAt: "2026-08-09T10:00:00Z",
    },
    items: [
      featuredItem(
        1,
        lead,
        { precision: "day", value: "2026-08-20" },
        [
          ["10000000-0000-4000-8000-000000000001", "PlayStation 5"],
          ["10000000-0000-4000-8000-000000000003", "Windows PC"],
          ["10000000-0000-4000-8000-000000000004", "Xbox Series X|S"],
          ["platform-new", "Plataforma recién adquirida"],
        ],
        { ...landscapeMedia("artwork", "arlead", lead), ...titleLogo("lolead", lead) },
      ),
      featuredItem(2, "Pragmata", { precision: "day", value: "2026-08-04" }, undefined, landscapeMedia("artwork", "arpragmata", "Pragmata")),
      featuredItem(
        3,
        "Ghost of Yōtei",
        { precision: "month", value: "2026-08" },
        [
          ["10000000-0000-4000-8000-000000000001", "PlayStation 5"],
          ["10000000-0000-4000-8000-000000000002", "Nintendo Switch 2"],
          ["10000000-0000-4000-8000-000000000003", "Windows PC"],
          ["10000000-0000-4000-8000-000000000004", "Xbox Series X|S"],
        ],
        { ...landscapeMedia("screenshot", "scyotei", "Ghost of Yōtei"), ...titleLogo("loyotei", "Ghost of Yōtei") },
      ),
      featuredItem(4, "Fable", { precision: "day", value: "2026-08-28" }, undefined, coverMedia("cofable", "Fable")),
      featuredItem(5, "Subnautica 2", { precision: "day", value: "2026-08-11" }),
      featuredItem(6, "The Witcher IV", { precision: "day", value: "2026-08-31" }, undefined, landscapeMedia("artwork", "arwitcher", "The Witcher IV")),
    ],
    ...overrides,
  };
}
