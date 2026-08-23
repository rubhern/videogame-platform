import { describe, expect, it, vi } from "vitest";

import type { components } from "../../shared/api/generated/schema";
import { createProductApiClient } from "../../shared/api/product-api-client";
import { ReleasesApiError, getReleases } from "./releases-api";

const releasePageResponse = {
  view: "recent",
  evaluatedOn: "2026-08-13",
  window: { from: "2026-02-13", to: "2026-08-13" },
  activeFilters: { platformId: null, regionId: null },
  availableFilters: { platforms: [], regions: [] },
  items: [],
  page: { number: 1, size: 6, totalItems: 0, totalPages: 0 },
} as const satisfies components["schemas"]["ReleasePage"];

describe("releases product API", () => {
  it("calls GET /releases through the typed product transport with bounded parameters", async () => {
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(
      Response.json(releasePageResponse, { status: 200 }),
    );
    const client = createProductApiClient({
      baseUrl: "http://localhost/api/v1",
      fetch: fetchMock,
    });

    const result = await getReleases(
      { view: "recent", page: 1, pageSize: 6 },
      client,
    );

    expect(result).toEqual(releasePageResponse);
    expect(fetchMock).toHaveBeenCalledOnce();
    const request = fetchMock.mock.calls[0]?.[0];
    expect(request).toBeInstanceOf(Request);
    expect((request as Request).url).toBe(
      "http://localhost/api/v1/releases?view=recent&page=1&pageSize=6",
    );
    expect((request as Request).credentials).toBe("same-origin");
  });

  it("preserves stable Problem Details semantics for the UI", async () => {
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(
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
    const client = createProductApiClient({
      baseUrl: "http://localhost/api/v1",
      fetch: fetchMock,
    });

    await expect(
      getReleases({ view: "recent", page: 1, pageSize: 6 }, client),
    ).rejects.toEqual(
      expect.objectContaining<Partial<ReleasesApiError>>({
        code: "CATALOGUE_NOT_READY",
        correlationId: "correlation-test",
        status: 503,
      }),
    );
  });
});
