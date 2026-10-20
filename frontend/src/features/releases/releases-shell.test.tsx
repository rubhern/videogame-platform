import { act, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";

import type { DayRange } from "./day-bar";
import { ReleasesShell, type ReleasesShellState } from "./releases-shell";
import { defaultReleasesSearch, type ReleasesSearch } from "./releases-search";
import type { ReleaseListItem, ReleasesViewModel } from "./releases-view-model";

const search: ReleasesSearch = defaultReleasesSearch;

const pragmata: ReleaseListItem = {
  scores: [],
  gameId: "game-pragmata",
  genres: [],
  slug: "pragmata",
  title: "Pragmata",
  releaseGroups: [
    {
      key: "day|2026-08-12|worldwide",
      date: "12 de agosto de 2026",
      shortDate: "12 ago 2026",
      day: "2026-08-12",
      region: "Mundial",
      platforms: ["Windows PC"],
      platformIds: ["windows-pc"],
      review: false,
      releaseCount: 1,
    },
  ],
  hiddenReleaseCount: 0,
  cover: {
    kind: "fallback",
    url: "/assets/covers/fallback.svg",
    alternativeText: "Portada no disponible de Pragmata",
  },
};

/** The current week of 13 August 2026, a Thursday. */
function viewModel(overrides: Partial<ReleasesViewModel> = {}): ReleasesViewModel {
  return {
    window: { from: "2026-08-10", to: "2026-08-16" },
    evaluatedOn: "2026-08-13",
    windowDescription: "Del 10 de agosto de 2026 al 16 de agosto de 2026",
    windowRange: "10–16 ago 2026",
    platforms: [
      { id: "platform-pc", name: "Windows PC" },
      { id: "platform-ps5", name: "PlayStation 5" },
    ],
    regions: [{ id: "region-worldwide", name: "Mundial" }],
    activePlatformIds: [],
    activeRegionIds: [],
    items: [pragmata],
    page: { number: 1, size: 12, totalItems: 1, totalPages: 1 },
    ...overrides,
  };
}

function ready(overrides: Partial<ReleasesViewModel> = {}): ReleasesShellState {
  return { status: "ready", model: viewModel(overrides), isRefreshing: false, isPlaceholderData: false };
}

/** Prints where the shell navigated and the history state it left. */
function LocationProbe() {
  const location = useLocation();
  return (
    <div data-testid="location">
      {`${location.pathname}${location.search}|${JSON.stringify(location.state ?? null)}`}
    </div>
  );
}

function renderShell(
  state: ReleasesShellState,
  options: { search?: ReleasesSearch; onRetry?: () => void; keptFrame?: DayRange | null } = {},
) {
  const onRetry = options.onRetry ?? vi.fn();
  const result = render(
    <MemoryRouter initialEntries={["/lanzamientos"]}>
      <Routes>
        <Route
          element={
            <>
              <ReleasesShell
                keptFrame={options.keptFrame ?? null}
                onRetry={onRetry}
                search={options.search ?? search}
                state={state}
              />
              <LocationProbe />
            </>
          }
          path="*"
        />
      </Routes>
    </MemoryRouter>,
  );
  return { ...result, onRetry };
}

const location = () => screen.getByTestId("location").textContent ?? "";

describe("release explorer shell", () => {
  it("opens as Explorar lanzamientos without the former windows or context lines", () => {
    renderShell({ status: "loading" });

    expect(screen.getByRole("region", { name: "Explorar lanzamientos" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1, name: "Explorar lanzamientos" })).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Cargando lanzamientos");
    expect(screen.queryByRole("navigation", { name: "Ventana de lanzamientos" })).not.toBeInTheDocument();
    expect(screen.queryByText("Ya disponibles")).not.toBeInTheDocument();
    expect(screen.queryByText("En calendario")).not.toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: /Periodo/ })).not.toBeInTheDocument();
  });

  it("shows the evaluated range, its cards and the result total", () => {
    renderShell(ready());

    expect(screen.getByText("10–16 ago 2026")).toBeInTheDocument();
    expect(screen.getByText("Del 10 de agosto de 2026 al 16 de agosto de 2026")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("1 juego · Página 1 de 1");
    const results = within(screen.getByRole("list", { name: "Explorar lanzamientos" }));
    const card = within(results.getAllByRole("listitem")[0] as HTMLElement);
    expect(card.getByRole("heading", { level: 3, name: "Pragmata" })).toBeInTheDocument();
    expect(card.getByRole("link", { name: "Pragmata" })).toHaveAttribute(
      "href",
      "/games/game-pragmata/pragmata",
    );
    expect(screen.getByLabelText("Desde")).toHaveValue("2026-08-10");
    expect(screen.getByLabelText("Hasta")).toHaveValue("2026-08-16");
  });

  it("makes every day of the bar a button that explores that day alone", async () => {
    const user = userEvent.setup();
    renderShell(ready());

    const days = within(screen.getByRole("toolbar", { name: "Elegir un día del periodo" }));
    const buttons = days.getAllByRole("button");
    expect(buttons).toHaveLength(7);
    expect(buttons[0]).toHaveAccessibleName("lunes, 10 de agosto de 2026, en el periodo");
    expect(buttons[3]).toHaveAccessibleName("jueves, 13 de agosto de 2026, hoy, en el periodo");
    // One tab stop: today, inside the explored week.
    expect(buttons.filter((button) => button.tabIndex === 0)).toEqual([buttons[3]]);

    await user.click(days.getByRole("button", { name: /viernes, 14 de agosto/ }));

    expect(location()).toBe(
      '/lanzamientos?from=2026-08-14&to=2026-08-14|{"dayFrame":{"from":"2026-08-10","to":"2026-08-16"}}',
    );
  });

  it("highlights one selected day inside the frame it was chosen from and offers the whole period", async () => {
    const user = userEvent.setup();
    renderShell(
      ready({
        window: { from: "2026-08-14", to: "2026-08-14" },
        windowRange: "14 ago 2026",
        windowDescription: "El 14 de agosto de 2026",
      }),
      {
        search: { ...search, from: "2026-08-14", to: "2026-08-14" },
        keptFrame: { from: "2026-08-01", to: "2026-08-31" },
      },
    );

    const days = within(screen.getByRole("toolbar", { name: "Elegir un día del periodo" }));
    expect(days.getAllByRole("button")).toHaveLength(31);
    const selected = days.getByRole("button", { name: "viernes, 14 de agosto de 2026" });
    expect(selected).toHaveAttribute("aria-pressed", "true");
    expect(selected.tabIndex).toBe(0);
    expect(days.getByRole("button", { name: /^sábado, 1 de agosto/ })).toHaveAttribute(
      "aria-pressed",
      "false",
    );

    await user.click(screen.getByRole("button", { name: /Ver todo el periodo/ }));
    expect(location()).toBe(
      '/lanzamientos?from=2026-08-01&to=2026-08-31|{"dayFrame":{"from":"2026-08-01","to":"2026-08-31"}}',
    );
  });

  it("moves between days with the arrow keys, Home and End, without exploring", async () => {
    const user = userEvent.setup();
    renderShell(ready());
    const days = within(screen.getByRole("toolbar", { name: "Elegir un día del periodo" }));

    const today = days.getByRole("button", { name: /jueves, 13 de agosto/ });
    act(() => today.focus());
    await user.keyboard("{ArrowRight}");
    expect(days.getByRole("button", { name: /viernes, 14 de agosto/ })).toHaveFocus();
    await user.keyboard("{Home}");
    expect(days.getByRole("button", { name: /lunes, 10 de agosto/ })).toHaveFocus();
    await user.keyboard("{ArrowLeft}");
    expect(days.getByRole("button", { name: /lunes, 10 de agosto/ })).toHaveFocus();
    await user.keyboard("{End}");
    const sunday = days.getByRole("button", { name: /domingo, 16 de agosto/ });
    expect(sunday).toHaveFocus();
    expect(sunday.tabIndex).toBe(0);
    expect(location()).toBe("/lanzamientos|null");

    await user.keyboard("{Enter}");
    expect(location()).toMatch(/^\/lanzamientos\?from=2026-08-16&to=2026-08-16\|/);
  });

  it("applies a typed range only when the API would accept it", async () => {
    const user = userEvent.setup();
    renderShell(ready());
    const from = screen.getByLabelText("Desde");
    const to = screen.getByLabelText("Hasta");

    await user.clear(from);
    await user.type(from, "2026-08-31");
    await user.clear(to);
    await user.type(to, "2026-08-01");
    await user.click(screen.getByRole("button", { name: "Aplicar" }));
    expect(screen.getByRole("alert")).toHaveTextContent("El día de fin no puede ser anterior al de inicio");
    expect(from).toHaveAttribute("aria-invalid", "true");
    expect(location()).toBe("/lanzamientos|null");

    await user.clear(to);
    await user.type(to, "2026-12-31");
    await user.click(screen.getByRole("button", { name: "Aplicar" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Elige como máximo 92 días");

    await user.clear(from);
    await user.type(from, "2026-12-01");
    await user.click(screen.getByRole("button", { name: "Aplicar" }));
    // A typed range draws its own frame, so it keeps no history state.
    expect(location()).toBe("/lanzamientos?from=2026-12-01&to=2026-12-31|null");
  });

  it("returns a named range to the current week", () => {
    renderShell(
      ready({ window: { from: "2026-07-01", to: "2026-07-31" } }),
      { search: { ...search, from: "2026-07-01", to: "2026-07-31", sort: "hype" } },
    );

    expect(screen.getByRole("link", { name: "Esta semana" })).toHaveAttribute(
      "href",
      "/lanzamientos?sort=hype",
    );
  });

  it("orders by date, Hype or rating and returns to the first page", async () => {
    const user = userEvent.setup();
    renderShell(ready(), { search: { ...search, page: 3 } });
    const order = screen.getByRole("combobox", { name: /Ordenar por/ });
    expect(order).toHaveTextContent("Fecha de lanzamiento");

    await user.click(order);
    expect(screen.getAllByRole("option")).toHaveLength(4);
    expect(screen.getByRole("option", { name: "Jugadores IGDB" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "Fecha de lanzamiento" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(screen.getByRole("option", { name: "Puntuación Gameómetro" })).toBeInTheDocument();
    await user.click(screen.getByRole("option", { name: "Hype" }));

    expect(location()).toMatch(/^\/lanzamientos\?sort=hype\|/);
  });

  it("offers approximate dates only while the range reaches beyond today", () => {
    const { unmount } = renderShell(ready());
    expect(screen.getByRole("checkbox", { name: "Incluir fechas aproximadas" })).not.toBeChecked();
    unmount();

    // Wholly before today, or ending today: nothing approximate is still to come.
    const past = renderShell(
      ready({ window: { from: "2026-08-01", to: "2026-08-13" } }),
      { search: { ...search, from: "2026-08-01", to: "2026-08-13", includeApproximateDates: true } },
    );
    expect(screen.queryByRole("checkbox", { name: "Incluir fechas aproximadas" })).not.toBeInTheDocument();
    past.unmount();

    renderShell(ready(), { search: { ...search, includeApproximateDates: true } });
    expect(screen.getByRole("checkbox", { name: "Incluir fechas aproximadas" })).toBeChecked();
  });

  it("toggles approximate dates in the URL and drops them when a day before today is chosen", async () => {
    const user = userEvent.setup();
    const { unmount } = renderShell(ready());
    await user.click(screen.getByRole("checkbox", { name: "Incluir fechas aproximadas" }));
    expect(location()).toMatch(/^\/lanzamientos\?includeApproximateDates=true\|/);
    unmount();

    renderShell(ready(), { search: { ...search, includeApproximateDates: true } });
    await user.click(screen.getByRole("button", { name: /martes, 11 de agosto/ }));
    expect(location()).toMatch(/^\/lanzamientos\?from=2026-08-11&to=2026-08-11\|/);
  });

  it("folds the controls under one toggle that states the order, filters and precision", async () => {
    const user = userEvent.setup();
    renderShell(ready({ activePlatformIds: ["platform-ps5"] }), {
      search: { ...search, sort: "hype", platformIds: ["platform-ps5"], regionIds: ["region-worldwide"], includeApproximateDates: true },
    });

    // Phones show only this toggle until it opens; wider screens hide it and show the controls.
    const toggle = screen.getByRole("button", { name: /^Filtros y orden/ });
    expect(toggle).toHaveAccessibleName("Filtros y orden: Hype · 2 filtros · Fechas aproximadas");
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    const controls = document.getElementById(toggle.getAttribute("aria-controls") ?? "");
    expect(controls).toContainElement(screen.getByLabelText("Desde"));
    expect(controls).not.toHaveClass("releases-controls-open");

    await user.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    expect(controls).toHaveClass("releases-controls-open");
  });

  it("keeps an active approximate-date choice removable when the read fails", () => {
    renderShell(
      {
        status: "error",
        message: "A Gameómetro le está costando un poco. Prueba más tarde",
        correlationId: null,
      },
      { search: { ...search, includeApproximateDates: true } },
    );

    expect(screen.getByRole("checkbox", { name: "Incluir fechas aproximadas" })).toBeEnabled();
  });

  it("keeps a selected filter checked and representable with a clear action", async () => {
    const user = userEvent.setup();
    renderShell(ready({ activePlatformIds: ["platform-ps5"], items: [] }), {
      search: { ...search, platformIds: ["platform-ps5"], sort: "rating" },
    });

    const platform = screen.getByRole("combobox", { name: /Plataforma/ });
    expect(platform).toHaveTextContent("PlayStation 5");
    expect(screen.getByRole("combobox", { name: /Región/ })).toHaveTextContent("Todas");
    await user.click(platform);
    expect(screen.getByRole("option", { name: "PlayStation 5" })).toHaveAttribute("aria-selected", "true");

    expect(
      screen.getByText("No tenemos lanzamientos para estos filtros, pero afinando la búsqueda seguro que los encontramos"),
    ).toBeInTheDocument();
    for (const reset of screen.getAllByRole("link", { name: "Quitar filtros" })) {
      expect(reset).toHaveAttribute("href", "/lanzamientos?sort=rating");
    }
  });

  it("explains an unavailable catalogue and offers a retry", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderShell({ status: "catalogue-not-ready" }, { onRetry });

    const alert = within(screen.getByRole("status"));
    expect(alert.getByRole("heading", { name: "Los juegos todavía no están disponibles" })).toBeInTheDocument();
    await user.click(alert.getByRole("button", { name: "Reintentar" }));
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it("separates an unsupported filter from a generic failure", () => {
    renderShell(
      { status: "unsupported-filters", message: "¿Qué plataforma es esa? A Gameómetro no le suena de nada" },
      { search: { ...search, platformIds: ["platform-unknown"] } },
    );

    const alert = within(screen.getByRole("alert"));
    expect(alert.getByRole("heading", { name: "Filtro no admitido" })).toBeInTheDocument();
    expect(alert.getByRole("link", { name: "Quitar filtros" })).toHaveAttribute("href", "/lanzamientos");
  });

  it("reports a generic failure with its support reference and no stale range", () => {
    renderShell({
      status: "error",
      message: "A Gameómetro le está costando un poco. Prueba más tarde",
      correlationId: "correlation-1",
    });

    const alert = within(screen.getByRole("alert"));
    expect(alert.getByRole("heading", { name: "No se pudieron cargar los lanzamientos" })).toBeInTheDocument();
    expect(alert.getByText("Referencia para soporte: correlation-1")).toBeInTheDocument();
    expect(screen.queryByRole("list", { name: "Explorar lanzamientos" })).not.toBeInTheDocument();
    expect(screen.queryByRole("toolbar")).not.toBeInTheDocument();
  });

  it("paginates with links that keep the range, order, filters and day frame", () => {
    renderShell(
      ready({
        window: { from: "2026-08-01", to: "2026-08-31" },
        activePlatformIds: ["platform-ps5"],
        page: { number: 2, size: 12, totalItems: 30, totalPages: 3 },
      }),
      {
        search: {
          ...search,
          from: "2026-08-01",
          to: "2026-08-31",
          sort: "hype",
          page: 2,
          platformIds: ["platform-ps5"],
        },
      },
    );

    const pagination = within(screen.getByRole("navigation", { name: "Paginación de lanzamientos" }));
    expect(screen.getByRole("status")).toHaveTextContent("30 juegos · Página 2 de 3");
    expect(pagination.getByRole("link", { name: "Página anterior" })).toHaveAttribute(
      "href",
      "/lanzamientos?from=2026-08-01&to=2026-08-31&sort=hype&platformIds=platform-ps5",
    );
    expect(pagination.getByRole("link", { name: "Página siguiente" })).toHaveAttribute(
      "href",
      "/lanzamientos?from=2026-08-01&to=2026-08-31&sort=hype&platformIds=platform-ps5&page=3",
    );
  });

  it("recovers directly when a shared page is beyond the last available page", () => {
    renderShell(ready({ items: [], page: { number: 99, size: 12, totalItems: 2, totalPages: 1 } }), {
      search: { ...search, page: 99 },
    });

    expect(screen.getByText("2 juegos · La página 99 ya no está disponible")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Ir a la última página" })).toHaveAttribute(
      "href",
      "/lanzamientos",
    );
  });

  it("announces a refresh instead of hiding the visible results", () => {
    renderShell({ ...ready(), isRefreshing: true } as ReleasesShellState);

    expect(screen.getByRole("status")).toHaveTextContent("Actualizando lanzamientos…");
    expect(screen.getByRole("link", { name: "Pragmata" })).toBeInTheDocument();
  });
});
