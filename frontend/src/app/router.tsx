import { createBrowserRouter, type RouteObject } from "react-router-dom";

import { NotFoundPage } from "../pages/not-found-page";
import { TechnicalFoundationPage } from "../pages/technical-foundation-page";
import { AppShell } from "./app-shell";

export const appRoutes: RouteObject[] = [
  {
    path: "/",
    element: <AppShell />,
    children: [
      { index: true, element: <TechnicalFoundationPage /> },
      { path: "*", element: <NotFoundPage /> },
    ],
  },
];

export function createAppRouter() {
  return createBrowserRouter(appRoutes);
}
