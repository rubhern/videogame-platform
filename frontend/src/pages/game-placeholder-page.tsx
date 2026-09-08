import { Link, useParams } from "react-router-dom";

export function GamePlaceholderPage() {
  const { slug } = useParams();

  return (
    <section aria-labelledby="game-placeholder-title" className="page-container page-section">
      <p className="text-sm font-semibold text-accent">Destino provisional</p>
      <h1 id="game-placeholder-title" className="mt-3 page-title">
        Detalle de juego todavía no disponible
      </h1>
      <p className="mt-4 max-w-xl text-muted">
        La navegación funciona para {slug ?? "este juego"}, pero su página de detalle pertenece a una
        issue posterior.
      </p>
      <Link
        className="mt-8 button button-primary"
        to="/"
      >
        Volver a lanzamientos
      </Link>
    </section>
  );
}
