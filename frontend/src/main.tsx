import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { RouterProvider } from "react-router-dom";

import { AppProviders } from "./app/app-providers";
import { createAppRouter } from "./app/router";
import "./styles/index.css";

const rootElement = document.getElementById("root");

if (rootElement === null) {
  throw new Error("The frontend root element is missing.");
}

createRoot(rootElement).render(
  <StrictMode>
    <AppProviders>
      <RouterProvider router={createAppRouter()} />
    </AppProviders>
  </StrictMode>,
);
