import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createMemoryRouter, RouterProvider } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";

import { gameDetailsFixture } from "../../test/game-details-fixture";
import { gameDetailsQueryKey } from "../game-details/game-details-api";
import { GameRatingPanel } from "./game-rating-panel";
import type { PersonalRating } from "./personal-rating-api";

const assignLocation = vi.fn();
vi.mock("../../shared/browser/navigate", () => ({
  assignLocation: (url: string) => assignLocation(url),
}));

afterEach(() => {
  vi.unstubAllGlobals();
  assignLocation.mockClear();
});

const gameId = "30000000-0000-4000-8000-000000000005";
const basePath = `/games/${gameId}/resident-evil-requiem`;

function rating(value: number, version: number): PersonalRating {
  return {
    gameId,
    value,
    createdAt: "2026-08-13T10:00:00Z",
    updatedAt: "2026-08-13T10:00:00Z",
    entityTag: `"rating-version-${version}"`,
  };
}

function statistics(mean: number | null, count: number) {
  return {
    status: "available" as const,
    mean,
    count,
    distribution: {
      "1": 0, "2": 0, "3": 0, "4": 0, "5": 0, "6": 0, "7": 0, "8": 0, "9": 0, "10": 0,
    },
  };
}

function problem(status: number, code: string) {
  return new Response(JSON.stringify({ code, correlationId: "corr-1" }), {
    status,
    headers: { "Content-Type": "application/problem+json" },
  });
}

type Handlers = {
  session?: () => Response;
  read?: () => Response;
  put?: (request: Request) => Response | Promise<Response>;
  remove?: (request: Request) => Response | Promise<Response>;
  intent?: () => Response;
};

/** A small same-origin server double keyed by method and path. */
function serve(handlers: Handlers) {
  const calls: Request[] = [];
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    // Relative paths (the BFF `/auth` client) need a base before Node's Request accepts them.
    const url = new URL(
      input instanceof Request ? input.url : String(input),
      "http://localhost",
    );
    const request =
      input instanceof Request ? input : new Request(url, init);
    calls.push(request);
    const path = url.pathname;
    if (path === "/api/v1/session") {
      return handlers.session?.() ?? Response.json({ authenticated: false });
    }
    if (path === "/auth/rating-intent") {
      return handlers.intent?.() ?? new Response(null, { status: 404 });
    }
    if (path === `/api/v1/me/ratings/${gameId}`) {
      if (request.method === "GET") {
        return handlers.read?.() ?? problem(404, "RATING_NOT_FOUND");
      }
      if (request.method === "PUT" && handlers.put) return handlers.put(request);
      if (request.method === "DELETE" && handlers.remove) {
        return handlers.remove(request);
      }
    }
    throw new Error(`Unexpected request ${request.method} ${request.url}`);
  });
  vi.stubGlobal("fetch", fetchMock);
  return {
    calls,
    commands: () =>
      calls.filter((r) => r.method === "PUT" || r.method === "DELETE"),
    sessionReads: () => calls.filter((r) => r.url.endsWith("/api/v1/session")),
    ratingReads: () =>
      calls.filter(
        (r) => r.method === "GET" && r.url.includes("/api/v1/me/ratings/"),
      ),
  };
}

const authenticated = () =>
  Response.json({ authenticated: true, csrfToken: "opaque-token" });

function renderPanel(initialPath = basePath, game = gameDetailsFixture()) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  queryClient.setQueryData(gameDetailsQueryKey(gameId), game);
  const router = createMemoryRouter(
    [{ path: "/games/:gameId/:slug", element: <GameRatingPanel game={game} /> }],
    { initialEntries: [initialPath] },
  );
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return { router, queryClient };
}

const note = (value: number) =>
  screen.getByRole("button", { name: String(value) });
const pressed = (value: number) =>
  expect(note(value)).toHaveAttribute("aria-pressed", "true");
const nonePressed = () =>
  expect(
    screen.queryByRole("button", { pressed: true }),
  ).not.toBeInTheDocument();

describe("inline personal rating", () => {
  it("is a labelled scale where arrows only move focus and an anonymous press starts authentication", async () => {
    const user = userEvent.setup();
    serve({});
    renderPanel();

    expect(
      screen.getByRole("region", { name: "Tu puntuación" }),
    ).toBeInTheDocument();
    const group = screen.getByRole("group", { name: "Nota del 1 al 10" });
    expect(within(group).getAllByRole("button")).toHaveLength(10);
    await waitFor(() =>
      expect(screen.getByRole("status")).toHaveTextContent("Selecciona una nota"),
    );
    expect(
      screen.queryByRole("button", { name: /Puntuar|Actualizar/ }),
    ).not.toBeInTheDocument();

    // One tab stop; arrows browse without saving or starting authentication.
    await user.tab();
    expect(note(1)).toHaveFocus();
    await user.keyboard("{ArrowRight}{ArrowRight}{End}{ArrowLeft}");
    expect(note(9)).toHaveFocus();
    expect(assignLocation).not.toHaveBeenCalled();
    nonePressed();

    await user.keyboard("{Enter}");
    expect(assignLocation).toHaveBeenCalledWith(
      `/auth/rating-intent/start?gameId=${gameId}&slug=resident-evil-requiem&value=9`,
    );
  });

  it("disables the scale when the game is not eligible and explains why in place", async () => {
    serve({});
    const game = gameDetailsFixture();
    game.ratingEligibility = {
      eligible: false,
      reason: "RELEASE_NOT_OCCURRED",
      evaluatedOn: "2026-08-13",
    };
    renderPanel(basePath, game);

    expect(note(8)).toBeDisabled();
    expect(screen.getByRole("status")).toHaveTextContent(
      "el lanzamiento aún no ha ocurrido",
    );
    expect(screen.queryByText(/Disponible para puntuar/)).not.toBeInTheDocument();
  });

  it("persists the recovered value once after authentication with If-None-Match: *", async () => {
    const server = serve({
      session: authenticated,
      intent: () => Response.json({ gameId, slug: "resident-evil-requiem", value: 8 }),
      put: () =>
        Response.json(
          { personalRating: rating(8, 1), ratingStatistics: statistics(8, 1) },
          { status: 201 },
        ),
    });
    const { router, queryClient } = renderPanel(`${basePath}?rating-intent=resumed`);

    // Announced to assistive technology only: the pressed value already shows the result.
    expect(await screen.findByText("Puntuación guardada: 8/10.")).toHaveClass("sr-only");
    const [put] = server.commands();
    expect(put?.headers.get("If-None-Match")).toBe("*");
    expect(put?.headers.get("If-Match")).toBeNull();
    expect(put?.headers.get("X-CSRF-Token")).toBe("opaque-token");
    expect(server.commands()).toHaveLength(1);
    pressed(8);
    expect(screen.queryByText(/Tu nota actual/)).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Eliminar puntuación" }),
    ).toBeEnabled();
    // The aggregate context comes from the command response, not a new public read.
    expect(
      queryClient.getQueryData(gameDetailsQueryKey(gameId)),
    ).toMatchObject({ ratingStatistics: { mean: 8, count: 1 } });
    // The single-use return marker is consumed with the command.
    expect(router.state.location.search).toBe("");
  });

  it("does not repeat a command when the recovered value equals the existing rating", async () => {
    const server = serve({
      session: authenticated,
      read: () => Response.json(rating(8, 1)),
      intent: () => Response.json({ gameId, slug: "resident-evil-requiem", value: 8 }),
    });
    const { router } = renderPanel(`${basePath}?rating-intent=resumed`);

    await waitFor(() => expect(router.state.location.search).toBe(""));
    pressed(8);
    expect(server.commands()).toHaveLength(0);
  });

  it("updates on a press with the current If-Match and deletes with the new one, keeping focus in the scale", async () => {
    const user = userEvent.setup();
    const server = serve({
      session: authenticated,
      read: () => Response.json(rating(7, 1)),
      put: () =>
        Response.json({
          personalRating: rating(9, 2),
          ratingStatistics: statistics(9, 1),
        }),
      remove: () =>
        Response.json({ personalRating: null, ratingStatistics: statistics(null, 0) }),
    });
    renderPanel();

    await waitFor(() => pressed(7));
    expect(screen.queryByText(/Tu nota actual/)).not.toBeInTheDocument();

    // Pressing the current value is a no-op.
    await user.click(note(7));
    expect(server.commands()).toHaveLength(0);

    await user.click(note(9));

    // Announced to assistive technology only: the pressed value already shows the result.
    expect(await screen.findByText("Puntuación guardada: 9/10.")).toHaveClass("sr-only");
    expect(server.commands()[0]?.headers.get("If-Match")).toBe('"rating-version-1"');
    expect(server.commands()[0]?.headers.get("If-None-Match")).toBeNull();
    pressed(9);

    await user.click(screen.getByRole("button", { name: "Eliminar puntuación" }));

    // Announced to assistive technology only: the pressed value already shows the result.
    expect(await screen.findByText("Puntuación eliminada.")).toHaveClass("sr-only");
    const remove = server.commands()[1];
    expect(remove?.method).toBe("DELETE");
    expect(remove?.headers.get("If-Match")).toBe('"rating-version-2"');
    expect(screen.getByText("Selecciona una nota")).toBeVisible();
    nonePressed();
    expect(
      screen.queryByRole("button", { name: "Eliminar puntuación" }),
    ).not.toBeInTheDocument();
    expect(note(1)).toHaveFocus();
  });

  it("shows the winning state after a stale-ETag conflict without retrying the command", async () => {
    const user = userEvent.setup();
    let current = rating(7, 1);
    const server = serve({
      session: authenticated,
      read: () => Response.json(current),
      put: () => {
        current = rating(5, 2);
        return problem(412, "RATING_WRITE_CONFLICT");
      },
    });
    renderPanel();

    await waitFor(() => pressed(7));
    await user.click(note(9));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Tu nota cambió desde otra sesión",
    );
    await waitFor(() => pressed(5));
    expect(server.commands()).toHaveLength(1);
    expect(server.ratingReads()).toHaveLength(2);
    expect(screen.getByText("Referencia para soporte: corr-1")).toBeVisible();
  });

  it("preserves the previous valid state after an ambiguous transport failure and only re-reads on request", async () => {
    const user = userEvent.setup();
    const server = serve({
      session: authenticated,
      read: () => Response.json(rating(7, 1)),
      put: () => Promise.reject(new TypeError("Failed to fetch")),
    });
    renderPanel();

    await waitFor(() => pressed(7));
    await user.click(note(9));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("No sabemos si el cambio se aplicó");
    expect(server.commands()).toHaveLength(1);
    pressed(7);

    await user.click(within(alert).getByRole("button", { name: "Comprobar mi nota" }));

    await waitFor(() => expect(server.ratingReads()).toHaveLength(2));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    pressed(7);
    expect(server.commands()).toHaveLength(1);
  });

  it.each([
    ["validation", 422, "RATING_VALUE_INVALID", "entero del 1 al 10"],
    ["eligibility", 422, "RATING_NOT_ELIGIBLE", "ya no admite nuevas puntuaciones"],
    ["CSRF", 403, "CSRF_VALIDATION_FAILED", "No se pudo verificar la solicitud"],
  ])(
    "keeps the previous rating after a %s rejection",
    async (_, status, code, message) => {
      const user = userEvent.setup();
      const server = serve({
        session: authenticated,
        read: () => Response.json(rating(7, 1)),
        put: () => problem(status, code),
      });
      renderPanel();

      await waitFor(() => pressed(7));
      await user.click(note(3));

      expect(await screen.findByRole("alert")).toHaveTextContent(message);
      pressed(7);
      expect(server.commands()).toHaveLength(1);
    },
  );

  it("offers to sign in again when the session expired", async () => {
    const user = userEvent.setup();
    let signedIn = true;
    serve({
      session: () =>
        signedIn ? authenticated() : Response.json({ authenticated: false }),
      put: () => {
        signedIn = false;
        return problem(401, "AUTHENTICATION_REQUIRED");
      },
    });
    renderPanel();

    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("Selecciona una nota"));
    await user.click(note(6));

    expect(await screen.findByRole("alert")).toHaveTextContent("Tu sesión ha caducado");
    await waitFor(() => nonePressed());
    await user.click(note(6));
    expect(assignLocation).toHaveBeenCalledWith(
      expect.stringContaining("value=6"),
    );
  });

  it("keeps the scale usable when the personal read fails and lets a conflict correct it", async () => {
    const user = userEvent.setup();
    const server = serve({
      session: authenticated,
      read: () => problem(500, "INTERNAL_ERROR"),
      put: () => problem(412, "RATING_ALREADY_EXISTS"),
    });
    renderPanel();

    await waitFor(() =>
      expect(screen.getByRole("status")).toHaveTextContent(
        "No se pudo comprobar si ya tenías una nota",
      ),
    );
    await user.click(note(4));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Tu nota cambió desde otra sesión",
    );
    expect(server.commands()[0]?.headers.get("If-None-Match")).toBe("*");
    expect(server.commands()).toHaveLength(1);
  });

  it("shows a safe notice when authentication was cancelled", async () => {
    serve({});
    renderPanel(`${basePath}?rating-intent=cancelled`);

    expect(
      await screen.findByText(/no se completó el inicio de sesión/i),
    ).toBeVisible();
    nonePressed();
  });
});
