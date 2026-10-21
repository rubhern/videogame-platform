import type { RatingInsights } from "../../features/ratings/rating-insights-api";

/** Synthetic test evidence only; never imported by the product experience. */
export function ratingInsightsFixture(): RatingInsights {
  const distribution = [1, 2, 3, 5, 7, 8, 14, 18, 18, 8];
  const mostRatedGenres = [
    { genre: { genreId: "adventure", name: "Aventura" }, count: 38, mean: 8.3 },
    { genre: { genreId: "rpg", name: "Rol" }, count: 29, mean: 8.5 },
    { genre: { genreId: "action", name: "Acción" }, count: 23, mean: 7.2 },
    { genre: { genreId: "indie", name: "Independiente" }, count: 19, mean: 8.1 },
    { genre: { genreId: "strategy", name: "Estrategia" }, count: 12, mean: 6.8 },
    { genre: { genreId: "platform", name: "Plataformas" }, count: 8, mean: 7.5 },
  ];
  const game = (id: string, title: string, cover: string) => ({ gameId: id, slug: id,
    canonicalTitle: title, primaryCover: { kind: "provider", url: `https://images.igdb.com/igdb/image/upload/t_cover_big_2x/${cover}.webp`,
      alternativeText: `Portada de ${title}`, attribution: { label: "IGDB", sourceUrl: `https://www.igdb.com/games/${id}` } } });
  return {
    evaluatedOn: "2026-10-20", total: 84, mean: 7.2, distribution, mostRatedGenres,
    bestRatedGenres: [...mostRatedGenres].sort((a,b) => b.mean-a.mean), gamesWithoutGenres: 3,
    highest: { game: game("disco-elysium", "The Witcher 3: Wild Hunt", "co1wyy"), value: 10, tiedGames: 8 },
    lowest: { game: { gameId: "game-with-a-long-title", slug: "game-with-a-long-title", canonicalTitle: "Un juego con un título extraordinariamente largo: más allá de todos los horizontes", primaryCover: {kind:"fallback",url:"/assets/covers/fallback.svg",alternativeText:"Portada no disponible"} }, value: 1, tiedGames: 1 },
    activity: [4, 0, 6, 3, 8, 5, 11, 7, 6, 8, 4, 10].map((count,index) => ({
      month: index === 0 ? "2025-11" : index === 1 ? "2025-12" : `2026-${String(index-1).padStart(2,"0")}`, count,
    })), olderRatings: 12,
  };
}

export function emptyRatingInsightsFixture(): RatingInsights {
  const data = ratingInsightsFixture();
  return { ...data, total: 0, mean: null, distribution: Array.from({ length: 10 }, () => 0),
    mostRatedGenres: [], bestRatedGenres: [], gamesWithoutGenres: 0, highest: null, lowest: null,
    activity: data.activity.map(month => ({ ...month, count: 0 })), olderRatings: 0 };
}
