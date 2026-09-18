import { Link } from "react-router-dom";

export function NotFoundPage() {
  return (
    <div className="page-container page-section">
      <div className="notice notice-info">
        <span className="notice-symbol" aria-hidden="true">
          ?
        </span>
        <p className="notice-kicker">Error 404</p>
        <h1 className="page-title">Página no encontrada</h1>
        <p>Esta ruta todavía no forma parte de la aplicación.</p>
        <Link className="button button-primary" to="/">
          Volver al inicio
        </Link>
      </div>
    </div>
  );
}
