import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { renderApp } from "../test/render-app";

const SWITCH_2 = "10000000-0000-4000-8000-000000000002";

const directory = [
  { platformId: SWITCH_2, name: "Nintendo Switch 2" },
  { platformId: "ps4-id", name: "PlayStation 4" },
  { platformId: "steamvr-id", name: "SteamVR" },
];

function game(gameId: string, title: string, release: Record<string, unknown>, extra: Record<string, unknown> = {}) {
  return {
    gameId,
    slug: gameId,
    canonicalTitle: title,
    primaryCover: { kind: "fallback", url: "/assets/covers/fallback.svg", alternativeText: "Portada", attribution: null },
    genres: [{ genreId: "adventure", name: "Aventura" }],
    platformRelease: {
      platform: { platformId: SWITCH_2, name: "Nintendo Switch 2" },
      region: { regionId: "world", name: "Mundial" },
      ...release,
    },
    scores: { community: { status: "available", mean: 8.2, count: 3 } },
    ...extra,
  };
}

const items = [
  game("g-soon", "Juego próximo", { releaseDate: { precision: "month", value: "2026-12" }, status: "scheduled" }),
  game("g-out", "Juego lanzado", { releaseDate: { precision: "day", value: "2026-06-05" }, status: "released" }),
  game("g-tba", "Juego sin fecha", { releaseDate: { precision: "unknown", value: null }, status: "announced" }),
];

function pageOf(list: unknown[], total = list.length) {
  return {
    platforms: [{ platformId: SWITCH_2, name: "Nintendo Switch 2" }],
    availableFilters: {
      genres: [{ genreId: "adventure", name: "Aventura" }, { genreId: "puzzle", name: "Puzles" }],
      earliestYear: 2025,
      latestYear: 2027,
    },
    items: list,
    page: { number: 1, size: 24, totalItems: total, totalPages: Math.ceil(total / 24) },
  };
}

function problem(status: number, code: string) {
  return Response.json(
    { type: "about:blank", title: "Fallo", status, code, correlationId: "corr-389" },
    { status, headers: { "Content-Type": "application/problem+json" } },
  );
}

function stubApi(games: (url: URL) => Response) {
  const requests: URL[] = [];
  vi.stubGlobal("fetch", vi.fn<typeof fetch>().mockImplementation(async (input) => {
    const url = new URL(input instanceof Request ? input.url : String(input), "http://localhost");
    if (url.pathname.endsWith("/session")) return Response.json({ authenticated: false });
    if (url.pathname.endsWith("/platforms")) return Response.json({ items: directory });
    if (url.pathname.endsWith("/platform-games")) {
      requests.push(url);
      return games(url);
    }
    return Response.json({}, { status: 404 });
  }));
  return requests;
}

const results = () => screen.findByRole("list", { name: "Juegos de Nintendo Switch 2" });

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("Explorar catálogo", () => {
  it("opens a featured platform's games without typing, latest first, each with its state", async () => {
    const requests = stubApi(() => Response.json(pageOf(items, 49)));
    renderApp("/plataformas/nintendo-switch-2/catalogo");

    expect(await screen.findByRole("heading", { level: 1, name: "Explorar catálogo de Nintendo Switch 2" }))
      .toBeInTheDocument();
    const cards = within(await results()).getAllByRole("article");
    expect(cards.map((card) => within(card).getByRole("heading", { level: 3 }).textContent)).toEqual([
      "Juego próximo", "Juego lanzado", "Juego sin fecha",
    ]);
    expect(cards[0]).toHaveTextContent("Próximo lanzamiento: diciembre de 2026, Mundial");
    expect(cards[1]).toHaveTextContent("Lanzado: 5 de junio de 2026, Mundial");
    expect(cards[2]).toHaveTextContent("Próximo lanzamiento, fecha por confirmar");
    expect(within(cards[1] as HTMLElement).getByRole("link", { name: "Juego lanzado" }))
      .toHaveAttribute("href", "/games/g-out/g-out");
    expect(screen.getByRole("status", { name: "" })).toHaveTextContent("49 juegos en Gameómetro · Página 1 de 3");
    expect(document.title).toBe("Nintendo Switch 2 · Explorar catálogo · Gameómetro");

    const sent = requests.at(-1) as URL;
    expect(sent.searchParams.getAll("platformIds")).toEqual([SWITCH_2]);
    expect(sent.searchParams.get("sort")).toBe("release_date_desc");
    expect(sent.searchParams.get("pageSize")).toBe("24");
    expect(sent.searchParams.has("q")).toBe(false);

    const trail = screen.getByRole("navigation", { name: "Ruta de navegación" });
    expect(within(trail).getByRole("link", { name: "Nintendo Switch 2" }))
      .toHaveAttribute("href", "/plataformas?plataforma=nintendo-switch-2");
    // Plataformas stays the current section on a platform's destination.
    expect(within(screen.getByRole("navigation", { name: "Principal" })).getByRole("link", { name: "Plataformas" }))
      .toHaveAttribute("aria-current", "true");
  });

  it("switches the release state in the URL and orders the games still to come soonest first", async () => {
    const user = userEvent.setup();
    const requests = stubApi(() => Response.json(pageOf(items)));
    const { router } = renderApp("/plataformas/nintendo-switch-2/catalogo?page=2");
    await results();

    const states = screen.getByRole("navigation", { name: "Estado de lanzamiento" });
    expect(within(states).getByRole("link", { name: "Todos" })).toHaveAttribute("aria-current", "true");
    await user.click(within(states).getByRole("link", { name: "Próximos" }));

    expect(router.state.location.search).toBe("?releaseState=upcoming");
    await waitFor(() => expect(requests.at(-1)?.searchParams.get("releaseState")).toBe("upcoming"));
    expect(requests.at(-1)?.searchParams.get("sort")).toBe("release_date_asc");
    expect(within(states).getByRole("link", { name: "Próximos" })).toHaveAttribute("aria-current", "true");
  });

  it("searches the platform's titles on Enter and explains a query it cannot send", async () => {
    const user = userEvent.setup();
    const requests = stubApi(() => Response.json(pageOf(items.slice(1))));
    const { router } = renderApp("/plataformas/nintendo-switch-2/catalogo");
    await results();

    const field = screen.getByRole("searchbox", { name: "Buscar por título en Nintendo Switch 2" });
    await user.type(field, "¿?{Enter}");
    expect(screen.getByRole("alert")).toHaveTextContent("Escribe alguna letra o número del título");
    expect(field).toHaveAttribute("aria-invalid", "true");
    expect(router.state.location.search).toBe("");

    await user.clear(field);
    await user.type(field, "  lanzado {Enter}");
    expect(router.state.location.search).toBe("?q=lanzado");
    await waitFor(() => expect(requests.at(-1)?.searchParams.get("q")).toBe("lanzado"));
    const chips = await screen.findByRole("list", { name: "Filtros activos" });
    await user.click(within(chips).getByRole("link", { name: "Quitar la búsqueda «lanzado»" }));
    expect(router.state.location.search).toBe("");
  });

  it("folds the year and genre filters behind Filtros and keeps them as removable chips", async () => {
    const user = userEvent.setup();
    const requests = stubApi(() => Response.json(pageOf(items)));
    const { router } = renderApp("/plataformas/nintendo-switch-2/catalogo?fromYear=2026");
    await results();

    const toggle = screen.getByRole("button", { name: /^Filtros/ });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(toggle).toHaveAccessibleName("Filtros, 1 activo");
    const controlled = document.getElementById(toggle.getAttribute("aria-controls") ?? "");
    expect(controlled).not.toBeVisible();

    await user.click(toggle);
    const panel = screen.getByRole("region", { name: "Filtros avanzados" });
    expect(panel).toBe(controlled);
    await user.click(within(panel).getByRole("button", { name: "Puzles" }));
    expect(router.state.location.search).toBe("?fromYear=2026&genreIds=puzzle");
    await waitFor(() => expect(requests.at(-1)?.searchParams.getAll("genreIds")).toEqual(["puzzle"]));
    expect(within(panel).getByRole("button", { name: "Puzles" })).toHaveAttribute("aria-pressed", "true");

    const chips = screen.getByRole("list", { name: "Filtros activos" });
    expect(within(chips).getAllByRole("link").map((chip) => chip.textContent)).toEqual(["Desde 2026", "Puzles"]);
    await user.click(screen.getByRole("link", { name: "Quitar todo" }));
    expect(router.state.location.search).toBe("");
  });

  it("resolves another platform by its own identifier and refuses one not on offer", async () => {
    const requests = stubApi(() => Response.json({ ...pageOf([]), platforms: [{ platformId: "ps4-id", name: "PlayStation 4" }] }));
    renderApp("/plataformas/ps4-id/catalogo");

    expect(await screen.findByRole("heading", { level: 1, name: "Explorar catálogo de PlayStation 4" })).toBeInTheDocument();
    expect(await screen.findByText("Aún sin juegos de PlayStation 4 en Gameómetro")).toBeVisible();
    expect(requests.at(-1)?.searchParams.getAll("platformIds")).toEqual(["ps4-id"]);
  });

  it.each(["steamvr-id", "dreamcast"])("never opens %s, which is not on offer", async (key) => {
    const requests = stubApi(() => Response.json(pageOf(items)));
    renderApp(`/plataformas/${key}/catalogo`);

    expect(await screen.findByText("Esa plataforma no tiene juegos en Gameómetro ahora mismo")).toBeVisible();
    expect(screen.getByRole("link", { name: "Ver todas las plataformas" })).toHaveAttribute("href", "/plataformas");
    expect(requests).toHaveLength(0);
  });

  it("states a catalogue not ready, an unsupported filter and a technical failure apart", async () => {
    const user = userEvent.setup();
    let next = () => problem(503, "CATALOGUE_NOT_READY");
    stubApi(() => next());
    renderApp("/plataformas/nintendo-switch-2/catalogo?genreIds=invented");

    expect(await screen.findByText("Los juegos todavía no están disponibles")).toBeVisible();
    next = () => problem(422, "FILTER_INVALID");
    await user.click(screen.getByRole("button", { name: "Reintentar" }));
    expect(await screen.findByText("Filtro no admitido")).toBeVisible();
    expect(screen.getByRole("link", { name: "Quitar filtros" })).toHaveAttribute("href", "/plataformas/nintendo-switch-2/catalogo");
  });

  it("offers a way out of an empty selection", async () => {
    stubApi(() => Response.json(pageOf([])));
    renderApp("/plataformas/nintendo-switch-2/catalogo?releaseState=upcoming&genreIds=adventure");

    expect(await screen.findByText("Ningún juego coincide con estos filtros")).toBeVisible();
    expect(screen.getByRole("link", { name: "Quitar filtros" }))
      .toHaveAttribute("href", "/plataformas/nintendo-switch-2/catalogo?releaseState=upcoming");
  });
});
