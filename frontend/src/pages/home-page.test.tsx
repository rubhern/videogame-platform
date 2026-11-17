import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { homeCalendar, homeFeatured, homeRanking, requestedPaths, stubHomeApi } from "../test/home-api";
import { renderApp } from "../test/render-app";

afterEach(() => {
  vi.unstubAllGlobals();
});

const section = (name: RegExp | string) => screen.getByRole("region", { name });

describe("home page (#382)", () => {
  it("opens on the week's featured release with its way into the game and into Destacados", async () => {
    const user = userEvent.setup();
    stubHomeApi();
    const { router } = renderApp();

    expect(screen.getByRole("heading", { level: 1, name: "Descubre tu próximo juego" })).toBeVisible();
    const lead = await screen.findByRole("article", { name: "Phantom Blade Zero" });
    expect(within(lead).getByText("29 de octubre de 2026")).toBeVisible();
    expect(within(lead).getByRole("list", { name: "Plataformas" })).toHaveTextContent("PlayStation 5");
    expect(screen.getByRole("link", { name: "Abrir Destacados" })).toHaveAttribute("href", "/lanzamientos/destacados");
    expect(screen.getByRole("list", { name: "Más destacados" }).children).toHaveLength(4);
    expect(screen.getByRole("link", { name: "Ver todos los lanzamientos" })).toHaveAttribute(
      "href",
      "/lanzamientos?from=2026-10-26&to=2026-11-01",
    );

    await user.click(within(lead).getByRole("link", { name: "Phantom Blade Zero" }));
    expect(router.state.location.pathname).toBe("/games/phantom/phantom");
    expect(router.state.location.state).toMatchObject({ returnTo: { path: "/" } });
  });

  it("steps through periods and carries a platform and selected date into the next action", async () => {
    const user = userEvent.setup();
    const ps5 = "10000000-0000-4000-8000-000000000001";
    const fetchMock = stubHomeApi({
      featured: (url) => {
        if (url.searchParams.get("granularity") === "month") {
          return Response.json({
            ...homeFeatured,
            granularity: "month",
            month: "2026-10",
            window: { from: "2026-10-01", to: "2026-10-31" },
            navigation: { next: "2026-11" },
          });
        }
        if (url.searchParams.get("week") === "2026-W45") {
          return Response.json({
            ...homeFeatured,
            week: "2026-W45",
            window: { from: "2026-11-02", to: "2026-11-08" },
            navigation: { previous: "2026-W44" },
          });
        }
        return Response.json({ ...homeFeatured, navigation: { next: "2026-W45" } });
      },
    });
    const { router } = renderApp();

    const discovery = within(section("Lanzamientos"));
    await discovery.findByRole("list", { name: "Días de la semana" });
    await user.click(discovery.getByRole("button", { name: "Plataforma Todas" }));
    expect(discovery.getByRole("button", { name: "Todas" })).toHaveAttribute("aria-pressed", "true");
    expect(discovery.getByRole("button", { name: "Mis plataformas" })).toBeDisabled();
    await user.click(discovery.getByRole("link", { name: "PlayStation 5" }));
    expect(discovery.getByRole("button", { name: "Plataforma PlayStation 5" })).toHaveFocus();
    await user.click(await discovery.findByRole("button", { name: /29 oct 2026/ }));
    expect(await discovery.findByRole("article", { name: "Phantom Blade Zero" })).toBeVisible();
    expect(requestedPaths(fetchMock)).toContain(
      "/api/v1/releases?from=2026-10-29&to=2026-10-29&sort=hype&platformIds=" + ps5 + "&page=1&pageSize=5",
    );
    expect(discovery.getByRole("link", { name: "Ver todos los lanzamientos" })).toHaveAttribute(
      "href",
      "/lanzamientos?from=2026-10-29&to=2026-10-29&platformIds=" + ps5,
    );
    expect(discovery.getByRole("link", { name: "Abrir Destacados" })).toHaveAttribute(
      "href",
      "/lanzamientos/destacados?plataforma=playstation-5",
    );
    await user.click(discovery.getByRole("link", { name: "Ver todo el periodo" }));
    await user.click(discovery.getByRole("link", { name: /Semana siguiente/ }));
    expect(await discovery.findByRole("button", { name: /2 nov 2026/ })).toBeVisible();
    expect(router.state.location.search).toBe("?inicio=2026-W45&plataforma=playstation-5");
    await user.click(discovery.getByRole("link", { name: "Semana actual" }));
    await user.click(discovery.getByRole("link", { name: "Mes" }));
    expect(await discovery.findByRole("list", { name: "Semanas del mes" })).toBeVisible();
    expect(router.state.location.search).toBe("?inicio=mes&plataforma=playstation-5");
    expect(requestedPaths(fetchMock)).toContain("/api/v1/release-calendar?month=2026-10&platformIds=" + ps5 + "&includePast=true");
  });

  it("starts with saved platforms and carries an other platform into Destacados", async () => {
    const user = userEvent.setup();
    const fetchMock = stubHomeApi({
      session: () => Response.json({ authenticated: true, csrfToken: "csrf", capabilities: [] }),
      preferences: () => Response.json({ configured: true, platforms: [{ platformId: "ps4", name: "PlayStation 4" }] }),
    });
    renderApp();

    const discovery = within(section("Lanzamientos"));
    const trigger = await discovery.findByRole("button", { name: "Plataforma Mis plataformas" });
    await user.click(trigger);
    expect(discovery.getByRole("button", { name: "Mis plataformas" })).toHaveAttribute("aria-pressed", "true");
    await user.click(discovery.getByRole("link", { name: "PlayStation 4" }));
    expect(discovery.getByRole("link", { name: "Abrir Destacados" })).toHaveAttribute("href", "/lanzamientos/destacados?plataforma=ps4");
    expect(requestedPaths(fetchMock)).toContain("/api/v1/featured-releases?granularity=week&platformIds=ps4");
  });

  it("reads the calendar density for a day and applies the higher scale to a week", async () => {
    const user = userEvent.setup();
    stubHomeApi({
      featured: (url) => Response.json(url.searchParams.get("granularity") === "month" ? {
        ...homeFeatured,
        granularity: "month",
        month: "2026-10",
        window: { from: "2026-10-01", to: "2026-10-31" },
      } : homeFeatured),
    });
    renderApp();

    const discovery = within(section("Lanzamientos"));
    expect(await discovery.findByRole("button", { name: /29 oct 2026.*5 lanzamientos.*Expectación 5 de 5/ })).toHaveAttribute("data-density", "4");
    await user.click(discovery.getByRole("link", { name: "Mes" }));
    expect(await discovery.findByRole("button", { name: /26–31 oct 2026.*Expectación 3 de 5/ })).toHaveAttribute("data-density", "2");
  });

  it("shows past days from both months when the previous week crosses a month boundary", async () => {
    const fetchMock = stubHomeApi({
      featured: () => Response.json({
        ...homeFeatured,
        evaluatedOn: "2026-11-13",
      }),
      calendar: (url) => {
        const month = url.searchParams.get("month");
        const november = month === "2026-11";
        return Response.json({
          ...homeCalendar,
          month,
          evaluatedOn: "2026-11-13",
          window: november ? { from: "2026-11-01", to: "2026-11-30" } : homeCalendar.window,
          days: [november ? { date: "2026-11-01", gameCount: 2, totalHype: 0 }
            : { date: "2026-10-29", gameCount: 5, totalHype: 284 }],
        });
      },
    });
    renderApp("/?inicio=2026-W44");
    const discovery = within(section("Lanzamientos"));
    expect(await discovery.findByRole("button", { name: /29 oct 2026.*5 lanzamientos.*Expectación 5 de 5/ })).toBeVisible();
    expect(await discovery.findByRole("button", { name: /1 nov 2026.*2 lanzamientos.*Expectación 1 de 5/ })).toBeVisible();
    expect(requestedPaths(fetchMock).filter((path) => path.includes("/release-calendar?"))).toEqual([
      "/api/v1/release-calendar?month=2026-10&includePast=true",
      "/api/v1/release-calendar?month=2026-11&includePast=true",
    ]);
  });

  it("shows both podiums independently, including an empty community podium", async () => {
    stubHomeApi({
      rankings: (url) =>
        Response.json(
          url.searchParams.get("source") === "gameometro_community"
            ? homeRanking("gameometro_community", [])
            : homeRanking("igdb_players"),
        ),
    });
    renderApp();

    const topometro = await screen.findByRole("region", { name: "TOPómetro" });
    const podium = await within(topometro).findByRole("list", { name: "Podio de Jugadores IGDB" });
    const places = within(podium).getAllByRole("article");
    expect(places).toHaveLength(3);
    expect(within(places[0] as HTMLElement).getByRole("link", { name: "Puesto 1: The Witcher 3: Wild Hunt" })).toBeInTheDocument();
    expect(within(places[0] as HTMLElement).getByLabelText("9,4 de 10")).toBeInTheDocument();
    expect(within(places[0] as HTMLElement).getByText("5.493 valoraciones")).toBeInTheDocument();
    const community = within(topometro).getByRole("region", { name: "Comunidad Gameómetro" });
    expect(await within(community).findByText("Aún no hay ningún juego puntuado")).toBeVisible();
    expect(podium).toBeVisible();
    expect(within(topometro).getByRole("link", { name: "Ver TOPómetro de Comunidad Gameómetro" })).toHaveAttribute("href", "/topometro?source=gameometro");
    expect(within(topometro).getByRole("link", { name: "Ver TOPómetro de Jugadores IGDB" })).toHaveAttribute("href", "/topometro");
  });

  it("offers each featured platform one way into its catalogue, and the index for everything else", async () => {
    stubHomeApi();
    renderApp();

    const platforms = await screen.findByRole("region", { name: "Explora por plataforma" });
    expect(within(platforms).getAllByRole("article").map((card) => card.getAttribute("aria-labelledby"))).toHaveLength(4);
    expect(within(platforms).getByRole("link", { name: "Explorar catálogo de PlayStation 5" })).toHaveAttribute(
      "href",
      "/plataformas/playstation-5/catalogo",
    );
    // One way in per platform; its TOPómetro, Próximos destacados and every other platform stay on
    // the platform pages and the index.
    expect(within(platforms).getAllByRole("link").map((link) => link.getAttribute("href"))).toEqual([
      "/plataformas",
      "/plataformas/nintendo-switch-2/catalogo",
      "/plataformas/playstation-5/catalogo",
      "/plataformas/xbox-series-x-s/catalogo",
      "/plataformas/windows-pc/catalogo",
    ]);
    // Only PlayStation 5 has games in this directory; the others say so honestly.
    expect(await within(platforms).findAllByText("Aún sin juegos en Gameómetro")).toHaveLength(3);
    expect(within(platforms).getByRole("link", { name: /Ver todas las plataformas/ })).toHaveAttribute("href", "/plataformas");
  });

  it("introduces an account to an anonymous visitor without reading anything personal", async () => {
    const fetchMock = stubHomeApi();
    renderApp();

    const personal = await screen.findByRole("region", { name: "Tu criterio. Tu historia." });
    const account = await within(personal).findByRole("navigation", { name: "Tu cuenta en Gameómetro" });
    expect(within(account).getByRole("link", { name: "Crear cuenta" })).toHaveAttribute(
      "href",
      "/auth/start?returnTo=%2F&intent=register",
    );
    expect(within(account).getByRole("link", { name: "Iniciar sesión" })).toHaveAttribute("href", "/auth/start?returnTo=%2F");
    expect(within(personal).getByRole("list", { name: "Lo que añade tu cuenta" })).toHaveTextContent("Tus estadísticas");
    expect(within(personal).queryByRole("link", { name: /Mis puntuaciones/ })).not.toBeInTheDocument();
    expect(requestedPaths(fetchMock).some((path) => path.startsWith("/api/v1/me/"))).toBe(false);
  });

  it("gives a signed-in player their own pages, scoped to the session, and still reads nothing personal", async () => {
    const fetchMock = stubHomeApi({
      session: () => Response.json({ authenticated: true, csrfToken: "csrf", capabilities: [] }),
    });
    renderApp();

    const personal = await screen.findByRole("region", { name: "Tu criterio. Tu historia." });
    const links = await within(personal).findByRole("list", { name: "Tu espacio en Gameómetro" });
    expect(within(links).getAllByRole("link").map((link) => link.getAttribute("href"))).toEqual([
      "/mis-puntuaciones",
      "/mis-puntuaciones/estadisticas",
      "/mis-plataformas",
    ]);
    expect(within(personal).queryByRole("link", { name: "Crear cuenta" })).not.toBeInTheDocument();
    // Preferences shape the discovery previews; the personal collection is never read here.
    expect(requestedPaths(fetchMock).filter((path) => path.startsWith("/api/v1/me/"))).toEqual([
      "/api/v1/me/platform-preferences",
    ]);
  });

  it("keeps every other preview usable when one read fails or the catalogue is not ready", async () => {
    const user = userEvent.setup();
    let featuredFails = true;
    stubHomeApi({
      featured: () =>
        featuredFails
          ? Response.json({ code: "INTERNAL_ERROR", correlationId: "corr-featured" }, { status: 500 })
          : Response.json({ code: "CATALOGUE_NOT_READY" }, { status: 503 }),
      platforms: () => Response.json({ code: "INTERNAL_ERROR" }, { status: 500 }),
    });
    renderApp();

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Los lanzamientos se han tomado un respiro");
    expect(alert).toHaveTextContent("Referencia para soporte: corr-featured");
    expect(screen.getByRole("link", { name: "Ver calendario" })).toBeVisible();
    // TOPómetro and the four platforms are unaffected; only the honest coverage line is left out.
    expect(await within(section("TOPómetro")).findByRole("list", { name: "Podio de Jugadores IGDB" })).toBeInTheDocument();
    expect(within(section("Explora por plataforma")).getAllByRole("article")).toHaveLength(4);
    expect(within(section("Explora por plataforma")).queryByText("Aún sin juegos en Gameómetro")).not.toBeInTheDocument();

    featuredFails = false;
    await user.click(within(alert).getByRole("button", { name: "Reintentar" }));
    expect(await within(section("Lanzamientos")).findByText("Datos en preparación")).toBeVisible();
  });

  it("says so when the week has no featured release, with ways to every release", async () => {
    stubHomeApi({
      featured: () =>
        Response.json({
          granularity: "week",
          week: "2026-W44",
          evaluatedOn: "2026-10-28",
          window: { from: "2026-10-26", to: "2026-11-01" },
          navigation: {},
          selection: { status: "popularity_unavailable" },
          items: [],
        }),
    });
    renderApp();

    const week = section("Lanzamientos");
    expect(await within(week).findByText("Sin destacados para este periodo")).toBeVisible();
    expect(within(week).getByRole("link", { name: "Ver todos los lanzamientos" })).toHaveAttribute(
      "href",
      "/lanzamientos?from=2026-10-26&to=2026-11-01",
    );
    expect(within(week).queryByRole("article")).not.toBeInTheDocument();
  });
});
