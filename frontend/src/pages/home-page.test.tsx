import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { homeRanking, requestedPaths, stubHomeApi } from "../test/home-api";
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
    expect(within(lead).getByText("Lanzamiento de la semana")).toBeVisible();
    // The date is announced in full, the platforms by name; the week scale stays decorative.
    expect(within(lead).getByText("29 de octubre de 2026")).toBeInTheDocument();
    expect(within(lead).getByRole("list", { name: "Plataformas" })).toHaveTextContent("PlayStation 5");
    expect(within(lead).getByText(/Calendario de la semana del 26 de octubre al 1 de noviembre de 2026/)).toBeInTheDocument();
    // Destacados's own hero and its temporal G, closed by the home's two ways out.
    expect(lead.querySelector(".featured-dial-g")).not.toBeNull();
    expect(screen.getByText("Lanzamientos, destacados y los juegos mejor valorados.")).toBeVisible();
    expect(within(lead).getByRole("link", { name: "Ver destacados" })).toHaveAttribute("href", "/lanzamientos/destacados");
    expect(within(lead).getByRole("link", { name: "Ver ficha de Phantom Blade Zero" })).toHaveAttribute(
      "href",
      "/games/phantom/phantom",
    );

    // Up to four more featured releases of the same week, in the API's order.
    const more = screen.getByRole("region", { name: "Más destacados de la semana" });
    expect(within(more).getAllByRole("heading", { level: 4 }).map((title) => title.textContent)).toEqual([
      "The Wolf Among Us Remastered",
      "Queen's Domain",
      "Terrible Lizards",
      "Remothered: Red Nun's Legacy",
    ]);
    expect(within(more).getByRole("link", { name: /Ver todos los lanzamientos de la semana/ })).toHaveAttribute(
      "href",
      "/lanzamientos?from=2026-10-26&to=2026-11-01",
    );

    // A game opened from the home carries the home as its way back.
    await user.click(within(lead).getByRole("link", { name: "Phantom Blade Zero" }));
    expect(router.state.location.pathname).toBe("/games/phantom/phantom");
    expect(router.state.location.state).toMatchObject({ returnTo: { path: "/" } });
  });

  it("previews the highlights calendar on its busiest day and lets a day be chosen", async () => {
    const user = userEvent.setup();
    const fetchMock = stubHomeApi();
    renderApp();

    const calendar = await screen.findByRole("region", { name: "Calendario de destacados" });
    await within(calendar).findByRole("button", { name: /jueves, 5 de noviembre/ });
    const days = within(calendar).getAllByRole("button", { pressed: false });
    // October runs short, so the API's following month is read too.
    expect(within(calendar).getAllByRole("button").map((day) => day.getAttribute("aria-label"))).toEqual([
      "miércoles, 28 de octubre de 2026: 1 lanzamiento, hoy",
      "jueves, 29 de octubre de 2026: 5 lanzamientos",
      "viernes, 30 de octubre de 2026: 1 lanzamiento",
      "martes, 3 de noviembre de 2026: 2 lanzamientos",
      "jueves, 5 de noviembre de 2026: 2 lanzamientos",
    ]);
    expect(days).toHaveLength(4);
    expect(within(calendar).getByRole("button", { pressed: true })).toHaveAccessibleName(/jueves, 29 de octubre/);
    expect(await within(calendar).findByRole("list", { name: "Lanzamientos destacados del jueves, 29 de octubre" }))
      .toHaveTextContent("Phantom Blade Zero");
    // The calendar's own preview read: that day only, in Hype order, capped by its highlights.
    expect(requestedPaths(fetchMock)).toContain(
      "/api/v1/releases?from=2026-10-29&to=2026-10-29&sort=hype&page=1&pageSize=4",
    );

    await user.click(within(calendar).getByRole("button", { name: /martes, 3 de noviembre/ }));
    expect(within(calendar).getByRole("button", { name: /martes, 3 de noviembre/ })).toHaveAttribute("aria-pressed", "true");
    await waitFor(() =>
      expect(requestedPaths(fetchMock)).toContain(
        "/api/v1/releases?from=2026-11-03&to=2026-11-03&sort=hype&page=1&pageSize=2",
      ),
    );
    expect(within(calendar).getByRole("link", { name: /Ver todos los lanzamientos del día/ })).toHaveAttribute(
      "href",
      "/lanzamientos?from=2026-11-03&to=2026-11-03&sort=hype",
    );
    expect(within(calendar).getByRole("link", { name: "Abrir este día en el calendario" })).toHaveAttribute(
      "href",
      "/lanzamientos/calendario?day=2026-11-03",
    );
    expect(within(calendar).getByRole("link", { name: /Ver calendario/ })).toHaveAttribute("href", "/lanzamientos/calendario");
  });

  it("shows one source of player ratings at a time with its own counts and an honest empty source", async () => {
    const user = userEvent.setup();
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
    expect(within(topometro).getByRole("button", { name: "Jugadores IGDB" })).toHaveAttribute("aria-pressed", "true");

    await user.click(within(topometro).getByRole("button", { name: "Comunidad Gameómetro" }));
    expect(await within(topometro).findByText("Aún no hay ningún juego puntuado")).toBeVisible();
    // The other ranking is never shown in its place.
    expect(within(topometro).queryByText("The Witcher 3: Wild Hunt", { exact: false })).not.toBeInTheDocument();
    expect(within(topometro).getByRole("link", { name: /Ver TOPómetro/ })).toHaveAttribute("href", "/topometro?source=gameometro");
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
      calendar: () => Response.json({ code: "CATALOGUE_NOT_READY" }, { status: 503 }),
      platforms: () => Response.json({ code: "INTERNAL_ERROR" }, { status: 500 }),
    });
    renderApp();

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Destacados se ha tomado un respiro");
    expect(alert).toHaveTextContent("Referencia para soporte: corr-featured");
    expect(within(section("Calendario de destacados")).getByText("Los juegos todavía no están disponibles")).toBeVisible();
    // TOPómetro and the four platforms are unaffected; only the honest coverage line is left out.
    expect(await within(section("TOPómetro")).findByRole("list", { name: "Podio de Jugadores IGDB" })).toBeInTheDocument();
    expect(within(section("Explora por plataforma")).getAllByRole("article")).toHaveLength(4);
    expect(within(section("Explora por plataforma")).queryByText("Aún sin juegos en Gameómetro")).not.toBeInTheDocument();

    featuredFails = false;
    await user.click(within(alert).getByRole("button", { name: "Reintentar" }));
    expect(await within(section("Destacados de la semana")).findByText("Datos en preparación")).toBeVisible();
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

    const week = section("Destacados de la semana");
    expect(await within(week).findByText("Sin datos de Hype")).toBeVisible();
    expect(within(week).getByRole("link", { name: "Explorar lanzamientos" })).toHaveAttribute(
      "href",
      "/lanzamientos?from=2026-10-26&to=2026-11-01",
    );
    expect(within(week).queryByRole("article")).not.toBeInTheDocument();
  });
});
