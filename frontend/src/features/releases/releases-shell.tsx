import { Link } from "react-router-dom";

import type { ReleaseListItem } from "./releases-view-model";

export type ReleasesShellState =
  | { status: "loading" }
  | { status: "success"; items: readonly ReleaseListItem[] }
  | { status: "empty" }
  | { status: "error"; message: string };

type ReleasesShellProps = {
  state: ReleasesShellState;
};

export function ReleasesShell({ state }: ReleasesShellProps) {
  return (
    <section aria-labelledby="releases-title" className="mx-auto max-w-5xl px-5 py-12 sm:px-8">
      <p className="text-sm font-semibold uppercase tracking-widest text-cyan-300">
        Descubrimiento de lanzamientos
      </p>
      <h1 id="releases-title" className="mt-3 text-4xl font-bold tracking-tight text-white">
        Lanzamientos recientes
      </h1>
      <p className="mt-4 max-w-2xl text-slate-300">
        Una muestra mínima del catálogo local, servida por la API de producto.
      </p>

      <div className="mt-10">
        {state.status === "loading" ? (
          <p aria-live="polite" role="status" className="text-slate-200">
            Cargando lanzamientos…
          </p>
        ) : null}

        {state.status === "empty" ? (
          <p aria-live="polite" role="status" className="rounded-lg border border-slate-700 p-5">
            No hay lanzamientos recientes en este momento.
          </p>
        ) : null}

        {state.status === "error" ? (
          <div role="alert" className="rounded-lg border border-red-400/60 bg-red-950/30 p-5">
            <h2 className="font-semibold text-red-200">No se pudieron cargar los lanzamientos</h2>
            <p className="mt-2 text-red-100">{state.message}</p>
          </div>
        ) : null}

        {state.status === "success" ? (
          <ul aria-label="Lanzamientos recientes" className="grid gap-5">
            {state.items.map((item) => (
              <li key={item.gameId}>
                <article className="rounded-xl border border-slate-700 bg-slate-900 p-5">
                  <h2 className="text-2xl font-semibold text-white">{item.title}</h2>
                  <p className="mt-2 text-lg text-cyan-200">{item.date}</p>
                  <p className="mt-1 text-sm text-slate-300">
                    {item.platform} · {item.region}
                  </p>
                  <p className="mt-3 text-sm text-slate-400">Fuente: {item.provenance}</p>
                  <p className="mt-1 text-sm text-amber-200">{item.freshness}</p>
                  {item.review === null ? null : (
                    <p className="mt-1 text-sm text-amber-200">{item.review}</p>
                  )}
                  <Link
                    className="mt-5 inline-flex min-h-11 items-center rounded-lg bg-cyan-300 px-4 py-2 font-semibold text-slate-950 hover:bg-cyan-200 focus-visible:outline focus-visible:outline-2 focus-visible:outline-cyan-200"
                    to={`/games/${item.slug}`}
                  >
                    Ver {item.title}
                  </Link>
                </article>
              </li>
            ))}
          </ul>
        ) : null}
      </div>
    </section>
  );
}
