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
  hiddenPlatforms: GameSuggestionPlatform[];
  year: string;
};

export type GameSuggestions = { suggestions: GameSuggestion[]; totalItems: number };

type GameSummary = GameSearchPage["items"][number];

/** Every release-date precision except `unknown` starts with its four-digit year. */
function releaseYear(context: GameSummary["releaseContext"][number]): number | null {
  const value = "value" in context.releaseDate ? context.releaseDate.value : null;
  const match = value === null ? null : /^(\d{4})/.exec(value);
  return match === null ? null : Number(match[1]);
}

/** One known year, an inclusive range across several, or `Por confirmar` without any. */
export function suggestionYear(releaseContext: GameSummary["releaseContext"]): string {
  const years = releaseContext.map(releaseYear).filter((year) => year !== null);
  if (years.length === 0) {
    return "Por confirmar";
  }
  const first = Math.min(...years);
  const last = Math.max(...years);
  return first === last ? String(first) : `${first}–${last}`;
}

function distinctPlatforms(releaseContext: GameSummary["releaseContext"]): GameSuggestionPlatform[] {
  const platforms = new Map<string, GameSuggestionPlatform>();
  for (const { platform } of releaseContext) {
    if (!platforms.has(platform.platformId)) {
      platforms.set(platform.platformId, {
        id: platform.platformId,
        name: platform.name,
        icon: platformIcon(platform.platformId, platform.name),
      });
    }
  }
  return [...platforms.values()];
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
      const platforms = distinctPlatforms(item.releaseContext);
      return {
        gameId: item.gameId,
        title: item.canonicalTitle,
        path: `/games/${item.gameId}/${item.slug}`,
        alias: explainingAlias(item),
        cover: toCover(item.primaryCover),
        platforms: platforms.slice(0, VISIBLE_SUGGESTION_PLATFORMS),
        hiddenPlatforms: platforms.slice(VISIBLE_SUGGESTION_PLATFORMS),
        year: suggestionYear(item.releaseContext),
      };
    }),
    totalItems: page.page.totalItems,
  };
}

/** The option's accessible name never depends on the decorative platform icons. */
export function suggestionAccessibleName(suggestion: GameSuggestion): string {
  const platforms = [...suggestion.platforms, ...suggestion.hiddenPlatforms].map(({ name }) => name);
  return [
    suggestion.title,
    suggestion.alias === null ? null : `también conocido como ${suggestion.alias}`,
    platforms.length === 0 ? null : platforms.join(", "),
    suggestion.year,
  ]
    .filter((part) => part !== null)
    .join(" · ");
}
