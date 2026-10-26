import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { components } from "../shared/api/generated/schema";
import { renderApp } from "../test/render-app";

type ReleaseCalendar = components["schemas"]["ReleaseCalendar"];
type ReleasePage = components["schemas"]["ReleasePage"];
type Problem = components["schemas"]["Problem"];

function calendar(overrides: Partial<ReleaseCalendar> = {}): ReleaseCalendar {
  return {
    month: "2026-11",
    evaluatedOn: "2026-10-21",
    window: { from: "2026-11-01", to: "2026-11-30" },
    navigation: { first: "2026-10", last: "2027-03", previous: "2026-10", next: "2026-12" },
    activeFilters: { platformIds: [], regionIds: [] },
    availableFilters: {
      platforms: [{ platformId: "playstation-5", name: "PlayStation 5" }],
      regions: [{ regionId: "worldwide", name: "Mundial" }],
    },
    days: [
      { date: "2026-11-01", gameCount: 2, totalHype: 20 },
      { date: "2026-11-13", gameCount: 6, totalHype: 60 },
      { date: "2026-11-20", gameCount: 31, totalHype: 310 },
    ],
    ...overrides,
  };
}

function game(id: number, title: string, date: string): ReleasePage["items"][number] {
  const gameId = `30000000-0000-4000-8000-${String(id).padStart(12, "0")}`;
  return {
    gameId,
    slug: `juego-${id}`,
    canonicalTitle: title,
    primaryCover: {
      kind: "fallback",
      url: "/assets/covers/fallback.svg",
      alternativeText: `Portada no disponible de ${title}`,
      attribution: null,
    },
    genres: [{ genreId: "adventure", name: "Aventura" }],
    releases: [
      {
        releaseId: `40000000-0000-4000-8000-${String(id).padStart(12, "0")}`,
        stage: "full_release",
        gameId,
        platform: { platformId: "playstation-5", name: "PlayStation 5" },
        region: { regionId: "worldwide", name: "Mundial" },
        releaseDate: { precision: "day", value: date },
        status: "scheduled",
        provenance: { sourceKind: "external_provider", sourceName: "IGDB", sourceEntityType: "release_date" },
        verificationLevel: "provider_only",
        reviewStatus: "not_required",
      },
    ],
  };
}

function dayPage(date: string, totalItems: number): ReleasePage {
  return {
    evaluatedOn: "2026-10-21",
    window: { from: date, to: date },
    activeFilters: { platformIds: [], regionIds: [] },
    availableFilters: { platforms: [], regions: [] },
    items: [game(1, "Voidbreakers", date), game(2, "Tales of Elaris", date), game(3, "Neon Apex", date)].slice(
      0,
      Math.min(3, totalItems),
    ),
    page: { number: 1, size: 3, totalItems, totalPages: Math.ceil(totalItems / 3) },
  };
}

function problem(status: number, code: Problem["code"]): Problem {
  return {
    type: `urn:videogame-platform:problem:${code.toLowerCase()}`,
    title: "Request rejected",
    status,
    detail: "The calendar request could not be served.",
    instance: "urn:videogame-platform:problem-instance:test",
    code,
    category: status === 503 ? "dependency" : "validation",
    correlationId: "correlation-test",
  };
}

type Routes = {
  calendar?: (query: URLSearchParams) => Response;
  releases?: (query: URLSearchParams) => Response;
};

function stub({ calendar: onCalendar, releases }: Routes) {
  const fetchMock = vi.fn<typeof fetch>().mockImplementation(async (input) => {
    const request = input as Request;
    const url = new URL(request.url);
    if (url.pathname === "/api/v1/session") return Response.json({ authenticated: false });
    if (url.pathname === "/api/v1/release-calendar") {
      return onCalendar?.(url.searchParams) ?? Response.json(calendar());
    }
    if (url.pathname === "/api/v1/releases") {
      const from = url.searchParams.get("from") ?? "2026-11-13";
      if (releases !== undefined) return releases(url.searchParams);
      const body = dayPage(from, from === "2026-11-20" ? 31 : 6);
      const size = Number(url.searchParams.get("pageSize") ?? 3);
      return Response.json({ ...body, items: body.items.slice(0, size), page: { ...body.page, size, totalPages: Math.ceil(body.page.totalItems / size) } });
    }
    return new Response(null, { status: 404 });
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

function queries(fetchMock: ReturnType<typeof stub>, pathname: string): URLSearchParams[] {
  return fetchMock.mock.calls
    .map((call) => new URL((call[0] as Request).url))
    .filter((url) => url.pathname === pathname)
    .map((url) => url.searchParams);
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("release calendar page", () => {
  it("shows a Monday-to-Sunday month with counts and opens on its first day with releases", async () => {
    const fetchMock = stub({});

    renderApp("/lanzamientos/calendario?month=2026-11");

    expect(
      await screen.findByRole("heading", { level: 1, name: "Calendario de destacados" }),
    ).toBeInTheDocument();
    const grid = await screen.findByRole("table", { name: "Lanzamientos de Noviembre 2026" });
    const headers = within(grid).getAllByRole("columnheader").map((header) => header.getAttribute("abbr"));
    expect(headers).toEqual(["Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo"]);
    // 1 November 2026 is a Sunday, the last column of the first week.
    const firstWeek = within(grid).getAllByRole("row")[1];
    expect(firstWeek?.querySelectorAll("td")[6]).toContainElement(
      screen.getByRole("button", { name: "domingo, 1 de noviembre de 2026: 2 lanzamientos" }),
    );
    expect(screen.getByRole("button", { name: "viernes, 20 de noviembre de 2026: 31 lanzamientos" }))
      .toHaveAttribute("data-density", "4");
    expect(screen.getByRole("button", { name: "domingo, 1 de noviembre de 2026: 2 lanzamientos" })).toHaveAccessibleDescription("Interés bajo");
    expect(screen.getByRole("button", { name: "lunes, 2 de noviembre de 2026: sin lanzamientos" }))
      .toHaveAttribute("data-density", "0");

    // No day in the URL: the first day with releases from today on is chosen.
    expect(screen.getByRole("button", { name: /domingo, 1 de noviembre/ })).toHaveAttribute("aria-pressed", "true");
    expect(await screen.findByRole("heading", { level: 2, name: "Domingo, 1 de noviembre" })).toBeInTheDocument();
    expect(queries(fetchMock, "/api/v1/release-calendar")[0]?.get("month")).toBe("2026-11");
    await waitFor(() => expect(queries(fetchMock, "/api/v1/releases")).toHaveLength(1));
    const preview = queries(fetchMock, "/api/v1/releases")[0];
    expect(preview?.get("from")).toBe("2026-11-01");
    expect(preview?.get("to")).toBe("2026-11-01");
    expect(preview?.get("sort")).toBe("hype");
    expect(preview?.has("featuredOnly")).toBe(false);
    expect(preview?.get("pageSize")).toBe("2");
    expect(preview?.has("includeApproximateDates")).toBe(false);
  });

  it("previews only the leading highlighted game when the full day has more releases", async () => {
    const fetchMock = stub({ calendar: () => Response.json(calendar({ days: [{ date: "2026-11-01", gameCount: 1, totalHype: 100 }] })) });
    renderApp("/lanzamientos/calendario?month=2026-11");
    expect(await screen.findByRole("heading", { level: 3, name: "Voidbreakers" })).toBeVisible();
    expect(screen.queryByRole("heading", { level: 3, name: "Tales of Elaris" })).not.toBeInTheDocument();
    expect(queries(fetchMock, "/api/v1/releases")[0]?.get("pageSize")).toBe("1");
    expect(screen.getByRole("link", { name: "Ver todos los lanzamientos del día" })).toHaveAttribute("href", "/lanzamientos?from=2026-11-01&to=2026-11-01&sort=hype");
  });

  it("chooses a day by click, keeps it in the URL and lists its three leading games", async () => {
    const user = userEvent.setup();
    const fetchMock = stub({});
    const { router } = renderApp("/lanzamientos/calendario?month=2026-11");

    await user.click(await screen.findByRole("button", { name: /viernes, 20 de noviembre/ }));

    expect(router.state.location.search).toBe("?month=2026-11&day=2026-11-20");
    expect(await screen.findByRole("heading", { level: 2, name: "Viernes, 20 de noviembre" })).toBeInTheDocument();
    expect(await screen.findByText("31 lanzamientos · Mostrando 3")).toBeInTheDocument();
    const list = await screen.findByRole("list", { name: "Lanzamientos del viernes, 20 de noviembre" });
    const links = within(list).getAllByRole("link").filter((link) => link.textContent !== "");
    expect(links.map((link) => link.textContent)).toEqual(["Voidbreakers", "Tales of Elaris", "Neon Apex"]);
    expect(within(list).getAllByRole("list", { name: /Plataformas de/ })[0]).toHaveTextContent("PlayStation 5");
    expect(screen.getByRole("link", { name: /Ver todos los lanzamientos del día/ })).toHaveAttribute(
      "href",
      "/lanzamientos?from=2026-11-20&to=2026-11-20&sort=hype",
    );
    expect(queries(fetchMock, "/api/v1/releases").at(-1)?.get("from")).toBe("2026-11-20");
    // Hype orders the preview and is never named or shown.
    expect(screen.queryByText(/hype/i)).not.toBeInTheDocument();
  });

  it("moves between days with the keyboard without choosing until Enter", async () => {
    const user = userEvent.setup();
    stub({});
    const { router } = renderApp("/lanzamientos/calendario?month=2026-11&day=2026-11-13");

    const thirteenth = await screen.findByRole("button", { name: /viernes, 13 de noviembre/ });
    expect(thirteenth).toHaveAttribute("tabindex", "0");
    thirteenth.focus();
    await user.keyboard("{ArrowDown}");
    expect(screen.getByRole("button", { name: /viernes, 20 de noviembre/ })).toHaveFocus();
    await user.keyboard("{ArrowRight}{Home}");
    expect(screen.getByRole("button", { name: /lunes, 16 de noviembre/ })).toHaveFocus();
    await user.keyboard("{End}");
    expect(screen.getByRole("button", { name: /domingo, 22 de noviembre/ })).toHaveFocus();
    await user.keyboard("{ArrowUp}{ArrowUp}{ArrowUp}{ArrowUp}");
    expect(screen.getByRole("button", { name: /domingo, 1 de noviembre/ })).toHaveFocus();
    expect(router.state.location.search).toBe("?month=2026-11&day=2026-11-13");

    await user.keyboard("{Enter}");
    expect(router.state.location.search).toBe("?month=2026-11&day=2026-11-01");
  });

  it("keeps the days before today inert and never chooses one", async () => {
    stub({
      calendar: () =>
        Response.json(
          calendar({
            month: "2026-10",
            window: { from: "2026-10-01", to: "2026-10-31" },
            navigation: { first: "2026-10", last: "2027-03", next: "2026-11" },
            days: [{ date: "2026-10-23", gameCount: 3, totalHype: 30 }],
          }),
        ),
    });
    renderApp("/lanzamientos/calendario?day=2026-10-05");

    const grid = await screen.findByRole("table", { name: "Lanzamientos de Octubre 2026" });
    expect(within(grid).queryByRole("button", { name: /20 de octubre/ })).not.toBeInTheDocument();
    expect(within(grid).getByText("martes, 20 de octubre de 2026, ya pasado")).toBeInTheDocument();
    expect(within(grid).getByRole("button", { name: "miércoles, 21 de octubre de 2026: sin lanzamientos, hoy" }))
      .toHaveAttribute("tabindex", "-1");
    expect(within(grid).getAllByRole("button")).toHaveLength(11);
    // A day that has gone by in the URL gives way to the first day with releases.
    expect(within(grid).getByRole("button", { name: /viernes, 23 de octubre/ })).toHaveAttribute("aria-pressed", "true");
    expect(screen.queryByText("Sin día confirmado")).not.toBeInTheDocument();
  });

  it("navigates months only inside the API's bounds", async () => {
    stub({
      calendar: () =>
        Response.json(
          calendar({
            month: "2026-10",
            window: { from: "2026-10-01", to: "2026-10-31" },
            navigation: { first: "2026-10", last: "2027-03", next: "2026-11" },
            days: [],
          }),
        ),
    });
    renderApp("/lanzamientos/calendario");

    const navigation = await screen.findByRole("navigation", { name: "Mes del calendario" });
    expect(within(navigation).getByRole("link", { name: "Mes anterior" })).toHaveAttribute("aria-disabled", "true");
    expect(within(navigation).getByRole("link", { name: "Mes siguiente: Noviembre 2026" })).toHaveAttribute(
      "href",
      "/lanzamientos/calendario?month=2026-11",
    );
    expect(within(navigation).getByRole("link", { name: "Octubre 2026" })).toHaveAttribute("aria-current", "true");
    // An empty month is a notice, not a failure, and offers no day.
    expect(await screen.findByRole("heading", { name: "Sin destacados con día confirmado" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { level: 2, name: /de octubre/ })).not.toBeInTheDocument();
  });

  it("returns a rejected month to the current one, keeping the filters", async () => {
    const fetchMock = stub({
      calendar: (query) =>
        query.get("month") === "2026-09"
          ? Response.json(problem(422, "FILTER_INVALID"), { status: 422 })
          : Response.json(calendar({ month: "2026-10", window: { from: "2026-10-01", to: "2026-10-31" } })),
    });
    const { router } = renderApp("/lanzamientos/calendario?month=2026-09&day=2026-09-10&regionIds=worldwide");

    await waitFor(() => expect(router.state.location.search).toBe("?regionIds=worldwide"));
    expect(await screen.findByText("Octubre 2026", { selector: ".calendar-period" })).toBeInTheDocument();
    expect(queries(fetchMock, "/api/v1/release-calendar").at(-1)?.has("month")).toBe(false);
  });

  it("distinguishes a catalogue still preparing from a technical failure", async () => {
    stub({ calendar: () => Response.json(problem(503, "CATALOGUE_NOT_READY"), { status: 503 }) });
    renderApp("/lanzamientos/calendario");
    expect(await screen.findByRole("heading", { name: "Los juegos todavía no están disponibles" })).toBeInTheDocument();
  });

  it("explains a failed read with its support reference and retries it", async () => {
    const user = userEvent.setup();
    let failing = true;
    stub({
      calendar: () =>
        failing ? Response.json(problem(500, "INTERNAL_ERROR"), { status: 500 }) : Response.json(calendar()),
    });
    renderApp("/lanzamientos/calendario");

    expect(await screen.findByRole("heading", { name: "No se pudo cargar el calendario" })).toBeInTheDocument();
    expect(screen.getByText("Referencia para soporte: correlation-test")).toBeInTheDocument();
    failing = false;
    await user.click(screen.getByRole("button", { name: "Reintentar" }));
    expect(await screen.findByRole("table", { name: "Lanzamientos de Noviembre 2026" })).toBeInTheDocument();
  });

  it("offers the list view of the same month with its filters", async () => {
    stub({});
    renderApp("/lanzamientos/calendario?month=2026-11&platformIds=playstation-5");

    const views = await screen.findByRole("navigation", { name: "Vista de lanzamientos" });
    await screen.findByRole("table");
    expect(within(views).getByRole("link", { name: "Calendario" })).toHaveAttribute("aria-current", "page");
    expect(within(views).getByRole("link", { name: "Lista" })).toHaveAttribute(
      "href",
      "/lanzamientos?from=2026-11-01&to=2026-11-30&platformIds=playstation-5",
    );
    expect(screen.getByRole("button", { name: "Lanzamientos" })).toHaveAttribute("aria-current", "true");
  });
});
