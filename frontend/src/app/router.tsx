import type { QueryClient } from "@tanstack/react-query";
import { createBrowserRouter, ScrollRestoration, type RouteObject } from "react-router-dom";

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
          // Initial restoration needs the ready page's height, not its fallback.
          element: <>{child.element}<ScrollRestoration /></>,
          loader,
          ...(child.path?.startsWith("games/") ? {
            // The public game read has no query parameters. Consuming an auth
            // marker must not refetch over a rating command's returned aggregate.
            shouldRevalidate: ({ currentUrl, nextUrl, defaultShouldRevalidate }) =>
              currentUrl.pathname === nextUrl.pathname
                ? false
                : defaultShouldRevalidate,
          } satisfies Pick<RouteObject, "shouldRevalidate"> : {}),
          // A destination must mount once, after its read. Mounting it as its own
          // fallback can resume rating commands before hydration then discard them.
          hydrateFallbackElement: <p className="page-container" role="status">Cargando página…</p>,
        })),
      };
    }),
  );
}
