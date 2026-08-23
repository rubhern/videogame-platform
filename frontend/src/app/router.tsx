import { createBrowserRouter, type RouteObject } from "react-router-dom";

import { GamePlaceholderPage } from "../pages/game-placeholder-page";
import { NotFoundPage } from "../pages/not-found-page";
import { ReleasesPage } from "../pages/releases-page";
import { AppShell } from "./app-shell";

export const appRoutes: RouteObject[] = [
  {
    path: "/",
    element: <AppShell />,
    children: [
      { index: true, element: <ReleasesPage /> },
      { path: "games/:slug", element: <GamePlaceholderPage /> },
      { path: "*", element: <NotFoundPage /> },
    ],
  },
];

export function createAppRouter() {
  return createBrowserRouter(appRoutes);
}
