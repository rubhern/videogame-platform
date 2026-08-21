import { Link } from "react-router-dom";

export function NotFoundPage() {
  return (
    <div className="mx-auto max-w-5xl px-5 py-16 sm:px-8 sm:py-24">
      <p className="text-sm font-semibold text-cyan-300">404</p>
      <h1 className="mt-3 text-4xl font-bold tracking-tight text-white">
        Página no encontrada
      </h1>
      <p className="mt-4 max-w-xl text-slate-300">
        Esta ruta todavía no forma parte de la aplicación.
      </p>
      <Link
        className="mt-8 inline-flex rounded-lg bg-cyan-300 px-4 py-2 font-semibold text-slate-950 hover:bg-cyan-200 focus-visible:outline focus-visible:outline-2 focus-visible:outline-cyan-200"
        to="/"
      >
        Volver al inicio
      </Link>
    </div>
  );
}
