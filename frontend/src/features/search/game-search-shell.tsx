import { useEffect, useRef } from "react";

import { CatalogueLoading } from "../../shared/ui/catalogue-loading";

import { GameSearchCard } from "./game-search-card";
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
    <section aria-labelledby="search-title" className="page-container search-section">
      <p className="eyebrow eyebrow-dot">Catálogo local</p>
      <h1 className="page-title" id="search-title">Buscar juegos</h1>
      <p className="search-intro">
        La búsqueda solo consulta el catálogo local aprobado. No se consulta ningún proveedor
        externo, así que un título que no forma parte del catálogo no devuelve resultados.
      </p>

      <h2
        className="sr-only"
        ref={resultsHeadingRef}
        tabIndex={-1}
      >
        Resultados
      </h2>

      {state.status === "prompt" ? (
        <div className="notice notice-empty" role="status">
          <span className="notice-symbol" aria-hidden="true">⌕</span>
          <p className="notice-kicker">Buscar en el catálogo</p>
          <h3>Encuentra un juego por su título</h3>
          <p>Escribe un título o un título alternativo aprobado en el buscador de la cabecera.</p>
        </div>
      ) : null}

      {state.status === "loading" ? (
        <CatalogueLoading message="Buscando en el catálogo…" />
      ) : null}

      {state.status === "query-invalid" ? (
        <div className="notice notice-warning" role="alert">
          <span className="notice-symbol notice-symbol-warning" aria-hidden="true">!</span>
          <p className="notice-kicker">Consulta no válida</p>
          <h3>La búsqueda no es válida</h3>
          <p className="mt-2 text-muted">
            Escribe al menos un carácter con letras o números y como máximo 100 caracteres.
          </p>
        </div>
      ) : null}

      {state.status === "catalogue-not-ready" ? (
        <div className="notice notice-info" role="alert">
          <span className="notice-symbol" aria-hidden="true">◷</span>
          <p className="notice-kicker">Catálogo en preparación</p>
          <h3>El catálogo todavía no está disponible</h3>
          <p className="mt-2 text-muted">
            Aún no hay una publicación local válida del catálogo. No se consulta ningún proveedor
            externo para completarla.
          </p>
          <button className="button button-primary mt-4" onClick={onRetry} type="button">
            Reintentar
          </button>
        </div>
      ) : null}

      {state.status === "error" ? (
        <div className="notice notice-danger" role="alert">
          <span className="notice-symbol notice-symbol-danger" aria-hidden="true">×</span>
          <p className="notice-kicker">Error de carga</p>
          <h3>No se pudo completar la búsqueda</h3>
          <p className="mt-2 text-muted">{state.message}</p>
          {state.correlationId === null ? null : (
            <p className="mt-2 text-sm text-danger">
              Referencia para soporte: {state.correlationId}
            </p>
          )}
          <button className="button button-primary mt-4" onClick={onRetry} type="button">
            Reintentar
          </button>
        </div>
      ) : null}

      {state.status === "ready" && state.isPlaceholderData ? (
        <CatalogueLoading message="Buscando resultados para la nueva consulta…" />
      ) : null}

      {state.status === "ready" && !state.isPlaceholderData ? (
        <>
          <p className="result-count" role="status">
            {resultsSummary(state.model, state.isRefreshing)}
          </p>

          {state.model.results.length === 0 ? (
            <div className="notice notice-empty">
              <span className="notice-symbol" aria-hidden="true">⌕</span>
              <p className="notice-kicker">Sin resultados</p>
              <h3>No hay coincidencias en el catálogo local</h3>
              <p className="text-ink">
                {state.model.page.totalItems > 0 &&
                state.model.page.totalPages > 0 &&
                state.model.page.number > state.model.page.totalPages
                  ? "La página solicitada ya no está disponible para esta búsqueda."
                  : `Ningún juego del catálogo local coincide con “${params.query}”. El catálogo es una selección acotada, así que un título publicado fuera de él no aparece aquí.`}
              </p>
            </div>
          ) : (
            <ul className="release-grid" aria-label="Resultados de la búsqueda">
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
