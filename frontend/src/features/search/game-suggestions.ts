import { platformIcon } from "../../shared/catalogue/taxonomy-icons";
import type { SelectIconName } from "../../shared/ui/select-icon";
import type { GameSearchPage } from "./game-search-api";
import { countCodePoints, MAX_QUERY_CODE_POINTS } from "./game-search-params";
import { toCover, type GameSearchCover } from "./game-search-view-model";

/**
 * Typeahead suggestions reuse the full catalogue search (#156): same endpoint, matching and
 * ranking, only a smaller first page. The threshold gates suggestion requests only; it never
 * redefines what an explicit full search accepts.
 */
export const MIN_SUGGESTION_CODE_POINTS = 2;
export const SUGGESTION_LIMIT = 5;
export const SUGGESTION_DEBOUNCE_MS = 250;
/** Distinct platform icons shown per row before the compact `+N` overflow. */
export const VISIBLE_SUGGESTION_PLATFORMS = 3;

export function isSuggestionTerm(term: string): boolean {
  const length = countCodePoints(term);
  return length >= MIN_SUGGESTION_CODE_POINTS && length <= MAX_QUERY_CODE_POINTS;
}

export type GameSuggestionPlatform = { id: string; name: string; icon: SelectIconName };

export type GameSuggestion = {
  gameId: string;
  title: string;
  path: string;
  alias: string | null;
  cover: GameSearchCover;
  platforms: GameSuggestionPlatform[];
  /** Exact number of further distinct platforms, folded into the compact `+N`. */
  hiddenPlatformCount: number;
  year: string;
};

export type GameSuggestions = { suggestions: GameSuggestion[]; totalItems: number };

type GameSummary = GameSearchPage["items"][number];
type CompactReleaseSummary = GameSummary["releaseSummary"];

/** One known year, an inclusive range across several, or `Por confirmar` without any. */
export function suggestionYear({ earliestKnownYear, latestKnownYear }: CompactReleaseSummary): string {
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
function visiblePlatforms({ platforms }: CompactReleaseSummary): GameSuggestionPlatform[] {
  return platforms.slice(0, VISIBLE_SUGGESTION_PLATFORMS).map(({ platformId, name }) => ({
    id: platformId,
    name,
    icon: platformIcon(platformId, name),
  }));
}

/** An alias only explains the match when it is not merely the canonical title again. */
function explainingAlias(item: GameSummary): string | null {
  const alias = item.matchedAlias ?? null;
  return alias === null || alias.toLocaleLowerCase() === item.canonicalTitle.toLocaleLowerCase()
    ? null
    : alias;
}

export function toGameSuggestions(page: GameSearchPage): GameSuggestions {
  return {
    suggestions: page.items.slice(0, SUGGESTION_LIMIT).map((item) => {
      const platforms = visiblePlatforms(item.releaseSummary);
      return {
        gameId: item.gameId,
        title: item.canonicalTitle,
        path: `/games/${item.gameId}/${item.slug}`,
        alias: explainingAlias(item),
        cover: toCover(item.primaryCover),
        platforms,
        hiddenPlatformCount: Math.max(item.releaseSummary.totalPlatforms - platforms.length, 0),
        year: suggestionYear(item.releaseSummary),
      };
    }),
    totalItems: page.page.totalItems,
  };
}

/** Only the count of the platforms behind `+N` is known, never their names. */
export function hiddenPlatformsLabel(count: number): string {
  return count === 1 ? "1 plataforma más" : `${count} plataformas más`;
}

/** The option's accessible name never depends on the decorative platform icons. */
export function suggestionAccessibleName(suggestion: GameSuggestion): string {
  const platforms = suggestion.platforms.map(({ name }) => name).join(", ");
  return [
    suggestion.title,
    suggestion.alias === null ? null : `también conocido como ${suggestion.alias}`,
    platforms === "" ? null : platforms,
    suggestion.hiddenPlatformCount === 0 ? null : hiddenPlatformsLabel(suggestion.hiddenPlatformCount),
    suggestion.year,
  ]
    .filter((part) => part !== null)
    .join(" · ");
}
