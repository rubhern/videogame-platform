import { Link } from "react-router-dom";

export function NotFoundPage() {
  return (
    <div className="page-container page-section">
      <p className="text-sm font-semibold text-accent">404</p>
      <h1 className="mt-3 page-title">
        Página no encontrada
      </h1>
      <p className="mt-4 max-w-xl text-muted">
        Esta ruta todavía no forma parte de la aplicación.
      </p>
      <Link
        className="mt-8 button button-primary"
        to="/"
      >
        Volver al inicio
      </Link>
    </div>
  );
}
