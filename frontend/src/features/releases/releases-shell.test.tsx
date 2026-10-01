import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";

import { ReleasesShell, type ReleasesShellState } from "./releases-shell";
import type { ReleasesSearch } from "./releases-search";
import type { ReleaseListItem, ReleasesViewModel } from "./releases-view-model";

const search: ReleasesSearch = {
  view: "recent",
  weeks: 1,
  platformIds: [],
  regionIds: [],
  page: 1,
  pageSize: 12,
};

const pragmata: ReleaseListItem = {
  gameId: "game-pragmata",
  slug: "pragmata",
  title: "Pragmata",
  releaseGroups: [
    {
      key: "quarter|2026-Q2|worldwide",
      date: "2.º trimestre de 2026",
      shortDate: "T2 2026",
      region: "Mundial",
      platforms: ["Windows PC"],
      isStale: false,
      review: false,
      releaseCount: 1,
    },
  ],
  hiddenReleaseCount: 0,
  isStale: false,
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
    compactWindowDescription: "Del 13/02/2026 al 13/08/2026",
    platforms: [
      { id: "platform-pc", name: "Windows PC" },
      { id: "platform-ps5", name: "PlayStation 5" },
    ],
    regions: [{ id: "region-worldwide", name: "Mundial" }],
    activePlatformIds: [],
    activeRegionIds: [],
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

  it("shows the recent window beside the kicker, without evaluation copy", () => {
    renderShell({ status: "ready", model: viewModel(), isRefreshing: false, isPlaceholderData: false });

    expect(screen.getByText("Del 13 de febrero de 2026 al 13 de agosto de 2026")).toBeInTheDocument();
    expect(screen.getByText("Del 13/02/2026 al 13/08/2026")).toBeInTheDocument();
    expect(screen.getByText("Ya disponibles").parentElement).toContainElement(
      screen.getByText("Del 13 de febrero de 2026 al 13 de agosto de 2026"),
    );
    expect(screen.queryByText("Ventana evaluada el 13 de agosto de 2026")).not.toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("1 juego · Página 1 de 1");

    const results = within(screen.getByRole("list", { name: "Lanzamientos recientes" }));
    const card = within(results.getAllByRole("listitem")[0] as HTMLElement);
    expect(card.getByRole("heading", { level: 3, name: "Pragmata" })).toBeInTheDocument();
    // The compact visible row shows the first release's date, its grouped platforms and region.
    expect(card.getByText("Windows PC · Mundial")).toBeInTheDocument();
    expect(card.getByText("2.º trimestre de 2026")).toBeInTheDocument();
    expect(card.queryByRole("button", { name: /lanzamientos más/ })).not.toBeInTheDocument();
    expect(card.queryByText("Publicado")).not.toBeInTheDocument();
    expect(card.queryByText(/Fuente:/)).not.toBeInTheDocument();
    expect(card.queryByText("Ver ficha →")).not.toBeInTheDocument();
    expect(card.getByRole("link", { name: "Pragmata" })).toHaveAttribute(
      "href",
      "/games/game-pragmata/pragmata",
    );
    expect(card.getByRole("img", { name: "Portada no disponible de Pragmata" })).toHaveAttribute(
      "src",
      "/assets/covers/fallback.svg",
    );
    expect(card.queryByText("Carátula oficial no disponible")).not.toBeInTheDocument();
  });

  it("keeps a selected filter checked and representable with a clear action", async () => {
    const user = userEvent.setup();
    const activeSearch: ReleasesSearch = { ...search, platformIds: ["platform-ps5"] };
    renderShell(
      {
        status: "ready",
        model: viewModel({ activePlatformIds: ["platform-ps5"], items: [], staleItemCount: 0 }),
        isRefreshing: false,
        isPlaceholderData: false,
      },
      { search: activeSearch },
    );

    // The closed selector summarises the selection; Región stays unfiltered ("Todas").
    const platform = screen.getByRole("combobox", { name: /Plataforma/ });
    expect(platform).toHaveTextContent("PlayStation 5");
    expect(screen.getByRole("combobox", { name: /Región/ })).toHaveTextContent("Todas");

    // Opened, the selected value is exposed as selected and remains representable to unselect.
    await user.click(platform);
    expect(screen.getByRole("option", { name: "PlayStation 5" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(screen.getByRole("option", { name: "Windows PC" })).toHaveAttribute(
      "aria-selected",
      "false",
    );

    expect(
      screen.getByText("Ningún lanzamiento del catálogo local coincide con esta ventana y estos filtros."),
    ).toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: "Quitar filtros" })[0]).toHaveAttribute("href", "/?weeks=1");
  });

  it("gives upcoming the same hero, selectors and closing bar", () => {
    renderShell(
      { status: "ready", model: viewModel({ view: "upcoming", windowDescription: "Del 13 de agosto de 2026 al 13 de febrero de 2027" }), isRefreshing: false, isPlaceholderData: false },
      { search: { ...search, view: "upcoming" } },
    );

    expect(screen.getByRole("heading", { level: 1, name: "Próximos lanzamientos" })).toBeInTheDocument();
    expect(screen.getByText("En calendario").parentElement).toContainElement(
      screen.getByText("Del 13 de agosto de 2026 al 13 de febrero de 2027"),
    );
    expect(screen.queryByText("Ventana evaluada el 13 de agosto de 2026")).not.toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: /Plataforma/ })).toHaveTextContent("Todas");
    expect(screen.getByRole("combobox", { name: /Región/ })).toHaveTextContent("Todas");
    expect(screen.getByRole("status")).toHaveTextContent("1 juego · Página 1 de 1");
    expect(screen.queryByRole("navigation", { name: "Ventana de lanzamientos" })).not.toBeInTheDocument();
  });

  it("distinguishes stale local data from a technical failure", () => {
    renderShell({
      status: "ready",
      model: viewModel({
        items: [{ ...pragmata, isStale: true }],
        staleItemCount: 1,
      }),
      isRefreshing: false,
      isPlaceholderData: false,
    });

    expect(screen.queryByText(
      "Algunos lanzamientos usan la última copia local guardada y pueden estar desactualizados.",
    )).not.toBeInTheDocument();
    expect(screen.queryByText("Datos locales desactualizados")).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Pragmata" })).toBeInTheDocument();
  });

  it("explains an unavailable catalogue and offers a retry", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderShell({ status: "catalogue-not-ready" }, { onRetry });

    const alert = within(screen.getByRole("status"));
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
      { search: { ...search, platformIds: ["platform-unknown"] } },
    );

    const alert = within(screen.getByRole("alert"));
    expect(alert.getByRole("heading", { name: "Filtro no admitido" })).toBeInTheDocument();
    expect(alert.getByRole("link", { name: "Quitar filtros" })).toHaveAttribute("href", "/?weeks=1");
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
    expect(screen.getByRole("combobox", { name: /^Periodo:/ })).toHaveTextContent("1 semana");
    expect(screen.queryByText("Del 13 de febrero de 2026 al 13 de agosto de 2026"))
      .not.toBeInTheDocument();
  });

  it("paginates with keyboard-reachable links that preserve the active filters", () => {
    const paginatedSearch: ReleasesSearch = { ...search, page: 2, platformIds: ["platform-ps5"] };
    renderShell(
      {
        status: "ready",
        model: viewModel({
          activePlatformIds: ["platform-ps5"],
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
    expect(screen.getByRole("status")).toHaveTextContent("15 juegos · Página 2 de 3");
    expect(pagination.queryByText("Página 2 de 3")).not.toBeInTheDocument();
    expect(pagination.getByRole("link", { name: "Página anterior" })).toHaveAttribute(
      "href",
      "/?weeks=1&platformIds=platform-ps5",
    );
    expect(pagination.getByRole("link", { name: "Página siguiente" })).toHaveAttribute(
      "href",
      "/?weeks=1&platformIds=platform-ps5&page=3",
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

    expect(
      screen.getByText("2 juegos · La página 99 ya no está disponible"),
    ).toBeInTheDocument();
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
      "/?weeks=1&page=2",
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
    expect(screen.getByRole("link", { name: "Pragmata" })).toBeInTheDocument();
  });
});
