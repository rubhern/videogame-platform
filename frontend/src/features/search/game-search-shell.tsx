import { useEffect, useRef } from "react";

import { CatalogueLoading } from "../../shared/ui/catalogue-loading";
import { HeroTitle } from "../../shared/ui/hero-title";
import { StageLight } from "../../shared/ui/stage-light";

import { GameSearchCard } from "./game-search-card";
import { GameSearchPagination } from "./game-search-pagination";
import { hasSearchableQuery, type GameSearchParams } from "./game-search-params";
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
    totalItems === 1 ? "1 juego" : `${totalItems} juegos`;
  if (totalPages > 0 && number > totalPages) {
    return `${count} · La página ${number} ya no está disponible`;
  }
  return `${count} · Página ${number} de ${Math.max(totalPages, 1)}`;
}

/** Placeholders never outnumber the widest release grid, whatever page size the URL asks for. */
const MAX_PLACEHOLDERS = 12;

/** An active search makes its own query the page title; without one the page invites a search. */
function pageTitle(params: GameSearchParams): string {
  return hasSearchableQuery(params) ? `Resultados para «${params.query}»` : "Buscar juegos";
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
    resultsHeadingRef.current?.focus({ preventScroll: true });
  }, [params.query, params.page]);

  const placeholders = Math.min(params.pageSize, MAX_PLACEHOLDERS);
  // The opening is lit by the first games found, never by a placeholder image.
  const lights =
    state.status === "ready" && !state.isPlaceholderData
      ? state.model.results.flatMap((result) => (result.cover.kind === "fallback" ? [] : [result.cover.url]))
      : [];

  return (
    <section aria-labelledby="search-title" className="hero-page search-page">
      <StageLight images={lights} />
      <div className="page-container search-section">
        <header className="page-head">
          <p className="eyebrow">Búsqueda de juegos</p>
          <HeroTitle id="search-title" title={pageTitle(params)} />
        </header>

        <h2
          className="sr-only"
          ref={resultsHeadingRef}
          tabIndex={-1}
        >
          Resultados
        </h2>

        {state.status === "prompt" ? (
          <div className="notice notice-empty" role="status">
            <p className="notice-kicker">Buscar juegos</p>
            <h3>Encuentra un juego por su título</h3>
            <p>Escribe un título o un título alternativo, seguro que lo tenemos</p>
          </div>
        ) : null}

        {state.status === "loading" ? (
          <CatalogueLoading message="Buscando juegos…" placeholders={placeholders} shape="index" />
        ) : null}

        {state.status === "query-invalid" ? (
          <div className="notice notice-warning" role="alert">
            <p className="notice-kicker">Consulta no válida</p>
            <h3>La búsqueda no es válida</h3>
            <p>Escribe al menos un carácter con letras o números y como máximo 100 caracteres.</p>
          </div>
        ) : null}

        {state.status === "catalogue-not-ready" ? (
          <div className="notice notice-info" role="alert">
            <p className="notice-kicker">Datos en preparación</p>
            <h3>Los juegos todavía no están disponibles</h3>
            <p>
              Aún no tenemos datos válidos de juegos, pero tranquilo que llegarán
            </p>
            <button className="button button-primary" onClick={onRetry} type="button">
              Reintentar
            </button>
          </div>
        ) : null}

        {state.status === "error" ? (
          <div className="notice notice-danger" role="alert">
            <p className="notice-kicker">Error de carga</p>
            <h3>No se pudo completar la búsqueda</h3>
            <p>{state.message}</p>
            <button className="button button-danger" onClick={onRetry} type="button">
              Reintentar
            </button>
            {state.correlationId === null ? null : (
              <p className="notice-reference">Referencia para soporte: {state.correlationId}</p>
            )}
          </div>
        ) : null}

        {state.status === "ready" && state.isPlaceholderData ? (
          <CatalogueLoading
            message="Buscando resultados para la nueva consulta…"
            placeholders={placeholders}
            shape="index"
          />
        ) : null}

        {state.status === "ready" && !state.isPlaceholderData ? (
          <>
            <p className="result-count" role="status">
              {resultsSummary(state.model, state.isRefreshing)}
            </p>

            {state.model.results.length === 0 ? (
              <div className="notice notice-empty">
                <p className="notice-kicker">Sin resultados</p>
                <h3>No hay coincidencias</h3>
                <p className="text-ink">
                  {state.model.page.totalItems > 0 &&
                  state.model.page.totalPages > 0 &&
                  state.model.page.number > state.model.page.totalPages
                    ? "Ay, esta página ya no está disponible"
                    : `No tenemos nada que coincida con «${params.query}», al menos por ahora porque Gameómetro no deja de crecer`}
                </p>
              </div>
            ) : (
              <ul className="search-index" aria-label="Resultados de la búsqueda">
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
      </div>
    </section>
  );
}
