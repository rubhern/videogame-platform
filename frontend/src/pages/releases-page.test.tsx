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
      lastSyncedAt: "2026-08-09T10:00:00Z",
      verificationLevel: "provider_only",
      reviewStatus: "not_required",
      freshnessStatus: "fresh",
    },
  ],
};

function releasePage(overrides: Partial<ReleasePage> = {}): ReleasePage {
  return {
    view: "recent",
    evaluatedOn: "2026-08-13",
    window: { from: "2026-02-13", to: "2026-08-13" },
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

describe("releases page", () => {
  it("requests twelve recent releases and renders the window above the title", async () => {
    const fetchMock = stubReleases(() => Response.json(releasePage(), { status: 200 }));

    renderApp();

    expect(
      await screen.findByRole("heading", { level: 1, name: "Lanzamientos recientes" }),
    ).toBeInTheDocument();
    expect(
      await screen.findByText("Del 13 de febrero de 2026 al 13 de agosto de 2026"),
    ).toBeInTheDocument();
    expect(screen.queryByText("Ventana evaluada el 13 de agosto de 2026")).not.toBeInTheDocument();

    const query = requestedQueries(fetchMock)[0];
    expect(query?.get("view")).toBe("recent");
    expect(query?.get("page")).toBe("1");
    expect(query?.get("pageSize")).toBe("12");
    expect(query?.has("platformIds")).toBe(false);
  });

  it("restores a shared multi-select filtered and paginated URL", async () => {
    const fetchMock = stubReleases(() =>
      Response.json(
        releasePage({
          activeFilters: { platformIds: ["playstation-5"], regionIds: [] },
          items: [],
          page: { number: 2, size: 6, totalItems: 0, totalPages: 0 },
        }),
        { status: 200 },
      ),
    );

    renderApp("/?platformIds=playstation-5&page=2");

    // The closed Platform selector summarises the restored selection without opening.
    expect(await screen.findByRole("combobox", { name: /Plataforma/ })).toHaveTextContent(
      "PlayStation 5",
    );
    const query = requestedQueries(fetchMock)[0];
    expect(query?.getAll("platformIds")).toEqual(["playstation-5"]);
    expect(query?.get("page")).toBe("2");
  });

  it("returns to the first page when a filter is applied", async () => {
    const user = userEvent.setup();
    const fetchMock = stubReleases(() => Response.json(releasePage(), { status: 200 }));

    const { router } = renderApp("/?page=3");
    await user.click(await screen.findByRole("combobox", { name: /Plataforma/ }));
    await user.click(screen.getByRole("option", { name: "PlayStation 5" }));

    await waitFor(() => expect(releaseCalls(fetchMock).length).toBeGreaterThan(1));
    const query = requestedQueries(fetchMock).at(-1);
    expect(query?.getAll("platformIds")).toEqual(["playstation-5"]);
    expect(query?.get("page")).toBe("1");
    expect(router.state.location.search).toBe("?weeks=1&platformIds=playstation-5");
  });

  it("combines several platforms with OR and adds a region with AND from one open popover", async () => {
    const user = userEvent.setup();
    const fetchMock = stubReleases(() => Response.json(releasePage(), { status: 200 }));

    const { router } = renderApp("/");
    await user.click(await screen.findByRole("combobox", { name: /Plataforma/ }));
    await user.click(screen.getByRole("option", { name: "PlayStation 5" }));

    await waitFor(() => expect(releaseCalls(fetchMock).length).toBeGreaterThan(1));
    let query = requestedQueries(fetchMock).at(-1);
    expect(query?.getAll("platformIds")).toEqual(["playstation-5"]);
    expect(router.state.location.search).toBe("?weeks=1&platformIds=playstation-5");

    // The popover stays open, so a second value is added with OR without reopening.
    expect(screen.getByRole("listbox", { name: "Plataforma" })).toBeInTheDocument();
    await user.click(screen.getByRole("option", { name: "Windows PC" }));
    await waitFor(() => expect(releaseCalls(fetchMock).length).toBeGreaterThan(2));
    query = requestedQueries(fetchMock).at(-1);
    expect(query?.getAll("platformIds")).toEqual(["playstation-5", "windows-pc"]);
    expect(router.state.location.search).toBe(
      "?weeks=1&platformIds=playstation-5&platformIds=windows-pc",
    );

    // A second dimension combines with AND.
    await user.click(await screen.findByRole("combobox", { name: /Región/ }));
    await user.click(screen.getByRole("option", { name: "Mundial" }));
    await waitFor(() => expect(releaseCalls(fetchMock).length).toBeGreaterThan(3));
    expect(router.state.location.search).toBe(
      "?weeks=1&platformIds=playstation-5&platformIds=windows-pc&regionIds=worldwide",
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

    renderApp("/");
    await user.click(await screen.findByRole("combobox", { name: /Región/ }));
    const mark = (name: string) =>
      screen.getByRole("option", { name }).querySelector("[class*='app-select-icon-']")?.className;

    expect(mark("Asia")).toContain("app-select-icon-asia");
    expect(mark("Japón")).toContain("app-select-icon-japan");
    // A region without a supplied mark keeps the generic location marker.
    expect(mark("China")).toBeUndefined();
  });

  it("switches to the upcoming window through navigation", async () => {
    const user = userEvent.setup();
    const fetchMock = stubReleases(async (request) =>
      Response.json(
        new URL(request.url).searchParams.get("view") === "upcoming"
          ? releasePage({ view: "upcoming", window: { from: "2026-08-13", to: "2027-02-13" } })
          : releasePage(),
        { status: 200 },
      ),
    );

    renderApp();
    await screen.findByRole("combobox", { name: /Plataforma/ });

    await user.click(screen.getByRole("link", { name: "Próximos" }));

    expect(
      await screen.findByRole("heading", { level: 1, name: "Próximos lanzamientos" }),
    ).toBeInTheDocument();
    await waitFor(() =>
      expect(requestedQueries(fetchMock).at(-1)?.get("view")).toBe("upcoming"),
    );
    expect(requestedQueries(fetchMock).at(-1)?.get("pageSize")).toBe("12");
  });

  it("requests exact upcoming days by default and returns to the first page when opting in", async () => {
    const user = userEvent.setup();
    const fetchMock = stubReleases(() =>
      Response.json(releasePage({ view: "upcoming", window: { from: "2026-08-13", to: "2026-08-20" } }), {
        status: 200,
      }),
    );

    const { router } = renderApp("/?view=upcoming&platformIds=playstation-5&page=3");
    const optIn = await screen.findByRole("checkbox", { name: "Incluir fechas aproximadas" });
    expect(optIn).not.toBeChecked();
    expect(requestedQueries(fetchMock)[0]?.has("includeApproximateDates")).toBe(false);

    await user.click(optIn);

    await waitFor(() =>
      expect(requestedQueries(fetchMock).at(-1)?.get("includeApproximateDates")).toBe("true"),
    );
    const query = requestedQueries(fetchMock).at(-1);
    expect(query?.get("page")).toBe("1");
    expect(query?.getAll("platformIds")).toEqual(["playstation-5"]);
    expect(router.state.location.search).toBe(
      "?view=upcoming&weeks=1&includeApproximateDates=true&platformIds=playstation-5",
    );
    expect(screen.getByRole("checkbox", { name: "Incluir fechas aproximadas" })).toBeChecked();
  });

  it("toggles approximate dates with the keyboard and keeps focus on the control", async () => {
    const user = userEvent.setup();
    const fetchMock = stubReleases(() =>
      Response.json(releasePage({ view: "upcoming", window: { from: "2026-08-13", to: "2026-08-20" } }), {
        status: 200,
      }),
    );

    const { router } = renderApp("/?view=upcoming");
    const optIn = await screen.findByRole("checkbox", { name: "Incluir fechas aproximadas" });
    optIn.focus();
    await user.keyboard(" ");

    await waitFor(() =>
      expect(router.state.location.search).toBe("?view=upcoming&weeks=1&includeApproximateDates=true"),
    );
    expect(optIn).toBeChecked();
    expect(optIn).toHaveFocus();

    await user.keyboard(" ");
    await waitFor(() => expect(router.state.location.search).toBe("?view=upcoming&weeks=1"));
    expect(optIn).not.toBeChecked();
    expect(requestedQueries(fetchMock).at(-1)?.has("includeApproximateDates")).toBe(false);
  });

  it("shows opted-in approximate dates at their real precision without inventing a day", async () => {
    stubReleases(() =>
      Response.json(
        releasePage({
          view: "upcoming",
          window: { from: "2026-08-13", to: "2026-08-20" },
          items: [
            upcomingGame(21, "Juego mensual", { precision: "month", value: "2026-10" }),
            upcomingGame(22, "Juego trimestral", { precision: "quarter", value: "2026-Q4" }),
            upcomingGame(23, "Juego anual", { precision: "year", value: "2027" }),
            upcomingGame(24, "Juego sin fecha", { precision: "unknown", value: null }),
          ],
          page: { number: 1, size: 12, totalItems: 4, totalPages: 1 },
        }),
        { status: 200 },
      ),
    );

    renderApp("/?view=upcoming&includeApproximateDates=true");

    const results = within(await screen.findByRole("list", { name: "Próximos lanzamientos" }));
    // Each card keeps the contract precision in its full wording and in its cover chip.
    expect(results.getByText("octubre de 2026")).toBeInTheDocument();
    expect(results.getByText("oct 2026")).toBeInTheDocument();
    expect(results.getByText("4.º trimestre de 2026")).toBeInTheDocument();
    expect(results.getByText("T4 2026")).toBeInTheDocument();
    expect(results.getAllByText("2027")).toHaveLength(2);
    expect(results.getByText("Fecha por confirmar")).toBeInTheDocument();
    expect(results.getByText("Por confirmar")).toBeInTheDocument();
    expect(results.queryByText(/\d{1,2} de octubre de 2026/)).not.toBeInTheDocument();
  });

  it("leaves the approximate-date opt-in behind when navigating to recent", async () => {
    const user = userEvent.setup();
    const fetchMock = stubReleases((request) =>
      Response.json(
        new URL(request.url).searchParams.get("view") === "upcoming"
          ? releasePage({ view: "upcoming", window: { from: "2026-08-13", to: "2026-08-20" } })
          : releasePage(),
        { status: 200 },
      ),
    );

    const { router } = renderApp("/?view=upcoming&includeApproximateDates=true");
    expect(
      await screen.findByRole("checkbox", { name: "Incluir fechas aproximadas" }),
    ).toBeChecked();
    expect(requestedQueries(fetchMock)[0]?.get("includeApproximateDates")).toBe("true");

    await user.click(screen.getByRole("link", { name: "Recientes" }));

    await waitFor(() => expect(requestedQueries(fetchMock).at(-1)?.get("view")).toBe("recent"));
    expect(requestedQueries(fetchMock).at(-1)?.has("includeApproximateDates")).toBe(false);
    expect(router.state.location.search).toBe("?weeks=1");
    expect(
      screen.queryByRole("checkbox", { name: "Incluir fechas aproximadas" }),
    ).not.toBeInTheDocument();
  });

  it("changes the week horizon by keyboard, preserves filters, and resets pagination", async () => {
    const user = userEvent.setup();
    const fetchMock = stubReleases((request) =>
      Response.json(
        releasePage({
          window: new URL(request.url).searchParams.get("weeks") === "4"
            ? { from: "2026-08-13", to: "2026-09-10" }
            : { from: "2026-08-13", to: "2026-08-20" },
        }),
        { status: 200 },
      ),
    );
    const { router } = renderApp("/?view=upcoming&platformIds=playstation-5&page=3");
    const selector = await screen.findByRole("combobox", { name: /^Periodo:/ });
    selector.focus();
    await user.keyboard("{ArrowDown}{End}{Enter}");

    await waitFor(() => expect(requestedQueries(fetchMock).at(-1)?.get("weeks")).toBe("4"));
    expect(requestedQueries(fetchMock).at(-1)?.get("page")).toBe("1");
    expect(router.state.location.search).toBe("?view=upcoming&weeks=4&platformIds=playstation-5");
    expect(await screen.findByText(/13 de agosto de 2026 al 10 de septiembre de 2026/)).toBeInTheDocument();
  });

  it("keeps filters visible without presenting previous results as the new window", async () => {
    const user = userEvent.setup();
    let resolveUpcoming: ((response: Response) => void) | undefined;
    const upcomingResponse = new Promise<Response>((resolve) => {
      resolveUpcoming = resolve;
    });
    stubReleases((request) =>
      new URL(request.url).searchParams.get("view") === "upcoming"
        ? upcomingResponse
        : Response.json(releasePage(), { status: 200 }),
    );

    renderApp();
    await screen.findByRole("link", { name: "Pragmata" });

    await user.click(screen.getByRole("link", { name: "Próximos" }));

    expect(screen.getByRole("heading", { level: 1, name: "Próximos lanzamientos" })).toBeVisible();
    expect(screen.getByRole("combobox", { name: /Plataforma/ })).toBeVisible();
    expect(screen.getByRole("status")).toHaveTextContent(
      "Cargando lanzamientos para la nueva selección",
    );
    expect(screen.queryByRole("link", { name: "Pragmata" })).not.toBeInTheDocument();
    expect(
      screen.queryByText("Del 13 de agosto de 2026 al 13 de febrero de 2027"),
    ).not.toBeInTheDocument();

    resolveUpcoming?.(
      Response.json(
        releasePage({ view: "upcoming", window: { from: "2026-08-13", to: "2027-02-13" } }),
        { status: 200 },
      ),
    );

    expect(
      await screen.findByText("Del 13 de agosto de 2026 al 13 de febrero de 2027"),
    ).toBeInTheDocument();
  });

  it("explains a not-ready catalogue instead of a generic failure", async () => {
    stubReleases(() =>
      Response.json(problem(503, "CATALOGUE_NOT_READY"), {
        status: 503,
        headers: { "Content-Type": "application/problem+json" },
      }),
    );

    renderApp();

    expect(
      await screen.findByRole("heading", { name: "El catálogo todavía no está disponible" }),
    ).toBeInTheDocument();
  });

  it("offers a filter reset when the API rejects the requested platform", async () => {
    stubReleases(() =>
      Response.json(problem(422, "PLATFORM_NOT_SUPPORTED"), {
        status: 422,
        headers: { "Content-Type": "application/problem+json" },
      }),
    );

    renderApp("/?platformIds=platform-removed");

    expect(await screen.findByRole("heading", { name: "Filtro no admitido" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Quitar filtros" })).toHaveAttribute("href", "/?weeks=1");
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

    renderApp();

    expect(
      await screen.findByRole("heading", { name: "No se pudieron cargar los lanzamientos" }),
    ).toBeInTheDocument();
    expect(screen.getByText("Referencia para soporte: correlation-test")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByRole("link", { name: "Pragmata" })).toBeInTheDocument();
    expect(releaseCalls(fetchMock)).toHaveLength(2);
  });
});
