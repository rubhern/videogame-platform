import { platformIcon } from "../../shared/catalogue/taxonomy-icons";
import type { SelectIconName } from "../../shared/ui/select-icon";
import type { GameSearchPage } from "./game-search-api";

type GameSummary = GameSearchPage["items"][number];
type Cover = GameSummary["primaryCover"];
type CompactReleaseSummary = GameSummary["releaseSummary"];

/** Distinct platform icons shown per result before the compact `+N` overflow. */
export const VISIBLE_PLATFORMS = 3;

export type GameSearchCover =
  | {
      kind: "provider";
      url: string;
      alternativeText: string;
      attribution: { label: string; sourceUrl: string };
    }
  | { kind: "fallback"; url: string; alternativeText: string };

export type GameSearchPlatform = { id: string; name: string; icon: SelectIconName };

export type GameSearchResult = {
  gameId: string;
  slug: string;
  title: string;
  /** Only set when the alias explains the match, never when it repeats the canonical title. */
  matchedAlias: string | null;
  cover: GameSearchCover;
  platforms: GameSearchPlatform[];
  /** Exact number of further distinct platforms, folded into the compact `+N`. */
  hiddenPlatformCount: number;
  year: string;
};

export type GameSearchViewModel = {
  results: GameSearchResult[];
  page: { number: number; size: number; totalItems: number; totalPages: number };
};

/**
 * The reviewed contract distinguishes both cover variants by their attribution: a
 * provider cover always carries one and the product-owned fallback never does.
 */
export function toCover(cover: Cover): GameSearchCover {
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

/** One known year, an inclusive range across several, or `Por confirmar` without any. */
export function releaseYearLabel({ earliestKnownYear, latestKnownYear }: CompactReleaseSummary): string {
  if (earliestKnownYear === undefined || latestKnownYear === undefined) {
    return "Por confirmar";
  }
  return earliestKnownYear === latestKnownYear
    ? String(earliestKnownYear)
    : `${earliestKnownYear}–${latestKnownYear}`;
}

/**
 * Platforms come from the compact summary over every stored release (#187), never from the
 * bounded `releaseContext` sample, which cannot tell the complete set or the exact `+N`.
 */
export function compactPlatforms(summary: CompactReleaseSummary): {
  platforms: GameSearchPlatform[];
  hiddenPlatformCount: number;
} {
  const platforms = summary.platforms.slice(0, VISIBLE_PLATFORMS).map(({ platformId, name }) => ({
    id: platformId,
    name,
    icon: platformIcon(platformId, name),
  }));
  return { platforms, hiddenPlatformCount: Math.max(summary.totalPlatforms - platforms.length, 0) };
}

/** Only the count of the platforms behind `+N` is known, never their names. */
export function hiddenPlatformsLabel(count: number): string {
  return count === 1 ? "1 plataforma más" : `${count} plataformas más`;
}

/** An alias only explains the match when it is not merely the canonical title again. */
export function explainingAlias(item: GameSummary): string | null {
  const alias = item.matchedAlias ?? null;
  return alias === null || alias.toLocaleLowerCase() === item.canonicalTitle.toLocaleLowerCase()
    ? null
    : alias;
}

export function toGameSearchViewModel(page: GameSearchPage): GameSearchViewModel {
  return {
    results: page.items.map((item) => ({
      gameId: item.gameId,
      slug: item.slug,
      title: item.canonicalTitle,
      matchedAlias: explainingAlias(item),
      cover: toCover(item.primaryCover),
      ...compactPlatforms(item.releaseSummary),
      year: releaseYearLabel(item.releaseSummary),
    })),
    page: page.page,
  };
}
