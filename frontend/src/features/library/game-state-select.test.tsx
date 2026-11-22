import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, expect, it, vi } from "vitest";

import { GameStateSelect } from "./game-state-select";
import { useGameStates } from "./use-game-states";

function Harness({ ids, released = true }: { ids: string[]; released?: boolean }) {
  const library = useGameStates(ids);
  return (
    <>
      {ids.map((id) => (
        <GameStateSelect key={id} csrfToken={library.csrfToken} gameId={id} released={released} title={`Juego ${id}`}
          ratingOffer={<button type="button">¿Qué te ha parecido? Puntúalo</button>}
          state={library.states === null ? undefined : library.states.get(id) ?? null} />
      ))}
    </>
  );
}

function renderHarness(ids: string[], released = true) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter><Harness ids={ids} released={released} /></MemoryRouter>
    </QueryClientProvider>,
  );
}

afterEach(() => vi.unstubAllGlobals());

it("reads one batch, changes a state with CSRF and offers rating only after finishing", async () => {
  const requests: Request[] = [];
  const states = new Map([["two", "playing"]]);
  vi.stubGlobal("fetch", vi.fn(async (request: Request) => {
    requests.push(request.clone());
    const url = new URL(request.url);
    if (url.pathname.endsWith("/session")) return Response.json({ authenticated: true, csrfToken: "csrf-library" });
    if (url.pathname.endsWith("/library/states")) {
      return Response.json({ entries: [...states].map(([gameId, state]) => ({ gameId, state })) });
    }
    expect(request.headers.get("X-CSRF-Token")).toBe("csrf-library");
    const gameId = url.pathname.split("/").at(-1) ?? "";
    if (request.method === "DELETE") states.delete(gameId);
    else states.set(gameId, ((await request.json()) as { state: string }).state);
    return new Response(null, { status: 204 });
  }));
  renderHarness(["one", "two"]);

  const first = await screen.findByLabelText("Estado de Juego one");
  const second = screen.getByLabelText("Estado de Juego two");
  await waitFor(() => expect(second).toHaveValue("playing"));
  expect(first).toHaveValue("");
  const reads = requests.filter((request) => new URL(request.url).pathname.endsWith("/library/states"));
  expect(reads).toHaveLength(1);
  expect(new URL(reads[0]?.url ?? "").searchParams.getAll("gameIds")).toEqual(["one", "two"]);

  const user = userEvent.setup();
  await user.selectOptions(first, "backlog");
  await waitFor(() => expect(first).toHaveValue("backlog"));
  expect(await screen.findByText("Juego one: Pendiente.")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "¿Qué te ha parecido? Puntúalo" })).toBeNull();

  await user.selectOptions(second, "completed");
  expect(await screen.findByRole("button", { name: "¿Qué te ha parecido? Puntúalo" })).toBeVisible();
  const command = requests.find((request) => request.method === "PUT" && request.url.endsWith("/games/two"));
  expect(await command?.json()).toEqual({ state: "completed" });

  await user.selectOptions(first, "");
  await waitFor(() => expect(first).toHaveValue(""));
  expect(requests.some((request) => request.method === "DELETE" && request.url.endsWith("/games/one"))).toBe(true);
});

it("shows no selector and reads no state for a visitor", async () => {
  const fetch = vi.fn(async () => Response.json({ authenticated: false }));
  vi.stubGlobal("fetch", fetch);
  renderHarness(["one"]);
  await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1));
  expect(screen.queryByRole("combobox")).toBeNull();
});

it("keeps the saved state and alerts when a change fails", async () => {
  vi.stubGlobal("fetch", vi.fn(async (request: Request) => {
    const url = new URL(request.url);
    if (url.pathname.endsWith("/session")) return Response.json({ authenticated: true, csrfToken: "csrf" });
    if (url.pathname.endsWith("/library/states")) return Response.json({ entries: [{ gameId: "one", state: "playing" }] });
    return Response.json({ code: "INTERNAL_ERROR" }, { status: 500 });
  }));
  renderHarness(["one"]);
  const select = await screen.findByLabelText("Estado de Juego one");
  await waitFor(() => expect(select).toHaveValue("playing"));
  await userEvent.setup().selectOptions(select, "abandoned");
  expect(await screen.findByRole("alert")).toHaveTextContent("No se pudo guardar el estado");
  await waitFor(() => expect(select).toHaveValue("playing"));
});

it("offers only Deseado for a game that has not come out, keeping a state it already has", async () => {
  vi.stubGlobal("fetch", vi.fn(async (request: Request) => {
    const url = new URL(request.url);
    if (url.pathname.endsWith("/session")) return Response.json({ authenticated: true, csrfToken: "csrf" });
    return Response.json({ entries: [{ gameId: "two", state: "backlog" }] });
  }));
  renderHarness(["one", "two"], false);

  const first = await screen.findByLabelText("Estado de Juego one");
  await waitFor(() => expect(first).not.toHaveAttribute("aria-disabled"));
  expect(first).toHaveAccessibleDescription("Solo Deseado hasta que salga.");
  const enabled = (select: HTMLElement) => [...select.querySelectorAll("option")]
    .filter((option) => !option.disabled).map((option) => option.textContent);
  expect(enabled(first)).toEqual(["Sin estado", "Deseado"]);
  expect(enabled(screen.getByLabelText("Estado de Juego two"))).toEqual(["Sin estado", "Deseado", "Pendiente"]);
});
