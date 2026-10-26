/**
 * TOPómetro's URL state (#386): only the ranking source, so a shared link always opens on the
 * podium of the same ranking. Loaded continuation stays in the query cache of the document, where
 * Back and the game page's way back find it again; a reload starts at the podium.
 */
export const TOPOMETRO_PATH = "/topometro";

export type RankingSource = "igdb" | "gameometro";

/** IGDB players cover most released games, so a cold-start ranking is never empty (#386). */
export const DEFAULT_RANKING_SOURCE: RankingSource = "igdb";

const SOURCE_PARAMETER = "source";

/** The contract's name for each source; the URL keeps the shorter, readable one. */
export const API_RANKING_SOURCE = {
  igdb: "igdb_players",
  gameometro: "gameometro_community",
} as const satisfies Record<RankingSource, string>;

/** Anything but an explicit `gameometro` reads as the default ranking, never as an error. */
export function readRankingSource(params: URLSearchParams): RankingSource {
  return params.get(SOURCE_PARAMETER) === "gameometro" ? "gameometro" : DEFAULT_RANKING_SOURCE;
}

/** The canonical path of a source's ranking; the default carries no parameter. */
export function rankingPath(source: RankingSource): string {
  return source === DEFAULT_RANKING_SOURCE ? TOPOMETRO_PATH : `${TOPOMETRO_PATH}?${SOURCE_PARAMETER}=${source}`;
}
