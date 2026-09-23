import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { AccountControl } from "./account-control";
import { MemoryRouter, Route, Routes } from "react-router-dom";

afterEach(() => vi.unstubAllGlobals());

function renderControl() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Routes>
          <Route path="/" element={<AccountControl />} />
          <Route path="/mis-puntuaciones" element={<h1>Mis puntuaciones</h1>} />
        </Routes>
      </MemoryRouter>
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

    const user = userEvent.setup();
    const trigger = await screen.findByRole("button", { name: "Mi cuenta" });
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("link", { name: "Mis puntuaciones" })).not.toBeInTheDocument();

    await user.click(trigger);
    expect(trigger).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByRole("link", { name: "Mis puntuaciones" })).toBeVisible();
    await user.keyboard("{Escape}");
    expect(trigger).toHaveFocus();
    expect(trigger).toHaveAttribute("aria-expanded", "false");

    await user.click(trigger);
    await user.click(screen.getByRole("button", { name: "Cerrar sesión" }));

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

  it("navigates to personal ratings from the dropdown", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => Response.json({ authenticated: true, csrfToken: "opaque-token" })));
    const user = userEvent.setup();
    renderControl();

    await user.click(await screen.findByRole("button", { name: "Mi cuenta" }));
    await user.click(screen.getByRole("link", { name: "Mis puntuaciones" }));

    expect(screen.getByRole("heading", { name: "Mis puntuaciones" })).toBeVisible();
  });
});
