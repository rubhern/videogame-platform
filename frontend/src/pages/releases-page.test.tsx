import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { components } from "../shared/api/generated/schema";
import { renderApp } from "../test/render-app";

type ReleasePage = components["schemas"]["ReleasePage"];
type ReleaseDate = components["schemas"]["ReleaseDate"];
type Problem = components["schemas"]["Problem"];

const pragmata: ReleasePage["items"][number] = {
  gameId: "30000000-0000-4000-8000-000000000006",
  slug: "pragmata",
  canonicalTitle: "Pragmata",
  primaryCover: {
    kind: "fallback",
    url: "/assets/covers/fallback.svg",
    alternativeText: "Portada no disponible de Pragmata",
    attribution: null,
  },
  releases: [
    {
      releaseId: "40000000-0000-4000-8000-000000000006",
      stage: "unknown",
      gameId: "30000000-0000-4000-8000-000000000006",
      platform: { platformId: "windows-pc", name: "Windows PC" },
      region: { regionId: "worldwide", name: "Mundial" },
      releaseDate: { precision: "quarter", value: "2026-Q2" },
      status: "released",
      provenance: {
        sourceKind: "product_curated",
        sourceName: "VideoGame Platform clickable prototype",
        sourceEntityType: "prototype_release",
      },
      verificationLevel: "provider_only",
      reviewStatus: "not_required",
    },
  ],
};

/** The current week of 13 August 2026, a Thursday, unless a case names another range. */
function releasePage(overrides: Partial<ReleasePage> = {}): ReleasePage {
  return {
    evaluatedOn: "2026-08-13",
    window: { from: "2026-08-10", to: "2026-08-16" },
    activeFilters: { platformIds: [], regionIds: [] },
    availableFilters: {
      platforms: [
        { platformId: "playstation-5", name: "PlayStation 5" },
        { platformId: "windows-pc", name: "Windows PC" },
      ],
      regions: [{ regionId: "worldwide", name: "Mundial" }],
    },
    items: [pragmata],
    page: { number: 1, size: 6, totalItems: 1, totalPages: 1 },
    ...overrides,
  };
}

function upcomingGame(id: number, title: string, releaseDate: ReleaseDate): ReleasePage["items"][number] {
  const gameId = `30000000-0000-4000-8000-${String(id).padStart(12, "0")}`;
  const [release] = pragmata.releases;
  if (release === undefined) {
    throw new Error("The fixture game needs one release.");
  }
  return {
    ...pragmata,
    gameId,
    slug: `juego-${id}`,
    canonicalTitle: title,
    releases: [
      {
        ...release,
        releaseId: `40000000-0000-4000-8000-${String(id).padStart(12, "0")}`,
        stage: "unknown",
        gameId,
        releaseDate,
        status: "scheduled",
      },
    ],
  };
}

function problem(status: number, code: Problem["code"]): Problem {
  return {
    type: `urn:videogame-platform:problem:${code.toLowerCase()}`,
    title: "Request rejected",
    status,
    detail: "The releases request could not be served.",
    instance: "urn:videogame-platform:problem-instance:test",
    code,
    category: status === 503 ? "dependency" : "validation",
    correlationId: "correlation-test",
  };
}

function stubReleases(
  respond: (request: Request) => Response | Promise<Response>,
): ReturnType<typeof vi.fn<typeof fetch>> {
  const fetchMock = vi.fn<typeof fetch>().mockImplementation(async (input) => {
    const request = input as Request;
    // The header reads BFF session state on every page; keep tests scoped to the catalogue.
    if (request.url.includes("/api/v1/session")) {
      return Response.json({ authenticated: false });
    }
    if (request.url.includes("/auth/rating-intent")) {
      return new Response(null, { status: 404 });
    }
    return respond(request);
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

function releaseCalls(
  fetchMock: ReturnType<typeof vi.fn<typeof fetch>>,
): Request[] {
  return fetchMock.mock.calls
    .map((call) => call[0] as Request)
    .filter((request) => new URL(request.url).pathname === "/api/v1/releases");
}

function requestedQueries(fetchMock: ReturnType<typeof vi.fn<typeof fetch>>): URLSearchParams[] {
  return releaseCalls(fetchMock).map((request) => new URL(request.url).searchParams);
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("release explorer page", () => {
  it("adds only localized genres to release cards", async () => {
    const fetchMock = vi.fn<typeof fetch>().mockImplementation(async input => {
      const request = input instanceof Request ? input : new Request(input);
      if (new URL(request.url).pathname === "/api/v1/session") return Response.json({ authenticated: false });
      const item = { ...pragmata, genres: [{ genreId: "adventure", name: "Aventura" }], summary: { text: "Hidden summary" }, developers: [{ name: "Hidden developer" }], publishers: [{ name: "Hidden publisher" }], gameModes: [{ name: "Hidden mode" }] };
      return Response.json(releasePage({ items: [item] }));
    });
    vi.stubGlobal("fetch", fetchMock);
    renderApp("/lanzamientos");
    const title = await screen.findByRole("link", { name: "Pragmata" });
    const card = title.closest("article");
    if (!card) throw new Error("Expected a release card");
    expect(within(card).getByLabelText("Géneros: Aventura")).toBeVisible();
    expect(within(card).getByRole("list", { name: "Plataformas de Pragmata" })).toHaveTextContent("Windows PC");
    expect(within(card).getByText("Mundial")).toBeVisible();
    expect(within(card).queryByText(/Hidden/)).not.toBeInTheDocument();
  });

  it("requests twelve releases of the current week and renders its range under the title", async () => {
    const fetchMock = stubReleases(() => Response.json(releasePage(), { status: 200 }));

    renderApp("/lanzamientos");

    expect(
      await screen.findByRole("heading", { level: 1, name: "Explorar lanzamientos" }),
    ).toBeInTheDocument();
    expect(
      await screen.findByText("Del 10 de agosto de 2026 al 16 de agosto de 2026"),
    ).toBeInTheDocument();

    const query = requestedQueries(fetchMock)[0];
    // Only the API knows today, so the current week is requested without a range.
    expect(query?.has("from")).toBe(false);
    expect(query?.has("to")).toBe(false);
    expect(query?.has("sort")).toBe(false);
    expect(query?.get("page")).toBe("1");
    expect(query?.get("pageSize")).toBe("12");
    expect(query?.has("platformIds")).toBe(false);
  });

  it("restores a shared range, order, filters and page", async () => {
    const fetchMock = stubReleases(() =>
      Response.json(
        releasePage({
          window: { from: "2026-08-01", to: "2026-08-31" },
          activeFilters: { platformIds: ["playstation-5"], regionIds: [] },
          items: [],
          page: { number: 2, size: 12, totalItems: 0, totalPages: 0 },
        }),
        { status: 200 },
      ),
    );

    renderApp("/lanzamientos?from=2026-08-01&to=2026-08-31&sort=rating&platformIds=playstation-5&page=2");

    expect(await screen.findByRole("combobox", { name: /Plataforma/ })).toHaveTextContent(
      "PlayStation 5",
    );
    expect(screen.getByRole("combobox", { name: /Ordenar por/ })).toHaveTextContent("Puntuación Gameómetro");
    const query = requestedQueries(fetchMock)[0];
    expect(query?.get("from")).toBe("2026-08-01");
    expect(query?.get("to")).toBe("2026-08-31");
    expect(query?.get("sort")).toBe("rating");
    expect(query?.getAll("platformIds")).toEqual(["playstation-5"]);
    expect(query?.get("page")).toBe("2");
  });

  it("lands a former recent or upcoming link on the explorer with its filters", async () => {
    const fetchMock = stubReleases(() => Response.json(releasePage(), { status: 200 }));

    const { router } = renderApp("/?view=upcoming&weeks=4&platformIds=playstation-5&page=3");

    expect(
      await screen.findByRole("heading", { level: 1, name: "Explorar lanzamientos" }),
    ).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/lanzamientos");
    expect(router.state.location.search).toBe("?platformIds=playstation-5");
    // The read waits for the session, which decides whether preferred platforms apply.
    await waitFor(() => expect(requestedQueries(fetchMock)).not.toHaveLength(0));
    const query = requestedQueries(fetchMock).at(-1);
    expect(query?.has("view")).toBe(false);
    expect(query?.has("weeks")).toBe(false);
    expect(query?.getAll("platformIds")).toEqual(["playstation-5"]);
  });

  it("returns to the first page when a filter is applied", async () => {
    const user = userEvent.setup();
    const fetchMock = stubReleases(() => Response.json(releasePage(), { status: 200 }));

    const { router } = renderApp("/lanzamientos?page=3");
    await user.click(await screen.findByRole("combobox", { name: /Plataforma/ }));
    await user.click(screen.getByRole("option", { name: "PlayStation 5" }));

    await waitFor(() => expect(releaseCalls(fetchMock).length).toBeGreaterThan(1));
    const query = requestedQueries(fetchMock).at(-1);
    expect(query?.getAll("platformIds")).toEqual(["playstation-5"]);
    expect(query?.get("page")).toBe("1");
    expect(router.state.location.search).toBe("?platformIds=playstation-5");
  });

  it("combines several platforms with OR and adds a region with AND from one open popover", async () => {
    const user = userEvent.setup();
    const fetchMock = stubReleases(() => Response.json(releasePage(), { status: 200 }));

    const { router } = renderApp("/lanzamientos");
    await user.click(await screen.findByRole("combobox", { name: /Plataforma/ }));
    await user.click(screen.getByRole("option", { name: "PlayStation 5" }));

    await waitFor(() => expect(releaseCalls(fetchMock).length).toBeGreaterThan(1));
    let query = requestedQueries(fetchMock).at(-1);
    expect(query?.getAll("platformIds")).toEqual(["playstation-5"]);
    expect(router.state.location.search).toBe("?platformIds=playstation-5");

    // The popover stays open, so a second value is added with OR without reopening.
    expect(screen.getByRole("listbox", { name: "Plataforma" })).toBeInTheDocument();
    await user.click(screen.getByRole("option", { name: "Windows PC" }));
    await waitFor(() => expect(releaseCalls(fetchMock).length).toBeGreaterThan(2));
    query = requestedQueries(fetchMock).at(-1);
    expect(query?.getAll("platformIds")).toEqual(["playstation-5", "windows-pc"]);

    // A second dimension combines with AND.
    await user.click(await screen.findByRole("combobox", { name: /Región/ }));
    await user.click(screen.getByRole("option", { name: "Mundial" }));
    await waitFor(() => expect(releaseCalls(fetchMock).length).toBeGreaterThan(3));
    expect(router.state.location.search).toBe(
      "?platformIds=playstation-5&platformIds=windows-pc&regionIds=worldwide",
    );
  });

  it("marks region options with supplied icons, including regions acquired after the seed", async () => {
    const user = userEvent.setup();
    stubReleases(() =>
      Response.json(
        releasePage({
          availableFilters: {
            platforms: [],
            regions: [
              // Acquired regions carry identities created per environment; only the label is shared.
              { regionId: "637c2274-1a20-41a9-8237-e0ea70eb146d", name: "Asia" },
              { regionId: "21aec642-4a0e-4e44-b204-2cf0a04ac968", name: "China" },
              { regionId: "20000000-0000-4000-8000-000000000005", name: "Japón" },
            ],
          },
        }),
        { status: 200 },
      ),
    );

    renderApp("/lanzamientos");
    await user.click(await screen.findByRole("combobox", { name: /Región/ }));
    const mark = (name: string) =>
      screen.getByRole("option", { name }).querySelector("[class*='app-select-icon-']")?.className;

    expect(mark("Asia")).toContain("app-select-icon-asia");
    expect(mark("Japón")).toContain("app-select-icon-japan");
    // A region without a supplied mark keeps the generic location marker.
    expect(mark("China")).toBeUndefined();
  });

  it("explores a typed past, future or mixed range from the first page", async () => {
    const user = userEvent.setup();
    const fetchMock = stubReleases((request) => {
      const params = new URL(request.url).searchParams;
      return Response.json(
        releasePage(
          params.has("from")
            ? { window: { from: params.get("from") ?? "", to: params.get("to") ?? "" } }
            : {},
        ),
        { status: 200 },
      );
    });

    const { router } = renderApp("/lanzamientos?platformIds=playstation-5&page=3");
    // The hydration fallback draws empty fields first; type into the ready page's own.
    await screen.findByText("Del 10 de agosto de 2026 al 16 de agosto de 2026");
    const from = screen.getByLabelText("Desde");
    await user.clear(from);
    await user.type(from, "2026-07-20");
    const to = screen.getByLabelText("Hasta");
    await user.clear(to);
    await user.type(to, "2026-09-05");
    await user.keyboard("{Enter}");

    await waitFor(() => expect(requestedQueries(fetchMock).at(-1)?.get("from")).toBe("2026-07-20"));
    const query = requestedQueries(fetchMock).at(-1);
    expect(query?.get("to")).toBe("2026-09-05");
    expect(query?.get("page")).toBe("1");
    expect(query?.getAll("platformIds")).toEqual(["playstation-5"]);
    expect(router.state.location.search).toBe(
      "?from=2026-07-20&to=2026-09-05&platformIds=playstation-5",
    );
    expect(
      await screen.findByText("Del 20 de julio de 2026 al 5 de septiembre de 2026"),
    ).toBeInTheDocument();
  });

  it("selects one day from the bar and keeps the month's scale around it", async () => {
    const user = userEvent.setup();
    const fetchMock = stubReleases((request) => {
      const params = new URL(request.url).searchParams;
      return Response.json(
        releasePage({ window: { from: params.get("from") ?? "", to: params.get("to") ?? "" } }),
        { status: 200 },
      );
    });

    const { router } = renderApp("/lanzamientos?from=2026-08-01&to=2026-08-31");
    const days = within(await screen.findByRole("toolbar", { name: "Elegir un día del periodo" }));
    expect(days.getAllByRole("button")).toHaveLength(31);

    await user.click(days.getByRole("button", { name: /miércoles, 19 de agosto/ }));

    await waitFor(() => expect(requestedQueries(fetchMock).at(-1)?.get("from")).toBe("2026-08-19"));
    expect(requestedQueries(fetchMock).at(-1)?.get("to")).toBe("2026-08-19");
    expect(router.state.location.search).toBe("?from=2026-08-19&to=2026-08-19");
    const bar = within(await screen.findByRole("toolbar", { name: "Elegir un día del periodo" }));
    await waitFor(() =>
      expect(bar.getByRole("button", { name: /miércoles, 19 de agosto/ })).toHaveAttribute(
        "aria-pressed",
        "true",
      ),
    );
    expect(bar.getAllByRole("button")).toHaveLength(31);

    // Refreshing keeps the entry's state, and Back returns to the whole month.
    await router.navigate(-1);
    await waitFor(() => expect(router.state.location.search).toBe("?from=2026-08-01&to=2026-08-31"));
  });

  it("orders by Hype from the keyboard and returns to the first page", async () => {
    const user = userEvent.setup();
    const fetchMock = stubReleases(() => Response.json(releasePage(), { status: 200 }));

    const { router } = renderApp("/lanzamientos?page=2");
    const order = await screen.findByRole("combobox", { name: /Ordenar por/ });
    order.focus();
    await user.keyboard("{ArrowDown}{ArrowDown}{Enter}");

    await waitFor(() => expect(requestedQueries(fetchMock).at(-1)?.get("sort")).toBe("hype"));
    expect(requestedQueries(fetchMock).at(-1)?.get("page")).toBe("1");
    expect(router.state.location.search).toBe("?sort=hype");
  });

  it("orders by the IGDB players' score and keeps the choice in the URL", async () => {
    const user = userEvent.setup();
    const fetchMock = stubReleases(() => Response.json(releasePage(), { status: 200 }));

    const { router } = renderApp("/lanzamientos?from=2026-08-01&to=2026-08-31");
    await user.click(await screen.findByRole("combobox", { name: /Ordenar por/ }));
    await user.click(screen.getByRole("option", { name: "Jugadores IGDB" }));

    await waitFor(() =>
      expect(requestedQueries(fetchMock).at(-1)?.get("sort")).toBe("externalPlayerScore"),
    );
    expect(router.state.location.search).toBe(
      "?from=2026-08-01&to=2026-08-31&sort=externalPlayerScore",
    );
    expect(screen.getByRole("combobox", { name: /Ordenar por/ })).toHaveTextContent("Jugadores IGDB");
  });

  it("toggles approximate dates with the keyboard while the range reaches beyond today", async () => {
    const user = userEvent.setup();
    const fetchMock = stubReleases(() => Response.json(releasePage(), { status: 200 }));

    // From the first page, so moving focus to the results for a new page never applies.
    const { router } = renderApp("/lanzamientos?platformIds=playstation-5");
    await screen.findByText("Del 10 de agosto de 2026 al 16 de agosto de 2026");
    const optIn = screen.getByRole("checkbox", { name: "Incluir fechas aproximadas" });
    expect(optIn).not.toBeChecked();
    expect(requestedQueries(fetchMock)[0]?.has("includeApproximateDates")).toBe(false);
    optIn.focus();
    await user.keyboard(" ");

    await waitFor(() =>
      expect(router.state.location.search).toBe(
        "?includeApproximateDates=true&platformIds=playstation-5",
      ),
    );
    expect(requestedQueries(fetchMock).at(-1)?.get("includeApproximateDates")).toBe("true");
    expect(requestedQueries(fetchMock).at(-1)?.get("page")).toBe("1");
    expect(optIn).toBeChecked();
    expect(optIn).toHaveFocus();

    await user.keyboard(" ");
    await waitFor(() => expect(router.state.location.search).toBe("?platformIds=playstation-5"));
    expect(optIn).not.toBeChecked();
  });

  it("hides the approximate-date choice for a range that ends by today", async () => {
    stubReleases(() =>
      Response.json(releasePage({ window: { from: "2026-07-01", to: "2026-08-13" } }), { status: 200 }),
    );

    renderApp("/lanzamientos?from=2026-07-01&to=2026-08-13");

    expect(await screen.findByText("Del 1 de julio de 2026 al 13 de agosto de 2026")).toBeInTheDocument();
    expect(screen.queryByRole("checkbox", { name: "Incluir fechas aproximadas" })).not.toBeInTheDocument();
  });

  it("shows opted-in approximate dates at their real precision without inventing a day", async () => {
    stubReleases(() =>
      Response.json(
        releasePage({
          window: { from: "2026-10-01", to: "2026-12-31" },
          items: [
            upcomingGame(21, "Juego mensual", { precision: "month", value: "2026-10" }),
            upcomingGame(22, "Juego trimestral", { precision: "quarter", value: "2026-Q4" }),
            upcomingGame(23, "Juego anual", { precision: "year", value: "2027" }),
          ],
          page: { number: 1, size: 12, totalItems: 3, totalPages: 1 },
        }),
        { status: 200 },
      ),
    );

    renderApp("/lanzamientos?from=2026-10-01&to=2026-12-31&includeApproximateDates=true");

    const results = within(await screen.findByRole("list", { name: "Explorar lanzamientos" }));
    // Each card keeps the contract precision in its full wording and in its compact date.
    expect(results.getByText("octubre de 2026")).toBeInTheDocument();
    expect(results.getByText("oct 2026")).toBeInTheDocument();
    expect(results.getByText("4.º trimestre de 2026")).toBeInTheDocument();
    expect(results.getByText("T4 2026")).toBeInTheDocument();
    expect(results.getAllByText("2027")).toHaveLength(2);
    expect(results.queryByText(/\d{1,2} de octubre de 2026/)).not.toBeInTheDocument();
  });

  it("keeps the controls visible without presenting previous results as the new range", async () => {
    const user = userEvent.setup();
    let resolveRange: ((response: Response) => void) | undefined;
    const rangeResponse = new Promise<Response>((resolve) => {
      resolveRange = resolve;
    });
    stubReleases((request) =>
      new URL(request.url).searchParams.has("from")
        ? rangeResponse
        : Response.json(releasePage(), { status: 200 }),
    );

    renderApp("/lanzamientos");
    await screen.findByRole("link", { name: "Pragmata" });

    await user.click(screen.getByRole("button", { name: /viernes, 14 de agosto/ }));

    expect(screen.getByRole("heading", { level: 1, name: "Explorar lanzamientos" })).toBeVisible();
    expect(screen.getByRole("combobox", { name: /Plataforma/ })).toBeVisible();
    expect(screen.getByRole("status")).toHaveTextContent(
      "Cargando lanzamientos para la nueva selección",
    );
    expect(screen.queryByRole("link", { name: "Pragmata" })).not.toBeInTheDocument();

    resolveRange?.(
      Response.json(releasePage({ window: { from: "2026-08-14", to: "2026-08-14" } }), { status: 200 }),
    );

    expect(await screen.findByText("El 14 de agosto de 2026")).toBeInTheDocument();
  });

  it("explains a not-ready catalogue instead of a generic failure", async () => {
    stubReleases(() =>
      Response.json(problem(503, "CATALOGUE_NOT_READY"), {
        status: 503,
        headers: { "Content-Type": "application/problem+json" },
      }),
    );

    renderApp("/lanzamientos");

    expect(
      await screen.findByRole("heading", { name: "Los juegos todavía no están disponibles" }),
    ).toBeInTheDocument();
  });

  it.each([
    ["PLATFORM_NOT_SUPPORTED", "¿Qué plataforma es esa? A Gameómetro no le suena de nada"],
    ["REGION_NOT_SUPPORTED", "¿Qué región es esa? A Gameómetro no le suena de nada"],
    ["FILTER_INVALID", "Filtros inválidos, o al menos Gameómetro no los entiende"],
  ] as const)("preserves the %s mapping and offers a filter reset", async (code, message) => {
    stubReleases(() =>
      Response.json(problem(422, code), {
        status: 422,
        headers: { "Content-Type": "application/problem+json" },
      }),
    );

    renderApp("/lanzamientos?sort=hype&platformIds=platform-removed");

    expect(await screen.findByRole("heading", { name: "Filtro no admitido" })).toBeInTheDocument();
    expect(screen.getByText(message)).toBeVisible();
    expect(screen.getByRole("link", { name: "Quitar filtros" })).toHaveAttribute(
      "href",
      "/lanzamientos?sort=hype",
    );
  });

  it("retries a technical failure on request", async () => {
    const user = userEvent.setup();
    let attempts = 0;
    const fetchMock = stubReleases(() => {
      attempts += 1;
      return attempts === 1
        ? Response.json(problem(500, "INTERNAL_ERROR"), {
            status: 500,
            headers: { "Content-Type": "application/problem+json" },
          })
        : Response.json(releasePage(), { status: 200 });
    });

    renderApp("/lanzamientos");

    expect(
      await screen.findByRole("heading", { name: "No se pudieron cargar los lanzamientos" }),
    ).toBeInTheDocument();
    expect(screen.getByText("Referencia para soporte: correlation-test")).toBeInTheDocument();
    expect(screen.getByText("A Gameómetro le está costando un poco. Prueba más tarde")).toBeVisible();

    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByRole("link", { name: "Pragmata" })).toBeInTheDocument();
    expect(releaseCalls(fetchMock)).toHaveLength(2);
  });
});
