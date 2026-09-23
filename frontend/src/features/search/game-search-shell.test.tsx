import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";

import type { GameSearchParams } from "./game-search-params";
import { GameSearchShell, type GameSearchShellState } from "./game-search-shell";
import type { GameSearchResult, GameSearchViewModel } from "./game-search-view-model";

const params: GameSearchParams = { query: "resident evil", page: 1, pageSize: 6 };

const requiem: GameSearchResult = {
  gameId: "game-resident-evil-requiem",
  slug: "resident-evil-requiem",
  title: "Resident Evil Requiem",
  matchedAlias: null,
  cover: {
    kind: "provider",
    url: "https://images.igdb.com/igdb/image/upload/t_cover_big_2x/co1.webp",
    alternativeText: "Carátula de Resident Evil Requiem",
    attribution: { label: "IGDB", sourceUrl: "https://www.igdb.com/games/resident-evil-requiem" },
  },
  platforms: [{ id: "platform-ps5", name: "PlayStation 5", icon: "playstation-5" }],
  hiddenPlatformCount: 0,
  year: "2026",
};

function viewModel(overrides: Partial<GameSearchViewModel> = {}): GameSearchViewModel {
  return {
    results: [requiem],
    page: { number: 1, size: 6, totalItems: 1, totalPages: 1 },
    ...overrides,
  };
}

function renderShell(
  state: GameSearchShellState,
  options: { params?: GameSearchParams; onRetry?: () => void } = {},
) {
  const onRetry = options.onRetry ?? vi.fn();
  const result = render(
    <MemoryRouter>
      <GameSearchShell onRetry={onRetry} params={options.params ?? params} state={state} />
    </MemoryRouter>,
  );
  return { ...result, onRetry };
}

describe("catalogue search shell", () => {
  it("invites a first search instead of showing an empty result", () => {
    renderShell({ status: "prompt" }, { params: { query: "", page: 1, pageSize: 6 } });

    expect(
      screen.getByText(
        "Escribe un título o un título alternativo aprobado en el buscador de la cabecera.",
      ),
    ).toBeInTheDocument();
    expect(screen.queryByRole("list", { name: "Resultados de la búsqueda" })).not.toBeInTheDocument();
  });

  it("announces that the catalogue is being searched", () => {
    renderShell({ status: "loading" });

    expect(screen.getByRole("status")).toHaveTextContent("Buscando en el catálogo…");
  });

  it("makes the query the page context without the old explanatory copy", () => {
    renderShell({
      status: "ready",
      model: viewModel(),
      isRefreshing: false,
      isPlaceholderData: false,
    });

    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(
      "Resultados para «resident evil»",
    );
    expect(screen.getByText("Catálogo de juegos")).toBeInTheDocument();
    expect(screen.queryByText(/Solo se muestran/)).not.toBeInTheDocument();
    expect(screen.queryByText(/No se consulta ningún proveedor/)).not.toBeInTheDocument();
  });

  it("keeps the generic heading until there is a query to present", () => {
    renderShell({ status: "prompt" }, { params: { query: "", page: 1, pageSize: 6 } });

    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Buscar juegos");
  });

  it("renders a matching game as a compact card that opens its detail", () => {
    renderShell({
      status: "ready",
      model: viewModel(),
      isRefreshing: false,
      isPlaceholderData: false,
    });

    expect(screen.getByRole("status")).toHaveTextContent(
      "1 juego del catálogo local · Página 1 de 1",
    );
    const results = screen.getByRole("list", { name: "Resultados de la búsqueda" });
    const heading = within(results).getByRole("heading", { level: 3, name: "Resident Evil Requiem" });
    expect(within(heading).getByRole("link", { name: "Resident Evil Requiem" })).toHaveAttribute(
      "href",
      "/games/game-resident-evil-requiem/resident-evil-requiem",
    );
    expect(within(results).getByText("2026")).toBeInTheDocument();
    const platforms = within(results).getByRole("list", {
      name: "Plataformas de Resident Evil Requiem",
    });
    expect(within(platforms).getAllByRole("listitem").map((item) => item.textContent)).toEqual([
      "PlayStation 5",
    ]);
    // The game page every cover links to owns the attribution (ADR-0001, GAME-012).
    expect(within(results).queryByRole("link", { name: "IGDB" })).not.toBeInTheDocument();
    expect(within(results).queryByRole("link", { name: /Ver ficha/ })).not.toBeInTheDocument();
  });

  it("folds platforms beyond three into an exact, named overflow", () => {
    renderShell({
      status: "ready",
      model: viewModel({
        results: [
          {
            ...requiem,
            platforms: [
              { id: "p1", name: "PlayStation 5", icon: "playstation-5" },
              { id: "p2", name: "Windows PC", icon: "windows" },
              { id: "p3", name: "Xbox Series X|S", icon: "xbox-series-x-s" },
            ],
            hiddenPlatformCount: 2,
          },
        ],
      }),
      isRefreshing: false,
      isPlaceholderData: false,
    });

    const platforms = screen.getByRole("list", { name: "Plataformas de Resident Evil Requiem" });
    const items = within(platforms).getAllByRole("listitem");
    expect(items).toHaveLength(4);
    expect(items[3]).toHaveTextContent("+2");
    expect(items[3]).toHaveTextContent("2 plataformas más");
  });

  it("states an unknown release year instead of inventing one", () => {
    renderShell({
      status: "ready",
      model: viewModel({
        results: [{ ...requiem, platforms: [], hiddenPlatformCount: 0, year: "Por confirmar" }],
      }),
      isRefreshing: false,
      isPlaceholderData: false,
    });

    expect(screen.getByText("Por confirmar")).toBeInTheDocument();
    expect(screen.queryByRole("list", { name: /Plataformas de/ })).not.toBeInTheDocument();
  });

  it("keeps several matching games as separate results", () => {
    renderShell({
      status: "ready",
      model: viewModel({
        results: [
          { ...requiem, gameId: "game-a", title: "Death Stranding 2: On the Beach" },
          { ...requiem, gameId: "game-b", title: "Subnautica 2" },
        ],
        page: { number: 1, size: 6, totalItems: 2, totalPages: 1 },
      }),
      isRefreshing: false,
      isPlaceholderData: false,
    });

    const headings = within(screen.getByRole("list", { name: "Resultados de la búsqueda" }))
      .getAllByRole("heading", { level: 3 })
      .map((heading) => heading.textContent);
    expect(headings).toEqual(["Death Stranding 2: On the Beach", "Subnautica 2"]);
  });

  it("explains a zero-result search as a bounded-catalogue outcome", () => {
    renderShell(
      {
        status: "ready",
        model: viewModel({
          results: [],
          page: { number: 1, size: 6, totalItems: 0, totalPages: 0 },
        }),
        isRefreshing: false,
        isPlaceholderData: false,
      },
      { params: { query: "elden ring", page: 1, pageSize: 6 } },
    );

    expect(screen.getByText(/Ningún juego del catálogo local coincide con/)).toHaveTextContent(
      "elden ring",
    );
    expect(screen.getByRole("status")).toHaveTextContent("0 juegos del catálogo local");
  });

  it("distinguishes an invalid query from a technical failure", () => {
    renderShell({ status: "query-invalid" });

    const alert = screen.getByRole("alert");
    expect(within(alert).getByRole("heading", { name: "La búsqueda no es válida" }))
      .toBeInTheDocument();
    expect(within(alert).queryByRole("button", { name: "Reintentar" })).not.toBeInTheDocument();
  });

  it("distinguishes a catalogue that is not ready and states no provider is consulted", () => {
    renderShell({ status: "catalogue-not-ready" });

    const alert = screen.getByRole("alert");
    expect(
      within(alert).getByRole("heading", { name: "El catálogo todavía no está disponible" }),
    ).toBeInTheDocument();
    expect(within(alert).getByText(/No se consulta ningún proveedor/)).toBeInTheDocument();
  });

  it("offers an actionable retry that reports the correlation reference", async () => {
    const user = userEvent.setup();
    const { onRetry } = renderShell({
      status: "error",
      message: "No se pudo leer el catálogo local. Inténtalo de nuevo más tarde.",
      correlationId: "correlation-test",
    });

    expect(screen.getByText("Referencia para soporte: correlation-test")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it("keeps pagination navigable and preserves the query", () => {
    renderShell(
      {
        status: "ready",
        model: viewModel({ page: { number: 2, size: 6, totalItems: 20, totalPages: 4 } }),
        isRefreshing: false,
        isPlaceholderData: false,
      },
      { params: { query: "resident evil", page: 2, pageSize: 6 } },
    );

    const pagination = screen.getByRole("navigation", { name: "Paginación de resultados" });
    expect(within(pagination).getByText("Página 2 de 4")).toBeInTheDocument();
    expect(within(pagination).getByRole("link", { name: "Página anterior" })).toHaveAttribute(
      "href",
      "/search?q=resident+evil",
    );
    expect(within(pagination).getByRole("link", { name: "Página siguiente" })).toHaveAttribute(
      "href",
      "/search?q=resident+evil&page=3",
    );
  });

  it("offers a way back when the requested page no longer exists", () => {
    renderShell(
      {
        status: "ready",
        model: viewModel({
          results: [],
          page: { number: 9, size: 6, totalItems: 20, totalPages: 4 },
        }),
        isRefreshing: false,
        isPlaceholderData: false,
      },
      { params: { query: "resident evil", page: 9, pageSize: 6 } },
    );

    expect(screen.getByText("La página solicitada ya no está disponible para esta búsqueda."))
      .toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Ir a la última página" })).toHaveAttribute(
      "href",
      "/search?q=resident+evil&page=4",
    );
  });

});
