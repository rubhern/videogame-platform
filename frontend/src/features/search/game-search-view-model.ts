import { formatReleaseDate } from "../../shared/catalogue/release-date";
import type { GameSearchPage } from "./game-search-api";

type GameSummary = GameSearchPage["items"][number];
type Cover = GameSummary["primaryCover"];
type ReleaseSummary = GameSummary["releaseContext"][number];
type ReleaseStatus = ReleaseSummary["status"];

export type GameSearchCover =
  | {
      kind: "provider";
      url: string;
      alternativeText: string;
      attribution: { label: string; sourceUrl: string };
    }
  | { kind: "fallback"; url: string; alternativeText: string };

export type GameSearchReleaseContext = {
  key: string;
  platform: string;
  region: string;
  date: string;
  status: string;
  isStale: boolean;
};

export type GameSearchResult = {
  gameId: string;
  slug: string;
  title: string;
  matchedAlias: string | null;
  cover: GameSearchCover;
  releaseContext: GameSearchReleaseContext[];
  hasStaleContext: boolean;
};

export type GameSearchViewModel = {
  results: GameSearchResult[];
  page: { number: number; size: number; totalItems: number; totalPages: number };
};

const statusLabels: Record<ReleaseStatus, string> = {
  announced: "Anunciado",
  scheduled: "Programado",
  released: "Publicado",
  delayed: "Retrasado",
  cancelled: "Cancelado",
  unknown: "Estado sin confirmar",
};

/**
 * The reviewed contract distinguishes both cover variants by their attribution: a
 * provider cover always carries one and the product-owned fallback never does.
 */
function toCover(cover: Cover): GameSearchCover {
  const attribution = "attribution" in cover ? cover.attribution : null;

  if (attribution === null) {
    return { kind: "fallback", url: cover.url, alternativeText: cover.alternativeText };
  }

  return {
    kind: "provider",
    url: cover.url,
    alternativeText: cover.alternativeText,
    attribution: { label: attribution.label, sourceUrl: attribution.sourceUrl },
  };
}

function toReleaseContext(context: ReleaseSummary, index: number): GameSearchReleaseContext {
  return {
    // The contract's concise release context carries no release identifier, so the
    // stable position inside the bounded list is the row identity.
    key: `${context.platform.platformId}-${context.region.regionId}-${index}`,
    platform: context.platform.name,
    region: context.region.name,
    date: formatReleaseDate(context.releaseDate),
    status: statusLabels[context.status],
    isStale: context.freshnessStatus === "stale",
  };
}

export function toGameSearchViewModel(page: GameSearchPage): GameSearchViewModel {
  return {
    results: page.items.map((item) => {
      const releaseContext = item.releaseContext.map(toReleaseContext);
      return {
        gameId: item.gameId,
        slug: item.slug,
        title: item.canonicalTitle,
        matchedAlias: item.matchedAlias ?? null,
        cover: toCover(item.primaryCover),
        releaseContext,
        hasStaleContext: releaseContext.some((context) => context.isStale),
      };
    }),
    page: page.page,
  };
}
