import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { renderApp } from "../test/render-app";
import type { components } from "../shared/api/generated/schema";

const releasesResponse = {
  evaluatedOn: "2026-08-13",
  window: { from: "2026-08-10", to: "2026-08-16" },
  activeFilters: { platformIds: [], regionIds: [] },
  availableFilters: { platforms: [], regions: [] },
  items: [
    {
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
    },
  ],
  page: { number: 1, size: 6, totalItems: 1, totalPages: 1 },
} as const satisfies components["schemas"]["ReleasePage"];

const featuredResponse = {
  granularity: "week",
  week: "2026-W33",
  evaluatedOn: "2026-08-13",
  window: { from: "2026-08-10", to: "2026-08-16" },
  navigation: { previous: "2026-W32", next: "2026-W34" },
  selection: {
    status: "ranked",
    popularityFreshness: "fresh",
    popularityObservedAt: "2026-08-09T10:00:00Z",
  },
  items: [
    {
      ...releasesResponse.items[0],
      featuredImage: {
        kind: "fallback",
        presentation: "fill",
        url: "/assets/featured/fallback.svg",
        compactUrl: "/assets/featured/fallback.svg",
        alternativeText: "Imagen destacada no disponible de Pragmata",
        attribution: null,
      },
    },
  ],
} as const satisfies components["schemas"]["FeaturedReleases"];

beforeEach(() => {
  vi.stubGlobal(
    "fetch",
    vi.fn<typeof fetch>().mockImplementation(async (input) => {
      const url = input instanceof Request ? input.url : String(input);
      return Response.json(url.includes("/featured-releases") ? featuredResponse : releasesResponse, {
        status: 200,
      });
    }),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("application routing", () => {
  it("opens on the featured releases of the week through the application providers", async () => {
    renderApp();

    expect(
      await screen.findByRole("heading", { level: 1, name: "TOPómetro de la semana" }),
    ).toBeInTheDocument();
    expect(
      await screen.findByRole("heading", { level: 2, name: "Pragmata" }),
    ).toBeInTheDocument();
    const primary = screen.getByRole("navigation", { name: "Principal" });
    expect(within(primary).getByRole("link", { name: "TOPómetro" })).toHaveAttribute(
      "aria-current",
      "page",
    );
  });

  it("opens Explorar lanzamientos from the Lanzamientos entry and returns to TOPómetro", async () => {
    const user = userEvent.setup();
    const { router } = renderApp();
    const primary = screen.getByRole("navigation", { name: "Principal" });

    expect(within(primary).getAllByRole("link").map((link) => link.textContent)).toEqual([
      "Lanzamientos",
      "TOPómetro",
    ]);
    await user.click(within(primary).getByRole("link", { name: "Lanzamientos" }));

    expect(
      await screen.findByRole("heading", { level: 1, name: "Explorar lanzamientos" }),
    ).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/lanzamientos");
    expect(router.state.location.search).toBe("");
    expect(within(primary).getByRole("link", { name: "Lanzamientos" })).toHaveAttribute(
      "aria-current",
      "page",
    );
    expect(within(primary).getByRole("link", { name: "TOPómetro" })).not.toHaveAttribute(
      "aria-current",
    );
    // One explorer replaces the former recent and upcoming windows.
    expect(screen.queryByRole("navigation", { name: "Ventana de lanzamientos" })).not.toBeInTheDocument();

    await user.click(within(primary).getByRole("link", { name: "TOPómetro" }));
    expect(
      await screen.findByRole("heading", { level: 1, name: "TOPómetro de la semana" }),
    ).toBeInTheDocument();
    expect(router.state.location.search).toBe("");
  });

  it("presents the product as Gameómetro in the shell", async () => {
    renderApp();

    await screen.findByRole("heading", { level: 1, name: "TOPómetro de la semana" });
    expect(screen.getByRole("link", { name: "Gameómetro · Inicio" })).toHaveAttribute("href", "/");
    expect(within(screen.getByRole("contentinfo")).getByText("Gameómetro")).toBeVisible();
  });

  it("uses the integrated search instead of a duplicate navigation link", async () => {
    const user = userEvent.setup();
    renderApp();

    await screen.findByRole("heading", { level: 1, name: "TOPómetro de la semana" });
    const sections = screen.getByRole("navigation", { name: "Principal" });
    expect(within(sections).queryByRole("link", { name: "Buscar" })).not.toBeInTheDocument();
    expect(screen.queryByText("Catálogo de lanzamientos · MVP privado")).not.toBeInTheDocument();
    expect(screen.queryByText("Datos locales · sin consultas al proveedor")).not.toBeInTheDocument();
    await user.type(screen.getByRole("combobox", { name: "Buscar juegos" }), "Pragmata{Enter}");

    expect(
      await screen.findByRole("heading", { level: 1, name: "Resultados para «Pragmata»" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("main")).toHaveFocus();
  });

  it("offers a keyboard-accessible return from an unknown route", async () => {
    const user = userEvent.setup();
    renderApp("/not-implemented");

    expect(
      screen.getByRole("heading", { level: 1, name: "Página no encontrada" }),
    ).toBeInTheDocument();

    expect(screen.getByText("Página no disponible. No quieres ver lo que hay aquí, hazme caso")).toBeVisible();
    const homeLink = screen.getByRole("link", { name: "Volver al inicio" });
    homeLink.focus();
    await user.keyboard("{Enter}");

    await screen.findByRole("heading", { level: 1, name: "TOPómetro de la semana" });
    expect(screen.getByRole("main")).toHaveFocus();
  });

  it("focuses the integrated catalogue search with its keyboard shortcut", async () => {
    const user = userEvent.setup();
    renderApp();
    const search = screen.getByRole("combobox", { name: "Buscar juegos" });

    await user.keyboard("/");

    expect(search).toHaveFocus();
    await user.paste("a".repeat(101));
    expect(search).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByRole("alert")).toHaveTextContent("Usa como máximo 100 caracteres.");
  });
});
