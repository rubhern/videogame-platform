import { Link } from "react-router-dom";

import { useDocumentTitle } from "../shared/browser/use-document-title";

export function NotFoundPage() {
  useDocumentTitle("Página no encontrada · Gameómetro");
  return (
    <div className="page-container page-section">
      <div className="notice notice-info">
        <p className="notice-kicker">Error 404</p>
        <h1>Página no encontrada</h1>
        <p>Página no disponible. No quieres ver lo que hay aquí, hazme caso</p>
        <Link className="button button-primary" to="/">
          Volver al inicio
        </Link>
      </div>
    </div>
  );
}
