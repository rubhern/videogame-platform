import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { components } from "../shared/api/generated/schema";
import { renderApp } from "../test/render-app";

type ReleasePage = components["schemas"]["ReleasePage"];
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
  release: {
    releaseId: "40000000-0000-4000-8000-000000000006",
    gameId: "30000000-0000-4000-8000-000000000006",
    platform: { platformId: "windows-pc", name: "Windows PC" },
    region: { regionId: "worldwide", name: "Worldwide" },
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
};

function releasePage(overrides: Partial<ReleasePage> = {}): ReleasePage {
  return {
    view: "recent",
    evaluatedOn: "2026-08-13",
    window: { from: "2026-02-13", to: "2026-08-13" },
    activeFilters: { platformId: null, regionId: null },
    availableFilters: {
      platforms: [
        { platformId: "playstation-5", name: "PlayStation 5" },
        { platformId: "windows-pc", name: "Windows PC" },
      ],
      regions: [{ regionId: "worldwide", name: "Worldwide" }],
    },
    items: [pragmata],
    page: { number: 1, size: 6, totalItems: 1, totalPages: 1 },
    ...overrides,
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
  it("requests the recent first page and renders the evaluated window", async () => {
    const fetchMock = stubReleases(() => Response.json(releasePage(), { status: 200 }));

    renderApp();

    expect(
      await screen.findByRole("heading", { level: 1, name: "Lanzamientos recientes" }),
    ).toBeInTheDocument();
    expect(
      await screen.findByText(
        (_, element) => element?.classList.contains("release-window") === true,
      ),
    ).toBeInTheDocument();

    const query = requestedQueries(fetchMock)[0];
    expect(query?.get("view")).toBe("recent");
    expect(query?.get("page")).toBe("1");
    expect(query?.get("pageSize")).toBe("6");
    expect(query?.has("platformId")).toBe(false);
  });

  it("restores a shared filtered and paginated URL", async () => {
    const fetchMock = stubReleases(() =>
      Response.json(
        releasePage({
          activeFilters: { platformId: "playstation-5", regionId: null },
          items: [],
          page: { number: 2, size: 6, totalItems: 0, totalPages: 0 },
        }),
        { status: 200 },
      ),
    );

    renderApp("/?platformId=playstation-5&page=2");

    expect(
      await screen.findByRole("link", { name: "PlayStation 5" }),
    ).toHaveAttribute("aria-current", "page");
    const query = requestedQueries(fetchMock)[0];
    expect(query?.get("platformId")).toBe("playstation-5");
    expect(query?.get("page")).toBe("2");
  });

  it("applies a platform filter and returns to the first page", async () => {
    const user = userEvent.setup();
    const fetchMock = stubReleases(() => Response.json(releasePage(), { status: 200 }));

    const { router } = renderApp("/?page=3");
    const platformFilters = within(
      await screen.findByRole("list", { name: "Filtrar por plataforma" }),
    );

    await user.click(platformFilters.getByRole("link", { name: "PlayStation 5" }));

    await waitFor(() => expect(releaseCalls(fetchMock).length).toBeGreaterThan(1));
    const query = requestedQueries(fetchMock).at(-1);
    expect(query?.get("platformId")).toBe("playstation-5");
    expect(query?.get("page")).toBe("1");
    expect(router.state.location.search).toBe("?platformId=playstation-5");
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
    await screen.findByRole("list", { name: "Filtrar por plataforma" });

    await user.click(screen.getByRole("link", { name: "Próximos" }));

    expect(
      await screen.findByRole("heading", { level: 1, name: "Próximos lanzamientos" }),
    ).toBeInTheDocument();
    await waitFor(() =>
      expect(requestedQueries(fetchMock).at(-1)?.get("view")).toBe("upcoming"),
    );
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
    await screen.findByRole("link", { name: "Ver Pragmata" });

    await user.click(screen.getByRole("link", { name: "Próximos" }));

    expect(screen.getByRole("heading", { level: 1, name: "Próximos lanzamientos" })).toBeVisible();
    expect(screen.getByRole("list", { name: "Filtrar por plataforma" })).toBeVisible();
    expect(screen.getByRole("status")).toHaveTextContent(
      "Cargando lanzamientos para la nueva selección",
    );
    expect(screen.queryByRole("link", { name: "Ver Pragmata" })).not.toBeInTheDocument();
    expect(
      screen.queryByText(
        (_, element) => element?.classList.contains("release-window") === true,
      ),
    ).not.toBeInTheDocument();

    resolveUpcoming?.(
      Response.json(
        releasePage({ view: "upcoming", window: { from: "2026-08-13", to: "2027-02-13" } }),
        { status: 200 },
      ),
    );

    expect(
      await screen.findByText(
        (_, element) => element?.classList.contains("release-window") === true,
      ),
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

    renderApp("/?platformId=platform-removed");

    expect(await screen.findByRole("heading", { name: "Filtro no admitido" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Quitar filtros" })).toHaveAttribute("href", "/");
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

    expect(await screen.findByRole("link", { name: "Ver Pragmata" })).toBeInTheDocument();
    expect(releaseCalls(fetchMock)).toHaveLength(2);
  });
});
