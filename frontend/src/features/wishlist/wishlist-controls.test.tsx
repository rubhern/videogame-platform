import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, expect, it, vi } from "vitest";

import { SESSION_QUERY_KEY } from "../session/use-session";
import { setEventSink } from "../analytics/events";
import { WishlistActions, WishlistButton } from "./wishlist-controls";

function renderActions(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <WishlistActions batches={[["one", "two"]]}>
          <WishlistButton gameId="one" title="Primer juego" compact />
          <WishlistButton gameId="two" title="Segundo juego" compact />
        </WishlistActions>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return client;
}

afterEach(() => {
  vi.unstubAllGlobals();
  setEventSink(null);
});

it("batches list state, changes the pressed state, and reports only the successful action", async () => {
  const requests: Request[] = [];
  let saved = false;
  vi.stubGlobal("fetch", vi.fn(async (request: Request) => {
    requests.push(request);
    const url = new URL(request.url);
    if (url.pathname.endsWith("/session")) {
      return Response.json({ authenticated: true, csrfToken: "csrf-wishlist" });
    }
    if (url.pathname.endsWith("/wishlist/status")) {
      return Response.json({ gameIds: ["two", ...(saved ? ["one"] : [])] });
    }
    expect(url.pathname).toBe("/api/v1/me/wishlist/games/one");
    expect(request.headers.get("X-CSRF-Token")).toBe("csrf-wishlist");
    saved = request.method === "PUT";
    return new Response(null, { status: 204 });
  }));
  const events = vi.fn();
  setEventSink(events);
  renderActions("/topometro?source=gameometro");

  const first = await screen.findByRole("button", { name: "La quiero: Primer juego", pressed: false });
  expect(screen.getByRole("button", { name: "La quiero: Segundo juego", pressed: true })).toBeVisible();
  const statusReads = requests.filter((request) => new URL(request.url).pathname.endsWith("/wishlist/status"));
  expect(statusReads).toHaveLength(1);
  expect(new URL(statusReads[0]?.url ?? "").searchParams.getAll("gameIds")).toEqual(["one", "two"]);

  const user = userEvent.setup();
  await user.click(first);
  await waitFor(() => expect(first).toHaveAttribute("aria-pressed", "true"));
  await waitFor(() => expect(first).toBeEnabled());
  await user.click(first);
  await waitFor(() => expect(first).toHaveAttribute("aria-pressed", "false"));
  expect(events.mock.calls).toEqual([
    ["wishlist_changed", { action: "add" }],
    ["wishlist_changed", { action: "remove" }],
  ]);
});

it("offers sign-in back to the current ranking and never reads anonymous wishes", async () => {
  const fetch = vi.fn(async () => Response.json({ authenticated: false }));
  vi.stubGlobal("fetch", fetch);
  renderActions("/plataformas/ps5/topometro?source=gameometro");
  const link = await screen.findByRole("link", { name: /la quiero: primer juego/i });
  const href = new URL(link.getAttribute("href") ?? "", "https://example.test");
  expect(href.pathname).toBe("/auth/start");
  expect(href.searchParams.get("returnTo")).toBe("/plataformas/ps5/topometro?source=gameometro");
  expect(fetch).toHaveBeenCalledTimes(1);
});


it("does not render another session's cached wish while checking a new session", async () => {
  const gate: { resolve?: (response: Response) => void } = {};
  const secondStatus = new Promise<Response>((resolve) => { gate.resolve = resolve; });
  let reads = 0;
  vi.stubGlobal("fetch", vi.fn(async (request: Request) => {
    const url = new URL(request.url);
    if (url.pathname.endsWith("/session")) {
      return Response.json({ authenticated: true, csrfToken: "first-session" });
    }
    return ++reads === 1 ? Response.json({ gameIds: ["one"] }) : secondStatus;
  }));
  const client = renderActions("/search?q=test");
  const button = await screen.findByRole("button", { name: "La quiero: Primer juego", pressed: true });

  await act(() => {
    client.setQueryData(SESSION_QUERY_KEY, { authenticated: true, csrfToken: "second-session" });
  });
  await waitFor(() => expect(button).not.toHaveAttribute("aria-pressed"));
  gate.resolve?.(Response.json({ gameIds: [] }));
  await waitFor(() => expect(button).toHaveAttribute("aria-pressed", "false"));
});
