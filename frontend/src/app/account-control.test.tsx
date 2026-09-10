import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { AccountControl } from "./account-control";

afterEach(() => vi.unstubAllGlobals());

function renderControl() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <AccountControl />
    </QueryClientProvider>,
  );
}

describe("header account control", () => {
  it("shows no account or login entry point for an anonymous session", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => Response.json({ authenticated: false })),
    );

    renderControl();

    await waitFor(() =>
      expect(screen.queryByText("Mi cuenta")).not.toBeInTheDocument(),
    );
    expect(
      screen.queryByRole("button", { name: "Cerrar sesión" }),
    ).not.toBeInTheDocument();
  });

  it("exposes Mi cuenta and a CSRF-protected logout for an authenticated session", async () => {
    let authenticated = true;
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const request = input instanceof Request ? input : new Request(input);
      if (request.method === "POST") {
        authenticated = false;
        return new Response(null, { status: 204 });
      }
      return Response.json(
        authenticated
          ? { authenticated: true, csrfToken: "opaque-token" }
          : { authenticated: false },
      );
    });
    vi.stubGlobal("fetch", fetchMock);

    renderControl();

    expect(await screen.findByText("Mi cuenta")).toBeVisible();
    await userEvent.click(
      screen.getByRole("button", { name: "Cerrar sesión" }),
    );

    const logoutRequest = fetchMock.mock.calls
      .map((call) => call[0])
      .find(
        (input): input is Request =>
          input instanceof Request && input.method === "POST",
      );
    expect(logoutRequest).toBeDefined();
    expect(logoutRequest?.headers.get("X-CSRF-Token")).toBe("opaque-token");

    await waitFor(() =>
      expect(screen.queryByText("Mi cuenta")).not.toBeInTheDocument(),
    );
  });
});
