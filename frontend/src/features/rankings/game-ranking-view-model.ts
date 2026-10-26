import { toExternalPlayerScore } from "../../shared/score/external-player-score";
import {
  compactPlatforms,
  releaseYearLabel,
  toCover,
  type GameSearchCover,
  type GameSearchPlatform,
} from "../search/game-search-view-model";
import type { GameRankingPage } from "./game-ranking-api";
import type { RankingSource } from "./ranking-search";

type RankingItem = GameRankingPage["items"][number];

/** The ranked source's own reading: never another source's, never a blend. */
export type RankingReading =
  | { source: "gameometro"; value: number; count: number }
  | { source: "igdb"; value: number; count: number; attributionUrl: string };

export type RankedGame = {
  position: number;
  gameId: string;
  slug: string;
  title: string;
  cover: GameSearchCover;
  platforms: GameSearchPlatform[];
  hiddenPlatformCount: number;
  year: string;
  genres: RankingItem["genres"];
  reading: RankingReading;
};

export type RankingView = {
  /** Places 1–3 of the one sequence, in order; fewer when fewer games are ranked. */
  podium: RankedGame[];
  /** Every further loaded place, in order. */
  rows: RankedGame[];
  /** Ranked games in the source, as the latest page counted them. */
  total: number;
  loaded: number;
};

export const PODIUM_PLACES = 3;

const exactCount = new Intl.NumberFormat("es-ES", { useGrouping: "always" });

/** The exact count in the source's own word: Gameómetro counts puntuaciones, IGDB valoraciones. */
export function readingCount(reading: RankingReading): string {
  const count = exactCount.format(reading.count);
  if (reading.source === "gameometro") {
    return `${count} ${reading.count === 1 ? "puntuación" : "puntuaciones"}`;
  }
  return `${count} ${reading.count === 1 ? "valoración" : "valoraciones"}`;
}


function toReading(score: RankingItem["score"], source: RankingSource): RankingReading | null {
  if (source === "gameometro") {
    if (score.source !== "gameometro" || !("kind" in score) || score.kind !== "community") return null;
    const { value, count } = score;
    return Number.isFinite(value) && value >= 1 && value <= 10 && Number.isInteger(count) && count > 0
      ? { source: "gameometro", value, count }
      : null;
  }
  if (!("attribution" in score)) return null;
  const external = toExternalPlayerScore(score);
  return external === null ? null : { source: "igdb", ...external };
}

/**
 * One ordered sequence from every loaded page. Scores change independently of the catalogue, so a
 * game can move between two page reads: its first occurrence keeps its place and a later repeat
 * is dropped, so no game is ever listed twice. A reading that is not the requested source's own is
 * never shown, nor replaced by an invented one.
 */
export function toRankingView(pages: readonly GameRankingPage[], source: RankingSource): RankingView {
  const seen = new Set<string>();
  const games: RankedGame[] = [];
  for (const page of pages) {
    for (const item of page.items) {
      if (seen.has(item.gameId)) continue;
      const reading = toReading(item.score, source);
      if (reading === null) continue;
      seen.add(item.gameId);
      games.push({
        position: item.position,
        gameId: item.gameId,
        slug: item.slug,
        title: item.canonicalTitle,
        cover: toCover(item.primaryCover),
        ...compactPlatforms(item.releaseSummary),
        year: releaseYearLabel(item.releaseSummary),
        genres: item.genres.slice(0, 2),
        reading,
      });
    }
  }
  const podium = games.filter((game) => game.position <= PODIUM_PLACES);
  return {
    podium,
    rows: games.filter((game) => game.position > PODIUM_PLACES),
    total: pages.at(-1)?.page.totalItems ?? 0,
    loaded: games.length,
  };
}
