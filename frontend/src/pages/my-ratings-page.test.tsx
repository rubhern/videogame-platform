import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { renderApp } from "../test/render-app";

const rating = { gameId: "game-1", value: 7, createdAt: "2026-08-13T10:00:00Z",
  updatedAt: "2026-08-13T10:00:00Z", entityTag: '"version-1"' };
const game = { gameId: "game-1", slug: "elite", canonicalTitle: "Élite Dangerous",
  primaryCover: { kind: "fallback", url: "/assets/covers/fallback.svg",
    alternativeText: "Portada no disponible", attribution: null } };
function page(value = rating, total = 1, number = 1) {
  return { items: total ? [{ game, personalRating: value }] : [],
    page: { number, size: 20, totalItems: total, totalPages: Math.ceil(total / 20) } };
}
const stats = { status: "available", mean: 9, count: 1,
  distribution: { "1": 0, "2": 0, "3": 0, "4": 0, "5": 0, "6": 0, "7": 0, "8": 0, "9": 1, "10": 0 } };

function stub(handler: (request: Request) => Response | Promise<Response>, authenticated = true) {
  const fetchMock = vi.fn<typeof fetch>().mockImplementation(async input => {
    const request = input instanceof Request ? input : new Request(input);
    if (new URL(request.url).pathname === "/api/v1/session")
      return Response.json(authenticated ? { authenticated: true, csrfToken: "csrf" } : { authenticated: false });
    return handler(request);
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}
afterEach(() => vi.unstubAllGlobals());

describe("Mis puntuaciones", () => {
  it("keeps anonymous visits private and offers the existing rating authentication journey", async () => {
    const fetchMock = stub(() => Response.json(page()), false);
    renderApp("/mis-puntuaciones");
    expect(await screen.findByText("Necesitas una sesión activa")).toBeVisible();
    expect(fetchMock.mock.calls.some(([request]) => String((request as Request).url).includes("/me/ratings"))).toBe(false);
  });
  it("distinguishes no ratings from no search results without storing personal search", async () => {
    const fetchMock = stub(() => Response.json(page(rating, 0)));
    renderApp("/mis-puntuaciones");
    expect(await screen.findByText("Todavía no has puntuado ningún juego")).toBeVisible();
    const search = screen.getByRole("search", { name: "Buscar en mis puntuaciones" });
    await userEvent.type(within(search).getByRole("searchbox"), "space");
    await userEvent.click(within(search).getByRole("button", { name: "Buscar en mis puntuaciones" }));
    expect(await screen.findByText("No hay puntuaciones que coincidan con tu búsqueda")).toBeVisible();
    expect(fetchMock.mock.calls.some(([request]) => new URL((request as Request).url).searchParams.get("q") === "space")).toBe(true);
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
    await userEvent.click(screen.getByRole("button", { name: "Limpiar búsqueda" }));
    expect(await screen.findByText("Todavía no has puntuado ningún juego")).toBeVisible();
  });
  it("sends sorting and pagination to the server and moves focus to results", async () => {
    const fetchMock = stub(request => Response.json(page(rating, 21, Number(new URL(request.url).searchParams.get("page") ?? 1))));
    renderApp("/mis-puntuaciones");
    await screen.findByText("Élite Dangerous");
    await userEvent.click(screen.getByRole("combobox", { name: /^Ordenar por/ }));
    await userEvent.click(screen.getByRole("option", { name: "Título" }));
    await waitFor(() => expect(fetchMock.mock.calls.some(([request]) => {
      const url = new URL((request as Request).url);
      return url.searchParams.get("sort") === "canonicalTitle" && url.searchParams.get("direction") === "asc";
    })).toBe(true));
    await userEvent.click(await screen.findByRole("button", { name: "Página siguiente" }));
    await waitFor(() => expect(fetchMock.mock.calls.some(([request]) => new URL((request as Request).url).searchParams.get("page") === "2")).toBe(true));
    expect(screen.getByRole("heading", { name: "Resultados de mis puntuaciones" })).toHaveFocus();
  });
  it("updates and deletes with the collection ETag, then displays the real empty state", async () => {
    let current = rating;
    let total = 1;
    const fetchMock = stub(async request => {
      if (request.method === "PUT") {
        const body = await request.json() as { value: number };
        current = { ...rating, value: body.value, entityTag: '"version-2"' };
        return Response.json({ personalRating: current, ratingStatistics: stats });
      }
      if (request.method === "DELETE") { total = 0; return Response.json({ personalRating: null, ratingStatistics: stats }); }
      return Response.json(page(current, total));
    });
    renderApp("/mis-puntuaciones");
    await userEvent.click(await screen.findByRole("button", { name: "Editar puntuación" }));
    expect(screen.getByRole("combobox", { name: /^Nueva puntuación/ })).toHaveFocus();
    await userEvent.click(screen.getByRole("combobox", { name: /^Nueva puntuación/ }));
    await userEvent.click(screen.getByRole("option", { name: "9/10" }));
    await userEvent.click(screen.getByRole("button", { name: "Guardar cambios" }));
    expect(await screen.findByText("9/10")).toBeVisible();
    const put = fetchMock.mock.calls.map(([request]) => request as Request).find(request => request.method === "PUT");
    expect(put?.headers.get("If-Match")).toBe('"version-1"');
    expect(put?.headers.get("X-CSRF-Token")).toBe("csrf");
    await userEvent.click(screen.getByRole("button", { name: "Eliminar puntuación" }));
    expect(await screen.findByText("Todavía no has puntuado ningún juego")).toBeVisible();
    const remove = fetchMock.mock.calls.map(([request]) => request as Request).find(request => request.method === "DELETE");
    expect(remove?.headers.get("If-Match")).toBe('"version-2"');
  });
  it("surfaces a stale ETag and never retries a command automatically", async () => {
    let current = rating;
    const fetchMock = stub(request => {
      if (request.method === "DELETE") {
        current = { ...rating, value: 9, entityTag: '"winner"' };
        return Response.json({ code: "RATING_WRITE_CONFLICT" }, { status: 412 });
      }
      return Response.json(page(current));
    });
    renderApp("/mis-puntuaciones");
    await userEvent.click(await screen.findByRole("button", { name: "Eliminar puntuación" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("cambió en otra sesión");
    expect(await screen.findByText("9/10")).toBeVisible();
    expect(screen.getByRole("button", { name: "Eliminar puntuación" })).toBeDisabled();
    expect(fetchMock.mock.calls.filter(([request]) => (request as Request).method === "DELETE")).toHaveLength(1);
    await userEvent.click(screen.getByRole("button", { name: "Actualizar resultados" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Eliminar puntuación" })).toBeEnabled());
  });
  it("keeps ambiguous commands blocked until a successful read, including failed refreshes", async () => {
    let failed = false;
    const fetchMock = stub(request => {
      if (request.method === "DELETE") { failed = true; throw new TypeError("network"); }
      if (failed) return Response.json({ code: "PERSONAL_RATINGS_READ_FAILED" }, { status: 500 });
      return Response.json(page());
    });
    renderApp("/mis-puntuaciones");
    await userEvent.click(await screen.findByRole("button", { name: "Eliminar puntuación" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("No sabemos si se aplicó");
    await userEvent.click(screen.getByRole("button", { name: "Actualizar resultados" }));
    await screen.findByText("No se pudieron cargar tus puntuaciones. Inténtalo de nuevo.");
    expect(screen.getByRole("button", { name: "Eliminar puntuación" })).toBeDisabled();
    expect(fetchMock.mock.calls.filter(([request]) => (request as Request).method === "DELETE")).toHaveLength(1);
  });
  it("renders load errors and recovers without presenting a false empty state", async () => {
    let failing = true;
    stub(() => failing ? Response.json({ code: "PERSONAL_RATINGS_READ_FAILED" }, { status: 500 }) : Response.json(page()));
    renderApp("/mis-puntuaciones");
    expect(await screen.findByRole("alert")).toHaveTextContent("No se pudieron cargar");
    expect(screen.queryByText("Todavía no has puntuado ningún juego")).not.toBeInTheDocument();
    failing = false;
    await userEvent.click(screen.getByRole("button", { name: "Reintentar carga" }));
    expect(await screen.findByText("Élite Dangerous")).toBeVisible();
  });
});
