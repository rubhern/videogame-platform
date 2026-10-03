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
    page: { number, size: 10, totalItems: total, totalPages: Math.ceil(total / 10) } };
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

function listReads(fetchMock: ReturnType<typeof stub>) {
  return fetchMock.mock.calls.map(([request]) => new URL((request as Request).url))
    .filter(url => url.pathname === "/api/v1/me/ratings");
}
const commands = (fetchMock: ReturnType<typeof stub>, method: string) =>
  fetchMock.mock.calls.map(([request]) => request as Request).filter(request => request.method === method);

async function openEditor() {
  const card = await screen.findByRole("article", { name: "Élite Dangerous" });
  await userEvent.click(within(card).getByRole("button", { name: /^Tu puntuación/ }));
  return { card, editor: within(card).getByRole("dialog", { name: "Tu puntuación de Élite Dangerous" }) };
}

describe("Mis puntuaciones", () => {
  it("keeps anonymous visits private and offers the product account entry", async () => {
    const fetchMock = stub(() => Response.json(page()), false);
    renderApp("/mis-puntuaciones");
    expect(await screen.findByText("Necesitas una sesión activa")).toBeVisible();
    expect(screen.getAllByRole("link", { name: "Iniciar sesión" }).some(link => link.getAttribute("href") === "/auth/start?returnTo=%2Fmis-puntuaciones")).toBe(true);
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
    const fetchMock = stub(request => Response.json(page(rating, 11, Number(new URL(request.url).searchParams.get("page") ?? 1))));
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
  it("loads ten ratings per page by default, offers 10/20/50 and has no manual refresh", async () => {
    const fetchMock = stub(() => Response.json(page()));
    renderApp("/mis-puntuaciones");
    await screen.findByText("Élite Dangerous");
    expect(listReads(fetchMock).map(url => url.searchParams.get("pageSize"))).toEqual(["10"]);
    expect(screen.queryByRole("button", { name: "Actualizar resultados" })).not.toBeInTheDocument();
    // A single page needs no pager.
    expect(screen.queryByRole("navigation", { name: "Paginación de mis puntuaciones" })).not.toBeInTheDocument();
    // The score is the row's only maintenance control.
    expect(screen.queryByRole("button", { name: "Editar puntuación" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Eliminar puntuación" })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("combobox", { name: /^Por página/ }));
    const sizes = screen.getAllByRole("option");
    expect(sizes).toHaveLength(3);
    ["10", "20", "50"].forEach((size, index) => expect(sizes[index]).toHaveAccessibleName(size));
    expect(sizes[0]).toHaveAttribute("aria-selected", "true");
  });
  it("opens the keypad from the score, keeps a pick pending until Save, then re-reads the list", async () => {
    let current = rating;
    const fetchMock = stub(async request => {
      if (request.method === "PUT") {
        const body = await request.json() as { value: number };
        current = { ...rating, value: body.value, entityTag: '"version-2"' };
        return Response.json({ personalRating: current, ratingStatistics: stats });
      }
      return Response.json(page(current));
    });
    renderApp("/mis-puntuaciones");
    const card = await screen.findByRole("article", { name: "Élite Dangerous" });
    // The personal score names its temperature beside the number.
    const score = within(card).getByRole("button", { name: /^Tu puntuación Caliente 7\/10/ });
    expect(score).toHaveAttribute("aria-expanded", "false");
    await userEvent.click(score);
    expect(score).toHaveAttribute("aria-expanded", "true");
    const editor = within(card).getByRole("dialog", { name: "Tu puntuación de Élite Dangerous" });
    expect(within(editor).getByRole("button", { name: "7" })).toHaveFocus();
    expect(within(editor).getByRole("button", { name: "Guardar nota" })).toBeDisabled();
    // Arrows only browse; Enter picks a pending value and sends nothing.
    await userEvent.keyboard("{ArrowRight}{ArrowRight}");
    expect(within(editor).getByRole("button", { name: "9" })).toHaveFocus();
    expect(within(editor).getByRole("button", { name: "7" })).toHaveAttribute("aria-pressed", "true");
    await userEvent.keyboard("{Enter}");
    expect(within(editor).getByRole("button", { name: "9" })).toHaveAttribute("aria-pressed", "true");
    // The pending reading takes its own temperature while the persisted one stays named.
    expect(editor).toHaveAttribute("data-thermal", "burn");
    expect(within(editor).getByText("Nueva nota").parentElement).toHaveTextContent("9/10Ardiendo");
    expect(within(editor).getByText("Nota actual").parentElement).toHaveTextContent("7/10Caliente");
    await userEvent.click(within(editor).getByRole("button", { name: "4" }));
    expect(editor).toHaveAttribute("data-thermal", "cold");
    expect(within(editor).getByText("Nueva nota").parentElement).toHaveTextContent("4/10Frío");
    await userEvent.click(within(editor).getByRole("button", { name: "9" }));
    expect(commands(fetchMock, "PUT")).toHaveLength(0);
    await userEvent.click(within(editor).getByRole("button", { name: "Guardar nota" }));
    expect(await within(card).findByRole("button", { name: /^Tu puntuación Ardiendo 9\/10/ })).toBeVisible();
    expect(within(card).queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Resultados de mis puntuaciones" })).toHaveFocus();
    expect(commands(fetchMock, "PUT")[0]?.headers.get("If-Match")).toBe('"version-1"');
    expect(commands(fetchMock, "PUT")[0]?.headers.get("X-CSRF-Token")).toBe("csrf");
    // The list re-reads itself after the command; there is no refresh action.
    expect(listReads(fetchMock)).toHaveLength(2);
  });
  it("closes on Escape, Cancel or an outside press without sending a command", async () => {
    const fetchMock = stub(() => Response.json(page()));
    renderApp("/mis-puntuaciones");
    const card = await screen.findByRole("article", { name: "Élite Dangerous" });
    const score = within(card).getByRole("button", { name: /^Tu puntuación/ });
    score.focus();
    await userEvent.keyboard("{Enter}");
    await userEvent.click(within(card).getByRole("button", { name: "3" }));
    await userEvent.keyboard("{Escape}");
    expect(within(card).queryByRole("dialog")).not.toBeInTheDocument();
    expect(score).toHaveFocus();
    // Reopening starts again from the persisted value.
    await userEvent.keyboard("{Enter}");
    expect(within(card).getByRole("button", { name: "7" })).toHaveAttribute("aria-pressed", "true");
    await userEvent.click(within(card).getByRole("button", { name: "Cancelar" }));
    expect(within(card).queryByRole("dialog")).not.toBeInTheDocument();
    expect(score).toHaveFocus();
    await userEvent.click(score);
    await userEvent.click(screen.getByRole("heading", { name: "Mis puntuaciones" }));
    expect(within(card).queryByRole("dialog")).not.toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([request]) => (request as Request).method !== "GET")).toBe(false);
  });
  it("deletes only after an explicit confirmation, with the collection ETag, then shows the real empty state", async () => {
    let total = 1;
    const fetchMock = stub(request => {
      if (request.method === "DELETE") { total = 0; return Response.json({ personalRating: null, ratingStatistics: stats }); }
      return Response.json(page(rating, total));
    });
    renderApp("/mis-puntuaciones");
    const { editor } = await openEditor();
    const remove = within(editor).getByRole("button", { name: "Eliminar puntuación" });
    await userEvent.click(remove);
    expect(remove).toHaveAttribute("aria-expanded", "true");
    expect(within(editor).getByRole("group", { name: /¿Eliminar tu nota de 7\/10\?/ })).toBeVisible();
    expect(within(editor).getByRole("button", { name: "Conservar" })).toHaveFocus();
    await userEvent.click(within(editor).getByRole("button", { name: "Conservar" }));
    expect(remove).toHaveFocus();
    expect(within(editor).queryByRole("button", { name: "Sí, eliminar" })).not.toBeInTheDocument();
    expect(commands(fetchMock, "DELETE")).toHaveLength(0);
    await userEvent.click(remove);
    await userEvent.click(within(editor).getByRole("button", { name: "Sí, eliminar" }));
    expect(await screen.findByText("Todavía no has puntuado ningún juego")).toBeVisible();
    expect(screen.getByRole("heading", { name: "Resultados de mis puntuaciones" })).toHaveFocus();
    expect(commands(fetchMock, "DELETE")[0]?.headers.get("If-Match")).toBe('"version-1"');
    expect(listReads(fetchMock)).toHaveLength(2);
  });
  it("surfaces a stale ETag in the editor, re-reads the winner and never retries a command automatically", async () => {
    let current = rating;
    const fetchMock = stub(request => {
      if (request.method === "PUT") {
        current = { ...rating, value: 9, entityTag: '"winner"' };
        return Response.json({ code: "RATING_WRITE_CONFLICT" }, { status: 412 });
      }
      return Response.json(page(current));
    });
    renderApp("/mis-puntuaciones");
    const { card, editor } = await openEditor();
    await userEvent.click(within(editor).getByRole("button", { name: "5" }));
    await userEvent.click(within(editor).getByRole("button", { name: "Guardar nota" }));
    expect(await within(editor).findByRole("alert")).toHaveTextContent("cambió en otra sesión");
    // The winning value arrives through the automatic read; the editor keeps the pending pick.
    expect(await within(card).findByRole("button", { name: /^Tu puntuación Ardiendo 9\/10/ })).toBeVisible();
    expect(within(editor).getByText("Nota actual").parentElement).toHaveTextContent("9/10Ardiendo");
    expect(within(editor).getByText("Nueva nota").parentElement).toHaveTextContent("5/10Templado");
    await waitFor(() => expect(within(editor).getByRole("button", { name: "Guardar nota" })).toBeEnabled());
    expect(commands(fetchMock, "PUT")).toHaveLength(1);
  });
  it("keeps ambiguous commands blocked until a successful read, including failed automatic reads", async () => {
    let failed = false;
    let recovered = false;
    const fetchMock = stub(request => {
      if (request.method === "DELETE") { failed = true; throw new TypeError("network"); }
      if (failed && !recovered) return Response.json({ code: "PERSONAL_RATINGS_READ_FAILED" }, { status: 500 });
      return Response.json(page());
    });
    renderApp("/mis-puntuaciones");
    const { editor } = await openEditor();
    await userEvent.click(within(editor).getByRole("button", { name: "Eliminar puntuación" }));
    await userEvent.click(within(editor).getByRole("button", { name: "Sí, eliminar" }));
    expect(await within(editor).findByRole("alert")).toHaveTextContent("No sabemos si se aplicó");
    // The outcome is unknown, so the list is read again on its own; here that read fails too.
    await screen.findByText("No se pudieron cargar tus puntuaciones. Inténtalo de nuevo.");
    expect(within(editor).getByRole("button", { name: "Eliminar puntuación" })).toBeDisabled();
    await userEvent.click(within(editor).getByRole("button", { name: "8" }));
    expect(within(editor).getByRole("button", { name: "Guardar nota" })).toBeDisabled();
    recovered = true;
    // Pressing the retry outside the editor closes it; once the read succeeds, commands return.
    await userEvent.click(screen.getByRole("button", { name: "Reintentar carga" }));
    await waitFor(() => expect(screen.queryByRole("button", { name: "Reintentar carga" })).not.toBeInTheDocument());
    const reopened = await openEditor();
    expect(within(reopened.editor).queryByRole("alert")).not.toBeInTheDocument();
    expect(within(reopened.editor).getByRole("button", { name: "Eliminar puntuación" })).toBeEnabled();
    expect(commands(fetchMock, "DELETE")).toHaveLength(1);
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
