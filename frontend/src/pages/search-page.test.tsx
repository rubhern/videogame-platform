import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { components } from "../shared/api/generated/schema";
import { renderApp } from "../test/render-app";

type GameSearchPage = components["schemas"]["GameSearchPage"];

const resultPage = {
  items: [
    {
      gameId: "30000000-0000-4000-8000-000000000008",
      slug: "the-witcher-iv",
      canonicalTitle: "The Witcher IV",
      matchedAlias: "The Witcher 4",
      primaryCover: {
        kind: "fallback",
        url: "/assets/covers/fallback.svg",
        alternativeText: "Portada no disponible de The Witcher IV",
        attribution: null,
      },
      releaseContext: [
        {
          platform: { platformId: "platform-ps5", name: "PlayStation 5" },
          region: { regionId: "region-europe", name: "Europe" },
          releaseDate: { precision: "year", value: "2027" },
          status: "announced",
          freshnessStatus: "fresh",
        },
      ],
    },
  ],
  page: { number: 1, size: 6, totalItems: 1, totalPages: 1 },
} as const satisfies GameSearchPage;

const emptyPage = {
  items: [],
  page: { number: 1, size: 6, totalItems: 0, totalPages: 0 },
} as const satisfies GameSearchPage;

function stubSearch(handler: (url: URL) => Response) {
  const fetchMock = vi.fn<typeof fetch>().mockImplementation(async (input) => {
    const url = new URL(input instanceof Request ? input.url : String(input));
    return handler(url);
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("catalogue search page", () => {
  beforeEach(() => {
    stubSearch(() => Response.json(resultPage, { status: 200 }));
  });

  it("does not query the catalogue until the visitor searches", async () => {
    const fetchMock = stubSearch(() => Response.json(resultPage, { status: 200 }));
    renderApp("/search");

    expect(
      await screen.findByText(
        "Escribe un título o un título alternativo aprobado para buscar en el catálogo.",
      ),
    ).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("searches the local catalogue from the URL and shows the match context", async () => {
    const fetchMock = stubSearch(() => Response.json(resultPage, { status: 200 }));
    renderApp("/search?q=the+witcher+4");

    expect(await screen.findByRole("heading", { level: 3, name: "The Witcher IV" }))
      .toBeInTheDocument();
    expect(screen.getByText(/Coincide con el título alternativo/)).toHaveTextContent(
      "The Witcher 4",
    );
    const request = fetchMock.mock.calls[0]?.[0];
    expect(new URL((request as Request).url).pathname).toBe("/api/v1/games");
    expect(new URL((request as Request).url).searchParams.get("q")).toBe("the witcher 4");
  });

  it("makes a submitted search shareable through the URL", async () => {
    const user = userEvent.setup();
    stubSearch(() => Response.json(resultPage, { status: 200 }));
    const { router } = renderApp("/search");

    await user.type(
      await screen.findByRole("searchbox", { name: "Buscar en el catálogo" }),
      "the witcher 4",
    );
    await user.click(screen.getByRole("button", { name: "Buscar" }));

    await waitFor(() => {
      expect(router.state.location.search).toBe("?q=the+witcher+4");
    });
    expect(await screen.findByRole("heading", { level: 3, name: "The Witcher IV" }))
      .toBeInTheDocument();
  });

  it("presents a title outside the bounded catalogue as a zero-result search", async () => {
    stubSearch(() => Response.json(emptyPage, { status: 200 }));
    renderApp("/search?q=elden+ring");

    expect(await screen.findByText(/Ningún juego del catálogo local coincide con/))
      .toHaveTextContent("elden ring");
  });

  it("reports an invalid query with the stable contract code", async () => {
    stubSearch(() =>
      Response.json(
        {
          type: "urn:videogame-platform:problem:search-query-invalid",
          title: "Search query is invalid",
          status: 422,
          detail: "Supply a non-blank query of at most 100 Unicode code points.",
          instance: "urn:videogame-platform:problem-instance:test",
          code: "SEARCH_QUERY_INVALID",
          category: "validation",
          correlationId: "correlation-test",
        },
        { status: 422, headers: { "Content-Type": "application/problem+json" } },
      ),
    );
    renderApp("/search?q=!!!");

    expect(await screen.findByRole("heading", { name: "La búsqueda no es válida" }))
      .toBeInTheDocument();
  });

  it("reports an unready catalogue separately from a technical failure", async () => {
    stubSearch(() =>
      Response.json(
        {
          type: "urn:videogame-platform:problem:catalogue-not-ready",
          title: "Catalogue is not ready",
          status: 503,
          detail: "No local catalogue snapshot is available.",
          instance: "urn:videogame-platform:problem-instance:test",
          code: "CATALOGUE_NOT_READY",
          category: "technical",
          correlationId: "correlation-test",
        },
        { status: 503, headers: { "Content-Type": "application/problem+json" } },
      ),
    );
    renderApp("/search?q=evil");

    expect(
      await screen.findByRole("heading", { name: "El catálogo todavía no está disponible" }),
    ).toBeInTheDocument();
  });

  it("reports a technical failure with its correlation reference", async () => {
    stubSearch(
      () => new Response("", { status: 500, headers: { "X-Correlation-ID": "correlation-500" } }),
    );
    renderApp("/search?q=evil");

    expect(await screen.findByRole("heading", { name: "No se pudo completar la búsqueda" }))
      .toBeInTheDocument();
    expect(screen.getByText("Referencia para soporte: correlation-500")).toBeInTheDocument();
  });

  it("rejects a query beyond the contract bound without calling the API", async () => {
    const fetchMock = stubSearch(() => Response.json(resultPage, { status: 200 }));
    renderApp(`/search?q=${"a".repeat(101)}`);

    expect(await screen.findByRole("heading", { name: "La búsqueda no es válida" }))
      .toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("lets the visitor retry after a network failure without an empty support reference", async () => {
    const user = userEvent.setup();
    const fetchMock = stubSearch(() => {
      throw new TypeError("offline");
    });
    renderApp("/search?q=the+witcher+4");

    expect(await screen.findByRole("alert")).not.toHaveTextContent("Referencia para soporte:");
    fetchMock.mockResolvedValue(Response.json(resultPage));
    await user.click(screen.getByRole("button", { name: "Reintentar" }));
    expect(await screen.findByRole("heading", { name: "The Witcher IV" })).toBeInTheDocument();
  });

  it("moves focus to the results after a new search so keyboard users follow the change", async () => {
    const user = userEvent.setup();
    stubSearch(() => Response.json(resultPage, { status: 200 }));
    renderApp("/search");

    await user.type(
      await screen.findByRole("searchbox", { name: "Buscar en el catálogo" }),
      "the witcher 4",
    );
    await user.click(screen.getByRole("button", { name: "Buscar" }));

    await waitFor(() => {
      expect(screen.getByRole("heading", { level: 2, name: "Resultados" })).toHaveFocus();
    });
  });
});
