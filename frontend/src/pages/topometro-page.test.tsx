import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { components } from "../shared/api/generated/schema";
import { renderApp } from "../test/render-app";

type GameRankingPage = components["schemas"]["GameRankingPage"];
type Item = GameRankingPage["items"][number];

function game(position: number, overrides: Partial<Item> = {}): Item {
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
    releaseSummary: {
      platforms: [{ platformId: "platform-ps5", name: "PlayStation 5" }],
      totalPlatforms: 1,
      earliestKnownYear: 2020,
      latestKnownYear: 2020,
    },
    score: {
      source: "igdb",
      kind: "player",
      value: 9.9 - position / 10,
      scale: 10,
      count: position === 1 ? 1 : 1000 + position,
      attribution: { label: "Jugadores IGDB", sourceUrl: `https://www.igdb.com/games/game-${position}` },
      observedAt: "2026-10-20T08:00:00Z",
    },
    ...overrides,
  };
}

function page(
  number: number,
  items: Item[],
  totalItems: number,
  source: GameRankingPage["source"] = "igdb_players",
  size = 20,
): GameRankingPage {
  return { source, items, page: { number, size, totalItems, totalPages: Math.ceil(totalItems / size) } };
}

const firstPage = page(1, Array.from({ length: 20 }, (_, index) => game(index + 1)), 23);
// Scores moved between reads: game 20 now also appears on the second page.
const secondPage = page(2, [game(21, { gameId: "game-20", canonicalTitle: "Juego 20" }), game(22), game(23)], 23);

/** The places of a ranking list, never the platform lists nested inside them. */
function places(list: HTMLElement): HTMLElement[] {
  return within(list).getAllByRole("listitem").filter((item) => item.parentElement === list);
}

function stubRankings(handler: (url: URL) => Response) {
  const fetchMock = vi.fn<typeof fetch>().mockImplementation(async (input) => {
    const url = new URL(input instanceof Request ? input.url : String(input));
    if (url.pathname === "/api/v1/session") return Response.json({ authenticated: false });
    if (url.pathname.startsWith("/auth/rating-intent")) return new Response(null, { status: 404 });
    return handler(url);
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

function rankingRequests(fetchMock: ReturnType<typeof vi.fn<typeof fetch>>): URL[] {
  return fetchMock.mock.calls
    .map((call) => new URL((call[0] as Request).url))
    .filter((url) => url.pathname === "/api/v1/game-rankings");
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("TOPómetro page", () => {
  it("opens on the IGDB players ranking with one podium and one ledger from the same sequence", async () => {
    const fetchMock = stubRankings(() => Response.json(firstPage));
    renderApp("/topometro");

    const podium = await screen.findByRole("list", { name: "Podio" });
    expect(screen.getByRole("heading", { level: 1, name: "TOPómetro" })).toBeInTheDocument();
    expect(document.title).toBe("TOPómetro · Gameómetro");
    const placed = places(podium);
    expect(placed.map((place) => place.getAttribute("value"))).toEqual(["1", "2", "3"]);
    const [first] = placed;
    if (first === undefined) throw new Error("The podium has no first place");
    expect(within(first).getByRole("link", { name: "Puesto 1: Juego 1" })).toHaveAttribute(
      "href",
      "/games/game-1/game-1",
    );
    // A one-vote score is ranked and states its exact sample.
    expect(within(first).getByText("1 valoración")).toBeVisible();
    expect(within(first).getByLabelText("9,8 de 10")).toBeVisible();
    expect(
      within(first).getByRole("link", { name: "Jugadores IGDB: fuente de la puntuación de Juego 1" }),
    ).toHaveAttribute("href", "https://www.igdb.com/games/game-1");

    const ledger = screen.getByRole("list", { name: "Clasificación desde el cuarto puesto" });
    const rows = places(ledger);
    expect(rows).toHaveLength(17);
    const [fourth] = rows;
    if (fourth === undefined) throw new Error("The ledger has no fourth place");
    expect(fourth).toHaveAttribute("value", "4");
    expect(within(fourth).getByText("1.004 valoraciones")).toBeVisible();

    expect(screen.getByRole("link", { name: "Jugadores IGDB" })).toHaveAttribute("aria-current", "true");
    expect(screen.getByRole("link", { name: "Comunidad Gameómetro" })).not.toHaveAttribute("aria-current");
    expect(screen.queryByText(/juegos? clasificados?/)).not.toBeInTheDocument();
    expect(screen.queryByText("Cómo se ordena")).not.toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Mostrando 20 de 23 juegos");
    // The next page is read on request only, and the request says which places it brings.
    const more = screen.getByRole("button", { name: "Cargar más juegos" });
    expect(more).toHaveTextContent("Puestos 21–23");

    const [request] = rankingRequests(fetchMock);
    expect(request?.searchParams.get("source")).toBe("igdb_players");
    expect(request?.searchParams.get("page")).toBe("1");
    expect(request?.searchParams.get("pageSize")).toBe("20");
    // The global ranking is public: it never asks for personal preferences.
    expect(
      fetchMock.mock.calls.some((call) => new URL((call[0] as Request).url).pathname === "/api/v1/me/platform-preferences"),
    ).toBe(false);
    expect(screen.getByRole("link", { name: "TOPómetro" })).toHaveAttribute("aria-current", "page");
  });

  it("loads the next page on request, keeps one place per game and focuses the first new game", async () => {
    const user = userEvent.setup();
    const fetchMock = stubRankings((url) => Response.json(url.searchParams.get("page") === "2" ? secondPage : firstPage));
    renderApp("/topometro");

    await user.click(await screen.findByRole("button", { name: "Cargar más juegos" }));

    const ledger = screen.getByRole("list", { name: "Clasificación desde el cuarto puesto" });
    await waitFor(() => expect(places(ledger)).toHaveLength(19));
    // Game 20 moved to place 21 between reads; it keeps its first place and is listed once.
    expect(within(ledger).getAllByRole("link", { name: /^Puesto \d+: Juego 20$/ })).toHaveLength(1);
    expect(within(ledger).getByRole("link", { name: "Puesto 20: Juego 20" })).toBeInTheDocument();
    expect(within(ledger).getByRole("link", { name: "Puesto 22: Juego 22" })).toHaveFocus();
    expect(screen.getByRole("status")).toHaveTextContent("Mostrando 22 de 23 juegos");
    expect(screen.getByText("Has llegado al final del TOPómetro")).toBeVisible();
    expect(screen.queryByRole("button", { name: "Cargar más juegos" })).not.toBeInTheDocument();
    expect(rankingRequests(fetchMock).map((url) => url.searchParams.get("page"))).toEqual(["1", "2"]);
  });

  it("keeps the loaded places when a further page fails and retries only that page", async () => {
    const user = userEvent.setup();
    let failNext = true;
    stubRankings((url) => {
      if (url.searchParams.get("page") === "2" && failNext) {
        failNext = false;
        return Response.json(
          { code: "CATALOGUE_READ_FAILED", correlationId: "corr-386" },
          { status: 503, headers: { "Content-Type": "application/problem+json" } },
        );
      }
      return Response.json(url.searchParams.get("page") === "2" ? secondPage : firstPage);
    });
    renderApp("/topometro");

    await user.click(await screen.findByRole("button", { name: "Cargar más juegos" }));
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("No se pudieron cargar más juegos");
    expect(alert).toHaveTextContent("corr-386");
    expect(screen.getByRole("list", { name: "Podio" })).toBeInTheDocument();

    await user.click(within(alert).getByRole("button", { name: "Reintentar" }));
    await waitFor(() => expect(screen.getByText("Has llegado al final del TOPómetro")).toBeVisible());
  });

  it("re-ranks by the Gameómetro community without borrowing IGDB readings", async () => {
    const user = userEvent.setup();
    const community = page(
      1,
      [
        game(1, { score: { source: "gameometro", kind: "community", value: 9, scale: 10, count: 1 } }),
        game(2, { score: { source: "gameometro", kind: "community", value: 8.3, scale: 10, count: 3 } }),
        // A reading of another source is never shown in this ranking.
        game(3),
      ],
      3,
      "gameometro_community",
    );
    const fetchMock = stubRankings((url) =>
      Response.json(url.searchParams.get("source") === "gameometro_community" ? community : firstPage),
    );
    const { router } = renderApp("/topometro");

    await screen.findByRole("list", { name: "Podio" });
    await user.click(screen.getByRole("link", { name: "Comunidad Gameómetro" }));

    await waitFor(() => expect(router.state.location.search).toBe("?source=gameometro"));
    const podium = await screen.findByRole("list", { name: "Podio" });
    await waitFor(() => expect(places(podium)).toHaveLength(2));
    expect(within(podium).getByText("1 puntuación")).toBeVisible();
    expect(within(podium).getAllByText("Ardiendo")).toHaveLength(2);
    expect(within(podium).queryByRole("link", { name: /Jugadores IGDB/ })).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Comunidad Gameómetro" })).toHaveAttribute("aria-current", "true");
    expect(rankingRequests(fetchMock).at(-1)?.searchParams.get("source")).toBe("gameometro_community");
  });

  it("explains an empty community ranking and offers the IGDB one", async () => {
    stubRankings(() => Response.json(page(1, [], 0, "gameometro_community")));
    renderApp("/topometro?source=gameometro");

    expect(await screen.findByRole("heading", { name: "Aún no hay ningún juego puntuado" })).toBeVisible();
    expect(screen.getByRole("link", { name: "Ver el TOPómetro de Jugadores IGDB" })).toHaveAttribute(
      "href",
      "/topometro",
    );
    expect(screen.queryByRole("list", { name: "Podio" })).not.toBeInTheDocument();
  });

  it.each([
    ["CATALOGUE_NOT_READY", "El TOPómetro todavía no está disponible", null],
    ["INTERNAL_ERROR", "No se pudo cargar el TOPómetro", "Referencia para soporte: corr-500"],
  ])("tells %s apart and retries", async (code, heading, reference) => {
    const user = userEvent.setup();
    let calls = 0;
    stubRankings(() => {
      calls += 1;
      return calls === 1
        ? Response.json(
            { code, correlationId: "corr-500" },
            { status: code === "INTERNAL_ERROR" ? 500 : 503, headers: { "Content-Type": "application/problem+json" } },
          )
        : Response.json(firstPage);
    });
    renderApp("/topometro");

    const alert = await screen.findByRole("alert");
    expect(within(alert).getByRole("heading", { name: heading })).toBeVisible();
    if (reference !== null) expect(alert).toHaveTextContent(reference);
    await user.click(within(alert).getByRole("button", { name: "Reintentar" }));
    expect(await screen.findByRole("list", { name: "Podio" })).toBeInTheDocument();
  });

  it("returns from a game page to the TOPómetro it was opened from", async () => {
    const user = userEvent.setup();
    stubRankings((url) => {
      if (url.pathname === "/api/v1/game-rankings") return Response.json(firstPage);
      return new Response(null, { status: 404 });
    });
    const { router } = renderApp("/topometro?source=igdb");

    const podium = await screen.findByRole("list", { name: "Podio" });
    await user.click(within(podium).getByRole("link", { name: "Puesto 2: Juego 2" }));
    await waitFor(() => expect(router.state.location.pathname).toBe("/games/game-2/game-2"));
    expect(router.state.location.state).toMatchObject({ returnTo: { path: "/topometro?source=igdb" } });
  });
});
