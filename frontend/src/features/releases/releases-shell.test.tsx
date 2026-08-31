import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";

import { ReleasesShell, type ReleasesShellState } from "./releases-shell";
import type { ReleasesSearch } from "./releases-search";
import type { ReleaseListItem, ReleasesViewModel } from "./releases-view-model";

const search: ReleasesSearch = {
  view: "recent",
  platformId: null,
  regionId: null,
  page: 1,
  pageSize: 6,
};

const pragmata: ReleaseListItem = {
  releaseId: "release-pragmata-pc-worldwide",
  gameId: "game-pragmata",
  slug: "pragmata",
  title: "Pragmata",
  date: "2.º trimestre de 2026",
  platform: "Windows PC",
  region: "Worldwide",
  status: "Publicado",
  provenance: "VideoGame Platform clickable prototype",
  isStale: false,
  freshness: "Datos locales actualizados",
  review: null,
  cover: {
    kind: "fallback",
    url: "/assets/covers/fallback.svg",
    alternativeText: "Portada no disponible de Pragmata",
  },
};

function viewModel(overrides: Partial<ReleasesViewModel> = {}): ReleasesViewModel {
  return {
    view: "recent",
    title: "Lanzamientos recientes",
    windowDescription: "Del 13 de febrero de 2026 al 13 de agosto de 2026",
    evaluatedOnDescription: "Ventana evaluada el 13 de agosto de 2026",
    platforms: [
      { id: "platform-pc", name: "Windows PC" },
      { id: "platform-ps5", name: "PlayStation 5" },
    ],
    regions: [{ id: "region-worldwide", name: "Worldwide" }],
    activePlatformId: null,
    activeRegionId: null,
    items: [pragmata],
    staleItemCount: 0,
    page: { number: 1, size: 6, totalItems: 1, totalPages: 1 },
    ...overrides,
  };
}

function renderShell(
  state: ReleasesShellState,
  options: { search?: ReleasesSearch; onRetry?: () => void } = {},
) {
  const onRetry = options.onRetry ?? vi.fn();
  const result = render(
    <MemoryRouter>
      <ReleasesShell onRetry={onRetry} search={options.search ?? search} state={state} />
    </MemoryRouter>,
  );
  return { ...result, onRetry };
}

describe("releases shell", () => {
  it("announces loading under the accessible view name", () => {
    renderShell({ status: "loading" });

    expect(screen.getByRole("region", { name: "Lanzamientos recientes" })).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Cargando lanzamientos");
    expect(screen.queryByRole("list", { name: "Lanzamientos recientes" })).not.toBeInTheDocument();
  });

  it("shows the evaluated window, the result summary and the release detail", () => {
    renderShell({ status: "ready", model: viewModel(), isRefreshing: false, isPlaceholderData: false });

    expect(
      screen.getByText(
        "Del 13 de febrero de 2026 al 13 de agosto de 2026. Ventana evaluada el 13 de agosto de 2026.",
      ),
    ).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent(
      "1 lanzamiento en la ventana · Página 1 de 1",
    );

    const results = within(screen.getByRole("list", { name: "Lanzamientos recientes" }));
    const card = within(results.getAllByRole("listitem")[0] as HTMLElement);
    expect(card.getByRole("heading", { level: 3, name: "Pragmata" })).toBeInTheDocument();
    expect(card.getByText("2.º trimestre de 2026")).toBeInTheDocument();
    expect(card.getByText("Windows PC · Worldwide")).toBeInTheDocument();
    expect(card.getByText("Publicado")).toBeInTheDocument();
    expect(card.getByRole("img", { name: "Portada no disponible de Pragmata" })).toHaveAttribute(
      "src",
      "/assets/covers/fallback.svg",
    );
    expect(card.getByText("Carátula oficial no disponible")).toBeInTheDocument();
  });

  it("keeps the filters visible with their active values and a clear action", () => {
    const activeSearch: ReleasesSearch = { ...search, platformId: "platform-ps5" };
    renderShell(
      {
        status: "ready",
        model: viewModel({ activePlatformId: "platform-ps5", items: [], staleItemCount: 0 }),
        isRefreshing: false,
        isPlaceholderData: false,
      },
      { search: activeSearch },
    );

    expect(screen.getByLabelText("Plataforma")).toHaveValue("platform-ps5");
    expect(screen.getByLabelText("Región")).toHaveValue("");
    expect(
      screen.getByText("Ningún lanzamiento del catálogo local coincide con esta ventana y estos filtros."),
    ).toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: "Quitar filtros" })[0]).toHaveAttribute("href", "/");
  });

  it("marks the current window and links to the other one", () => {
    renderShell({ status: "ready", model: viewModel(), isRefreshing: false, isPlaceholderData: false });

    const windowNav = within(screen.getByRole("navigation", { name: "Ventana de lanzamientos" }));
    expect(windowNav.getByRole("link", { name: "Recientes" })).toHaveAttribute(
      "aria-current",
      "page",
    );
    expect(windowNav.getByRole("link", { name: "Próximos" })).toHaveAttribute(
      "href",
      "/?view=upcoming",
    );
  });

  it("distinguishes stale local data from a technical failure", () => {
    renderShell({
      status: "ready",
      model: viewModel({
        items: [{ ...pragmata, isStale: true, freshness: "Datos locales desactualizados" }],
        staleItemCount: 1,
      }),
      isRefreshing: false,
      isPlaceholderData: false,
    });

    expect(
      screen.getByText(
        "Algunos lanzamientos muestran los últimos datos locales válidos, que ya están desactualizados.",
      ),
    ).toBeInTheDocument();
    expect(screen.getByText("Datos locales desactualizados")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("explains an unavailable catalogue and offers a retry", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderShell({ status: "catalogue-not-ready" }, { onRetry });

    const alert = within(screen.getByRole("alert"));
    expect(
      alert.getByRole("heading", { name: "El catálogo todavía no está disponible" }),
    ).toBeInTheDocument();
    await user.click(alert.getByRole("button", { name: "Reintentar" }));
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it("separates an unsupported filter from a generic failure", () => {
    renderShell(
      {
        status: "unsupported-filters",
        message: "La plataforma solicitada no existe en el catálogo local.",
      },
      { search: { ...search, platformId: "platform-unknown" } },
    );

    const alert = within(screen.getByRole("alert"));
    expect(alert.getByRole("heading", { name: "Filtro no admitido" })).toBeInTheDocument();
    expect(alert.getByRole("link", { name: "Quitar filtros" })).toHaveAttribute("href", "/");
  });

  it("reports a generic failure with its support reference", () => {
    renderShell({
      status: "error",
      message: "No se pudo leer el catálogo local. Inténtalo de nuevo más tarde.",
      correlationId: "correlation-1",
    });

    const alert = within(screen.getByRole("alert"));
    expect(
      alert.getByRole("heading", { name: "No se pudieron cargar los lanzamientos" }),
    ).toBeInTheDocument();
    expect(alert.getByText("Referencia para soporte: correlation-1")).toBeInTheDocument();
    expect(screen.queryByRole("list", { name: "Lanzamientos recientes" })).not.toBeInTheDocument();
  });

  it("paginates with keyboard-reachable links that preserve the active filters", () => {
    const paginatedSearch: ReleasesSearch = { ...search, page: 2, platformId: "platform-ps5" };
    renderShell(
      {
        status: "ready",
        model: viewModel({
          activePlatformId: "platform-ps5",
          page: { number: 2, size: 6, totalItems: 15, totalPages: 3 },
        }),
        isRefreshing: false,
        isPlaceholderData: false,
      },
      { search: paginatedSearch },
    );

    const pagination = within(
      screen.getByRole("navigation", { name: "Paginación de lanzamientos" }),
    );
    expect(pagination.getByText("Página 2 de 3")).toBeInTheDocument();
    expect(pagination.getByRole("link", { name: "Página anterior" })).toHaveAttribute(
      "href",
      "/?platformId=platform-ps5",
    );
    expect(pagination.getByRole("link", { name: "Página siguiente" })).toHaveAttribute(
      "href",
      "/?platformId=platform-ps5&page=3",
    );
  });

  it("recovers directly when a shared page is beyond the last available page", () => {
    renderShell(
      {
        status: "ready",
        model: viewModel({
          items: [],
          page: { number: 99, size: 6, totalItems: 2, totalPages: 2 },
        }),
        isRefreshing: false,
        isPlaceholderData: false,
      },
      { search: { ...search, page: 99 } },
    );

    expect(screen.getByRole("status")).toHaveTextContent(
      "2 lanzamientos en la ventana · La página 99 ya no está disponible",
    );
    expect(
      screen.getByText("La página solicitada ya no está disponible para estos resultados."),
    ).toBeInTheDocument();
    expect(
      screen.queryByText(
        "Ningún lanzamiento del catálogo local coincide con esta ventana y estos filtros.",
      ),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Ir a la última página" })).toHaveAttribute(
      "href",
      "/?page=2",
    );
  });

  it("moves focus to the results heading after a page change", () => {
    const state: ReleasesShellState = {
      status: "ready",
      model: viewModel({ page: { number: 1, size: 6, totalItems: 15, totalPages: 3 } }),
      isRefreshing: false,
      isPlaceholderData: false,
    };
    const { rerender } = renderShell(state);

    rerender(
      <MemoryRouter>
        <ReleasesShell
          onRetry={vi.fn()}
          search={{ ...search, page: 2 }}
          state={{
            ...state,
            model: viewModel({ page: { number: 2, size: 6, totalItems: 15, totalPages: 3 } }),
          }}
        />
      </MemoryRouter>,
    );

    expect(screen.getByRole("heading", { level: 2, name: "Resultados" })).toHaveFocus();
  });

  it("announces a refresh instead of hiding the visible results", () => {
    renderShell({ status: "ready", model: viewModel(), isRefreshing: true, isPlaceholderData: false });

    expect(screen.getByRole("status")).toHaveTextContent("Actualizando lanzamientos…");
    expect(screen.getByRole("link", { name: "Ver Pragmata" })).toBeInTheDocument();
  });
});
