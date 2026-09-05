import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";

import type { GameSearchParams } from "./game-search-params";
import { GameSearchShell, type GameSearchShellState } from "./game-search-shell";
import type { GameSearchResult, GameSearchViewModel } from "./game-search-view-model";

const params: GameSearchParams = { query: "resident evil", page: 1, pageSize: 6 };

const staleContext = {
  key: "platform-ps5-region-europe-0",
  platform: "PlayStation 5",
  region: "Europe",
  date: "27 de febrero de 2026",
  status: "Publicado",
  isStale: true,
};

const requiem: GameSearchResult = {
  gameId: "game-resident-evil-requiem",
  slug: "resident-evil-requiem",
  title: "Resident Evil Requiem",
  matchedAlias: null,
  cover: {
    kind: "fallback",
    url: "/assets/covers/fallback.svg",
    alternativeText: "Portada no disponible de Resident Evil Requiem",
  },
  releaseContext: [
    {
      key: "platform-ps5-region-europe-0",
      platform: "PlayStation 5",
      region: "Europe",
      date: "27 de febrero de 2026",
      status: "Publicado",
      isStale: false,
    },
  ],
  hasStaleContext: false,
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
  it("offers an accessible search landmark with a labelled query field", () => {
    renderShell({ status: "prompt" }, { params: { query: "", page: 1, pageSize: 6 } });

    const form = screen.getByRole("search");
    expect(within(form).getByRole("searchbox", { name: "Buscar en el catálogo" })).toBeInTheDocument();
    expect(within(form).getByRole("button", { name: "Buscar" })).toBeInTheDocument();
  });

  it("invites a first search instead of showing an empty result", () => {
    renderShell({ status: "prompt" }, { params: { query: "", page: 1, pageSize: 6 } });

    expect(
      screen.getByText(
        "Escribe un título o un título alternativo aprobado para buscar en el catálogo.",
      ),
    ).toBeInTheDocument();
    expect(screen.queryByRole("list", { name: "Resultados de la búsqueda" })).not.toBeInTheDocument();
  });

  it("announces that the catalogue is being searched", () => {
    renderShell({ status: "loading" });

    expect(screen.getByRole("status")).toHaveTextContent("Buscando en el catálogo…");
  });

  it("renders a matching game with its bounded release context", () => {
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
    expect(within(results).getByRole("heading", { level: 3, name: "Resident Evil Requiem" }))
      .toBeInTheDocument();
    expect(
      within(results).getByRole("link", { name: "Ver Resident Evil Requiem" }),
    ).toHaveAttribute("href", "/games/resident-evil-requiem");
    expect(within(results).getByText("PlayStation 5 · Europe")).toBeInTheDocument();
  });

  it("explains which approved alias produced the match", () => {
    renderShell({
      status: "ready",
      model: viewModel({
        results: [{ ...requiem, matchedAlias: "Biohazard Requiem" }],
      }),
      isRefreshing: false,
      isPlaceholderData: false,
    });

    expect(screen.getByText(/Coincide con el título alternativo/)).toHaveTextContent(
      "Biohazard Requiem",
    );
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

  it("marks a stale release context without hiding the result", () => {
    renderShell({
      status: "ready",
      model: viewModel({
        results: [
          { ...requiem, hasStaleContext: true, releaseContext: [staleContext] },
        ],
      }),
      isRefreshing: false,
      isPlaceholderData: false,
    });

    expect(screen.getByText("Datos locales desactualizados")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 3, name: "Resident Evil Requiem" }))
      .toBeInTheDocument();
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

  it("warns before submitting a query longer than the contract accepts", async () => {
    const user = userEvent.setup();
    renderShell({ status: "prompt" }, { params: { query: "", page: 1, pageSize: 6 } });

    const field = screen.getByRole("searchbox", { name: "Buscar en el catálogo" });
    await user.click(field);
    await user.paste("a".repeat(101));

    expect(field).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByText("Usa como máximo 100 caracteres.")).toBeInTheDocument();
  });
});
