import { describe, expect, it, vi } from "vitest";

import type { components } from "../../shared/api/generated/schema";
import { createProductApiClient } from "../../shared/api/product-api-client";
import { GameSearchApiError, searchGames } from "./game-search-api";

const emptyPage = {
  items: [],
  page: { number: 1, size: 6, totalItems: 0, totalPages: 0 },
} as const satisfies components["schemas"]["GameSearchPage"];

function clientReturning(response: Response) {
  const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(response);
  return {
    fetchMock,
    client: createProductApiClient({ baseUrl: "http://localhost/api/v1", fetch: fetchMock }),
  };
}

describe("catalogue search product API", () => {
  it("normalizes network failures without inventing an HTTP status or correlation ID", async () => {
    const client = createProductApiClient({
      baseUrl: "http://localhost/api/v1",
      fetch: vi.fn<typeof fetch>().mockRejectedValue(new TypeError("offline")),
    });

    await expect(searchGames({ q: "evil" }, client)).rejects.toMatchObject({
      name: "GameSearchApiError",
      code: "GAME_SEARCH_REQUEST_FAILED",
      status: null,
      correlationId: null,
    });
  });

  it("calls GET /games through the typed product transport with bounded parameters", async () => {
    const { client, fetchMock } = clientReturning(Response.json(emptyPage, { status: 200 }));

    const result = await searchGames({ q: "resident evil", page: 1, pageSize: 6 }, client);

    expect(result).toEqual(emptyPage);
    const request = fetchMock.mock.calls[0]?.[0];
    expect(request).toBeInstanceOf(Request);
    expect((request as Request).url).toBe(
      "http://localhost/api/v1/games?q=resident%20evil&page=1&pageSize=6",
    );
    expect((request as Request).credentials).toBe("same-origin");
  });

  it("treats a zero-result page as a success rather than a failure", async () => {
    const { client } = clientReturning(Response.json(emptyPage, { status: 200 }));

    await expect(searchGames({ q: "elden ring", page: 1, pageSize: 6 }, client)).resolves.toEqual(
      emptyPage,
    );
  });

  it("preserves stable Problem Details semantics for the UI", async () => {
    const { client } = clientReturning(
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

    await expect(searchGames({ q: "!!!", page: 1, pageSize: 6 }, client)).rejects.toEqual(
      expect.objectContaining<Partial<GameSearchApiError>>({
        code: "SEARCH_QUERY_INVALID",
        correlationId: "correlation-test",
        status: 422,
      }),
    );
  });

  it("falls back to the correlation header when no Problem body is returned", async () => {
    const { client } = clientReturning(
      new Response("", { status: 500, headers: { "X-Correlation-ID": "correlation-header" } }),
    );

    await expect(searchGames({ q: "evil", page: 1, pageSize: 6 }, client)).rejects.toEqual(
      expect.objectContaining<Partial<GameSearchApiError>>({
        code: "GAME_SEARCH_REQUEST_FAILED",
        correlationId: "correlation-header",
        status: 500,
      }),
    );
  });
});
