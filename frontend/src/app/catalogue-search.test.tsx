import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { components } from "../shared/api/generated/schema";
import { renderApp } from "../test/render-app";

type GameSearchPage = components["schemas"]["GameSearchPage"];
type GameSummary = GameSearchPage["items"][number];

function game(title: string, index: number, overrides: Partial<GameSummary> = {}): GameSummary {
  return {
    gameId: `30000000-0000-4000-8000-00000000000${index}`,
    slug: title.toLowerCase().replaceAll(" ", "-"),
    canonicalTitle: title,
    primaryCover: {
      kind: "fallback",
      url: "/assets/covers/fallback.svg",
      alternativeText: `Portada no disponible de ${title}`,
      attribution: null,
    },
    releaseContext: [
      {
        platform: { platformId: "10000000-0000-4000-8000-000000000001", name: "PlayStation 5" },
        region: { regionId: "region-europe", name: "Europe" },
        releaseDate: { precision: "day", value: "2026-03-18" },
        status: "released",
        freshnessStatus: "fresh",
      },
    ],
    releaseSummary: {
      platforms: [{ platformId: "10000000-0000-4000-8000-000000000001", name: "PlayStation 5" }],
      totalPlatforms: 1,
      earliestKnownYear: 2026,
      latestKnownYear: 2026,
    },
    ...overrides,
  };
}

const titles = ["Eclipse of Aether", "Aetherfall", "Neon Tides", "Ashen Realms", "Hollow Grove", "Aether Sixth"];

function page(items: GameSummary[], totalItems = items.length): GameSearchPage {
  return { items, page: { number: 1, size: 5, totalItems, totalPages: 1 } };
}

const fivePage = page(
  titles.slice(0, 5).map((title, index) =>
    game(title, index, index === 1 ? { matchedAlias: "Aether Fall" } : {}),
  ),
  23,
);

type Handler = (url: URL, request: Request) => Response | Promise<Response>;

function stubCatalogue(handler: Handler) {
  const fetchMock = vi.fn<typeof fetch>().mockImplementation(async (input) => {
    const request = input instanceof Request ? input : new Request(String(input));
    const url = new URL(request.url);
    if (url.pathname === "/api/v1/session") {
      return Response.json({ authenticated: false });
    }
    if (url.pathname.startsWith("/auth/rating-intent")) {
      return new Response(null, { status: 404 });
    }
    return handler(url, request);
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

function gameSearchRequests(fetchMock: ReturnType<typeof stubCatalogue>): Request[] {
  return fetchMock.mock.calls
    .map((call) => call[0] as Request)
    .filter((request) => new URL(request.url).pathname === "/api/v1/games");
}

function searchInput() {
  return screen.getByRole("combobox", { name: "Buscar en el catálogo" });
}

/** The popup's own live status, distinct from the page's result status. */
function suggestionStatus() {
  const form = searchInput().closest("form");
  if (form === null) throw new Error("The catalogue search form is missing.");
  return within(form).getByRole("status");
}

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("catalogue search typeahead", () => {
  describe("request policy", () => {
    function setupFakeClock() {
      vi.useFakeTimers({ shouldAdvanceTime: true });
      return userEvent.setup({ advanceTimers: (ms) => vi.advanceTimersByTime(ms) });
    }

    it("sends nothing below two trimmed Unicode code points", async () => {
      const fetchMock = stubCatalogue(() => Response.json(fivePage));
      const user = setupFakeClock();
      renderApp("/search");

      await user.type(searchInput(), "  a  ");
      await act(() => vi.advanceTimersByTimeAsync(1_000));
      await user.clear(searchInput());
      // One emoji is one code point even though it is two UTF-16 units.
      await user.type(searchInput(), "🎮");
      await act(() => vi.advanceTimersByTimeAsync(1_000));

      expect(gameSearchRequests(fetchMock)).toHaveLength(0);
      expect(searchInput()).toHaveAttribute("aria-expanded", "false");
    });

    it("waits 250 ms after the latest change and asks only for the first five results", async () => {
      const fetchMock = stubCatalogue(() => Response.json(fivePage));
      const user = setupFakeClock();
      renderApp("/search");

      await user.type(searchInput(), "ae");
      await act(() => vi.advanceTimersByTimeAsync(200));
      await user.type(searchInput(), "t");
      await act(() => vi.advanceTimersByTimeAsync(200));
      expect(gameSearchRequests(fetchMock)).toHaveLength(0);

      await act(() => vi.advanceTimersByTimeAsync(60));

      await waitFor(() => expect(gameSearchRequests(fetchMock)).toHaveLength(1));
      const [request] = gameSearchRequests(fetchMock);
      const params = new URL(request?.url ?? "").searchParams;
      expect(Object.fromEntries(params)).toEqual({ q: "aet", page: "1", pageSize: "5" });
    });
  });

  it("shows at most five suggestions with the approved row content", async () => {
    stubCatalogue(() =>
      Response.json(page(titles.map((title, index) => game(title, index)), 23)),
    );
    const user = userEvent.setup();
    renderApp("/search");

    await user.type(searchInput(), "aether");

    const listbox = await screen.findByRole("listbox", { name: "Sugerencias de juegos" });
    expect(within(listbox).getAllByRole("option")).toHaveLength(5);
    expect(screen.getByText("Resultados (23)")).toBeInTheDocument();
    expect(
      within(listbox).getByRole("option", {
        name: "Eclipse of Aether · PlayStation 5 · 2026",
      }),
    ).toHaveAttribute("aria-selected", "false");
    expect(suggestionStatus()).toHaveTextContent("5 sugerencias disponibles");
    expect(screen.getByRole("button", { name: "Ver todos los resultados para «aether»" })).toBeVisible();
  });

  it("renders the compact release summary's platforms, exact +N and year range", async () => {
    stubCatalogue(() =>
      Response.json(
        page([
          game("Eclipse of Aether", 0, {
            releaseSummary: {
              platforms: [
                { platformId: "10000000-0000-4000-8000-000000000001", name: "PlayStation 5" },
                { platformId: "10000000-0000-4000-8000-000000000003", name: "Windows PC" },
                { platformId: "10000000-0000-4000-8000-000000000004", name: "Xbox Series X|S" },
              ],
              totalPlatforms: 6,
              earliestKnownYear: 2024,
              latestKnownYear: 2026,
            },
          }),
        ]),
      ),
    );
    const user = userEvent.setup();
    renderApp("/search");

    await user.type(searchInput(), "aether");

    const option = await screen.findByRole("option", {
      name: "Eclipse of Aether · PlayStation 5, Windows PC, Xbox Series X|S · 3 plataformas más · 2024–2026",
    });
    expect(within(option).getByText("+3")).toHaveAttribute("title", "3 plataformas más");
    expect(within(option).getByText("2024–2026")).toBeVisible();
  });

  it("explains an alias match in the row and its accessible name", async () => {
    stubCatalogue(() => Response.json(fivePage));
    const user = userEvent.setup();
    renderApp("/search");

    await user.type(searchInput(), "aether fall");

    const option = await screen.findByRole("option", {
      name: "Aetherfall · también conocido como Aether Fall · PlayStation 5 · 2026",
    });
    expect(within(option).getByText("También «Aether Fall»")).toBeVisible();
  });

  it("moves the active option with the arrows while the input keeps focus and opens it with Enter", async () => {
    stubCatalogue(() => Response.json(fivePage));
    const user = userEvent.setup();
    const { router } = renderApp("/search");

    await user.type(searchInput(), "aether");
    const options = await screen.findAllByRole("option");

    await user.keyboard("{ArrowDown}");
    expect(options[0]).toHaveAttribute("aria-selected", "true");
    expect(searchInput()).toHaveAttribute("aria-activedescendant", options[0]?.id);
    expect(searchInput()).toHaveFocus();

    await user.keyboard("{ArrowDown}{ArrowDown}{ArrowUp}");
    expect(options[1]).toHaveAttribute("aria-selected", "true");
    expect(options[0]).toHaveAttribute("aria-selected", "false");

    await user.keyboard("{ArrowUp}{ArrowUp}");
    expect(options[4]).toHaveAttribute("aria-selected", "true");

    await user.keyboard("{ArrowDown}{Enter}");

    await waitFor(() =>
      expect(router.state.location.pathname).toBe(
        "/games/30000000-0000-4000-8000-000000000000/eclipse-of-aether",
      ),
    );
  });

  it("runs the existing full search with Enter when no suggestion is active", async () => {
    const fetchMock = stubCatalogue(() => Response.json(fivePage));
    const user = userEvent.setup();
    const { router } = renderApp("/search");

    await user.type(searchInput(), "aether");
    await screen.findByRole("listbox");
    await user.keyboard("{Enter}");

    await waitFor(() => expect(router.state.location.search).toBe("?q=aether"));
    await waitFor(() =>
      expect(
        gameSearchRequests(fetchMock).some(
          (request) => new URL(request.url).searchParams.get("pageSize") === "6",
        ),
      ).toBe(true),
    );
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
  });

  it("opens a game from a pointer selection", async () => {
    stubCatalogue(() => Response.json(fivePage));
    const user = userEvent.setup();
    const { router } = renderApp("/search");

    await user.type(searchInput(), "aether");
    await user.click(await screen.findByRole("option", { name: /^Neon Tides/ }));

    await waitFor(() =>
      expect(router.state.location.pathname).toBe(
        "/games/30000000-0000-4000-8000-000000000002/neon-tides",
      ),
    );
  });

  it("runs the full search from the footer action", async () => {
    stubCatalogue(() => Response.json(fivePage));
    const user = userEvent.setup();
    const { router } = renderApp("/search");

    await user.type(searchInput(), "aether");
    await user.click(
      await screen.findByRole("button", { name: "Ver todos los resultados para «aether»" }),
    );

    await waitFor(() => expect(router.state.location.search).toBe("?q=aether"));
  });

  it("closes with Escape keeping text and focus, and reopens with the arrow keys", async () => {
    stubCatalogue(() => Response.json(fivePage));
    const user = userEvent.setup();
    renderApp("/search");

    await user.type(searchInput(), "aether");
    await screen.findByRole("listbox");
    await user.keyboard("{Escape}");

    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
    expect(searchInput()).toHaveValue("aether");
    expect(searchInput()).toHaveFocus();
    expect(searchInput()).toHaveAttribute("aria-expanded", "false");

    await user.keyboard("{ArrowDown}");
    expect(await screen.findByRole("listbox")).toBeInTheDocument();
  });

  it("closes on an outside click and when the query is cleared", async () => {
    stubCatalogue(() => Response.json(fivePage));
    const user = userEvent.setup();
    renderApp("/search");

    await user.type(searchInput(), "aether");
    await screen.findByRole("listbox");
    await user.click(screen.getByRole("heading", { level: 1 }));
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();

    await user.type(searchInput(), "s");
    await screen.findByRole("listbox");
    await user.clear(searchInput());
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
    expect(searchInput()).toHaveAttribute("aria-expanded", "false");
  });

  it("never lets a slower superseded response replace newer suggestions", async () => {
    const pending = new Map<string, (response: Response) => void>();
    const fetchMock = stubCatalogue(
      (url) =>
        new Promise<Response>((resolve) => {
          pending.set(url.searchParams.get("q") ?? "", resolve);
        }),
    );
    const user = userEvent.setup();
    renderApp("/search");

    await user.type(searchInput(), "ae");
    await waitFor(() => expect(pending.has("ae")).toBe(true));
    await user.type(searchInput(), "on");
    await waitFor(() => expect(pending.has("aeon")).toBe(true));

    pending.get("aeon")?.(Response.json(page([game("Neon Tides", 2)])));
    expect(await screen.findByRole("option", { name: /^Neon Tides/ })).toBeInTheDocument();

    pending.get("ae")?.(Response.json(fivePage));
    await act(() => new Promise((resolve) => setTimeout(resolve, 50)));

    expect(screen.getAllByRole("option")).toHaveLength(1);
    expect(screen.queryByRole("option", { name: /^Eclipse of Aether/ })).not.toBeInTheDocument();
    // The superseded request was cancelled, not merely ignored.
    const superseded = gameSearchRequests(fetchMock).find(
      (request) => new URL(request.url).searchParams.get("q") === "ae",
    );
    expect(superseded?.signal.aborted).toBe(true);
  });

  it("shows a bounded loading state that never presents the previous term as results", async () => {
    const pending = new Map<string, (response: Response) => void>();
    stubCatalogue(
      (url) =>
        new Promise<Response>((resolve) => {
          pending.set(url.searchParams.get("q") ?? "", resolve);
        }),
    );
    const user = userEvent.setup();
    renderApp("/search");

    await user.type(searchInput(), "ae");
    expect(await screen.findByText("Buscando…")).toBeInTheDocument();
    expect(suggestionStatus()).toHaveTextContent("Buscando sugerencias…");
    await waitFor(() => expect(pending.has("ae")).toBe(true));
    pending.get("ae")?.(Response.json(fivePage));
    await screen.findByRole("listbox");

    await user.type(searchInput(), "x");

    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
    expect(screen.getByText("Buscando…")).toBeInTheDocument();
  });

  it("keeps full search available when there are no matches", async () => {
    stubCatalogue(() => Response.json(page([])));
    const user = userEvent.setup();
    const { router } = renderApp("/search");

    await user.type(searchInput(), "zzzz");

    expect(await screen.findByText("No hay coincidencias")).toBeVisible();
    expect(suggestionStatus()).toHaveTextContent("No hay coincidencias.");
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();

    await user.keyboard("{Enter}");
    await waitFor(() => expect(router.state.location.search).toBe("?q=zzzz"));
  });

  it("reports a failed suggestion request without blocking typing or full search", async () => {
    stubCatalogue((url) =>
      url.searchParams.get("pageSize") === "5"
        ? Response.json({ code: "INTERNAL_ERROR", correlationId: "corr-1" }, { status: 500 })
        : Response.json(page([])),
    );
    const user = userEvent.setup();
    const { router } = renderApp("/search");

    await user.type(searchInput(), "aether");

    expect(await screen.findByText("No se pudieron cargar las sugerencias")).toBeVisible();
    await user.type(searchInput(), "s{Enter}");

    expect(searchInput()).toHaveValue("aethers");
    await waitFor(() => expect(router.state.location.search).toBe("?q=aethers"));
  });
});
