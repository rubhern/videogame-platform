import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { renderApp } from "../test/render-app";
import type { components } from "../shared/api/generated/schema";

const releasesResponse = {
  view: "recent",
  evaluatedOn: "2026-08-13",
  window: { from: "2026-02-13", to: "2026-08-13" },
  activeFilters: { platformId: null, regionId: null },
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
        freshnessStatus: "stale",
      },
    },
  ],
  page: { number: 1, size: 6, totalItems: 1, totalPages: 1 },
} as const satisfies components["schemas"]["ReleasePage"];

beforeEach(() => {
  vi.stubGlobal(
    "fetch",
    vi
      .fn<typeof fetch>()
      .mockImplementation(async () => Response.json(releasesResponse, { status: 200 })),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("application routing", () => {
  it("renders the releases product slice through the application providers", async () => {
    renderApp();

    expect(
      await screen.findByRole("heading", { level: 1, name: "Lanzamientos recientes" }),
    ).toBeInTheDocument();
    expect(await screen.findByRole("link", { name: "Pragmata" })).toBeInTheDocument();
  });

  it("uses the integrated search instead of a duplicate navigation link", async () => {
    const user = userEvent.setup();
    renderApp();

    await screen.findByRole("heading", { level: 1, name: "Lanzamientos recientes" });
    const sections = screen.getByRole("navigation", { name: "Secciones principales" });
    expect(within(sections).queryByRole("link", { name: "Buscar" })).not.toBeInTheDocument();
    expect(screen.queryByText("Catálogo de lanzamientos · MVP privado")).not.toBeInTheDocument();
    expect(screen.queryByText("Datos locales · sin consultas al proveedor")).not.toBeInTheDocument();
    await user.type(screen.getByRole("searchbox", { name: "Buscar en el catálogo" }), "Pragmata{Enter}");

    expect(await screen.findByRole("heading", { level: 1, name: "Buscar juegos" })).toBeInTheDocument();
    expect(screen.getByRole("main")).toHaveFocus();
  });

  it("offers a keyboard-accessible return from an unknown route", async () => {
    const user = userEvent.setup();
    renderApp("/not-implemented");

    expect(
      screen.getByRole("heading", { level: 1, name: "Página no encontrada" }),
    ).toBeInTheDocument();

    const homeLink = screen.getByRole("link", { name: "Volver al inicio" });
    homeLink.focus();
    await user.keyboard("{Enter}");

    await screen.findByRole("heading", { level: 1, name: "Lanzamientos recientes" });
    expect(screen.getByRole("main")).toHaveFocus();
  });

  it("focuses the integrated catalogue search with its keyboard shortcut", async () => {
    const user = userEvent.setup();
    renderApp();
    const search = screen.getByRole("searchbox", { name: "Buscar en el catálogo" });

    await user.keyboard("/");

    expect(search).toHaveFocus();
    await user.paste("a".repeat(101));
    expect(search).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByRole("alert")).toHaveTextContent("Usa como máximo 100 caracteres.");
  });
});
