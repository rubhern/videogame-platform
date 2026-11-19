import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { components } from "../../shared/api/generated/schema";
import { gameDetailsFixture } from "../../test/game-details-fixture";
import { renderApp } from "../../test/render-app";
import type { PersonalRating } from "../ratings/personal-rating-api";

const assignLocation = vi.fn();
vi.mock("../../shared/browser/navigate", () => ({
  assignLocation: (url: string) => assignLocation(url),
}));

type GameRankingPage = components["schemas"]["GameRankingPage"];
type Item = GameRankingPage["items"][number];

function id(position: number) {
  return `00000000-0000-4000-8000-${String(position).padStart(12, "0")}`;
}

function game(position: number): Item {
  return {
    position,
    gameId: id(position),
    slug: `juego-${position}`,
    canonicalTitle: `Juego ${position}`,
    primaryCover: {
      kind: "fallback",
      url: "/assets/covers/fallback.svg",
      alternativeText: "Portada oficial no disponible",
      attribution: null,
    },
    genres: [],
    releaseSummary: { platforms: [], totalPlatforms: 0, earliestKnownYear: 2020, latestKnownYear: 2020 },
    score: { source: "gameometro", kind: "community", value: 9 - position / 10, scale: 10, count: position },
  };
}

function ranking(minimumRatings = 1): GameRankingPage {
  return {
    source: "gameometro_community",
    minimumRatings,
    items: [1, 2, 3, 4, 5].map(game),
    page: { number: 1, size: 20, totalItems: 5, totalPages: 1 },
  };
}

function rating(gameId: string, value: number, version = 1): PersonalRating {
  return {
    gameId,
    mode: "direct",
    value,
    contribution: value,
    platforms: [],
    createdAt: "2026-11-01T10:00:00Z",
    updatedAt: "2026-11-01T10:00:00Z",
    entityTag: `"v${version}"`,
  };
}

function myRatings(ratings: readonly PersonalRating[]) {
  return {
    items: ratings.map((personalRating) => ({
      game: {
        gameId: personalRating.gameId,
        slug: "juego",
        canonicalTitle: "Juego",
        primaryCover: {
          kind: "fallback",
          url: "/assets/covers/fallback.svg",
          alternativeText: "Portada oficial no disponible",
          attribution: null,
        },
      },
      personalRating,
      genres: [],
      ratingSummary: { status: "unavailable" },
    })),
    page: { number: 1, size: 20, totalItems: ratings.length, totalPages: 1 },
  };
}

function stub({
  authenticated,
  saved = [],
  pending = null,
}: {
  authenticated: boolean;
  saved?: PersonalRating[];
  pending?: object | null;
}) {
  const fetchMock = vi.fn<typeof fetch>().mockImplementation(async (input) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), "http://localhost"));
    const url = new URL(request.url);
    if (url.pathname === "/api/v1/session") {
      return Response.json(authenticated ? { authenticated: true, csrfToken: "csrf-1" } : { authenticated: false });
    }
    if (url.pathname === "/api/v1/web-analytics") return Response.json({});
    if (url.pathname === "/auth/rating-intent") {
      return pending === null ? new Response(null, { status: 404 }) : Response.json(pending);
    }
    if (url.pathname === "/api/v1/game-rankings") return Response.json(ranking());
    if (url.pathname.startsWith("/api/v1/games/")) {
      const game = gameDetailsFixture();
      return Response.json({ ...game, gameId: url.pathname.split("/").at(-1), canonicalTitle: "Juego 1" });
    }
    if (url.pathname === "/api/v1/me/ratings" && request.method === "GET") {
      const ids = url.searchParams.getAll("gameIds");
      return Response.json(myRatings(saved.filter((entry) => ids.includes(entry.gameId))));
    }
    const write = url.pathname.match(/^\/api\/v1\/me\/ratings\/(.+)$/);
    if (write?.[1] !== undefined && request.method === "GET") {
      const own = saved.find((entry) => entry.gameId === write[1]);
      return own === undefined
        ? Response.json({ status: 404, code: "RATING_NOT_FOUND" }, { status: 404 })
        : Response.json(own, { headers: { ETag: own.entityTag } });
    }
    if (write?.[1] !== undefined && request.method === "PUT") {
      const body = (await request.json()) as { value: number };
      return Response.json({
        personalRating: rating(write[1], body.value, 2),
        ratingStatistics: { status: "available", mean: body.value, count: 1, distribution: {}, platforms: [] },
      });
    }
    return new Response(null, { status: 404 });
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

function requests(fetchMock: ReturnType<typeof stub>, path: string, method = "GET") {
  return fetchMock.mock.calls
    .map((call) => (call[0] instanceof Request ? call[0] : new Request(new URL(String(call[0]), "http://localhost"))))
    .filter((request) => new URL(request.url).pathname === path && request.method === method);
}

afterEach(() => {
  vi.unstubAllGlobals();
  assignLocation.mockClear();
});

describe("Rating from the TOPómetro (#574)", () => {
  it("states which games enter the ranking with the server's own minimum", async () => {
    stub({ authenticated: false });
    renderApp("/topometro?source=gameometro");

    expect(
      await screen.findByText((_, element) =>
        element?.textContent === "Juegos ya lanzados con al menos 1 puntuación de la comunidad.",
      ),
    ).toBeVisible();
  });

  it("reads the player's own ratings for the page's games in one request and shows them", async () => {
    const fetchMock = stub({ authenticated: true, saved: [rating(id(2), 7)] });
    renderApp("/topometro?source=gameometro");

    expect(await screen.findByRole("button", { name: "Tu puntuación de Juego 2: Caliente 7/10. Cambiar" })).toBeVisible();
    expect(screen.getByRole("button", { name: "Puntuar Juego 1" })).toBeVisible();
    const reads = requests(fetchMock, "/api/v1/me/ratings");
    expect(reads).toHaveLength(1);
    expect(new URL(reads[0]?.url ?? "").searchParams.getAll("gameIds")).toEqual([1, 2, 3, 4, 5].map(id));
  });

  it("saves a pick from the stamp's card in one press and closes it", async () => {
    const user = userEvent.setup();
    const fetchMock = stub({ authenticated: true });
    renderApp("/topometro?source=gameometro");

    await user.click(await screen.findByRole("button", { name: "Puntuar Juego 1" }));
    const card = await screen.findByRole("dialog", { name: "Juego 1" });
    const before = requests(fetchMock, "/api/v1/game-rankings").length;
    await user.click(within(card).getByRole("button", { name: "9" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    const [put] = requests(fetchMock, `/api/v1/me/ratings/${id(1)}`, "PUT");
    expect(put?.headers.get("If-None-Match")).toBe("*");
    expect(put?.headers.get("X-CSRF-Token")).toBe("csrf-1");
    // Back on the list at the stamp, which now holds the score; the ranking is read again once.
    expect(screen.getByRole("button", { name: "Tu puntuación de Juego 1: Ardiendo 9/10. Cambiar" })).toHaveFocus();
    await waitFor(() => expect(requests(fetchMock, "/api/v1/game-rankings").length).toBe(before + 1));
  });

  it("updates a score from the card with its own validator and closes on the same value", async () => {
    const user = userEvent.setup();
    const fetchMock = stub({ authenticated: true, saved: [rating(id(2), 7)] });
    renderApp("/topometro?source=gameometro");

    await user.click(await screen.findByRole("button", { name: /Tu puntuación de Juego 2/ }));
    let card = await screen.findByRole("dialog", { name: "Juego 2" });
    expect(within(card).getByRole("button", { name: "7" })).toHaveAttribute("aria-pressed", "true");
    await user.click(within(card).getByRole("button", { name: "7" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(requests(fetchMock, `/api/v1/me/ratings/${id(2)}`, "PUT")).toHaveLength(0);

    await user.click(screen.getByRole("button", { name: /Tu puntuación de Juego 2/ }));
    card = await screen.findByRole("dialog", { name: "Juego 2" });
    await user.click(within(card).getByRole("button", { name: "4" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    const [put] = requests(fetchMock, `/api/v1/me/ratings/${id(2)}`, "PUT");
    expect(put?.headers.get("If-Match")).toBe('"v1"');
  });

  it("rates the list in a run from its first game and stays on the game it scores", async () => {
    const user = userEvent.setup();
    const fetchMock = stub({ authenticated: true, saved: [rating(id(1), 6), rating(id(3), 5)] });
    renderApp("/topometro?source=gameometro");

    await screen.findByRole("button", { name: /Tu puntuación de Juego 1/ });
    await user.click(screen.getByRole("button", { name: "Puntuar varios" }));
    // The run starts at the top of the list, rated or not.
    const sheet = await screen.findByRole("dialog", { name: "Juego 1" });
    await user.click(within(sheet).getByRole("button", { name: "Siguiente: Juego 2" }));
    await user.click(within(sheet).getByRole("button", { name: "9" }));

    // The sheet stays on the scored game, its new score in place and no message.
    await waitFor(() => expect(within(sheet).getByRole("button", { name: "9" })).toHaveAttribute("aria-pressed", "true"));
    expect(screen.getByRole("dialog", { name: "Juego 2" })).toBeVisible();
    expect(within(sheet).getByRole("status")).toHaveTextContent("");
    expect(within(sheet).queryByText(/Guardada|Primera puntuación/)).not.toBeInTheDocument();

    const before = requests(fetchMock, "/api/v1/game-rankings").length;
    await user.click(screen.getByRole("button", { name: "Cerrar" }));
    // Focus returns to the stamp of the game last shown, and the ranking is read again once.
    expect(screen.getByRole("button", { name: /Tu puntuación de Juego 2/ })).toHaveFocus();
    await waitFor(() => expect(requests(fetchMock, "/api/v1/game-rankings").length).toBe(before + 1));
  });

  it("continues from the card into a run that browses the list and closes on cancel", async () => {
    const user = userEvent.setup();
    const fetchMock = stub({ authenticated: true });
    renderApp("/topometro?source=gameometro");

    await user.click(await screen.findByRole("button", { name: "Puntuar Juego 4" }));
    const card = await screen.findByRole("dialog", { name: "Juego 4" });
    await user.click(within(card).getByRole("button", { name: "Puntuar varios" }));
    expect(await screen.findByRole("dialog", { name: "Juego 4" })).toHaveClass("quick-rate-dialog");
    await user.click(screen.getByRole("button", { name: "Siguiente: Juego 5" }));
    expect(screen.getByRole("dialog", { name: "Juego 5" })).toBeVisible();
    expect(screen.getByRole("button", { name: "Siguiente" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Anterior: Juego 4" }));
    expect(screen.getByRole("dialog", { name: "Juego 4" })).toBeVisible();

    // A browser turns Escape on a modal dialog into its cancel event; JSDOM has no modal layer.
    fireEvent(screen.getByRole("dialog"), new Event("cancel", { cancelable: true }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(requests(fetchMock, `/api/v1/me/ratings/${id(4)}`, "PUT")).toHaveLength(0);
  });

  it("opens the platform sheet for the card's game without leaving the list", async () => {
    const user = userEvent.setup();
    const fetchMock = stub({ authenticated: true });
    renderApp("/topometro?source=gameometro");

    await user.click(await screen.findByRole("button", { name: "Puntuar Juego 1" }));
    const card = await screen.findByRole("dialog", { name: "Juego 1" });
    await user.click(within(card).getByRole("button", { name: "Por plataforma" }));

    const platforms = await screen.findByRole("dialog", { name: "Puntuar por plataforma" });
    expect(within(platforms).getAllByRole("checkbox").length).toBeGreaterThan(0);
    expect(requests(fetchMock, `/api/v1/games/${id(1)}`)).toHaveLength(1);
    await user.click(within(platforms).getByRole("button", { name: "Cancelar" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(screen.getByRole("button", { name: "Puntuar Juego 1" })).toHaveFocus();
  });

  it("offers the platform sheet from the run for the game it shows", async () => {
    const user = userEvent.setup();
    stub({ authenticated: true });
    renderApp("/topometro?source=gameometro");

    await user.click(await screen.findByRole("button", { name: "Puntuar varios" }));
    const sheet = await screen.findByRole("dialog", { name: "Juego 1" });
    await user.click(within(sheet).getByRole("button", { name: "Puntuar por plataforma" }));
    expect(await screen.findByRole("dialog", { name: "Puntuar por plataforma" })).toBeInTheDocument();
    // The run stays underneath, ready to go on.
    expect(screen.getByRole("dialog", { name: "Juego 1", hidden: true })).toBeInTheDocument();
  });

  it("starts the rating sign-in when a visitor picks a score", async () => {
    const user = userEvent.setup();
    const fetchMock = stub({ authenticated: false });
    renderApp("/topometro?source=gameometro");

    await user.click(await screen.findByRole("button", { name: "Puntuar Juego 1" }));
    const sheet = await screen.findByRole("dialog", { name: "Juego 1" });
    expect(within(sheet).getByText(/te pediremos iniciar sesión/)).toBeVisible();
    await user.click(within(sheet).getByRole("button", { name: "8" }));

    expect(assignLocation).toHaveBeenCalledWith(
      `/auth/rating-intent/start?gameId=${id(1)}&slug=juego-1&value=8&returnTo=%2Ftopometro%3Fsource%3Dgameometro`,
    );
    // A visitor has no personal state to read.
    expect(requests(fetchMock, "/api/v1/me/ratings")).toHaveLength(0);
  });

  it("finishes on the list a rating a visitor started there before signing in", async () => {
    const fetchMock = stub({ authenticated: true, pending: { gameId: id(3), slug: "juego-3", value: 8 } });
    renderApp("/topometro?source=gameometro&rating-intent=resumed");

    expect(await screen.findByText("Puntuación guardada: Juego 3, 8/10.")).toBeVisible();
    const [put] = requests(fetchMock, `/api/v1/me/ratings/${id(3)}`, "PUT");
    expect(put?.headers.get("If-None-Match")).toBe("*");
    expect(await screen.findByRole("button", { name: /^Tu puntuación de Juego 3: .* 8\/10. Cambiar$/ })).toBeVisible();
    // The single-use selection was read once.
    expect(requests(fetchMock, "/auth/rating-intent")).toHaveLength(1);
  });

  it("says what happened when the sign-in for a rating was cancelled", async () => {
    stub({ authenticated: false });
    renderApp("/topometro?source=gameometro&rating-intent=cancelled");

    expect(
      await screen.findByText("No se completó el inicio de sesión. Tu puntuación no se ha guardado."),
    ).toBeVisible();
  });
});
