import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeAll, expect, it, vi } from "vitest";

import { GameStateButton } from "./game-state-button";
import { useGameStates } from "./use-game-states";

beforeAll(() => {
  // JSDOM lacks the modal dialog API; mirror its open/close contract.
  HTMLDialogElement.prototype.showModal ??= function showModal(this: HTMLDialogElement) {
    this.setAttribute("open", "");
  };
  HTMLDialogElement.prototype.close ??= function close(this: HTMLDialogElement) {
    this.removeAttribute("open");
    this.dispatchEvent(new Event("close"));
  };
});

function Harness({ released }: { released: boolean }) {
  const library = useGameStates(["one"]);
  return (
    <GameStateButton csrfToken={library.csrfToken} gameId="one" released={released} title="Juego uno"
      ratingOffer={<button type="button">¿Qué te ha parecido? Puntúalo</button>}
      state={library.states === null ? undefined : library.states.get("one") ?? null} />
  );
}

function renderHarness(released = true) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter><Harness released={released} /></MemoryRouter>
    </QueryClientProvider>,
  );
}

function stubLibrary(initial: string | null) {
  let state = initial;
  const commands: { method: string; body: unknown }[] = [];
  vi.stubGlobal("fetch", vi.fn(async (request: Request) => {
    const url = new URL(request.url);
    if (url.pathname.endsWith("/session")) return Response.json({ authenticated: true, csrfToken: "csrf" });
    if (url.pathname.endsWith("/library/states")) {
      return Response.json({ entries: state === null ? [] : [{ gameId: "one", state }] });
    }
    expect(request.headers.get("X-CSRF-Token")).toBe("csrf");
    const body = request.method === "PUT" ? await request.json() as { state: string } : null;
    commands.push({ method: request.method, body });
    state = body?.state ?? null;
    return new Response(null, { status: 204 });
  }));
  return commands;
}

afterEach(() => vi.unstubAllGlobals());

it("marks a game Jugando in one press and keeps focus on the same button", async () => {
  const commands = stubLibrary(null);
  renderHarness();
  const main = await screen.findByRole("button", { name: "Jugando: marcar Juego uno" });
  await waitFor(() => expect(main).not.toHaveAttribute("aria-disabled"));
  expect(screen.getByRole("button", { name: "Elegir el estado de Juego uno" })).toHaveAttribute("aria-haspopup", "dialog");

  const user = userEvent.setup();
  await user.click(main);
  await waitFor(() => expect(main).toHaveAccessibleName("Jugando: cambiar el estado de Juego uno"));
  expect(main).toHaveFocus();
  expect(screen.queryByRole("button", { name: "Elegir el estado de Juego uno" })).toBeNull();
  expect(commands).toEqual([{ method: "PUT", body: { state: "playing" } }]);
});

it("chooses any state from the caret's dialog and offers rating after finishing", async () => {
  const commands = stubLibrary(null);
  renderHarness();
  const user = userEvent.setup();
  const caret = await screen.findByRole("button", { name: "Elegir el estado de Juego uno" });
  await waitFor(() => expect(caret).not.toHaveAttribute("aria-disabled"));
  await user.click(caret);
  const dialog = screen.getByRole("dialog", { name: "Elige el estado de este juego" });
  expect(within(dialog).getAllByRole("button", { pressed: false }).map((button) => button.textContent))
    .toEqual(["Deseado", "Pendiente", "Jugando", "Completado", "Abandonado"]);
  expect(within(dialog).queryByRole("button", { name: "Quitar estado" })).toBeNull();

  await user.click(within(dialog).getByRole("button", { name: "Completado" }));
  expect(await screen.findByRole("button", { name: "¿Qué te ha parecido? Puntúalo" })).toBeVisible();
  expect(commands).toEqual([{ method: "PUT", body: { state: "completed" } }]);
});

it("marks the current state and removes it from the dialog", async () => {
  const commands = stubLibrary("backlog");
  renderHarness();
  const user = userEvent.setup();
  const main = await screen.findByRole("button", { name: "Pendiente: cambiar el estado de Juego uno" });
  await user.click(main);
  const dialog = screen.getByRole("dialog", { name: "Elige el estado de este juego" });
  expect(within(dialog).getByRole("button", { name: "Pendiente", pressed: true })).toHaveFocus();

  await user.click(within(dialog).getByRole("button", { name: "Quitar estado" }));
  expect(await screen.findByRole("button", { name: "Jugando: marcar Juego uno" })).toBeVisible();
  expect(commands).toEqual([{ method: "DELETE", body: null }]);
  expect(screen.queryByRole("dialog")).toBeNull();
});

it("marks a game that has not come out only as Deseado", async () => {
  const commands = stubLibrary(null);
  renderHarness(false);
  const main = await screen.findByRole("button", { name: "Deseado: marcar Juego uno" });
  await waitFor(() => expect(main).not.toHaveAttribute("aria-disabled"));
  expect(screen.queryByRole("button", { name: "Elegir el estado de Juego uno" })).toBeNull();

  const user = userEvent.setup();
  await user.click(main);
  await waitFor(() => expect(main).toHaveAccessibleName("Deseado: cambiar el estado de Juego uno"));
  expect(commands).toEqual([{ method: "PUT", body: { state: "wished" } }]);

  await user.click(main);
  const dialog = screen.getByRole("dialog", { name: "Elige el estado de este juego" });
  expect(within(dialog).getAllByRole("button", { pressed: true }).map((button) => button.textContent)).toEqual(["Deseado"]);
  expect(within(dialog).queryByRole("button", { pressed: false })).toBeNull();
  expect(within(dialog).getByText("Podrás marcar otros estados cuando el juego salga.")).toBeVisible();
  expect(within(dialog).getByRole("button", { name: "Quitar estado" })).toBeVisible();
});

it("shows nothing to a visitor", async () => {
  const fetch = vi.fn(async () => Response.json({ authenticated: false }));
  vi.stubGlobal("fetch", fetch);
  renderHarness();
  await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1));
  expect(screen.queryByRole("button")).toBeNull();
});
