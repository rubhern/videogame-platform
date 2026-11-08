import { vi } from "vitest";

import type { components } from "../shared/api/generated/schema";

type FeaturedReleases = components["schemas"]["FeaturedReleases"];
type FeaturedItem = FeaturedReleases["items"][number];
type ReleaseCalendar = components["schemas"]["ReleaseCalendar"];
type GameRankingPage = components["schemas"]["GameRankingPage"];
type RankingItem = GameRankingPage["items"][number];
type ReleasePage = components["schemas"]["ReleasePage"];

const PS5 = { platformId: "10000000-0000-4000-8000-000000000001", name: "PlayStation 5" };
const WORLD = { regionId: "20000000-0000-4000-8000-000000000001", name: "Mundial" };

const fallbackCover = {
  kind: "fallback",
  url: "/assets/covers/fallback.svg",
  alternativeText: "Portada oficial no disponible",
  attribution: null,
} as const;

function release(gameId: string, day: string) {
  return {
    releaseId: `release-${gameId}`,
    stage: "full_release",
    gameId,
    platform: PS5,
    region: WORLD,
    releaseDate: { precision: "day", value: day },
    status: "scheduled",
    provenance: { sourceKind: "external_provider", sourceName: "IGDB", sourceEntityType: "release_date" },
    verificationLevel: "provider_only",
    reviewStatus: "not_required",
  } as const;
}

/** One featured game of the fixture week, released on `day` on PlayStation 5. */
export function featuredGame(id: string, title: string, day: string): FeaturedItem {
  return {
    gameId: id,
    slug: id,
    canonicalTitle: title,
    primaryCover: fallbackCover,
    featuredImage: {
      kind: "fallback",
      presentation: "fill",
      url: "/assets/featured/fallback.svg",
      compactUrl: "/assets/featured/fallback.svg",
      alternativeText: `Imagen destacada no disponible de ${title}`,
      attribution: null,
    },
    releases: [release(id, day)],
    genres: [{ genreId: "adventure", name: "Aventura" }],
  };
}

/** The current week, 26 Oct–1 Nov 2026, evaluated on Wednesday 28 October. */
export const homeFeatured: FeaturedReleases = {
  granularity: "week",
  week: "2026-W44",
  evaluatedOn: "2026-10-28",
  window: { from: "2026-10-26", to: "2026-11-01" },
  navigation: { previous: "2026-W43", next: "2026-W45" },
  selection: { status: "ranked", popularityFreshness: "fresh", popularityObservedAt: "2026-10-27T05:00:00Z" },
  items: [
    featuredGame("phantom", "Phantom Blade Zero", "2026-10-29"),
    featuredGame("wolf", "The Wolf Among Us Remastered", "2026-10-29"),
    featuredGame("queen", "Queen's Domain", "2026-10-30"),
    featuredGame("lizards", "Terrible Lizards", "2026-10-29"),
    featuredGame("nun", "Remothered: Red Nun's Legacy", "2026-10-27"),
    featuredGame("racer", "Star Wars: Galactic Racer", "2026-10-30"),
  ],
};

/** October from the 28th: three days with highlighted games, so November is read as well. */
export const homeCalendar: ReleaseCalendar = {
  month: "2026-10",
  evaluatedOn: "2026-10-28",
  window: { from: "2026-10-01", to: "2026-10-31" },
  navigation: { first: "2026-10", last: "2027-03", next: "2026-11" },
  activeFilters: { platformIds: [], regionIds: [] },
  availableFilters: { platforms: [PS5], regions: [WORLD] },
  days: [
    { date: "2026-10-28", gameCount: 1, totalHype: 10 },
    { date: "2026-10-29", gameCount: 5, totalHype: 284 },
    { date: "2026-10-30", gameCount: 1, totalHype: 13 },
  ],
};

export const homeNextCalendar: ReleaseCalendar = {
  ...homeCalendar,
  month: "2026-11",
  window: { from: "2026-11-01", to: "2026-11-30" },
  navigation: { first: "2026-10", last: "2027-03", previous: "2026-10", next: "2026-12" },
  days: [
    { date: "2026-11-03", gameCount: 2, totalHype: 54 },
    { date: "2026-11-05", gameCount: 2, totalHype: 89 },
  ],
};

/** The explorer's first games of one day in the calendar's Hype order. */
export function homeDayReleases(day: string, titles: readonly string[]): ReleasePage {
  return {
    evaluatedOn: "2026-10-28",
    window: { from: day, to: day },
    activeFilters: { platformIds: [], regionIds: [] },
    availableFilters: { platforms: [PS5], regions: [WORLD] },
    items: titles.map((title, index) => ({
      gameId: `${day}-${index}`,
      slug: `${day}-${index}`,
      canonicalTitle: title,
      primaryCover: fallbackCover,
      releases: [release(`${day}-${index}`, day)],
    })),
    page: { number: 1, size: titles.length, totalItems: 52, totalPages: 1 },
  };
}

function rankedGame(position: number, title: string, source: "igdb_players" | "gameometro_community"): RankingItem {
  return {
    position,
    gameId: `ranked-${position}`,
    slug: `ranked-${position}`,
    canonicalTitle: title,
    primaryCover: fallbackCover,
    genres: [{ genreId: "rpg", name: "Rol" }],
    releaseSummary: { platforms: [PS5], totalPlatforms: 1, earliestKnownYear: 2015, latestKnownYear: 2015 },
    score:
      source === "igdb_players"
        ? {
            source: "igdb",
            kind: "player",
            value: 9.5 - position / 10,
            scale: 10,
            count: 5494 - position,
            attribution: { label: "Jugadores IGDB", sourceUrl: `https://www.igdb.com/games/ranked-${position}` },
            observedAt: "2026-10-20T08:00:00Z",
          }
        : { source: "gameometro", kind: "community", value: 10, scale: 10, count: 1 },
  };
}

/** A source's first TOPómetro page; the community ranking may hold no game yet. */
export function homeRanking(
  source: "igdb_players" | "gameometro_community",
  titles: readonly string[] = ["The Witcher 3: Wild Hunt", "The Legend of Zelda: Breath of the Wild", "Hollow Knight: Silksong"],
): GameRankingPage {
  return {
    source,
    items: titles.map((title, index) => rankedGame(index + 1, title, source)),
    page: { number: 1, size: 20, totalItems: titles.length, totalPages: titles.length === 0 ? 0 : 1 },
  };
}

type Handler = (url: URL) => Response | Promise<Response>;

export type HomeApi = Partial<
  Record<"session" | "preferences" | "featured" | "calendar" | "releases" | "rankings" | "platforms", Handler>
>;

const defaults: Required<HomeApi> = {
  session: () => Response.json({ authenticated: false }),
  preferences: () => Response.json({ configured: true, platforms: [] }),
  featured: () => Response.json(homeFeatured),
  calendar: (url) => Response.json(url.searchParams.get("month") === "2026-11" ? homeNextCalendar : homeCalendar),
  releases: (url) =>
    Response.json(homeDayReleases(url.searchParams.get("from") ?? "", ["Phantom Blade Zero", "Atmosfar", "Tom the Postgirl", "Terrible Lizards"])),
  rankings: (url) =>
    Response.json(homeRanking(url.searchParams.get("source") === "gameometro_community" ? "gameometro_community" : "igdb_players")),
  platforms: () => Response.json({ items: [PS5, { platformId: "ps4", name: "PlayStation 4" }] }),
};

const routes: [string, keyof HomeApi][] = [
  ["/api/v1/session", "session"],
  ["/api/v1/me/platform-preferences", "preferences"],
  ["/api/v1/featured-releases", "featured"],
  ["/api/v1/release-calendar", "calendar"],
  ["/api/v1/releases", "releases"],
  ["/api/v1/game-rankings", "rankings"],
  ["/api/v1/platforms", "platforms"],
];

/** Stubs the bounded public reads the home composes; any one can be replaced to fail or empty. */
export function stubHomeApi(api: HomeApi = {}) {
  const handlers = { ...defaults, ...api };
  const fetchMock = vi.fn<typeof fetch>().mockImplementation(async (input) => {
    const url = new URL(input instanceof Request ? input.url : String(input));
    const route = routes.find(([path]) => url.pathname === path);
    return route === undefined ? new Response(null, { status: 404 }) : handlers[route[1]](url);
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

/** The paths and queries the home read, in order. */
export function requestedPaths(fetchMock: ReturnType<typeof stubHomeApi>): string[] {
  return fetchMock.mock.calls.map((call) => {
    const url = new URL(call[0] instanceof Request ? call[0].url : String(call[0]));
    return url.pathname + url.search;
  });
}
