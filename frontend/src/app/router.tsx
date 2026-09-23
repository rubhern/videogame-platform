import { createBrowserRouter, type RouteObject } from "react-router-dom";

import { GameDetailsPage } from "../pages/game-details-page";
import { NotFoundPage } from "../pages/not-found-page";
import { ReleasesPage } from "../pages/releases-page";
import { SearchPage } from "../pages/search-page";
import { MyRatingsPage } from "../pages/my-ratings-page";
import { AppShell } from "./app-shell";

export const appRoutes: RouteObject[] = [
  {
    path: "/",
    element: <AppShell />,
    children: [
      { index: true, element: <ReleasesPage /> },
      { path: "search", element: <SearchPage /> },
      { path: "mis-puntuaciones", element: <MyRatingsPage /> },
      { path: "games/:gameId/:slug?", element: <GameDetailsPage /> },
      { path: "*", element: <NotFoundPage /> },
    ],
  },
];

export function createAppRouter() {
  return createBrowserRouter(appRoutes);
}
