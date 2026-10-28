import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { components } from "../shared/api/generated/schema";
import { renderApp } from "../test/render-app";

type GameRankingPage = components["schemas"]["GameRankingPage"];
type Item = GameRankingPage["items"][number];

const PS5 = "10000000-0000-4000-8000-000000000001";

const directory = [
  { platformId: PS5, name: "PlayStation 5" },
  { platformId: "ps4-id", name: "PlayStation 4" },
  { platformId: "steamvr-id", name: "SteamVR" },
];

function game(position: number, release: NonNullable<Item["platformRelease"]>["releaseDate"]): Item {
  return {
    position,
    gameId: `game-${position}`,
    slug: `game-${position}`,
    canonicalTitle: `Juego ${position}`,
    primaryCover: {
      kind: "fallback",
      url: "/assets/covers/fallback.svg",
      alternativeText: "Portada oficial no disponible",
      attribution: null,
    },
    genres: [{ genreId: "rpg", name: "Rol" }],
    // The game's every platform; a platform's TOPómetro never lists them on each place.
    releaseSummary: {
      platforms: [
        { platformId: "ps4-id", name: "PlayStation 4" },
        { platformId: PS5, name: "PlayStation 5" },
      ],
      totalPlatforms: 2,
      earliestKnownYear: 2015,
      latestKnownYear: 2021,
    },
    platformRelease: {
      platform: { platformId: PS5, name: "PlayStation 5" },
      region: { regionId: "world", name: "Mundial" },
      releaseDate: release,
      status: "released",
    },
    score: { source: "gameometro", kind: "community", value: 9.1 - position / 10, scale: 10, count: position },
  };
}

function page(items: Item[], totalItems = items.length, source: GameRankingPage["source"] = "igdb_players") {
  return { source, items, page: { number: 1, size: 20, totalItems, totalPages: Math.ceil(totalItems / 20) } };
}

function igdbGame(position: number): Item {
  return {
    ...game(position, { precision: "day", value: "2021-06-11" }),
    score: {
      source: "igdb",
      kind: "player",
      value: 9.4,
      scale: 10,
      count: 5494,
      attribution: { label: "Jugadores IGDB", sourceUrl: "https://www.igdb.com/games/game-1" },
      observedAt: "2026-10-20T08:00:00Z",
    },
  };
}

function stubApi(rankings: (url: URL) => Response) {
  const requests: URL[] = [];
  vi.stubGlobal("fetch", vi.fn<typeof fetch>().mockImplementation(async (input) => {
    const url = new URL(input instanceof Request ? input.url : String(input), "http://localhost");
    if (url.pathname.endsWith("/session")) return Response.json({ authenticated: false });
    if (url.pathname.startsWith("/auth/rating-intent")) return new Response(null, { status: 404 });
    if (url.pathname.endsWith("/platforms")) return Response.json({ items: directory });
    if (url.pathname.endsWith("/game-rankings")) {
      requests.push(url);
      return rankings(url);
    }
    return Response.json({}, { status: 404 });
  }));
  return requests;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("A platform's TOPómetro", () => {
  it("ranks a featured platform server-side under its plate, dating each game on that platform", async () => {
    const requests = stubApi(() => Response.json(page([igdbGame(1)])));
    renderApp("/plataformas/playstation-5/topometro");

    const podium = await screen.findByRole("list", { name: "Podio" });
    expect(screen.getByRole("heading", { level: 1, name: "TOPómetro de PlayStation 5" })).toBeInTheDocument();
    expect(document.title).toBe("TOPómetro de PlayStation 5 · Gameómetro");
    // The server ranks the platform; the browser never filters a global ranking.
    expect(requests).toHaveLength(1);
    expect(requests[0]?.searchParams.getAll("platformIds")).toEqual([PS5]);
    expect(requests[0]?.searchParams.get("source")).toBe("igdb_players");
    // The platform's own release, at its precision; no list of the game's other platforms.
    expect(within(podium).getByText("Lanzado en PlayStation 5: 11 de junio de 2021")).toBeInTheDocument();
    expect(within(podium).queryByRole("list", { name: "Plataformas de Juego 1" })).not.toBeInTheDocument();
    expect(within(podium).getByText("5.494 valoraciones")).toBeVisible();

    expect(screen.queryByRole("navigation", { name: "Ruta de navegación" })).not.toBeInTheDocument();
    const trail = screen.getByRole("navigation", { name: "Navegación de PlayStation 5" });
    expect(within(trail).getByRole("link", { name: "Catálogo" }))
      .toHaveAttribute("href", "/plataformas/playstation-5/catalogo");
    expect(screen.getByRole("link", { name: "Comunidad Gameómetro" }))
      .toHaveAttribute("href", "/plataformas/playstation-5/topometro?source=gameometro");
    expect(screen.getByRole("link", { name: "Jugadores IGDB" })).toHaveAttribute("aria-current", "true");
    // A shorter ranking fills no place: one game, one podium place, no ledger.
    expect(within(podium).getAllByRole("listitem").filter((item) => item.parentElement === podium)).toHaveLength(1);
    expect(screen.queryByRole("list", { name: "Clasificación desde el cuarto puesto" })).not.toBeInTheDocument();
    expect(screen.getByText("Has llegado al final del TOPómetro")).toBeVisible();
  });

  it("keeps the platform when switching source and when switching platform keeps the source", async () => {
    const user = userEvent.setup();
    const requests = stubApi((url) =>
      Response.json(
        url.searchParams.get("source") === "gameometro_community"
          ? page([game(1, { precision: "month", value: "2025-03" })], 1, "gameometro_community")
          : page([igdbGame(1)]),
      ));
    const { router } = renderApp("/plataformas/playstation-5/topometro");
    await screen.findByRole("list", { name: "Podio" });

    await user.click(screen.getByRole("link", { name: "Comunidad Gameómetro" }));

    expect(await screen.findByText("Lanzado en PlayStation 5: marzo de 2025")).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/plataformas/playstation-5/topometro");
    expect(requests.at(-1)?.searchParams.get("source")).toBe("gameometro_community");
    expect(requests.at(-1)?.searchParams.getAll("platformIds")).toEqual([PS5]);

    await user.click(screen.getByRole("button", { name: "Cambiar plataforma" }));
    const ps4 = await screen.findByRole("link", { name: "PlayStation 4" });
    expect(ps4).toHaveAttribute("href", "/plataformas/ps4-id/topometro?source=gameometro");
    expect(screen.getAllByRole("link", { name: "PlayStation 5" }).at(-1)).toHaveAttribute("aria-current", "page");
  });

  it("resolves another platform by its own identifier and states an empty ranking honestly", async () => {
    const requests = stubApi(() => Response.json(page([])));
    renderApp("/plataformas/ps4-id/topometro");

    expect(await screen.findByRole("heading", { level: 2, name: "Aún no hay juegos de PlayStation 4 con valoraciones de IGDB" }))
      .toBeVisible();
    expect(screen.getByRole("heading", { level: 1, name: "TOPómetro de PlayStation 4" })).toBeInTheDocument();
    expect(requests.at(-1)?.searchParams.getAll("platformIds")).toEqual(["ps4-id"]);
    expect(screen.getByRole("link", { name: "Ver la Comunidad Gameómetro en PlayStation 4" }))
      .toHaveAttribute("href", "/plataformas/ps4-id/topometro?source=gameometro");
    expect(screen.getByRole("link", { name: "Ver el TOPómetro de todas las plataformas" }))
      .toHaveAttribute("href", "/topometro");
  });

  it.each(["steamvr-id", "dreamcast"])("never ranks %s, which is not on offer", async (key) => {
    const requests = stubApi(() => Response.json(page([igdbGame(1)])));
    renderApp(`/plataformas/${key}/topometro`);

    expect(await screen.findByText("Esa plataforma no tiene juegos en Gameómetro ahora mismo")).toBeVisible();
    expect(screen.getByRole("link", { name: "Ver todas las plataformas" })).toHaveAttribute("href", "/plataformas");
    expect(requests).toHaveLength(0);
  });

  it("treats a platform the API no longer supports as not on offer", async () => {
    stubApi(() =>
      Response.json(
        { type: "about:blank", title: "Fallo", status: 422, code: "PLATFORM_NOT_SUPPORTED", correlationId: "corr-387" },
        { status: 422, headers: { "Content-Type": "application/problem+json" } },
      ));
    renderApp("/plataformas/playstation-5/topometro");

    await waitFor(() =>
      expect(screen.getByText("Esa plataforma no tiene juegos en Gameómetro ahora mismo")).toBeVisible());
  });

  it("returns from a game page to the same platform TOPómetro", async () => {
    const user = userEvent.setup();
    stubApi(() => Response.json(page([igdbGame(1)])));
    const { router } = renderApp("/plataformas/playstation-5/topometro?source=igdb");
    const podium = await screen.findByRole("list", { name: "Podio" });

    await user.click(within(podium).getByRole("link", { name: "Puesto 1: Juego 1" }));

    expect(router.state.location.pathname).toBe("/games/game-1/game-1");
    expect(router.state.location.state).toMatchObject({
      returnTo: { path: "/plataformas/playstation-5/topometro?source=igdb" },
    });
  });
});
