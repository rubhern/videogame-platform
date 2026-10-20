import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { components } from "../shared/api/generated/schema";
import { renderApp } from "../test/render-app";

type FeaturedReleases = components["schemas"]["FeaturedReleases"];
type ReleasePage = components["schemas"]["ReleasePage"];
type PlatformPreferences = components["schemas"]["PlatformPreferences"];

const PS5 = { platformId: "ps5", name: "PlayStation 5" };
const SWITCH_2 = { platformId: "switch-2", name: "Nintendo Switch 2" };
const PC = { platformId: "pc", name: "Windows PC" };

function item(gameId: string, title: string): FeaturedReleases["items"][number] {
  return {
    gameId,
    slug: gameId,
    canonicalTitle: title,
    primaryCover: {
      kind: "fallback",
      url: "/assets/covers/fallback.svg",
      alternativeText: `Portada no disponible de ${title}`,
      attribution: null,
    },
    featuredImage: {
      kind: "fallback",
      presentation: "fill",
      url: "/assets/featured/fallback.svg",
      compactUrl: "/assets/featured/fallback.svg",
      alternativeText: `Imagen destacada no disponible de ${title}`,
      attribution: null,
    },
    releases: [
      {
        releaseId: `release-${gameId}`,
        gameId,
        platform: PS5,
        region: { regionId: "worldwide", name: "Mundial" },
        releaseDate: { precision: "day", value: "2026-10-01" },
        status: "scheduled",
        stage: "unknown",
        provenance: { sourceKind: "external_provider", sourceName: "IGDB", sourceEntityType: "release_date" },
        verificationLevel: "provider_only",
        reviewStatus: "not_required",
      },
    ],
  };
}

function featured(items: FeaturedReleases["items"]): FeaturedReleases {
  return {
    granularity: "week",
    week: "2026-W40",
    evaluatedOn: "2026-10-03",
    window: { from: "2026-09-28", to: "2026-10-04" },
    navigation: { previous: "2026-W39", next: "2026-W41" },
    selection:
      items.length === 0
        ? { status: "no_qualifying_releases" }
        : { status: "ranked", popularityFreshness: "fresh", popularityObservedAt: "2026-10-02T05:00:00Z" },
    items,
  };
}

function releasePage(items: ReleasePage["items"] = [item("game-one", "Juego uno")]): ReleasePage {
  return {
    evaluatedOn: "2026-10-03",
    window: { from: "2026-09-28", to: "2026-10-04" },
    activeFilters: { platformIds: [], regionIds: [] },
    availableFilters: {
      platforms: [SWITCH_2, PS5, PC],
      regions: [{ regionId: "worldwide", name: "Mundial" }],
    },
    items,
    page: { number: 1, size: 12, totalItems: items.length, totalPages: items.length === 0 ? 0 : 1 },
  };
}

type Visitor =
  | { kind: "anonymous" }
  | { kind: "signed-in"; preferences: PlatformPreferences };

/** A scripted same-origin BFF: the session, the visitor's preferences and the public reads. */
function stubBff(visitor: Visitor, { featuredItems = [item("game-one", "Juego uno")], releaseItems }: {
  featuredItems?: FeaturedReleases["items"];
  releaseItems?: ReleasePage["items"];
} = {}) {
  const fetchMock = vi.fn<typeof fetch>().mockImplementation(async (input) => {
    const request = input as Request;
    const url = new URL(request.url);
    if (url.pathname === "/api/v1/session") {
      return Response.json(
        visitor.kind === "anonymous"
          ? { authenticated: false }
          : { authenticated: true, csrfToken: "csrf-test" },
      );
    }
    if (url.pathname === "/api/v1/me/platform-preferences") {
      return visitor.kind === "anonymous"
        ? Response.json({ code: "AUTHENTICATION_REQUIRED" }, { status: 401 })
        : Response.json(visitor.preferences);
    }
    if (url.pathname === "/api/v1/featured-releases") return Response.json(featured(featuredItems));
    if (url.pathname === "/api/v1/releases") return Response.json(releasePage(releaseItems));
    return new Response(null, { status: 404 });
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

function reads(fetchMock: ReturnType<typeof stubBff>, pathname: string): URL[] {
  return fetchMock.mock.calls
    .map((call) => new URL((call[0] as Request).url))
    .filter((url) => url.pathname === pathname);
}

function commands(fetchMock: ReturnType<typeof stubBff>): Request[] {
  return fetchMock.mock.calls.map((call) => call[0] as Request).filter((request) => request.method !== "GET");
}

const withInterests: Visitor = {
  kind: "signed-in",
  preferences: { configured: true, platforms: [SWITCH_2, PS5] },
};

afterEach(() => {
  vi.unstubAllGlobals();
});

/** Opens Mi cuenta and returns the checkbox that applies the preferred platforms. */
async function platformsCheck(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole("button", { name: "Mi cuenta" }));
  return screen.getByRole("checkbox", { name: "Filtrar por mis plataformas" });
}

describe("Mis plataformas on TOPómetro", () => {
  it("ranks only the preferred platforms by default, asking the API before ranking", async () => {
    const user = userEvent.setup();
    const fetchMock = stubBff(withInterests);
    renderApp("/");

    await screen.findByRole("heading", { name: /Juego uno/ });
    // The first and only read is already the restricted one; nothing filters the six afterwards.
    const featuredReads = reads(fetchMock, "/api/v1/featured-releases");
    expect(featuredReads).toHaveLength(1);
    expect(featuredReads[0]?.searchParams.getAll("platformIds")).toEqual(["switch-2", "ps5"]);
    // No list of the applied platforms is printed on the screen.
    expect(screen.queryByText(/Nintendo Switch 2 · PlayStation 5/)).not.toBeInTheDocument();

    const check = await platformsCheck(user);
    expect(check).toBeChecked();
    expect(check).toHaveAccessibleDescription(
      "En TOPómetro y Explorar lanzamientos: Nintendo Switch 2, PlayStation 5",
    );
    expect(screen.getByRole("link", { name: "Mis plataformas" })).toHaveAttribute("href", "/mis-plataformas");
  });

  it("shows every platform in both screens while unchecked, without changing the saved choice", async () => {
    const user = userEvent.setup();
    const fetchMock = stubBff(withInterests);
    const { router } = renderApp("/?week=2026-W40");

    await user.click(await platformsCheck(user));

    await waitFor(() =>
      expect(reads(fetchMock, "/api/v1/featured-releases").at(-1)?.searchParams.has("platformIds")).toBe(false),
    );
    expect(router.state.location.search).toBe("?week=2026-W40");
    // The same document-wide choice holds in the explorer.
    await router.navigate("/lanzamientos");
    await waitFor(() =>
      expect(reads(fetchMock, "/api/v1/releases").at(-1)?.searchParams.has("platformIds")).toBe(false),
    );
    expect(commands(fetchMock)).toHaveLength(0);

    await user.click(await platformsCheck(user));
    await waitFor(() =>
      expect(reads(fetchMock, "/api/v1/releases").at(-1)?.searchParams.getAll("platformIds")).toEqual([
        "switch-2",
        "ps5",
      ]),
    );
  });

  it("names an empty selection of the preferred platforms and offers every platform", async () => {
    const user = userEvent.setup();
    const fetchMock = stubBff(withInterests, { featuredItems: [] });
    renderApp("/");

    expect(
      await screen.findByRole("heading", { name: /Tus plataformas no tienen lanzamientos/ }),
    ).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Ver todas las plataformas" }));
    await waitFor(() =>
      expect(reads(fetchMock, "/api/v1/featured-releases").at(-1)?.searchParams.has("platformIds")).toBe(false),
    );
  });

  it("keeps the general TOPómetro for an anonymous visitor", async () => {
    const fetchMock = stubBff({ kind: "anonymous" });
    renderApp("/");

    await screen.findByRole("heading", { name: /Juego uno/ });
    expect(reads(fetchMock, "/api/v1/featured-releases")[0]?.searchParams.has("platformIds")).toBe(false);
    // Personal state is never asked for without a session.
    expect(reads(fetchMock, "/api/v1/me/platform-preferences")).toHaveLength(0);
  });

  it("offers only the management link to a signed-in visitor without preferences", async () => {
    const user = userEvent.setup();
    stubBff({ kind: "signed-in", preferences: { configured: true, platforms: [] } });
    renderApp("/");

    await user.click(await screen.findByRole("button", { name: "Mi cuenta" }));
    expect(await screen.findByRole("link", { name: "Mis plataformas" })).toBeInTheDocument();
    expect(screen.queryByRole("checkbox", { name: "Filtrar por mis plataformas" })).not.toBeInTheDocument();
  });
});

describe("Mis plataformas on Explorar lanzamientos", () => {
  it("applies the preferred platforms by default and shows them in Plataforma", async () => {
    const fetchMock = stubBff(withInterests);
    renderApp("/lanzamientos?from=2026-10-01&to=2026-10-31&sort=hype&regionIds=worldwide");

    expect(await screen.findByRole("combobox", { name: /Plataforma/ })).toHaveTextContent("Mis plataformas");
    const read = reads(fetchMock, "/api/v1/releases").at(-1);
    expect(read?.searchParams.getAll("platformIds")).toEqual(["switch-2", "ps5"]);
    // Dates, order and region stay exactly as the URL names them.
    expect(read?.searchParams.get("from")).toBe("2026-10-01");
    expect(read?.searchParams.get("sort")).toBe("hype");
    expect(read?.searchParams.getAll("regionIds")).toEqual(["worldwide"]);
  });

  it("turns the preferences off for a manual platform choice that starts from what is shown", async () => {
    const user = userEvent.setup();
    const fetchMock = stubBff(withInterests);
    const { router, interests } = renderApp("/lanzamientos?sort=hype");

    await user.click(await screen.findByRole("combobox", { name: /Plataforma/ }));
    await user.click(within(screen.getByRole("listbox", { name: "Plataforma" })).getByRole("option", { name: /Windows PC/ }));

    await waitFor(() =>
      expect(router.state.location.search).toBe(
        "?sort=hype&platformIds=switch-2&platformIds=ps5&platformIds=pc",
      ),
    );
    expect(interests.enabled()).toBe(false);
    expect(await platformsCheck(user)).not.toBeChecked();

    // Applying the preferences again replaces the manual choice; nothing intersects.
    await user.click(screen.getByRole("checkbox", { name: "Filtrar por mis plataformas" }));
    await waitFor(() => expect(router.state.location.search).toBe("?sort=hype"));
    await waitFor(() =>
      expect(reads(fetchMock, "/api/v1/releases").at(-1)?.searchParams.getAll("platformIds")).toEqual([
        "switch-2",
        "ps5",
      ]),
    );
    expect(commands(fetchMock)).toHaveLength(0);
  });

  it("shows every platform when every filter is removed", async () => {
    const user = userEvent.setup();
    const fetchMock = stubBff(withInterests);
    const { router, interests } = renderApp("/lanzamientos?regionIds=worldwide");

    await user.click(await screen.findByRole("link", { name: "Quitar filtros" }));

    await waitFor(() => expect(router.state.location.search).toBe(""));
    expect(interests.enabled()).toBe(false);
    await waitFor(() =>
      expect(reads(fetchMock, "/api/v1/releases").at(-1)?.searchParams.has("platformIds")).toBe(false),
    );
  });

  it("explains an empty view of the preferred platforms", async () => {
    const user = userEvent.setup();
    const fetchMock = stubBff(withInterests, { releaseItems: [] });
    renderApp("/lanzamientos");

    expect(await screen.findByRole("heading", { name: "Sin lanzamientos para tus plataformas" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Ver todas las plataformas" }));
    await waitFor(() =>
      expect(reads(fetchMock, "/api/v1/releases").at(-1)?.searchParams.has("platformIds")).toBe(false),
    );
  });
});
