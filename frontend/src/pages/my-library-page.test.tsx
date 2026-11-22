import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { renderApp } from "../test/render-app";

const cover = { kind: "fallback", url: "/assets/covers/fallback.svg", alternativeText: "Portada no disponible", attribution: null };
const counts = { total: 3, wished: 1, backlog: 0, playing: 2, completed: 0, abandoned: 0 };
const playing = [
  { gameId: "game-1", slug: "elite", canonicalTitle: "Élite Dangerous", primaryCover: cover,
    state: "playing", stateChangedAt: "2026-08-13T10:00:00Z", released: true },
  { gameId: "game-2", primaryCover: cover, state: "playing", stateChangedAt: "2026-08-12T10:00:00Z", released: true },
];

function stub(handler: (url: URL, request: Request) => Response | Promise<Response>, authenticated = true) {
  const fetchMock = vi.fn<typeof fetch>().mockImplementation(async (input) => {
    const request = input instanceof Request ? input : new Request(input);
    const url = new URL(request.url);
    if (url.pathname === "/api/v1/session") {
      return Response.json(authenticated ? { authenticated: true, csrfToken: "csrf" } : { authenticated: false });
    }
    return handler(url, request);
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

afterEach(() => vi.unstubAllGlobals());

describe("Mis juegos", () => {
  it("filters by the state in the URL, counts every state and pages through links", async () => {
    const fetchMock = stub((url) => Response.json({
      items: url.searchParams.get("state") === "playing" ? playing : [],
      counts,
      page: { number: Number(url.searchParams.get("page") ?? 1), size: 20, totalItems: 2, totalPages: 2 }, ratedWithoutState: 0,
    }));
    renderApp("/mis-juegos?estado=jugando");

    expect(await screen.findByRole("heading", { level: 1, name: "Mis juegos" })).toBeVisible();
    const filters = await screen.findByRole("navigation", { name: "Filtrar por estado" });
    const current = within(filters).getByRole("link", { name: /^Jugando:?\s*2$/ });
    expect(current).toHaveAttribute("aria-current", "page");
    expect(within(filters).getByRole("link", { name: /^Todos:?\s*3$/ })).toHaveAttribute("href", "/mis-juegos");
    expect(within(filters).getByRole("link", { name: /^Deseado:?\s*1$/ })).toHaveAttribute("href", "/mis-juegos?estado=deseado");

    const list = screen.getByRole("list", { name: "Tus juegos" });
    expect(within(list).getByRole("link", { name: "Élite Dangerous" })).toHaveAttribute("href", "/games/game-1/elite");
    // A game outside the current publication stays listed without a link.
    expect(within(list).getByText("Juego no disponible")).toBeVisible();
    expect(within(list).getAllByText(/^Desde el/)).toHaveLength(2);
    // Each row names its state in a chip that opens the same choice as the game page.
    expect(within(list).getByRole("button", { name: "Jugando: cambiar el estado de Élite Dangerous" }))
      .toHaveAttribute("aria-haspopup", "dialog");

    const reads = fetchMock.mock.calls.map(([request]) => new URL((request as Request).url))
      .filter((url) => url.pathname === "/api/v1/me/library");
    expect(reads.map((url) => url.searchParams.get("state"))).toEqual(["playing"]);
    expect(screen.getByRole("link", { name: /Página siguiente/ })).toHaveAttribute("href", "/mis-juegos?estado=jugando&page=2");
  });

  it("moves a game out of the filtered view and re-reads the page and counts", async () => {
    let state = "playing";
    const fetchMock = stub(async (url, request) => {
      if (url.pathname === "/api/v1/me/library/games/game-1") {
        state = ((await request.json()) as { state: string }).state;
        return new Response(null, { status: 204 });
      }
      const items = state === "playing" ? [playing[0]] : [];
      return Response.json({
        items, counts: { ...counts, playing: items.length, completed: state === "completed" ? 1 : 0 },
        page: { number: 1, size: 20, totalItems: items.length, totalPages: items.length }, ratedWithoutState: 0,
      });
    });
    renderApp("/mis-juegos?estado=jugando");
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "Jugando: cambiar el estado de Élite Dangerous" }));
    const dialog = screen.getByRole("dialog", { name: "Elige el estado de este juego" });
    await user.click(within(dialog).getByRole("button", { name: "Completado" }));
    expect(await screen.findByRole("heading", { name: "No tienes juegos en «Jugando»" })).toBeVisible();
    expect(screen.getByRole("link", { name: /^Completado:?\s*1$/ })).toBeVisible();
    await waitFor(() => expect(screen.getByText("0 juegos")).toHaveFocus());
    expect(fetchMock.mock.calls.some(([request]) => (request as Request).method === "PUT")).toBe(true);
  });

  it("offers only Deseado in the row of a game that has not come out", async () => {
    stub(() => Response.json({
      items: [{ ...playing[0], state: "wished", released: false }],
      counts: { ...counts, playing: 0, wished: 1, total: 1 },
      page: { number: 1, size: 20, totalItems: 1, totalPages: 1 }, ratedWithoutState: 0,
    }));
    renderApp("/mis-juegos");
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "Deseado: cambiar el estado de Élite Dangerous" }));
    const dialog = screen.getByRole("dialog", { name: "Elige el estado de este juego" });
    expect(within(dialog).getAllByRole("button", { pressed: true }).map((button) => button.textContent)).toEqual(["Deseado"]);
    expect(within(dialog).queryByRole("button", { name: "Jugando" })).toBeNull();
  });

  it("distinguishes an empty library from a filtered empty view", async () => {
    stub(() => Response.json({
      items: [], counts: { total: 0, wished: 0, backlog: 0, playing: 0, completed: 0, abandoned: 0 },
      page: { number: 1, size: 20, totalItems: 0, totalPages: 0 }, ratedWithoutState: 0,
    }));
    renderApp("/mis-juegos");
    expect(await screen.findByRole("heading", { name: "Aún no tienes juegos con estado" })).toBeVisible();
    expect(screen.queryByRole("list", { name: "Tus juegos" })).toBeNull();
  });

  it("brings rated games without a state in after the player confirms", async () => {
    let remaining = 3;
    const fetchMock = stub(async (url, request) => {
      if (url.pathname === "/api/v1/me/library/rated-games/completion") {
        expect(request.method).toBe("POST");
        expect(request.headers.get("X-CSRF-Token")).toBe("csrf");
        const completed = remaining;
        remaining = 0;
        return Response.json({ completed });
      }
      const done = remaining === 0;
      return Response.json({
        items: done ? [{ ...playing[0], state: "completed" }] : [],
        counts: { total: done ? 3 : 0, wished: 0, backlog: 0, playing: 0, completed: done ? 3 : 0, abandoned: 0 },
        page: { number: 1, size: 20, totalItems: done ? 1 : 0, totalPages: done ? 1 : 0 }, ratedWithoutState: remaining,
      });
    });
    renderApp("/mis-juegos");
    const offer = await screen.findByRole("region", { name: "Tus juegos puntuados" });
    expect(within(offer).getByRole("heading", { name: "Tienes 3 juegos puntuados sin estado" })).toBeVisible();

    const user = userEvent.setup();
    await user.click(within(offer).getByRole("button", { name: "Marcar como Completado" }));
    // Nothing is marked until the player confirms the number.
    expect(fetchMock.mock.calls.some(([request]) => (request as Request).method === "POST")).toBe(false);
    const confirm = within(offer).getByRole("button", { name: "Marcar 3 juegos como Completado" });
    expect(confirm).toHaveFocus();
    await user.click(confirm);

    const message = await screen.findByText(/3 juegos marcados como Completado\./);
    await waitFor(() => expect(message).toHaveFocus());
    expect(screen.queryByRole("heading", { name: /puntuados sin estado/ })).toBeNull();
    expect(await screen.findByRole("link", { name: /^Completado:?\s*3$/ })).toBeVisible();
  });

  it("asks a visitor to sign in and return to the same view", async () => {
    stub(() => Response.json({}), false);
    renderApp("/mis-juegos?estado=completado");
    const login = await within(await screen.findByRole("main")).findByRole("link", { name: "Iniciar sesión" });
    expect(new URL(login.getAttribute("href") ?? "", "https://example.test").searchParams.get("returnTo"))
      .toBe("/mis-juegos?estado=completado");
  });
});
