import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createMemoryRouter, RouterProvider } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";

import { gameDetailsFixture } from "../../test/game-details-fixture";
import { GameRatingEntry } from "./game-rating-entry";

const assignLocation = vi.fn();
vi.mock("../../shared/browser/navigate", () => ({
  assignLocation: (url: string) => assignLocation(url),
}));

afterEach(() => {
  vi.unstubAllGlobals();
  assignLocation.mockClear();
});

function renderEntry(initialPath: string) {
  const game = gameDetailsFixture();
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const router = createMemoryRouter(
    [
      {
        path: "/games/:gameId/:slug",
        element: <GameRatingEntry game={game} />,
      },
    ],
    { initialEntries: [initialPath] },
  );
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return { router, game };
}

const basePath = "/games/30000000-0000-4000-8000-000000000005/resident-evil-requiem";

describe("game rating entry", () => {
  it("starts authentication at the rating boundary without persisting a rating", async () => {
    vi.stubGlobal("fetch", vi.fn());
    renderEntry(basePath);

    await userEvent.selectOptions(
      screen.getByLabelText("Tu puntuación (1-10)"),
      "8",
    );
    await userEvent.click(screen.getByRole("button", { name: "Puntuar" }));

    expect(assignLocation).toHaveBeenCalledWith(
      "/auth/rating-intent/start?gameId=30000000-0000-4000-8000-000000000005&slug=resident-evil-requiem&value=8",
    );
  });

  it("presents the single-use recovered selection as pending, non-persisted state", async () => {
    const fetchMock = vi.fn(async () =>
      Response.json({
        gameId: "30000000-0000-4000-8000-000000000005",
        slug: "resident-evil-requiem",
        value: 8,
      }),
    );
    vi.stubGlobal("fetch", fetchMock);
    renderEntry(`${basePath}?rating-intent=resumed`);

    expect(
      await screen.findByText(/Tu puntuación seleccionada es 8\/10/),
    ).toBeVisible();
    expect(screen.getByText(/pendiente, todavía sin guardar/)).toBeVisible();
    expect(fetchMock).toHaveBeenCalledOnce();
  });

  it("shows no pending selection when the context was already consumed", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response(null, { status: 404 })),
    );
    renderEntry(`${basePath}?rating-intent=resumed`);

    await waitFor(() =>
      expect(
        screen.queryByText(/Tu puntuación seleccionada/),
      ).not.toBeInTheDocument(),
    );
  });

  it("shows a safe notice when authentication was cancelled", async () => {
    vi.stubGlobal("fetch", vi.fn());
    renderEntry(`${basePath}?rating-intent=cancelled`);

    expect(
      await screen.findByText(/no se completó el inicio de sesión/i),
    ).toBeVisible();
  });
});
