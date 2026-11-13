import { Link, type RouteObject } from "react-router-dom";

const demoPath = "/__dev/navegacion";

/** Exercise the same pending navigation as a slow destination without relying on the backend. */
export const navigationLoadingDemoRoutes: RouteObject[] = [
  {
    path: demoPath,
    element: (
      <section className="page-container page-section">
        <h1>Prueba de carga de Gameómetro</h1>
        <Link className="button button-primary" to={`${demoPath}/lista`}>
          Abrir una página que tarda 5 segundos
        </Link>
      </section>
    ),
  },
  {
    path: `${demoPath}/lista`,
    loader: ({ request }) => new Promise<null>(resolve => {
      function finish() {
        window.clearTimeout(timer);
        request.signal.removeEventListener("abort", finish);
        resolve(null);
      }

      const timer = window.setTimeout(finish, 5_000);
      request.signal.addEventListener("abort", finish, { once: true });
    }),
    element: (
      <section className="page-container page-section">
        <h1>Página cargada</h1>
        <Link className="button button-primary" to={demoPath}>Volver a probar</Link>
      </section>
    ),
  },
];
