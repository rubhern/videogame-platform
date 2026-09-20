import { useEffect, useRef } from "react";
import { Link } from "react-router-dom";

import { CatalogueLoading } from "../../shared/ui/catalogue-loading";
import { ReleaseCard } from "./release-card";
import { ReleasesFilters } from "./releases-filters";
import { ReleasesPagination } from "./releases-pagination";
import { hasActiveFilters, releasesSearchPath, type ReleasesSearch } from "./releases-search";
import { releaseViewTitle, type ReleasesViewModel } from "./releases-view-model";
import { ReleasesViewNav } from "./releases-view-nav";

export type ReleasesShellState =
  | { status: "loading" }
  | {
      status: "ready";
      model: ReleasesViewModel;
      isRefreshing: boolean;
      isPlaceholderData: boolean;
    }
  | { status: "catalogue-not-ready" }
  | { status: "unsupported-filters"; message: string }
  | { status: "error"; message: string; correlationId: string | null };

type ReleasesShellProps = {
  search: ReleasesSearch;
  state: ReleasesShellState;
  onRetry: () => void;
};

function resultsSummary(model: ReleasesViewModel, isRefreshing: boolean): string {
  if (isRefreshing) {
    return "Actualizando lanzamientos…";
  }
  const { totalItems, number, totalPages } = model.page;
  const count = totalItems === 1 ? "1 lanzamiento" : `${totalItems} lanzamientos`;
  if (totalPages > 0 && number > totalPages) {
    return `${count} · La página ${number} ya no está disponible`;
  }
  return count;
}

function LoadingFilters() {
  return (
    <div className="release-filters release-filters-selects" aria-hidden="true">
      <div className="release-select-row">
        <span className="release-select-skeleton" />
        <span className="release-select-skeleton" />
      </div>
    </div>
  );
}

export function ReleasesShell({ search, state, onRetry }: ReleasesShellProps) {
  const resultsHeadingRef = useRef<HTMLHeadingElement>(null);
  const previousPage = useRef(search.page);

  useEffect(() => {
    if (previousPage.current === search.page) {
      return;
    }
    previousPage.current = search.page;
    resultsHeadingRef.current?.focus();
  }, [search.page]);

  const model = state.status === "ready" ? state.model : null;
  const isTransitioning = state.status === "ready" && state.isPlaceholderData;
  const isBeyondLastPage =
    model !== null &&
    model.page.totalItems > 0 &&
    model.page.totalPages > 0 &&
    model.page.number > model.page.totalPages;
  return (
    <section aria-labelledby="releases-title" className="releases-page">
      <div className="page-container releases-section">
        <div className="releases-heading">
          <div>
            <div className="releases-kicker-row">
              <p className="eyebrow eyebrow-dot">
                {search.view === "recent" ? "Ya disponibles" : "En calendario"}
              </p>
              {model !== null && !isTransitioning ? (
                <p className="release-period">
                  <svg aria-hidden="true" fill="none" viewBox="0 0 20 20">
                    <rect x="2.5" y="4.5" width="15" height="13" rx="2" stroke="currentColor" strokeWidth="1.5" />
                    <path d="M6 2.5v4M14 2.5v4M2.5 8.5h15" stroke="currentColor" strokeLinecap="round" strokeWidth="1.5" />
                  </svg>
                  <span className="release-period-full">{model.windowDescription}</span>
                  <span className="release-period-compact">{model.compactWindowDescription}</span>
                </p>
              ) : null}
            </div>
            <h1 className="page-title" id="releases-title">
              {releaseViewTitle(search.view)}
            </h1>
          </div>
        </div>

        <div className="releases-toolbar">
          {model === null ? <LoadingFilters /> : <ReleasesFilters platforms={model.platforms} regions={model.regions} search={search} />}
        </div>

        <h2 className="sr-only" ref={resultsHeadingRef} tabIndex={-1}>
          Resultados
        </h2>

        {state.status === "loading" ? <CatalogueLoading message="Cargando lanzamientos…" /> : null}

        {state.status === "catalogue-not-ready" ? (
          <div className="notice notice-info" role="status">
            <span className="notice-symbol" aria-hidden="true">
              ◷
            </span>
            <p className="notice-kicker">Catálogo en preparación</p>
            <h3>El catálogo todavía no está disponible</h3>
            <p>
              Aún no hay una publicación local válida del catálogo. No se consulta ningún proveedor
              externo para completarla.
            </p>
            <button className="button" onClick={onRetry} type="button">
              Reintentar
            </button>
            <p className="notice-footnote">La búsqueda de juegos sigue disponible</p>
          </div>
        ) : null}

        {state.status === "unsupported-filters" ? (
          <div className="notice notice-warning" role="alert">
            <span className="notice-symbol notice-symbol-warning" aria-hidden="true">
              !
            </span>
            <p className="notice-kicker">Revisa la selección</p>
            <h3>Filtro no admitido</h3>
            <p>{state.message}</p>
            <Link
              className="button button-primary"
              to={releasesSearchPath(search, { platformId: null, regionId: null, page: 1 })}
            >
              Quitar filtros
            </Link>
          </div>
        ) : null}

        {state.status === "error" ? (
          <div className="notice notice-danger" role="alert">
            <span className="notice-symbol notice-symbol-danger" aria-hidden="true">
              ×
            </span>
            <p className="notice-kicker">Error de carga</p>
            <h3>No se pudieron cargar los lanzamientos</h3>
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
          <CatalogueLoading message="Cargando lanzamientos para la nueva selección…" />
        ) : null}

        {state.status === "ready" && !state.isPlaceholderData ? (
          <>
            {state.model.items.length === 0 ? (
              <div className="notice notice-empty" role="status">
                <span className="notice-symbol" aria-hidden="true">
                  ⌕
                </span>
                <p className="notice-kicker">Sin resultados</p>
                <h3>
                  {isBeyondLastPage
                    ? "Esta página ya no está disponible"
                    : "Sin lanzamientos para esta selección"}
                </h3>
                <p>
                  {isBeyondLastPage
                    ? "La página solicitada ya no está disponible para estos resultados."
                    : "Ningún lanzamiento del catálogo local coincide con esta ventana y estos filtros."}
                </p>
                {hasActiveFilters(search) && state.model.page.totalItems === 0 ? (
                  <Link
                    className="button button-primary"
                    to={releasesSearchPath(search, { platformId: null, regionId: null, page: 1 })}
                  >
                    Quitar filtros
                  </Link>
                ) : null}
              </div>
            ) : (
              <ul aria-label={releaseViewTitle(state.model.view)} className="release-grid">
                {state.model.items.map((item) => (
                  <li className="min-w-0" key={item.releaseId}>
                    <ReleaseCard item={item} />
                  </li>
                ))}
              </ul>
            )}
          </>
        ) : null}

        <div className="releases-footer">
          {state.status === "ready" && !state.isPlaceholderData ? (
            <>
              <p className="result-count" role="status">
                {resultsSummary(state.model, state.isRefreshing)}
              </p>
              <ReleasesPagination page={state.model.page} search={search} showPosition={false} />
            </>
          ) : null}
          <ReleasesViewNav search={search} />
        </div>
      </div>
    </section>
  );
}
