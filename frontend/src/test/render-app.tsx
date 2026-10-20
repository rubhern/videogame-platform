import { render } from "@testing-library/react";
import { createMemoryRouter, RouterProvider, type Location } from "react-router-dom";

import { AppProviders } from "../app/app-providers";
import { createAppQueryClient } from "../app/query-client";
import { appRoutes } from "../app/router";
import { createInterestsSwitch } from "../features/platform-preferences/interests-switch";

/** Starts the app at a path, or at a history entry carrying React Router state. */
export function renderApp(initialPath: string | Partial<Location> = "/") {
  const router = createMemoryRouter(appRoutes, { initialEntries: [initialPath] });
  const queryClient = createAppQueryClient();
  const interests = createInterestsSwitch();

  return {
    router,
    interests,
    ...render(
      <AppProviders interests={interests} queryClient={queryClient}>
        <RouterProvider router={router} />
      </AppProviders>,
    ),
  };
}
