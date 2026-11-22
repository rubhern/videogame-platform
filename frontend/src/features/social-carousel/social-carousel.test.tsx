import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { renderApp } from "../../test/render-app";

const attribution = { label: "IGDB", sourceUrl: "https://www.igdb.com/games/example" };

function featuredItem(gameId: string, title: string, scores?: object) {
  return {
    gameId, slug: gameId, canonicalTitle: title,
    primaryCover: { kind: "provider", url: "https://images.igdb.com/cover.jpg", alternativeText: "Portada", attribution },
    featuredImage: { kind: "fallback", presentation: "fill", url: "/assets/featured/fallback.svg",
      compactUrl: "/assets/featured/fallback.svg", alternativeText: "Sin imagen", attribution: null },
    ...(scores ? { scores } : {}),
    releases: [{
      releaseId: `r-${gameId}`, gameId,
      platform: { platformId: "10000000-0000-4000-8000-000000000001", name: "PlayStation 5" },
      region: { regionId: "20000000-0000-4000-8000-000000000001", name: "Mundial" },
      releaseDate: { precision: "day", value: "2026-11-20" }, status: "scheduled", stage: "full_release",
      provenance: { sourceKind: "external_provider", sourceName: "IGDB", sourceEntityType: "release_date" },
      verificationLevel: "provider_only", reviewStatus: "not_required",
    }],
  };
}

function featuredWeek(week: string, from: string, to: string, items: object[]) {
  return {
    granularity: "week", week, evaluatedOn: "2026-11-18", window: { from, to },
    navigation: { previous: "2026-W46", next: "2026-W48" },
    selection: items.length > 0
      ? { status: "ranked", popularityFreshness: "fresh", popularityObservedAt: "2026-11-18T05:00:00Z" }
      : { status: "no_qualifying_releases" },
    items,
  };
}

const current = featuredWeek("2026-W47", "2026-11-16", "2026-11-22", [
  featuredItem("a", "Juego Alfa", { community: { status: "available", mean: 8.4, count: 120 } }),
  featuredItem("b", "Juego Beta"),
]);
const empty = featuredWeek("2026-W46", "2026-11-09", "2026-11-15", []);

function stub() {
  const fetchMock = vi.fn<typeof fetch>().mockImplementation(async (input) => {
    const url = new URL((input instanceof Request ? input : new Request(input)).url);
    if (url.pathname === "/api/v1/web-analytics") return Response.json({});
    if (url.pathname === "/api/v1/session") {
      return Response.json({ authenticated: true, csrfToken: "csrf", capabilities: ["catalogue_administration"] });
    }
    if (url.pathname === "/api/v1/admin/catalogue/synchronization") return Response.json({ recentRuns: [] });
    if (url.pathname === "/api/v1/featured-releases") {
      return Response.json(url.searchParams.get("week") === "2026-W46" ? empty : current);
    }
    return Response.json({ code: "NOT_FOUND" }, { status: 404, headers: { "Content-Type": "application/problem+json" } });
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

beforeEach(() => {
  // JSDOM cannot paint a canvas; a real browser draws and exports the slides.
  vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue(null);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("Weekly social carousel", () => {
  it("shows the current week's slides, described in words, and its ready-to-paste caption", async () => {
    const fetchMock = stub();
    renderApp("/administracion/catalogo?tab=redes");

    const panel = await screen.findByRole("tabpanel", { name: "Redes sociales" });
    const slides = await within(panel).findByRole("list", { name: "Diapositivas del carrusel (4)" });
    const images = within(slides).getAllByRole("img");
    expect(images.map((image) => image.getAttribute("aria-label"))).toEqual([
      "Portada: Lanzamientos de la semana, 16–22 nov 2026. 2 portadas de juegos.",
      "Lanzamiento de la semana: Juego Alfa. 20 de noviembre de 2026. PlayStation 5.",
      "Juego Beta. 20 de noviembre de 2026. PlayStation 5.",
      "Cierre: ¿Cuál vas a jugar? Todos los lanzamientos de la semana, filtrados por tus plataformas. gameometro.com. Enlace en el perfil. Datos e imágenes: IGDB.",
    ]);
    expect((within(panel).getByRole("textbox", { name: "Texto de la publicación" }) as HTMLTextAreaElement).value)
      .toContain("1. Juego Alfa: 20 de noviembre de 2026 · PlayStation 5");
    // The current week is the API's own: no week is sent.
    const featuredReads = fetchMock.mock.calls.map(([request]) => new URL((request as Request).url))
      .filter((url) => url.pathname === "/api/v1/featured-releases");
    expect(featuredReads[0]?.searchParams.get("granularity")).toBe("week");
    expect(featuredReads[0]?.searchParams.has("week")).toBe(false);
    expect(within(panel).getByRole("button", { name: "Semana actual" })).toBeDisabled();
  });

  it("produces a clear empty result instead of empty slides for a week without featured games", async () => {
    stub();
    const { router } = renderApp("/administracion/catalogo?tab=redes");

    await userEvent.click(await screen.findByRole("button", { name: "Semana anterior" }));

    await waitFor(() => expect(router.state.location.search).toBe("?tab=redes&week=2026-W46"));
    expect(await screen.findByRole("heading", { name: "No hay lanzamientos destacados esta semana" })).toBeVisible();
    expect(screen.queryByRole("list", { name: /Diapositivas/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: "Texto de la publicación" })).not.toBeInTheDocument();
  });

  it("copies the caption and reports the result", async () => {
    stub();
    const writeText = vi.fn<(text: string) => Promise<void>>().mockResolvedValue(undefined);
    renderApp("/administracion/catalogo?tab=redes");
    const user = userEvent.setup();
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });

    await user.click(await screen.findByRole("button", { name: "Copiar texto" }));

    expect(writeText).toHaveBeenCalledWith(expect.stringContaining("#gameometro"));
  });
});
