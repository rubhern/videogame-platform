import { describe, expect, it, vi } from "vitest";

import { createProductApiClient } from "../../shared/api/product-api-client";
import { getSession, logout, LogoutError } from "./session-api";

function client(fetchMock: typeof fetch) {
  return createProductApiClient({
    baseUrl: "http://localhost/api/v1",
    fetch: fetchMock,
  });
}

describe("session API", () => {
  it("maps an anonymous session", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(Response.json({ authenticated: false }));

    await expect(getSession(client(fetchMock))).resolves.toEqual({
      authenticated: false,
    });
  });

  it("maps an authenticated session with its CSRF material", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(
        Response.json({ authenticated: true, csrfToken: "opaque-token" }),
      );

    await expect(getSession(client(fetchMock))).resolves.toEqual({
      authenticated: true,
      csrfToken: "opaque-token",
    });
  });

  it("sends the CSRF token when terminating the session", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(new Response(null, { status: 204 }));

    await logout("opaque-token", client(fetchMock));

    const request = fetchMock.mock.calls[0]?.[0] as Request;
    expect(request.method).toBe("POST");
    expect(request.url).toBe("http://localhost/api/v1/session");
    expect(request.headers.get("X-CSRF-Token")).toBe("opaque-token");
    expect(request.credentials).toBe("same-origin");
  });

  it("raises a logout error when the session cannot be terminated", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(new Response(null, { status: 403 }));

    await expect(logout("stale-token", client(fetchMock))).rejects.toBeInstanceOf(
      LogoutError,
    );
  });
});
