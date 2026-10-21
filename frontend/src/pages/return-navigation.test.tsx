import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { forgetMyRatingsQueries } from "../features/ratings/my-ratings-memory";
import type { components } from "../shared/api/generated/schema";
import { gameDetailsFixture } from "../test/game-details-fixture";
import { renderApp } from "../test/render-app";

type ReleasePage = components["schemas"]["ReleasePage"];
type FeaturedReleases = components["schemas"]["FeaturedReleases"];
type GameSearchPage = components["schemas"]["GameSearchPage"];

const GAME_ID = "30000000-0000-4000-8000-000000000005";
const SLUG = "resident-evil-requiem";
const TITLE = "Resident Evil Requiem";
const cover = {
  kind: "fallback",
  url: "/assets/covers/fallback.svg",
  alternativeText: `Portada no disponible de ${TITLE}`,
  attribution: null,
} as const;
const release = {
  releaseId: "40000000-0000-4000-8000-000000000005",
  stage: "unknown",
  gameId: GAME_ID,
  platform: { platformId: "windows-pc", name: "Windows PC" },
  region: { regionId: "worldwide", name: "Mundial" },
  releaseDate: { precision: "day", value: "2026-12-04" },
  status: "scheduled",
  provenance: { sourceKind: "external_provider", sourceName: "IGDB", sourceEntityType: "release_date" },
  verificationLevel: "provider_only",
  reviewStatus: "not_required",
} as const;

function releasePage(request: URL): ReleasePage {
  return {
    evaluatedOn: "2026-08-13",
    window: {
      from: request.searchParams.get("from") ?? "2026-08-10",
      to: request.searchParams.get("to") ?? "2026-08-16",
    },
    activeFilters: { platformIds: request.searchParams.getAll("platformIds"), regionIds: [] },
    availableFilters: { platforms: [{ platformId: "windows-pc", name: "Windows PC" }], regions: [] },
    items: [{ gameId: GAME_ID, slug: SLUG, canonicalTitle: TITLE, primaryCover: cover, releases: [release] }],
    page: { number: Number(request.searchParams.get("page") ?? 1), size: 12, totalItems: 30, totalPages: 3 },
  };
}

const featured: FeaturedReleases = {
  granularity: "month",
  month: "2026-12",
  evaluatedOn: "2026-08-13",
  window: { from: "2026-12-01", to: "2026-12-31" },
  navigation: { previous: "2026-11" },
  selection: { status: "ranked", popularityFreshness: "fresh", popularityObservedAt: "2026-08-12T05:00:00Z" },
  items: [
    {
      gameId: GAME_ID,
      slug: SLUG,
      canonicalTitle: TITLE,
      primaryCover: cover,
      featuredImage: {
        kind: "fallback",
        presentation: "fill",
        url: "/assets/featured/fallback.svg",
        compactUrl: "/assets/featured/fallback.svg",
        alternativeText: `Imagen destacada no disponible de ${TITLE}`,
        attribution: null,
      },
      releases: [release],
    },
  ],
};

const searchPage: GameSearchPage = {
  items: [
    {
      gameId: GAME_ID,
      slug: SLUG,
      canonicalTitle: TITLE,
      primaryCover: cover,
      releaseContext: [],
      releaseSummary: { platforms: [], totalPlatforms: 0 },
    },
  ],
  page: { number: 2, size: 6, totalItems: 8, totalPages: 2 },
};

const ratingsPage = {
  items: [
    {
      game: { gameId: GAME_ID, slug: SLUG, canonicalTitle: TITLE, primaryCover: cover },
      personalRating: {
        gameId: GAME_ID,
        mode: "direct",
        value: 7,
        contribution: 7,
        platforms: [],
        createdAt: "2026-08-13T10:00:00Z",
        updatedAt: "2026-08-13T10:00:00Z",
        entityTag: '"version-1"',
      },
    },
  ],
  page: { number: 1, size: 10, totalItems: 1, totalPages: 1 },
};

function serve(authenticated = false) {
  const fetchMock = vi.fn<typeof fetch>().mockImplementation(async (input) => {
    const request = input instanceof Request ? input : new Request(String(input));
    const url = new URL(request.url);
    switch (url.pathname) {
      case "/api/v1/session":
        return Response.json(authenticated ? { authenticated: true, csrfToken: "csrf" } : { authenticated: false });
      case "/api/v1/releases":
        return Response.json(releasePage(url));
      case "/api/v1/featured-releases":
        return Response.json(featured);
      case "/api/v1/games":
        return Response.json(searchPage);
      case "/api/v1/me/ratings":
        return Response.json(ratingsPage);
      default:
        if (url.pathname === `/api/v1/games/${GAME_ID}`) return Response.json(gameDetailsFixture());
        return new Response(null, { status: 404 });
    }
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

afterEach(() => {
  vi.unstubAllGlobals();
  forgetMyRatingsQueries();
});

async function openGameFrom(container: HTMLElement) {
  const user = userEvent.setup();
  await user.click(within(container).getAllByRole("link", { name: TITLE })[0] as HTMLElement);
  await screen.findByRole("heading", { level: 1, name: TITLE });
  return user;
}

describe("the game page's way back", () => {
  it("returns to Explorar lanzamientos with its range, order, filters and page", async () => {
    serve();
    const origin = "/lanzamientos?from=2026-12-01&to=2026-12-31&sort=hype&platformIds=windows-pc&page=2";
    const { router } = renderApp(origin);
    const results = await screen.findByRole("list", { name: "Explorar lanzamientos" });

    const user = await openGameFrom(results);
    const back = screen.getAllByRole("link", { name: /Volver a lanzamientos/ })[0] as HTMLElement;
    expect(back).toHaveAttribute("href", origin);
    // A shared game link stays clean: the origin travels in history state, never in the URL.
    expect(router.state.location.search).toBe("");

    await user.click(back);
    await screen.findByRole("heading", { level: 1, name: "Explorar lanzamientos" });
    expect(`${router.state.location.pathname}${router.state.location.search}`).toBe(origin);
    expect(screen.getByRole("combobox", { name: /Ordenar por/ })).toHaveTextContent("Hype");
    expect(screen.getByRole("status")).toHaveTextContent("Página 2 de 3");
  });

  it("returns to the TOPómetro period it was opened from", async () => {
    serve();
    const { router } = renderApp("/?month=2026-12");
    const hero = await screen.findByRole("article", { name: TITLE });

    const user = await openGameFrom(hero);
    const back = screen.getAllByRole("link", { name: /Volver al TOPómetro/ })[0] as HTMLElement;
    expect(back).toHaveAttribute("href", "/?month=2026-12");

    await user.click(back);
    await screen.findByRole("heading", { level: 1, name: "TOPómetro del mes" });
    expect(router.state.location.search).toBe("?month=2026-12");
  });

  it("returns to the search results page it was opened from", async () => {
    serve();
    const { router } = renderApp("/search?q=requiem&page=2");
    await screen.findAllByRole("link", { name: TITLE });

    const user = await openGameFrom(document.body);
    const back = screen.getAllByRole("link", { name: /Volver a resultados/ })[0] as HTMLElement;
    expect(back).toHaveAttribute("href", "/search?q=requiem&page=2");

    await user.click(back);
    await waitFor(() => expect(router.state.location.search).toBe("?q=requiem&page=2"));
  });

  it("returns to Mis puntuaciones with its personal search, kept only in memory", async () => {
    const fetchMock = serve(true);
    const { router } = renderApp("/mis-puntuaciones");
    const user = userEvent.setup();
    const search = await screen.findByRole("search", { name: "Buscar en mis puntuaciones" });
    await user.type(within(search).getByRole("searchbox"), "requiem");
    await user.click(within(search).getByRole("button", { name: "Buscar en mis puntuaciones" }));
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some(
          ([request]) => new URL((request as Request).url).searchParams.get("q") === "requiem",
        ),
      ).toBe(true),
    );

    const card = await screen.findByRole("article", { name: TITLE });
    await openGameFrom(card);
    const back = screen.getAllByRole("link", { name: /Volver a mis puntuaciones/ })[0] as HTMLElement;
    // The personal query never reaches the URL or the history state.
    expect(back).toHaveAttribute("href", "/mis-puntuaciones");
    expect(JSON.stringify(router.state.location.state)).not.toContain("requiem");

    await user.click(back);
    const restored = await screen.findByRole("search", { name: "Buscar en mis puntuaciones" });
    expect(within(restored).getByRole("searchbox")).toHaveValue("requiem");
    expect(router.state.location.search).toBe("");
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });

  it("falls back to TOPómetro for a direct link", async () => {
    serve();
    renderApp(`/games/${GAME_ID}/${SLUG}`);

    await screen.findByRole("heading", { level: 1, name: TITLE });
    expect(screen.getAllByRole("link", { name: /Volver al TOPómetro/ })[0]).toHaveAttribute("href", "/");
  });

  it("never follows a forged origin outside the product", async () => {
    serve();
    renderApp({
      pathname: `/games/${GAME_ID}/${SLUG}`,
      state: { returnTo: { path: "//evil.example/lanzamientos", entry: "entry-1" } },
    });

    await screen.findByRole("heading", { level: 1, name: TITLE });
    for (const back of screen.getAllByRole("link", { name: /Volver/ })) {
      expect(back).toHaveAttribute("href", "/");
    }
  });
});
