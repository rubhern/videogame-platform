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
        verificationLevel: "provider_only",
        reviewStatus: "not_required",
      },
    ],
  };
}

function featured(overrides: Partial<FeaturedReleases> = {}): FeaturedReleases {
  return {
    granularity: "month",
    month: "2026-10",
    evaluatedOn: "2026-10-03",
    window: { from: "2026-10-01", to: "2026-10-31" },
    navigation: { previous: "2026-09", next: "2026-11" },
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

/** The current ISO week on 3 October 2026: Monday 28 September to Sunday 4 October. */
function featuredWeek(overrides: Partial<FeaturedReleases> = {}): FeaturedReleases {
  const { evaluatedOn, selection } = featured();
  return {
    evaluatedOn,
    selection,
    granularity: "week",
    week: "2026-W40",
    window: { from: "2026-09-28", to: "2026-10-04" },
    navigation: { previous: "2026-W39", next: "2026-W41" },
    items: [
      item("game-lead", "Juego destacado", "2026-10-01"),
      item("game-two", "Segundo juego", "2026-09-30"),
    ],
    ...overrides,
  };
}

/** The current month's route while the week is the landing granularity. */
const MONTH = "/?granularity=month";

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
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("featured releases page", () => {
  it("opens on the current week's featured release, Monday to Sunday", async () => {
    const fetchMock = stubFeatured(featuredWeek());
    renderApp("/");

    const hero = await screen.findByRole("article", { name: "Juego destacado" });
    // The browser names only the granularity; the API derives the current week in Madrid.
    expect(requestedUrl(fetchMock).search).toBe("?granularity=week");
    expect(screen.getByRole("heading", { level: 1, name: "TOPómetro de la semana" })).toBeVisible();
    expect(within(hero).getByText("Lanzamiento de la semana")).toBeVisible();
    const switcher = screen.getByRole("navigation", { name: "Periodo del TOPómetro" });
    expect(within(switcher).getByRole("link", { name: "Semana" })).toHaveAttribute(
      "aria-current",
      "true",
    );
    expect(within(switcher).getByRole("link", { name: "Mes" })).toHaveAttribute(
      "href",
      "/?granularity=month",
    );
    expect(within(switcher).getByRole("link", { name: "Mes" })).not.toHaveAttribute("aria-current");
    const weeks = screen.getByRole("navigation", { name: "Semana de la selección" });
    expect(within(weeks).getByText("28 sep – 4 oct 2026")).toBeVisible();
    expect(
      within(weeks).getByText("Semana del 28 de septiembre al 4 de octubre de 2026"),
    ).toBeInTheDocument();
    expect(within(weeks).getByRole("time")).toHaveAttribute("dateTime", "2026-W40");
    expect(
      within(weeks).getByRole("link", {
        name: "Semana anterior: la semana del 21 al 27 de septiembre de 2026",
      }),
    ).toHaveAttribute("href", "/?week=2026-W39");
    expect(
      within(weeks).getByRole("link", {
        name: "Semana siguiente: la semana del 5 al 11 de octubre de 2026",
      }),
    ).toHaveAttribute("href", "/?week=2026-W41");
    // The current week needs no way back to itself.
    expect(screen.queryByRole("link", { name: "Semana actual" })).toBeNull();
    expect(screen.getByRole("status")).toHaveTextContent(
      "TOPómetro de la semana del 28 de septiembre al 4 de octubre de 2026",
    );
    // Thursday 1 October is the release's day; Saturday 3 October is today.
    expect(
      within(hero).getByText(
        "Calendario de la semana del 28 de septiembre al 4 de octubre de 2026: hoy es sábado 3, día 6 de 7; lanzamiento el jueves 1.",
      ),
    ).toBeInTheDocument();
    expect(
      within(screen.getByRole("region", { name: "Más lanzamientos del TOPómetro" })).getAllByRole(
        "heading",
        { level: 3 },
      ),
    ).toHaveLength(1);
  });

  it("presents the current month's featured release first and up to five more", async () => {
    const fetchMock = stubFeatured(featured());
    renderApp(MONTH);

    const hero = await screen.findByRole("article", { name: "Juego destacado" });
    expect(requestedUrl(fetchMock).pathname).toBe("/api/v1/featured-releases");
    expect(requestedUrl(fetchMock).search).toBe("?granularity=month");
    expect(screen.getByRole("heading", { level: 1, name: "TOPómetro del mes" })).toBeVisible();
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

    const others = screen.getByRole("region", { name: "Más lanzamientos del TOPómetro" });
    expect(
      within(others)
        .getAllByRole("heading", { level: 3 })
        .map((heading) => heading.textContent),
    ).toEqual(["Segundo juego", "Tercer juego"]);
    expect(within(others).getByRole("link", { name: "Ver todos los lanzamientos" })).toHaveAttribute(
      "href",
      "/?view=recent&weeks=1",
    );
    expect(screen.getByRole("status")).toHaveTextContent("TOPómetro de octubre de 2026");
    // The heading is the title and the month alone: no selection caption or selection note.
    expect(screen.queryByText(/Selección del mes|Selección automática/)).toBeNull();
    // Attention ranks the selection; nothing claims quality or an award.
    expect(document.body).not.toHaveTextContent(/mejor|juego del mes|visitas/i);
  });

  it("shows only discovery metadata, with a stretched title link and no nested controls", async () => {
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
    renderApp(MONTH);

    const hero = await screen.findByRole("article", { name: "Juego destacado" });
    const link = within(hero).getByRole("link", { name: "Juego destacado" });
    expect(link).toHaveAttribute("href", "/games/game-lead/juego-destacado");
    expect(within(hero).getByRole("heading", { name: "Juego destacado" })).toBeVisible();
    expect(within(hero).getByRole("list", { name: "Géneros" })).toHaveTextContent("AventuraRol (RPG)");
    // The summary belongs to the game page; the featured hero never shows it.
    expect(within(hero).queryByText(summary.text.trim())).toBeNull();
    expect(within(hero).queryByText(/Traducción|Fuente del original|IGDB/)).not.toBeInTheDocument();
    expect(link.querySelector("a, button, input, select, summary, [tabindex]")).toBeNull();
    const overflow = within(hero).getByRole("button", { name: /lanzamientos? más/ });
    expect(link.contains(overflow)).toBe(false);
    expect(within(hero).queryByText("Publicado")).toBeNull();
    expect(within(hero).queryByText("Lanzamiento completo")).toBeNull();
    expect(within(hero).queryByText("Ver ficha")).toBeNull();
    const card = screen.getByRole("region", { name: "Más lanzamientos del TOPómetro" });
    expect(within(card).getByRole("list", { name: "Géneros" })).toHaveTextContent("AventuraRol (RPG)");
    expect(within(card).getByText("22 de octubre de 2026: PlayStation 5.")).toBeInTheDocument();
    expect(within(card).getByText("Mundial")).toBeVisible();
    expect(document.body).not.toHaveTextContent(/Studio secret|Publisher secret|Multijugador|Other summary secret|Explora un mundo/);
  });

  it("omits missing enrichment and navigates to details through the hero with Enter", async () => {
    stubFeatured(featured());
    const user = userEvent.setup();
    const { router } = renderApp(MONTH);
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
    renderApp(MONTH);

    const lead = await screen.findByRole("article", { name: "Juego destacado" });
    const heading = within(lead).getByRole("heading", { level: 2, name: "Juego destacado" });
    expect(heading).toHaveTextContent("Juego destacado");
    expect(within(heading).queryByRole("img")).toBeNull();
    expect(within(lead).getByRole("img", { name: "Captura de Juego destacado" })).toHaveAttribute(
      "src",
      "https://images.igdb.com/igdb/image/upload/t_1080p/scexample.webp",
    );
    const cards = within(
      screen.getByRole("region", { name: "Más lanzamientos del TOPómetro" }),
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
    renderApp(MONTH);

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
    renderApp(MONTH);

    const lead = await screen.findByRole("article", { name: "Juego destacado" });
    const heading = within(lead).getByRole("heading", { level: 2 });
    fireEvent.error(within(lead).getByRole("img", { name: "Captura de Juego destacado" }));

    expect(within(heading).queryByRole("img")).toBeNull();
    expect(heading).toHaveTextContent("Juego destacado");
  });

  it("states the month's progress and the release's day in words beside the temporal G", async () => {
    stubFeatured(featured());
    renderApp(MONTH);

    const hero = await screen.findByRole("article", { name: "Juego destacado" });
    // The G is decorative; the same reading, from the API's evaluated date, is text.
    expect(
      within(hero).getByText(
        "Calendario de octubre de 2026: hoy es el día 3 de 31; lanzamiento el día 15.",
      ),
    ).toBeInTheDocument();
  });

  it("never pins a release known only to its month on a day of the scale", async () => {
    const lead = item("game-lead", "Juego destacado", "2026-10-15");
    stubFeatured(
      featured({
        month: "2026-09",
        window: { from: "2026-09-01", to: "2026-09-30" },
        items: [
          {
            ...lead,
            releases: lead.releases.map((release) => ({
              ...release,
              releaseDate: { precision: "month", value: "2026-09" },
            })),
          },
        ],
      }),
    );
    renderApp("/?month=2026-09");

    const hero = await screen.findByRole("article", { name: "Juego destacado" });
    expect(
      within(hero).getByText(
        "Calendario de septiembre de 2026: mes completo, 30 de 30 días transcurridos; lanzamiento sin día confirmado.",
      ),
    ).toBeInTheDocument();
    expect(within(hero).getByText("septiembre de 2026")).toBeInTheDocument();
  });

  it("reads the month again when the day changes in Madrid, so the scale follows the API's day", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    // One minute before midnight in Madrid (UTC+2) on 3 October.
    vi.setSystemTime(new Date("2026-10-03T21:59:00Z"));
    const fetchMock = stubFeatured(featured(), featured({ evaluatedOn: "2026-10-04" }));
    renderApp(MONTH);

    const hero = await screen.findByRole("article", { name: "Juego destacado" });
    expect(within(hero).getByText(/hoy es el día 3 de 31/)).toBeInTheDocument();

    await vi.advanceTimersByTimeAsync(70_000);

    expect(await within(hero).findByText(/hoy es el día 4 de 31/)).toBeInTheDocument();
    expect(fetchMock).toHaveLength(2);
  });

  it("omits the other featured releases when only one game is ranked", async () => {
    stubFeatured(featured({ items: [item("game-lead", "Juego destacado", "2026-10-15")] }));
    renderApp(MONTH);

    await screen.findByRole("article", { name: "Juego destacado" });
    expect(screen.queryByRole("region", { name: "Más lanzamientos del TOPómetro" })).toBeNull();
  });

  it("steps through months in the URL and offers the way back to the current month", async () => {
    const user = userEvent.setup();
    const fetchMock = stubFeatured(
      featured({
        month: "2026-09",
        window: { from: "2026-09-01", to: "2026-09-30" },
        navigation: { previous: "2026-08", next: "2026-10" },
      }),
      featured(),
    );
    const { router } = renderApp("/?month=2026-09");

    await screen.findByRole("article", { name: "Juego destacado" });
    expect(requestedUrl(fetchMock).searchParams.get("month")).toBe("2026-09");
    expect(screen.getByRole("heading", { level: 1, name: "TOPómetro del mes" })).toBeVisible();
    const months = screen.getByRole("navigation", { name: "Mes de la selección" });
    expect(within(months).getByText("Septiembre 2026")).toBeVisible();
    expect(within(months).getByRole("link", { name: "Mes anterior: agosto de 2026" })).toHaveAttribute(
      "href",
      "/?month=2026-08",
    );
    expect(screen.getByRole("link", { name: "Mes actual" })).toHaveAttribute(
      "href",
      "/?granularity=month",
    );
    const next = within(months).getByRole("link", { name: "Mes siguiente: octubre de 2026" });
    expect(next).toHaveAttribute("href", "/?month=2026-10");

    await user.click(next);

    expect(router.state.location.search).toBe("?month=2026-10");
    expect(
      await within(screen.getByRole("navigation", { name: "Mes de la selección" })).findByText(
        "Octubre 2026",
      ),
    ).toBeVisible();
    expect(requestedUrl(fetchMock, 1).searchParams.get("month")).toBe("2026-10");
    // October holds the API's evaluated date: it is the current month.
    expect(screen.queryByRole("link", { name: "Mes actual" })).toBeNull();
  });

  it("steps through weeks across months, back to the current week and through history", async () => {
    const user = userEvent.setup();
    const fetchMock = stubFeatured(
      featuredWeek({
        week: "2026-W44",
        window: { from: "2026-10-26", to: "2026-11-01" },
        navigation: { previous: "2026-W43", next: "2026-W45" },
        items: [item("game-lead", "Juego destacado", "2026-10-30")],
      }),
      featuredWeek({
        week: "2026-W45",
        window: { from: "2026-11-02", to: "2026-11-08" },
        navigation: { previous: "2026-W44", next: "2026-W46" },
        items: [item("game-lead", "Juego destacado", "2026-11-05")],
      }),
      featuredWeek(),
    );
    const { router } = renderApp("/?week=2026-W44");

    const hero = await screen.findByRole("article", { name: "Juego destacado" });
    expect(requestedUrl(fetchMock).search).toBe("?week=2026-W44");
    const weeks = screen.getByRole("navigation", { name: "Semana de la selección" });
    expect(within(weeks).getByText("26 oct – 1 nov 2026")).toBeVisible();
    // A future week shows no elapsed progress and names its release's weekday.
    expect(
      within(hero).getByText(
        "Calendario de la semana del 26 de octubre al 1 de noviembre de 2026: la semana aún no ha empezado; lanzamiento el viernes 30.",
      ),
    ).toBeInTheDocument();
    const current = screen.getByRole("link", { name: "Semana actual" });
    expect(current).toHaveAttribute("href", "/");

    await user.click(within(weeks).getByRole("link", { name: /^Semana siguiente/ }));
    expect(router.state.location.search).toBe("?week=2026-W45");
    expect(
      await within(screen.getByRole("navigation", { name: "Semana de la selección" })).findByText(
        "2–8 nov 2026",
      ),
    ).toBeVisible();

    await user.click(screen.getByRole("link", { name: "Semana actual" }));
    expect(router.state.location.search).toBe("");
    expect(
      await within(screen.getByRole("navigation", { name: "Semana de la selección" })).findByText(
        "28 sep – 4 oct 2026",
      ),
    ).toBeVisible();
    expect(screen.queryByRole("link", { name: "Semana actual" })).toBeNull();
    expect(requestedUrl(fetchMock, 2).search).toBe("?granularity=week");

    // Browser history restores the weeks visited, served from the query cache.
    await router.navigate(-1);
    expect(router.state.location.search).toBe("?week=2026-W45");
    expect(
      await within(screen.getByRole("navigation", { name: "Semana de la selección" })).findByText(
        "2–8 nov 2026",
      ),
    ).toBeVisible();
  });

  it("switches granularity to the current period of the other one", async () => {
    const user = userEvent.setup();
    const fetchMock = stubFeatured(
      featuredWeek({
        week: "2026-W44",
        window: { from: "2026-10-26", to: "2026-11-01" },
        items: [item("game-lead", "Juego destacado", "2026-10-30")],
      }),
      featured(),
      featuredWeek(),
    );
    const { router } = renderApp("/?week=2026-W44");
    await screen.findByRole("article", { name: "Juego destacado" });
    const switcher = screen.getByRole("navigation", { name: "Periodo del TOPómetro" });

    // Keyboard: the switch's links are ordinary tab stops before the steps.
    within(switcher).getByRole("link", { name: "Semana" }).focus();
    await user.tab();
    expect(within(switcher).getByRole("link", { name: "Mes" })).toHaveFocus();
    await user.keyboard("{Enter}");

    expect(router.state.location.search).toBe("?granularity=month");
    expect(await screen.findByRole("heading", { level: 1, name: "TOPómetro del mes" })).toBeVisible();
    expect(
      await within(screen.getByRole("navigation", { name: "Mes de la selección" })).findByText(
        "Octubre 2026",
      ),
    ).toBeVisible();
    expect(requestedUrl(fetchMock, 1).search).toBe("?granularity=month");

    await user.click(
      within(screen.getByRole("navigation", { name: "Periodo del TOPómetro" })).getByRole(
        "link",
        { name: "Semana" },
      ),
    );
    expect(router.state.location.search).toBe("");
    expect(await screen.findByRole("heading", { level: 1, name: "TOPómetro de la semana" })).toBeVisible();
    expect(requestedUrl(fetchMock, 2).search).toBe("?granularity=week");
  });

  it("takes the canonical form of a malformed or contradictory URL", async () => {
    const fetchMock = stubFeatured(featuredWeek());
    const { router, unmount } = renderApp("/?month=2026-13");

    await screen.findByRole("article", { name: "Juego destacado" });
    expect(requestedUrl(fetchMock).search).toBe("?granularity=week");
    expect(router.state.location.search).toBe("");
    unmount();

    stubFeatured(featured());
    const second = renderApp("/?granularity=month&month=2026-10");
    await screen.findByRole("article", { name: "Juego destacado" });
    expect(second.router.state.location.search).toBe("?month=2026-10");
  });

  it("disables the steps the API does not offer at its navigation bounds", async () => {
    stubFeatured(
      featured({
        month: "2025-12",
        window: { from: "2025-12-01", to: "2025-12-31" },
        navigation: { next: "2026-01" },
      }),
    );
    const { unmount } = renderApp("/?month=2025-12");
    await screen.findByRole("article", { name: "Juego destacado" });
    let months = screen.getByRole("navigation", { name: "Mes de la selección" });
    // Ten months back reaches into the previous year.
    expect(within(months).getByText("Diciembre 2025")).toBeVisible();
    // A disabled step stays in place: named, announced as unavailable, without a destination
    // and outside the tab order.
    const previous = within(months).getByRole("link", { name: "Mes anterior" });
    expect(previous).toHaveAttribute("aria-disabled", "true");
    expect(previous).not.toHaveAttribute("href");
    expect(previous).not.toHaveAttribute("tabindex");
    expect(within(months).getByRole("link", { name: "Mes siguiente: enero de 2026" })).toHaveAttribute(
      "href",
      "/?month=2026-01",
    );
    unmount();

    stubFeatured(
      featuredWeek({
        week: "2027-W05",
        window: { from: "2027-02-01", to: "2027-02-07" },
        navigation: { previous: "2027-W04" },
        selection: { status: "no_qualifying_releases" },
        items: [],
      }),
    );
    renderApp("/?week=2027-W05");
    await screen.findByRole("heading", {
      name: "No hay lanzamientos en la semana del 1 al 7 de febrero de 2027",
    });
    months = screen.getByRole("navigation", { name: "Semana de la selección" });
    expect(within(months).getByRole("link", { name: "Semana siguiente" })).toHaveAttribute(
      "aria-disabled",
      "true",
    );
    expect(
      within(months).getByRole("link", {
        name: "Semana anterior: la semana del 25 al 31 de enero de 2027",
      }),
    ).toHaveAttribute("href", "/?week=2027-W04");
    // An empty future week says so honestly, without synthetic picks.
    expect(
      screen.getByRole("heading", {
        name: "No hay lanzamientos en la semana del 1 al 7 de febrero de 2027",
      }),
    ).toBeVisible();
    expect(
      screen.getByText("Gameómetro no tiene lanzamientos con día confirmado dentro de esta semana."),
    ).toBeVisible();
    expect(screen.queryByRole("article")).toBeNull();
  });

  it("returns to the current period when the API rejects one outside its bounds", async () => {
    const fetchMock = stubFeatured(problem(422, "FILTER_INVALID"), featured());
    const { router } = renderApp("/?month=2024-01");

    await screen.findByRole("article", { name: "Juego destacado" });
    expect(requestedUrl(fetchMock).searchParams.get("month")).toBe("2024-01");
    expect(requestedUrl(fetchMock, 1).search).toBe("?granularity=month");
    expect(router.state.location.search).toBe("?granularity=month");
    // A period outside the bounds is never presented, nor a new error state.
    expect(screen.queryByText(/2024/)).toBeNull();
    expect(screen.queryByRole("alert")).toBeNull();
    expect(
      within(screen.getByRole("navigation", { name: "Mes de la selección" })).getByText(
        "Octubre 2026",
      ),
    ).toBeVisible();
  });

  it("returns to the current week when the API rejects a week outside its bounds", async () => {
    const fetchMock = stubFeatured(problem(422, "FILTER_INVALID"), featuredWeek());
    const { router } = renderApp("/?week=2027-W30");

    await screen.findByRole("article", { name: "Juego destacado" });
    expect(requestedUrl(fetchMock).searchParams.get("week")).toBe("2027-W30");
    expect(requestedUrl(fetchMock, 1).search).toBe("?granularity=week");
    expect(router.state.location.search).toBe("");
  });

  it("keeps a stale ranking usable without a selection note", async () => {
    stubFeatured(
      featured({
        selection: {
          status: "ranked",
          popularityFreshness: "stale",
          popularityObservedAt: "2026-09-02T05:00:00Z",
        },
      }),
    );
    renderApp(MONTH);

    await screen.findByRole("article", { name: "Juego destacado" });
    expect(screen.queryByText(/Selección automática|atención registrada/)).toBeNull();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("uses the approved weekly Hype explanation without exposing the signal value", async () => {
    stubFeatured(featuredWeek({ selection: { status: "popularity_unavailable" }, items: [] }));
    renderApp("/");

    expect(await screen.findByText("De momento, Gameómetro no tiene Hype para lanzamientos de esta semana")).toBeVisible();
    expect(screen.getByText("Sin datos de Hype")).toBeVisible();
    expect(screen.queryByRole("article")).not.toBeInTheDocument();
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
    const first = renderApp(MONTH);

    expect(
      await screen.findByRole("heading", { name: "Aún no hay TOPómetro de octubre de 2026" }),
    ).toBeVisible();
    expect(screen.getByRole("status")).toHaveTextContent(
      "Aún no hay TOPómetro de octubre de 2026",
    );
    // The missing signal is named as Hype, never with a value and never as a quality judgement.
    expect(screen.getByText("Sin datos de Hype")).toBeVisible();
    expect(
      screen.getByText("De momento, Gameómetro no tiene Hype para lanzamientos de este mes"),
    ).toBeVisible();
    expect(screen.getByRole("link", { name: "Ver lanzamientos recientes" })).toHaveAttribute(
      "href",
      "/?view=recent&weeks=1",
    );
    first.unmount();

    renderApp("/?month=2031-02");
    expect(
      await screen.findByRole("heading", { name: "No hay lanzamientos en febrero de 2031" }),
    ).toBeVisible();
    expect(screen.queryByRole("region", { name: "Más lanzamientos del TOPómetro" })).toBeNull();
  });

  it("separates a catalogue that is not ready from a technical failure and retries on request", async () => {
    const user = userEvent.setup();
    stubFeatured(problem(503, "CATALOGUE_NOT_READY"));
    const first = renderApp(MONTH);

    expect(
      await screen.findByRole("heading", { name: "Los juegos todavía no están disponibles" }),
    ).toBeVisible();
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.getByText("Aún no tenemos datos válidos de juegos, pero tranquilo que llegarán")).toBeVisible();
    expect(screen.getByText("Puedes usar la búsqueda de juegos")).toBeVisible();
    first.unmount();

    const fetchMock = stubFeatured(problem(500, "INTERNAL_ERROR"), featured());
    renderApp(MONTH);
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Ups, parece que TOPómetro se ha tomado unas vacaciones. Prueba de nuevo más tarde");
    expect(alert).toHaveTextContent("Referencia para soporte: correlation-featured");

    await user.click(within(alert).getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByRole("article", { name: "Juego destacado" })).toBeVisible();
    expect(fetchMock).toHaveLength(2);
  });
});
