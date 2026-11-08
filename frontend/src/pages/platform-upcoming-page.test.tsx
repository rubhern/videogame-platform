import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { renderApp } from "../test/render-app";

const PS5 = "10000000-0000-4000-8000-000000000001";

const directory = [
  { platformId: PS5, name: "PlayStation 5" },
  { platformId: "ps4-id", name: "PlayStation 4" },
];

function highlight(position: number, title: string, releaseDate: Record<string, unknown>) {
  return {
    position,
    gameId: `g${position}`,
    slug: `game-${position}`,
    canonicalTitle: title,
    primaryCover: { kind: "fallback", url: "/assets/covers/fallback.svg", alternativeText: "Portada oficial no disponible", attribution: null },
    featuredImage: {
      kind: "fallback",
      presentation: "fill",
      url: "/assets/featured/fallback.svg",
      compactUrl: "/assets/featured/fallback.svg",
      alternativeText: `Imagen destacada no disponible de ${title}`,
      attribution: null,
    },
    genres: [{ genreId: "adventure", name: "Aventura" }],
    platformRelease: {
      platform: { platformId: PS5, name: "PlayStation 5" },
      region: { regionId: "world", name: "Mundial" },
      releaseDate,
      status: "scheduled",
    },
  };
}

function horizon(items: unknown[], status = "ranked", platform = { platformId: PS5, name: "PlayStation 5" }) {
  return {
    platforms: [platform],
    evaluatedOn: "2026-10-27",
    window: { from: "2026-10-27", to: "2027-04-26" },
    selection: { status },
    items,
  };
}

const ranked = horizon([
  highlight(1, "El más esperado de todos", { precision: "day", value: "2027-02-12" }),
  highlight(2, "Una adaptación", { precision: "quarter", value: "2027-Q1" }),
  highlight(3, "Un estreno de mes", { precision: "month", value: "2026-11" }),
]);

function problem(status: number, code: string) {
  return Response.json(
    { type: "about:blank", title: "Fallo", status, code, correlationId: "corr-388" },
    { status, headers: { "Content-Type": "application/problem+json" } },
  );
}

function stubApi(read: (url: URL) => Response) {
  const requests: URL[] = [];
  vi.stubGlobal("fetch", vi.fn<typeof fetch>().mockImplementation(async (input) => {
    const url = new URL(input instanceof Request ? input.url : String(input), "http://localhost");
    if (url.pathname.endsWith("/session")) return Response.json({ authenticated: false });
    if (url.pathname.endsWith("/platforms")) return Response.json({ items: directory });
    if (url.pathname.endsWith("/platform-upcoming-highlights")) {
      requests.push(url);
      return read(url);
    }
    if (url.pathname.includes("/games/")) return problem(404, "GAME_NOT_FOUND");
    return Response.json({}, { status: 404 });
  }));
  return requests;
}

const ranking = () => screen.findByRole("list", { name: "Los más esperados en PlayStation 5" });

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("Próximos destacados", () => {
  it("ranks a featured platform's most anticipated games over the API's horizon", async () => {
    const requests = stubApi(() => Response.json(ranked));
    renderApp("/plataformas/playstation-5/proximos");

    expect(await screen.findByRole("heading", { level: 1, name: "Próximos destacados de PlayStation 5" }))
      .toBeInTheDocument();
    const entries = within(await ranking()).getAllByRole("listitem");
    expect(entries.map((entry) => within(entry).getByRole("heading", { level: 3 }).textContent)).toEqual([
      "El más esperado de todos", "Una adaptación", "Un estreno de mes",
    ]);
    // Each arrival reads in full at its own precision; no coarser date becomes a day.
    expect(entries[0]).toHaveTextContent("Llega a PlayStation 5 el 12 de febrero de 2027, Mundial.");
    expect(entries[0]).toHaveTextContent("Faltan 108 días");
    expect(entries[1]).toHaveTextContent("Llega a PlayStation 5 en 1.er trimestre de 2027, Mundial.");
    expect(entries[2]).toHaveTextContent("Llega a PlayStation 5 en noviembre de 2026, Mundial.");
    expect(within(entries[0] as HTMLElement).getByRole("link", { name: "El más esperado de todos" }))
      .toHaveAttribute("href", "/games/g1/game-1");
    expect(screen.getByText("Del 27 de octubre de 2026 al 26 de abril de 2027")).toBeInTheDocument();
    // Hype is attention, never a score, and coverage is never claimed complete.
    expect(screen.queryByText(/es expectación, no una valoración/)).not.toBeInTheDocument();
    expect(document.body).not.toHaveTextContent(/mejor valorad/i);
    expect(screen.getByRole("link", { name: "Ver todos los próximos juegos" }))
      .toHaveAttribute("href", "/plataformas/playstation-5/catalogo?releaseState=upcoming");
    expect(document.title).toBe("Próximos destacados de PlayStation 5 · Gameómetro");
    expect(requests.at(-1)?.searchParams.getAll("platformIds")).toEqual([PS5]);
    expect([...(requests.at(-1)?.searchParams.keys() ?? [])]).toEqual(["platformIds"]);

    expect(screen.queryByRole("navigation", { name: "Ruta de navegación" })).not.toBeInTheDocument();
    const trail = screen.getByRole("navigation", { name: "Navegación de PlayStation 5" });
    expect(within(trail).getByRole("link", { name: "Catálogo" }))
      .toHaveAttribute("href", "/plataformas/playstation-5/catalogo");
    expect(within(screen.getByRole("navigation", { name: "Principal" })).getByRole("button", { name: "Plataformas" }))
      .toHaveAttribute("aria-current", "true");
  });

  it("resolves another platform through the directory and switches platform in place", async () => {
    const user = userEvent.setup();
    const requests = stubApi((url) =>
      url.searchParams.get("platformIds") === "ps4-id"
        ? Response.json(horizon([], "no_qualifying_releases", { platformId: "ps4-id", name: "PlayStation 4" }))
        : Response.json(ranked),
    );
    const { router } = renderApp("/plataformas/playstation-5/proximos");
    await ranking();

    await user.click(screen.getByRole("button", { name: "Cambiar plataforma" }));
    await user.click(screen.getByRole("link", { name: "PlayStation 4" }));

    expect(router.state.location.pathname).toBe("/plataformas/ps4-id/proximos");
    expect(await screen.findByRole("heading", { level: 1, name: "Próximos destacados de PlayStation 4" }))
      .toBeInTheDocument();
    expect(await screen.findByRole("heading", {
      level: 2,
      name: "No conocemos próximos lanzamientos de PlayStation 4 con fecha en los próximos seis meses",
    })).toBeInTheDocument();
    expect(requests.at(-1)?.searchParams.getAll("platformIds")).toEqual(["ps4-id"]);
    expect(screen.getByRole("link", { name: "Ver todos los próximos juegos" }))
      .toHaveAttribute("href", "/plataformas/ps4-id/catalogo?releaseState=upcoming");
  });

  it("names a horizon without Hype apart from an empty one and never fills it", async () => {
    stubApi(() => Response.json(horizon([], "popularity_unavailable")));
    renderApp("/plataformas/playstation-5/proximos");

    const notice = await screen.findByRole("heading", {
      level: 2,
      name: "Aún no hay Hype para los próximos juegos de PlayStation 5",
    });
    expect(notice.closest(".notice")).toHaveTextContent("Sin datos de Hype");
    expect(screen.queryByRole("list", { name: /Los más esperados/ })).not.toBeInTheDocument();
  });

  it("separates a catalogue still in preparation from a technical failure, both with a retry", async () => {
    const user = userEvent.setup();
    let failure = problem(503, "CATALOGUE_NOT_READY");
    stubApi(() => failure);
    renderApp("/plataformas/playstation-5/proximos");

    expect(await screen.findByRole("heading", { name: "Gameómetro aún está preparando sus juegos" }))
      .toBeInTheDocument();
    failure = problem(500, "INTERNAL_ERROR");
    await user.click(screen.getByRole("button", { name: "Reintentar" }));
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("No se pudieron leer los próximos destacados");
    expect(alert).toHaveTextContent("corr-388");

    failure = Response.json(ranked);
    await user.click(within(alert).getByRole("button", { name: "Reintentar" }));
    expect(await ranking()).toBeInTheDocument();
  });

  it("never invents a platform from a stale key", async () => {
    stubApi(() => Response.json(ranked));
    renderApp("/plataformas/consola-inventada/proximos");

    expect(await screen.findByRole("heading", { name: "Esa plataforma no tiene juegos en Gameómetro ahora mismo" }))
      .toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Ver todas las plataformas" })).toHaveAttribute("href", "/plataformas");
  });

  it("returns from a game page to the same platform's highlights", async () => {
    const user = userEvent.setup();
    stubApi(() => Response.json(ranked));
    const { router } = renderApp("/plataformas/playstation-5/proximos");

    await user.click(within(await ranking()).getByRole("link", { name: "Una adaptación" }));
    await waitFor(() => expect(router.state.location.pathname).toBe("/games/g2/game-2"));
    const back = await screen.findByRole("link", { name: /Volver a Próximos destacados/ });
    expect(back).toHaveAttribute("href", "/plataformas/playstation-5/proximos");
  });
});
