import { formatReleaseDate, formatReleaseDateShort } from "../../shared/catalogue/release-date";
import { toDiscoveryScores, type DiscoveryScore } from "../../shared/score/discovery-scores-model";
import { toCover, type ReleaseCover } from "../releases/releases-view-model";
import type { PlatformGamesPage } from "./platform-games-api";

type Item = PlatformGamesPage["items"][number];

/**
 * Where a game stands on the platform, from the presented release's effective status: out, still
 * to come on a known date, still to come without one, or delayed. The API derives the status from
 * the date at its own evaluation date; the browser never decides "today".
 */
export type PlatformGameState = "released" | "upcoming" | "undated" | "delayed";

export type PlatformGameView = {
  gameId: string;
  slug: string;
  title: string;
  /** The approved alias that explains a search match the title does not. */
  matchedAlias: string | null;
  cover: ReleaseCover;
  state: PlatformGameState;
  /** The presented release's date in full, for assistive technology and the card's tooltip. */
  date: string;
  /** The same date in its compact readout form, at the same precision. */
  shortDate: string;
  platform: { id: string; name: string };
  region: string;
  scores: DiscoveryScore[];
  genres: Item["genres"];
};

export type PlatformCatalogueViewModel = {
  platforms: { id: string; name: string }[];
  genres: { id: string; name: string }[];
  /** The known years the presented releases span, without the year range; null when none. */
  years: { earliest: number; latest: number } | null;
  items: PlatformGameView[];
  page: { number: number; size: number; totalItems: number; totalPages: number };
};

function toState(item: Item): PlatformGameState {
  switch (item.platformRelease.status) {
    case "released":
      return "released";
    case "delayed":
      return "delayed";
    case "scheduled":
      return "upcoming";
    default:
      // Announced (or unconfirmed) evidence without a date that has occurred: still to come.
      return item.platformRelease.releaseDate.precision === "unknown" ? "undated" : "upcoming";
  }
}

export function toPlatformGameView(item: Item): PlatformGameView {
  const release = item.platformRelease;
  return {
    gameId: item.gameId,
    slug: item.slug,
    title: item.canonicalTitle,
    matchedAlias:
      item.matchedAlias !== undefined && item.matchedAlias !== item.canonicalTitle
        ? item.matchedAlias
        : null,
    cover: toCover(item.primaryCover),
    state: toState(item),
    date: formatReleaseDate(release.releaseDate),
    shortDate: formatReleaseDateShort(release.releaseDate),
    platform: { id: release.platform.platformId, name: release.platform.name },
    region: release.region.name,
    scores: toDiscoveryScores(item.scores),
    genres: item.genres.slice(0, 2),
  };
}

export function toPlatformCatalogueViewModel(page: PlatformGamesPage): PlatformCatalogueViewModel {
  const { earliestYear, latestYear } = page.availableFilters;
  return {
    platforms: page.platforms.map((platform) => ({ id: platform.platformId, name: platform.name })),
    genres: page.availableFilters.genres.map((genre) => ({ id: genre.genreId, name: genre.name })),
    years:
      earliestYear === undefined || latestYear === undefined
        ? null
        : { earliest: earliestYear, latest: latestYear },
    items: page.items.map(toPlatformGameView),
    page: page.page,
  };
}
