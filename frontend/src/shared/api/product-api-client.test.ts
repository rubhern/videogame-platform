import { describe, expect, it, vi } from "vitest";

import { createProductApiClient } from "./product-api-client";

describe("product API transport", () => {
  it("uses the generated contract and same-origin credentials", async () => {
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(
      new Response(JSON.stringify({ authenticated: false }), {
        headers: { "Content-Type": "application/json" },
        status: 200,
      }),
    );
    const client = createProductApiClient({
      baseUrl: "http://localhost/api/v1",
      fetch: fetchMock,
    });

    const result = await client.GET("/session");

    expect(result.error).toBeUndefined();
    expect(fetchMock).toHaveBeenCalledOnce();

    const request = fetchMock.mock.calls[0]?.[0];
    expect(request).toBeInstanceOf(Request);
    expect((request as Request).url).toBe("http://localhost/api/v1/session");
    expect((request as Request).credentials).toBe("same-origin");
  });
});
