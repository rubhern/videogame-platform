import { QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import { createMemoryRouter, RouterProvider, type Location } from "react-router-dom";

import { createAppQueryClient } from "../app/query-client";
import { appRoutes } from "../app/router";

/** Starts the app at a path, or at a history entry carrying React Router state. */
export function renderApp(initialPath: string | Partial<Location> = "/") {
  const router = createMemoryRouter(appRoutes, { initialEntries: [initialPath] });
  const queryClient = createAppQueryClient();

  return {
    router,
    ...render(
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    ),
  };
}
