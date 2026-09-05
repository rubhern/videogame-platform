import { useEffect, useRef } from "react";

import { GameSearchCard } from "./game-search-card";
import { GameSearchForm } from "./game-search-form";
import { GameSearchPagination } from "./game-search-pagination";
import { type GameSearchParams } from "./game-search-params";
import type { GameSearchViewModel } from "./game-search-view-model";

export type GameSearchShellState =
  | { status: "prompt" }
  | { status: "loading" }
  | {
      status: "ready";
      model: GameSearchViewModel;
      isRefreshing: boolean;
      isPlaceholderData: boolean;
    }
  | { status: "query-invalid" }
  | { status: "catalogue-not-ready" }
  | { status: "error"; message: string; correlationId: string | null };

type GameSearchShellProps = {
  params: GameSearchParams;
  state: GameSearchShellState;
  onRetry: () => void;
};

const actionClass =
  "inline-flex min-h-11 items-center rounded-lg bg-cyan-300 px-4 py-2 font-semibold text-slate-950 hover:bg-cyan-200 focus-visible:outline focus-visible:outline-2 focus-visible:outline-cyan-200";

function resultsSummary(model: GameSearchViewModel, isRefreshing: boolean): string {
  if (isRefreshing) {
    return "Actualizando resultados…";
  }
  const { totalItems, number, totalPages } = model.page;
  const count =
    totalItems === 1 ? "1 juego del catálogo local" : `${totalItems} juegos del catálogo local`;
  if (totalPages > 0 && number > totalPages) {
    return `${count} · La página ${number} ya no está disponible`;
  }
  return `${count} · Página ${number} de ${Math.max(totalPages, 1)}`;
}

export function GameSearchShell({ params, state, onRetry }: GameSearchShellProps) {
  const resultsHeadingRef = useRef<HTMLHeadingElement>(null);
  const previousRequest = useRef(`${params.query}|${params.page}`);

  useEffect(() => {
    const request = `${params.query}|${params.page}`;
    if (previousRequest.current === request) {
      return;
    }
    previousRequest.current = request;
    resultsHeadingRef.current?.focus();
  }, [params.query, params.page]);

  return (
    <section aria-labelledby="search-title" className="mx-auto max-w-5xl px-5 py-12 sm:px-8">
      <p className="text-sm font-semibold uppercase tracking-widest text-cyan-300">
        Catálogo local
      </p>
      <h1
        className="mt-3 text-4xl font-bold tracking-tight text-balance break-words text-white"
        id="search-title"
      >
        Buscar juegos
      </h1>
      <p className="mt-4 max-w-2xl text-slate-300">
        La búsqueda solo consulta el catálogo local aprobado. No se consulta ningún proveedor
        externo, así que un título que no forma parte del catálogo no devuelve resultados.
      </p>

      <GameSearchForm key={params.query} params={params} />

      <h2
        className="mt-10 text-2xl font-semibold text-white focus-visible:outline focus-visible:outline-2 focus-visible:outline-cyan-200"
        ref={resultsHeadingRef}
        tabIndex={-1}
      >
        Resultados
      </h2>

      {state.status === "prompt" ? (
        <p className="mt-4 text-slate-200">
          Escribe un título o un título alternativo aprobado para buscar en el catálogo.
        </p>
      ) : null}

      {state.status === "loading" ? (
        <p className="mt-4 text-slate-200" role="status">
          Buscando en el catálogo…
        </p>
      ) : null}

      {state.status === "query-invalid" ? (
        <div className="mt-4 rounded-lg border border-amber-400/60 bg-amber-950/30 p-5" role="alert">
          <h3 className="font-semibold text-amber-100">La búsqueda no es válida</h3>
          <p className="mt-2 text-amber-50">
            Escribe al menos un carácter con letras o números y como máximo 100 caracteres.
          </p>
        </div>
      ) : null}

      {state.status === "catalogue-not-ready" ? (
        <div className="mt-4 rounded-lg border border-amber-400/60 bg-amber-950/30 p-5" role="alert">
          <h3 className="font-semibold text-amber-100">El catálogo todavía no está disponible</h3>
          <p className="mt-2 text-amber-50">
            Aún no hay una publicación local válida del catálogo. No se consulta ningún proveedor
            externo para completarla.
          </p>
          <button className={`${actionClass} mt-4`} onClick={onRetry} type="button">
            Reintentar
          </button>
        </div>
      ) : null}

      {state.status === "error" ? (
        <div className="mt-4 rounded-lg border border-red-400/60 bg-red-950/30 p-5" role="alert">
          <h3 className="font-semibold text-red-200">No se pudo completar la búsqueda</h3>
          <p className="mt-2 text-red-100">{state.message}</p>
          {state.correlationId === null ? null : (
            <p className="mt-2 text-sm text-red-200">
              Referencia para soporte: {state.correlationId}
            </p>
          )}
          <button className={`${actionClass} mt-4`} onClick={onRetry} type="button">
            Reintentar
          </button>
        </div>
      ) : null}

      {state.status === "ready" && state.isPlaceholderData ? (
        <p className="mt-4 text-slate-200" role="status">
          Buscando resultados para la nueva consulta…
        </p>
      ) : null}

      {state.status === "ready" && !state.isPlaceholderData ? (
        <>
          <p className="mt-4 text-slate-300" role="status">
            {resultsSummary(state.model, state.isRefreshing)}
          </p>

          {state.model.results.length === 0 ? (
            <div className="mt-6 rounded-lg border border-slate-700 p-5">
              <p className="text-slate-200">
                {state.model.page.totalItems > 0 &&
                state.model.page.totalPages > 0 &&
                state.model.page.number > state.model.page.totalPages
                  ? "La página solicitada ya no está disponible para esta búsqueda."
                  : `Ningún juego del catálogo local coincide con “${params.query}”. El catálogo es una selección acotada, así que un título publicado fuera de él no aparece aquí.`}
              </p>
            </div>
          ) : (
            <ul className="mt-6 grid gap-5 sm:grid-cols-2 lg:grid-cols-3" aria-label="Resultados de la búsqueda">
              {state.model.results.map((result) => (
                <li className="min-w-0" key={result.gameId}>
                  <GameSearchCard result={result} />
                </li>
              ))}
            </ul>
          )}

          <GameSearchPagination page={state.model.page} params={params} />
        </>
      ) : null}
    </section>
  );
}
