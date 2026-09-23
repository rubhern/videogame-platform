import type { GameSearchPage } from "./game-search-api";
import { countCodePoints, MAX_QUERY_CODE_POINTS } from "./game-search-params";
import {
  compactPlatforms,
  explainingAlias,
  hiddenPlatformsLabel,
  releaseYearLabel,
  toCover,
  type GameSearchCover,
  type GameSearchPlatform,
} from "./game-search-view-model";

/**
 * Typeahead suggestions reuse the full catalogue search (#156): same endpoint, matching and
 * ranking, only a smaller first page. The threshold gates suggestion requests only; it never
 * redefines what an explicit full search accepts.
 */
export const MIN_SUGGESTION_CODE_POINTS = 2;
export const SUGGESTION_LIMIT = 5;
export const SUGGESTION_DEBOUNCE_MS = 250;

export function isSuggestionTerm(term: string): boolean {
  const length = countCodePoints(term);
  return length >= MIN_SUGGESTION_CODE_POINTS && length <= MAX_QUERY_CODE_POINTS;
}

export type GameSuggestion = {
  gameId: string;
  title: string;
  path: string;
  alias: string | null;
  cover: GameSearchCover;
  platforms: GameSearchPlatform[];
  /** Exact number of further distinct platforms, folded into the compact `+N`. */
  hiddenPlatformCount: number;
  year: string;
};

export type GameSuggestions = { suggestions: GameSuggestion[]; totalItems: number };

export function toGameSuggestions(page: GameSearchPage): GameSuggestions {
  return {
    suggestions: page.items.slice(0, SUGGESTION_LIMIT).map((item) => ({
      gameId: item.gameId,
      title: item.canonicalTitle,
      path: `/games/${item.gameId}/${item.slug}`,
      alias: explainingAlias(item),
      cover: toCover(item.primaryCover),
      ...compactPlatforms(item.releaseSummary),
      year: releaseYearLabel(item.releaseSummary),
    })),
    totalItems: page.page.totalItems,
  };
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
