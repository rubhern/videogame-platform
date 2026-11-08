import type { RouteObject } from "react-router-dom";

import { LegalPage } from "./legal-pages";

export function legalRoutes(): RouteObject[] {
  return [
    { path: "privacidad", element: <LegalPage document="privacidad" /> },
    { path: "condiciones", element: <LegalPage document="condiciones" /> },
    { path: "aviso-legal", element: <LegalPage document="aviso-legal" /> },
    { path: "cookies", element: <LegalPage document="cookies" /> },
  ];
}
