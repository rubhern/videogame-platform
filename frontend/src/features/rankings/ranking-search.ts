import { PLATFORMS_PATH } from "../platforms/platform-context";

/**
 * TOPómetro's URL state (#386): only the ranking source, so a shared link always opens on the
 * podium of the same ranking. A platform's TOPómetro (#387) names its platform in the path, as
 * Explorar catálogo does, and keeps the same source parameter. Loaded continuation stays in the
 * query cache of the document, where Back and the game page's way back find it again; a reload
 * starts at the podium.
 */
export const TOPOMETRO_PATH = "/topometro";

/** The destination's own segment under a platform. */
const TOPOMETRO_SEGMENT = "topometro";

/** `/plataformas/<key>/topometro`, where the key is a featured family's or a platform's own ID. */
const PLATFORM_TOPOMETRO = new RegExp(`^${PLATFORMS_PATH}/([^/]+)/${TOPOMETRO_SEGMENT}$`);

export const PLATFORM_TOPOMETRO_ROUTE = `${PLATFORMS_PATH.slice(1)}/:platformKey/${TOPOMETRO_SEGMENT}`;

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

/**
 * The canonical path of a source's ranking, globally or on one platform; the default source
 * carries no parameter.
 */
export function rankingPath(source: RankingSource, platformKey?: string): string {
  const path = platformKey === undefined
    ? TOPOMETRO_PATH
    : `${PLATFORMS_PATH}/${encodeURIComponent(platformKey)}/${TOPOMETRO_SEGMENT}`;
  return source === DEFAULT_RANKING_SOURCE ? path : `${path}?${SOURCE_PARAMETER}=${source}`;
}

/** The platform key a pathname names as a platform TOPómetro, or null for any other path. */
export function readPlatformRankingKey(pathname: string): string | null {
  const match = PLATFORM_TOPOMETRO.exec(pathname);
  if (match?.[1] === undefined) return null;
  try {
    return decodeURIComponent(match[1]);
  } catch {
    return null;
  }
}
