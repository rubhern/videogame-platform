import { QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router-dom";

import { createAppQueryClient } from "../app/query-client";
import { appRoutes } from "../app/router";

export function renderApp(initialPath = "/") {
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
