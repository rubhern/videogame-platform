import { describe, expect, it, vi } from "vitest";

import { createProductApiClient } from "../../shared/api/product-api-client";
import {
  deleteMyRating,
  getMyRating,
  putMyRating,
  RatingCommandError,
} from "./personal-rating-api";

const gameId = "30000000-0000-4000-8000-000000000005";
const context = { gameId, csrfToken: "opaque-token" };

const rating = {
  gameId,
  value: 8,
  createdAt: "2026-08-13T10:00:00Z",
  updatedAt: "2026-08-13T10:00:00Z",
  entityTag: '"rating-version-1"',
};
const statistics = {
  status: "available" as const,
  mean: 8,
  count: 1,
  distribution: {
    "1": 0, "2": 0, "3": 0, "4": 0, "5": 0, "6": 0, "7": 0, "8": 1, "9": 0, "10": 0,
  },
};

function client(fetchMock: typeof fetch) {
  return createProductApiClient({
    baseUrl: "http://localhost/api/v1",
    fetch: fetchMock,
  });
}

function problem(status: number, code: string) {
  return new Response(
    JSON.stringify({ code, correlationId: "corr-1", status }),
    {
      status,
      headers: { "Content-Type": "application/problem+json" },
    },
  );
}

async function failureOf(promise: Promise<unknown>) {
  try {
    await promise;
  } catch (error) {
    if (error instanceof RatingCommandError) return error;
    throw error;
  }
  throw new Error("Expected the command to fail.");
}

describe("personal rating API", () => {
  it("reads the current rating and maps scoped absence to null", async () => {
    const found = vi.fn<typeof fetch>().mockResolvedValue(Response.json(rating));
    await expect(getMyRating(gameId, client(found))).resolves.toEqual(rating);
    const request = found.mock.calls[0]?.[0] as Request;
    expect(request.url).toBe(`http://localhost/api/v1/me/ratings/${gameId}`);
    expect(request.credentials).toBe("same-origin");

    const absent = vi
      .fn<typeof fetch>()
      .mockResolvedValue(problem(404, "RATING_NOT_FOUND"));
    await expect(getMyRating(gameId, client(absent))).resolves.toBeNull();
  });

  it("creates with If-None-Match: * and the CSRF token, never If-Match", async () => {
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(
      Response.json(
        { personalRating: rating, ratingStatistics: statistics },
        { status: 201 },
      ),
    );

    const result = await putMyRating(
      context,
      8,
      { create: true },
      client(fetchMock),
    );

    expect(result.personalRating.entityTag).toBe('"rating-version-1"');
    const request = fetchMock.mock.calls[0]?.[0] as Request;
    expect(request.method).toBe("PUT");
    expect(request.headers.get("If-None-Match")).toBe("*");
    expect(request.headers.get("If-Match")).toBeNull();
    expect(request.headers.get("X-CSRF-Token")).toBe("opaque-token");
    await expect(request.json()).resolves.toEqual({ value: 8 });
  });

  it("updates with the current strong If-Match only", async () => {
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(
      Response.json({ personalRating: rating, ratingStatistics: statistics }),
    );

    await putMyRating(
      context,
      9,
      { create: false, entityTag: '"rating-version-1"' },
      client(fetchMock),
    );

    const request = fetchMock.mock.calls[0]?.[0] as Request;
    expect(request.headers.get("If-Match")).toBe('"rating-version-1"');
    expect(request.headers.get("If-None-Match")).toBeNull();
  });

  it("deletes with the current strong If-Match and returns the aggregate", async () => {
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(
      Response.json({ personalRating: null, ratingStatistics: statistics }),
    );

    await expect(
      deleteMyRating(context, '"rating-version-1"', client(fetchMock)),
    ).resolves.toEqual(statistics);

    const request = fetchMock.mock.calls[0]?.[0] as Request;
    expect(request.method).toBe("DELETE");
    expect(request.headers.get("If-Match")).toBe('"rating-version-1"');
    expect(request.headers.get("X-CSRF-Token")).toBe("opaque-token");
  });

  it.each([
    [401, "AUTHENTICATION_REQUIRED", "authentication"],
    [403, "CSRF_VALIDATION_FAILED", "csrf"],
    [412, "RATING_WRITE_CONFLICT", "conflict"],
    [412, "RATING_ALREADY_EXISTS", "conflict"],
    [404, "RATING_NOT_FOUND", "conflict"],
    [422, "RATING_VALUE_INVALID", "validation"],
    [422, "RATING_NOT_ELIGIBLE", "ineligible"],
    [429, "RATE_LIMIT_EXCEEDED", "rate-limited"],
    [500, "RATING_WRITE_FAILED", "unavailable"],
  ] as const)(
    "maps HTTP %s %s to the %s failure without retrying",
    async (status, code, kind) => {
      const fetchMock = vi
        .fn<typeof fetch>()
        .mockResolvedValue(problem(status, code));

      const error = await failureOf(
        putMyRating(context, 8, { create: true }, client(fetchMock)),
      );

      expect(error.kind).toBe(kind);
      expect(error.code).toBe(code);
      expect(error.correlationId).toBe("corr-1");
      expect(fetchMock).toHaveBeenCalledOnce();
    },
  );

  it("reports a transport failure as ambiguous", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockRejectedValue(new TypeError("Failed to fetch"));

    const error = await failureOf(
      deleteMyRating(context, '"rating-version-1"', client(fetchMock)),
    );

    expect(error.kind).toBe("ambiguous");
    expect(error.code).toBeNull();
    expect(fetchMock).toHaveBeenCalledOnce();
  });
});
