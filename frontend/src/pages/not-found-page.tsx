import { Link } from "react-router-dom";

export function NotFoundPage() {
  return (
    <div className="page-container page-section">
      <div className="notice notice-info">
        <p className="notice-kicker">Error 404</p>
        <h1>Página no encontrada</h1>
        <p>Esta ruta todavía no forma parte de la aplicación.</p>
        <Link className="button button-primary" to="/">
          Volver al inicio
        </Link>
      </div>
    </div>
  );
}
