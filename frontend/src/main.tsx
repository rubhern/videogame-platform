import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { RouterProvider } from "react-router-dom";

import { AppProviders } from "./app/app-providers";
import { createAppQueryClient } from "./app/query-client";
import { createAppRouter } from "./app/router";
import "./styles/index.css";

// Once per document startup, outside React's development StrictMode lifecycle.
const displayRevision = /^[0-9a-f]{40}$/.test(__SOURCE_REVISION__)
  ? __SOURCE_REVISION__.slice(0, 12)
  : __SOURCE_REVISION__;
console.info(`Gameómetro ${__APPLICATION_VERSION__} — revision ${displayRevision}`);

const rootElement = document.getElementById("root");

if (rootElement === null) {
  throw new Error("The frontend root element is missing.");
}

const queryClient = createAppQueryClient();
const router = createAppRouter(queryClient);

createRoot(rootElement).render(
  <StrictMode>
    <AppProviders queryClient={queryClient}>
      <RouterProvider router={router} />
    </AppProviders>
  </StrictMode>,
);
