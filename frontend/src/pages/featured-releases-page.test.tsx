import { fireEvent, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { components } from "../shared/api/generated/schema";
import { renderApp } from "../test/render-app";

type FeaturedReleases = components["schemas"]["FeaturedReleases"];
type FeaturedReleaseItem = components["schemas"]["FeaturedReleaseItem"];
type Problem = components["schemas"]["Problem"];

const igdb = { label: "IGDB", sourceUrl: "https://www.igdb.com/games/juego-destacado" };

function screenshot(title: string): FeaturedReleaseItem["featuredImage"] {
  return {
    kind: "screenshot",
    presentation: "fill",
    url: "https://images.igdb.com/igdb/image/upload/t_1080p/scexample.webp",
    compactUrl: "https://images.igdb.com/igdb/image/upload/t_720p/scexample.webp",
    alternativeText: `Captura de ${title}`,
    attribution: igdb,
  };
}

function logo(title: string): NonNullable<FeaturedReleaseItem["logo"]> {
  return {
    url: "https://images.igdb.com/igdb/image/upload/t_logo_med_2x/loexample.png",
    alternativeText: title,
    attribution: igdb,
  };
}

function item(
  gameId: string,
  title: string,
  day: string,
  media: Pick<FeaturedReleaseItem, "featuredImage" | "logo"> = {
    featuredImage: {
      kind: "fallback",
      presentation: "fill",
      url: "/assets/featured/fallback.svg",
      compactUrl: "/assets/featured/fallback.svg",
      alternativeText: `Imagen destacada no disponible de ${title}`,
      attribution: null,
    },
  },
): FeaturedReleaseItem {
  return {
    gameId,
    slug: title.toLowerCase().replaceAll(" ", "-"),
    canonicalTitle: title,
    primaryCover: {
      kind: "fallback",
      url: "/assets/covers/fallback.svg",
      alternativeText: `Portada no disponible de ${title}`,
      attribution: null,
    },
    ...media,
    releases: [
      {
        releaseId: `release-${gameId}`,
        gameId,
        platform: { platformId: "10000000-0000-4000-8000-000000000001", name: "PlayStation 5" },
        region: { regionId: "20000000-0000-4000-8000-000000000001", name: "Mundial" },
        releaseDate: { precision: "day", value: day },
        status: "scheduled",
        stage: "unknown",
        provenance: {
          sourceKind: "external_provider",
          sourceName: "IGDB",
          sourceEntityType: "release_date",
        },
        lastSyncedAt: "2026-10-02T05:00:00Z",
        verificationLevel: "provider_only",
        reviewStatus: "not_required",
        freshnessStatus: "fresh",
      },
    ],
  };
}

function featured(overrides: Partial<FeaturedReleases> = {}): FeaturedReleases {
  return {
    month: "2026-10",
    evaluatedOn: "2026-10-03",
    window: { from: "2026-10-01", to: "2026-10-31" },
    selection: {
      status: "ranked",
      popularityFreshness: "fresh",
      popularityObservedAt: "2026-10-02T05:00:00Z",
    },
    items: [
      item("game-lead", "Juego destacado", "2026-10-15"),
      item("game-two", "Segundo juego", "2026-10-22"),
      item("game-three", "Tercer juego", "2026-10-03"),
    ],
    ...overrides,
  };
}

function problem(status: number, code: Problem["code"]): Response {
  return Response.json(
    {
      type: `urn:videogame-platform:problem:${code.toLowerCase()}`,
      title: "Problem",
      status,
      detail: "Problem detail",
      instance: "urn:videogame-platform:problem-instance:correlation-featured",
      code,
      category: "technical",
      correlationId: "correlation-featured",
    } satisfies Problem,
    { status, headers: { "Content-Type": "application/problem+json" } },
  );
}

function urlOf(input: Parameters<typeof fetch>[0]): URL {
  return new URL(input instanceof Request ? input.url : String(input), "http://localhost");
}

/** Answers the featured releases in order; the shell's own session read stays anonymous. */
function stubFeatured(...responses: Array<FeaturedReleases | Response>) {
  const queue = [...responses];
  const featuredCalls: URL[] = [];
  const fetchMock = vi.fn<typeof fetch>().mockImplementation(async (input) => {
    const url = urlOf(input);
    if (!url.pathname.endsWith("/featured-releases")) {
      return Response.json({ authenticated: false }, { status: 200 });
    }
    featuredCalls.push(url);
    const next = queue.shift() ?? responses[responses.length - 1];
    if (next === undefined) {
      throw new Error("No featured response stubbed");
    }
    return next instanceof Response ? next.clone() : Response.json(next, { status: 200 });
  });
  vi.stubGlobal("fetch", fetchMock);
  return featuredCalls;
}

function requestedUrl(featuredCalls: URL[], call = 0): URL {
  const url = featuredCalls[call];
  if (url === undefined) {
    throw new Error(`No featured request number ${call}`);
  }
  return url;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("featured releases page", () => {
  it("presents the current month's featured release first and up to five more", async () => {
    const fetchMock = stubFeatured(featured());
    renderApp("/");

    const hero = await screen.findByRole("article", { name: "Juego destacado" });
    expect(requestedUrl(fetchMock).pathname).toBe("/api/v1/featured-releases");
    expect(requestedUrl(fetchMock).searchParams.has("month")).toBe(false);
    expect(screen.getByRole("heading", { level: 1, name: "Lanzamientos del mes" })).toBeVisible();
    expect(within(hero).getByText("Lanzamiento del mes")).toBeVisible();
    expect(within(hero).getByText("15 de octubre de 2026")).toBeInTheDocument();
    expect(within(hero).getByRole("list", { name: "Plataformas" })).toHaveTextContent(
      "PlayStation 5",
    );
    expect(within(hero).getByRole("link", { name: "Juego destacado" })).toHaveAttribute(
      "href",
      "/games/game-lead/juego-destacado",
    );
    // The entire hero is one native link; release-list navigation stays outside it.
    expect(within(hero).queryByRole("link", { name: /Explorar lanzamientos/ })).toBeNull();

    const others = screen.getByRole("region", { name: "Otros lanzamientos destacados" });
    expect(
      within(others)
        .getAllByRole("heading", { level: 3 })
        .map((heading) => heading.textContent),
    ).toEqual(["Segundo juego", "Tercer juego"]);
    expect(within(others).getByRole("link", { name: "Ver todos los lanzamientos" })).toHaveAttribute(
      "href",
      "/?view=recent&weeks=1",
    );
    expect(screen.getByRole("status")).toHaveTextContent("Lanzamientos destacados de octubre de 2026");
    expect(screen.getByText("Selección automática según atención actual")).toBeVisible();
    // Attention ranks the selection; nothing claims quality or an award.
    expect(document.body).not.toHaveTextContent(/mejor|juego del mes|visitas/i);
  });

  it("shows only discovery metadata, with one whole-hero link and no nested controls", async () => {
    const summary = {
      kind: "sourced" as const, text: "Explora un mundo lleno de historias. ".repeat(30), language: "es",
      provenance: { sourceKind: "external_provider" as const, sourceName: "IGDB", sourceEntityType: "game" },
      translation: { kind: "machine_translation" as const, sourceText: "Explore a world of stories.", sourceLanguage: "en", current: false },
    };
    const lead = item("game-lead", "Juego destacado", "2026-10-15");
    const other = item("game-two", "Segundo juego", "2026-10-22");
    const genres = [{ genreId: "adventure", name: "Aventura" }, { genreId: "rpg", name: "Rol (RPG)" }];
    // Extra detail-only content must never leak onto this discovery surface.
    const extra = { developers: [{ name: "Studio secret" }], publishers: [{ name: "Publisher secret" }], gameModes: [{ name: "Multijugador" }] };
    stubFeatured(featured({ items: [
      { ...lead, ...extra, genres, summary, releases: [
        { ...lead.releases[0] as FeaturedReleaseItem["releases"][number], status: "released", stage: "full_release" },
        { ...lead.releases[0] as FeaturedReleaseItem["releases"][number], releaseId: "another", releaseDate: { precision: "day", value: "2026-10-20" } },
      ] },
      { ...other, ...extra, genres, summary: { kind: "editorial", text: "Other summary secret", language: "es" } },
    ] }));
    renderApp("/");

    const hero = await screen.findByRole("article", { name: "Juego destacado" });
    const link = within(hero).getByRole("link", { name: "Juego destacado" });
    expect(link).toHaveAttribute("href", "/games/game-lead/juego-destacado");
    expect(within(link).getByRole("heading", { name: "Juego destacado" })).toBeVisible();
    expect(within(link).getByRole("list", { name: "Géneros" })).toHaveTextContent("AventuraRol (RPG)");
    const text = within(link).getByText(summary.text.trim());
    expect(text.textContent).toBe(summary.text);
    expect(text).toHaveAttribute("lang", "es");
    expect(within(link).queryByText(/Traducción|Fuente del original|IGDB/)).not.toBeInTheDocument();
    expect(link.querySelector("a, button, input, select, summary, [tabindex]")).toBeNull();
    const overflow = within(hero).getByRole("button", { name: /lanzamientos? más/ });
    expect(link.contains(overflow)).toBe(false);
    expect(within(hero).queryByText("Publicado")).toBeNull();
    expect(within(hero).queryByText("Lanzamiento completo")).toBeNull();
    expect(within(hero).queryByText("Ver ficha")).toBeNull();
    const card = screen.getByRole("region", { name: "Otros lanzamientos destacados" });
    expect(within(card).getByRole("list", { name: "Géneros" })).toHaveTextContent("AventuraRol (RPG)");
    expect(within(card).getByText("22 de octubre de 2026: PlayStation 5.")).toBeInTheDocument();
    expect(within(card).getByText("Mundial")).toBeVisible();
    expect(document.body).not.toHaveTextContent(/Studio secret|Publisher secret|Multijugador|Other summary secret/);
  });

  it("omits missing enrichment and navigates to details through the hero with Enter", async () => {
    stubFeatured(featured());
    const user = userEvent.setup();
    const { router } = renderApp("/");
    const hero = await screen.findByRole("article", { name: "Juego destacado" });
    expect(within(hero).queryByRole("list", { name: "Géneros" })).toBeNull();
    expect(hero.querySelector(".featured-hero-summary")).toBeNull();
    const link = within(hero).getByRole("link", { name: "Juego destacado" });
    link.focus();
    expect(link).toHaveFocus();
    await user.keyboard("{Enter}");
    expect(router.state.location.pathname).toBe("/games/game-lead/juego-destacado");
  });

  it("always renders the canonical hero title and retains the secondary screenshot logo", async () => {
    stubFeatured(
      featured({
        items: [
          item("game-lead", "Juego destacado", "2026-10-15", {
            featuredImage: screenshot("Juego destacado"),
            logo: logo("Juego destacado"),
          }),
          item("game-two", "Segundo juego", "2026-10-22", {
            featuredImage: screenshot("Segundo juego"),
            logo: logo("Segundo juego"),
          }),
          item("game-three", "Tercer juego", "2026-10-03"),
        ],
      }),
    );
    renderApp("/");

    const lead = await screen.findByRole("article", { name: "Juego destacado" });
    const heading = within(lead).getByRole("heading", { level: 2, name: "Juego destacado" });
    expect(heading).toHaveTextContent("Juego destacado");
    expect(within(heading).queryByRole("img")).toBeNull();
    expect(within(lead).getByRole("img", { name: "Captura de Juego destacado" })).toHaveAttribute(
      "src",
      "https://images.igdb.com/igdb/image/upload/t_1080p/scexample.webp",
    );
    const cards = within(
      screen.getByRole("region", { name: "Otros lanzamientos destacados" }),
    ).getAllByRole("article");
    // A screenshot carries no title, so its card sets the logo over it, hidden from the
    // accessibility tree because the title link below names the game.
    expect(cards[0]?.querySelector(".featured-card-logo")).not.toBeNull();
    expect(cards[1]?.querySelector(".featured-card-logo")).toBeNull();
    expect(within(cards[0] as HTMLElement).getByRole("img", { name: "Captura de Segundo juego" })).toHaveAttribute(
      "src",
      "https://images.igdb.com/igdb/image/upload/t_720p/scexample.webp",
    );
  });

  it("sets a subtitle as the second tier of the wordmark while reading the canonical title", async () => {
    stubFeatured(
      featured({
        items: [
          item("game-lead", "Onimusha: Way of the Sword", "2026-10-15", {
            featuredImage: screenshot("Onimusha: Way of the Sword"),
          }),
        ],
      }),
    );
    renderApp("/");

    const lead = await screen.findByRole("article", { name: "Onimusha: Way of the Sword" });
    const heading = within(lead).getByRole("heading", {
      level: 2,
      name: "Onimusha: Way of the Sword",
    });
    expect(heading).toHaveTextContent("Onimusha: Way of the Sword");
    expect(heading.querySelector(".featured-wordmark-main")).toHaveTextContent("Onimusha:");
    expect(heading.querySelector(".featured-wordmark-sub")).toHaveTextContent("Way of the Sword");
  });

  it("keeps its product-owned title when provider art cannot load", async () => {
    stubFeatured(
      featured({
        items: [
          item("game-lead", "Juego destacado", "2026-10-15", {
            featuredImage: screenshot("Juego destacado"),
            logo: logo("Juego destacado"),
          }),
        ],
      }),
    );
    renderApp("/");

    const lead = await screen.findByRole("article", { name: "Juego destacado" });
    const heading = within(lead).getByRole("heading", { level: 2 });
    fireEvent.error(within(lead).getByRole("img", { name: "Captura de Juego destacado" }));

    expect(within(heading).queryByRole("img")).toBeNull();
    expect(heading).toHaveTextContent("Juego destacado");
  });

  it("omits the other featured releases when only one game is ranked", async () => {
    stubFeatured(featured({ items: [item("game-lead", "Juego destacado", "2026-10-15")] }));
    renderApp("/");

    await screen.findByRole("article", { name: "Juego destacado" });
    expect(screen.queryByRole("region", { name: "Otros lanzamientos destacados" })).toBeNull();
  });

  it("steps through months in the URL and returns to the landing route for the current one", async () => {
    const user = userEvent.setup();
    const fetchMock = stubFeatured(
      featured({ month: "2026-09", window: { from: "2026-09-01", to: "2026-09-30" } }),
      featured(),
    );
    const { router } = renderApp("/?month=2026-09");

    await screen.findByRole("article", { name: "Juego destacado" });
    expect(requestedUrl(fetchMock).searchParams.get("month")).toBe("2026-09");
    const months = screen.getByRole("navigation", { name: "Mes de la selección" });
    expect(within(months).getByText("Septiembre 2026")).toBeVisible();
    expect(within(months).getByRole("link", { name: "Mes anterior: agosto de 2026" })).toHaveAttribute(
      "href",
      "/?month=2026-08",
    );
    const next = within(months).getByRole("link", { name: "Mes siguiente: octubre de 2026" });
    expect(next).toHaveAttribute("href", "/");

    await user.click(next);

    expect(router.state.location.search).toBe("");
    expect(await within(months).findByText("Octubre 2026")).toBeVisible();
    expect(requestedUrl(fetchMock, 1).searchParams.has("month")).toBe(false);
  });

  it("returns to the landing route when the URL names an impossible month", async () => {
    const fetchMock = stubFeatured(featured());
    const { router } = renderApp("/?month=2026-13");

    await screen.findByRole("article", { name: "Juego destacado" });
    expect(requestedUrl(fetchMock).searchParams.has("month")).toBe(false);
    expect(router.state.location.search).toBe("");
  });

  it("offers only months of the current year: January has no previous, December no next", async () => {
    stubFeatured(
      featured({ month: "2026-01", window: { from: "2026-01-01", to: "2026-01-31" } }),
    );
    const { unmount } = renderApp("/?month=2026-01");
    let months = await screen.findByRole("navigation", { name: "Mes de la selección" });
    expect(within(months).getByText("Enero 2026")).toBeVisible();
    // A disabled step stays in place: named, announced as unavailable, without a destination
    // and outside the tab order.
    const previous = within(months).getByRole("link", { name: "Mes anterior" });
    expect(previous).toHaveAttribute("aria-disabled", "true");
    expect(previous).not.toHaveAttribute("href");
    expect(previous).not.toHaveAttribute("tabindex");
    expect(within(months).getByRole("link", { name: "Mes siguiente: febrero de 2026" })).toHaveAttribute(
      "href",
      "/?month=2026-02",
    );
    unmount();

    stubFeatured(
      featured({ month: "2026-12", window: { from: "2026-12-01", to: "2026-12-31" } }),
    );
    renderApp("/?month=2026-12");
    months = await screen.findByRole("navigation", { name: "Mes de la selección" });
    expect(within(months).getByText("Diciembre 2026")).toBeVisible();
    expect(within(months).getByRole("link", { name: "Mes siguiente" })).toHaveAttribute(
      "aria-disabled",
      "true",
    );
    expect(within(months).getByRole("link", { name: "Mes anterior: noviembre de 2026" })).toHaveAttribute(
      "href",
      "/?month=2026-11",
    );
  });

  it("returns to the current month when the API rejects a month outside the current year", async () => {
    const fetchMock = stubFeatured(problem(422, "FILTER_INVALID"), featured());
    const { router } = renderApp("/?month=2025-12");

    await screen.findByRole("article", { name: "Juego destacado" });
    expect(requestedUrl(fetchMock).searchParams.get("month")).toBe("2025-12");
    expect(requestedUrl(fetchMock, 1).searchParams.has("month")).toBe(false);
    expect(router.state.location.search).toBe("");
    // Another year's selection is never presented, nor a new error state.
    expect(screen.queryByText(/2025/)).toBeNull();
    expect(screen.queryByRole("alert")).toBeNull();
    expect(
      within(screen.getByRole("navigation", { name: "Mes de la selección" })).getByText(
        "Octubre 2026",
      ),
    ).toBeVisible();
  });

  it("keeps a stale ranking usable and says when its attention was observed", async () => {
    stubFeatured(
      featured({
        selection: {
          status: "ranked",
          popularityFreshness: "stale",
          popularityObservedAt: "2026-09-02T05:00:00Z",
        },
      }),
    );
    renderApp("/");

    await screen.findByRole("article", { name: "Juego destacado" });
    expect(
      screen.getByText("Selección automática según la atención registrada el 2 sep 2026"),
    ).toBeVisible();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("explains an unranked month and an empty month differently, with a way to the lists", async () => {
    stubFeatured(
      featured({ selection: { status: "popularity_unavailable" }, items: [] }),
      featured({
        month: "2031-02",
        window: { from: "2031-02-01", to: "2031-02-28" },
        selection: { status: "no_qualifying_releases" },
        items: [],
      }),
    );
    const first = renderApp("/");

    expect(
      await screen.findByRole("heading", { name: "Aún no hay lanzamientos destacados en octubre de 2026" }),
    ).toBeVisible();
    expect(screen.getByRole("status")).toHaveTextContent(
      "Aún no hay lanzamientos destacados en octubre de 2026",
    );
    expect(screen.getByRole("link", { name: "Ver lanzamientos recientes" })).toHaveAttribute(
      "href",
      "/?view=recent&weeks=1",
    );
    first.unmount();

    renderApp("/?month=2031-02");
    expect(
      await screen.findByRole("heading", { name: "No hay lanzamientos en febrero de 2031" }),
    ).toBeVisible();
    expect(screen.queryByRole("region", { name: "Otros lanzamientos destacados" })).toBeNull();
  });

  it("separates a catalogue that is not ready from a technical failure and retries on request", async () => {
    const user = userEvent.setup();
    stubFeatured(problem(503, "CATALOGUE_NOT_READY"));
    const first = renderApp("/");

    expect(
      await screen.findByRole("heading", { name: "El catálogo todavía no está disponible" }),
    ).toBeVisible();
    expect(screen.queryByRole("alert")).toBeNull();
    first.unmount();

    const fetchMock = stubFeatured(problem(500, "INTERNAL_ERROR"), featured());
    renderApp("/");
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("No se pudieron cargar los lanzamientos destacados");
    expect(alert).toHaveTextContent("Referencia para soporte: correlation-featured");

    await user.click(within(alert).getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByRole("article", { name: "Juego destacado" })).toBeVisible();
    expect(fetchMock).toHaveLength(2);
  });
});
