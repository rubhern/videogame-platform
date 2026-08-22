import { Link, useParams } from "react-router-dom";

export function GamePlaceholderPage() {
  const { slug } = useParams();

  return (
    <section aria-labelledby="game-placeholder-title" className="mx-auto max-w-5xl px-5 py-16 sm:px-8">
      <p className="text-sm font-semibold text-cyan-300">Destino provisional</p>
      <h1 id="game-placeholder-title" className="mt-3 text-4xl font-bold tracking-tight text-white">
        Detalle de juego todavía no disponible
      </h1>
      <p className="mt-4 max-w-xl text-slate-300">
        La navegación funciona para {slug ?? "este juego"}, pero su página de detalle pertenece a una
        issue posterior.
      </p>
      <Link
        className="mt-8 inline-flex min-h-11 items-center rounded-lg bg-cyan-300 px-4 py-2 font-semibold text-slate-950 hover:bg-cyan-200 focus-visible:outline focus-visible:outline-2 focus-visible:outline-cyan-200"
        to="/"
      >
        Volver a lanzamientos
      </Link>
    </section>
  );
}
