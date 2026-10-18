import type { QueryClient } from "@tanstack/react-query";
import { createBrowserRouter, type RouteObject } from "react-router-dom";

import { CatalogueAdministrationPage } from "../pages/catalogue-administration-page";
import { DiscoveryPage } from "../pages/discovery-page";
import { GameDetailsPage } from "../pages/game-details-page";
import { NotFoundPage } from "../pages/not-found-page";
import { SearchPage } from "../pages/search-page";
import { MyRatingsPage } from "../pages/my-ratings-page";
import { legalPublicationApproved } from "../shared/legal/publication";
import { legalRoutes } from "../shared/legal/legal-routes";
import { routeReadiness } from "./route-readiness";
import { AppShell } from "./app-shell";

export const appRoutes: RouteObject[] = [
  {
    path: "/",
    element: <AppShell />,
    children: [
      { index: true, element: <DiscoveryPage /> },
      { path: "search", element: <SearchPage /> },
      { path: "mis-puntuaciones", element: <MyRatingsPage /> },
      { path: "administracion/catalogo", element: <CatalogueAdministrationPage /> },
      { path: "games/:gameId/:slug?", element: <GameDetailsPage /> },
      ...(import.meta.env.DEV || legalPublicationApproved ? legalRoutes(true) : []),
      { path: "*", element: <NotFoundPage /> },
    ],
  },
];

export function createAppRouter(queryClient: QueryClient) {
  const loader = routeReadiness(queryClient);
  return createBrowserRouter(
    appRoutes.map(route => {
      if (route.index || !route.children) return route;
      return {
        ...route,
        children: route.children.map(child => ({
          ...child,
          loader,
          hydrateFallbackElement: child.element,
        })),
      };
    }),
  );
}
