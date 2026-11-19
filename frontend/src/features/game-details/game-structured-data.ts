import type { GameDetails } from "./game-details-api";

type StructuredData = Record<string, unknown>;

/**
 * The schema.org description of a game page (#537), built only from what the page itself shows.
 *
 * - Only a sourced summary describes the game; the catalogue's editorial summary is its
 *   "no summary yet" notice.
 * - Only an approved provider cover is an image; the fallback is a placeholder.
 * - Platforms are the release platforms the page lists, cancelled-only ones excluded.
 * - `aggregateRating` is Gameómetro's community reading alone, with its real voter count. The
 *   IGDB player score is a third-party reading, never presented as this site's rating.
 * - Release dates are omitted: a game has several, and none is "the" publication date.
 */
export function gameStructuredData(game: GameDetails, origin: string): StructuredData {
  const data: StructuredData = {
    "@context": "https://schema.org",
    "@type": "VideoGame",
    name: game.canonicalTitle,
    url: `${origin}/games/${game.gameId}/${game.slug}`,
  };
  if (game.summary.kind === "sourced") data.description = game.summary.text;
  if (game.primaryCover.kind === "provider") data.image = game.primaryCover.url;
  if (game.genres.length > 0) data.genre = game.genres.map((genre) => genre.name);
  const platforms = [
    ...new Set(
      game.releases
        .filter((release) => release.status !== "cancelled")
        .map((release) => release.platform.name),
    ),
  ];
  if (platforms.length > 0) data.gamePlatform = platforms;
  if (game.developers.length > 0) data.author = organizations(game.developers);
  if (game.publishers.length > 0) data.publisher = organizations(game.publishers);
  const statistics = game.ratingStatistics;
  // The contract's status is a pattern, not a literal type: the available shape is the one with a count.
  if ("count" in statistics && statistics.count > 0 && statistics.mean !== null) {
    data.aggregateRating = {
      "@type": "AggregateRating",
      ratingValue: statistics.mean,
      ratingCount: statistics.count,
      bestRating: 10,
      worstRating: 1,
    };
  }
  return data;
}

function organizations(companies: GameDetails["developers"]) {
  return companies.map((company) => ({ "@type": "Organization", name: company.name }));
}
