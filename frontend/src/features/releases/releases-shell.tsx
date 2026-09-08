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
  return `${count} · Página ${number} de ${Math.max(totalPages, 1)}`;
}

function LoadingFilters() {
  return (
    <div className="release-filters" aria-hidden="true">
      <div className="filter-rail">
        <span className="filter-label">Plataforma</span>
        {[72, 96, 110].map((width) => (
          <span className="filter-chip-skeleton" key={width} style={{ width }} />
        ))}
        <span className="filter-divider" />
        <span className="filter-label">Región</span>
        <span className="filter-chip-skeleton" style={{ width: 88 }} />
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
  const hasStaleResults =
    state.status === "ready" && !state.isPlaceholderData && state.model.staleItemCount > 0;

  return (
    <section aria-labelledby="releases-title" className="releases-page">
      {hasStaleResults ? (
        <div className="stale-banner" role="status">
          <div className="page-container">
            <span className="notice-symbol notice-symbol-warning" aria-hidden="true">
              !
            </span>
            <p>Algunos lanzamientos usan la última copia local guardada y pueden estar desactualizados.</p>
          </div>
        </div>
      ) : null}

      <div className="page-container releases-section">
        <div className="releases-heading">
          <div>
            <p className="eyebrow eyebrow-dot">
              {search.view === "recent" ? "Ya disponibles" : "En calendario"}
            </p>
            <h1 className="page-title" id="releases-title">
              {releaseViewTitle(search.view)}
            </h1>
          </div>
          <ReleasesViewNav search={search} />
        </div>

        {model === null || isTransitioning ? null : (
          <p className="release-window">
            <span>{model.windowDescription}</span>
            <span className="release-window-evaluated">{model.evaluatedOnDescription}</span>
          </p>
        )}

        {model === null ? <LoadingFilters /> : <ReleasesFilters platforms={model.platforms} regions={model.regions} search={search} />}

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
              <p className="notice-footnote">Referencia para soporte: {state.correlationId}</p>
            )}
          </div>
        ) : null}

        {state.status === "ready" && state.isPlaceholderData ? (
          <CatalogueLoading message="Cargando lanzamientos para la nueva selección…" />
        ) : null}

        {state.status === "ready" && !state.isPlaceholderData ? (
          <>
            <p className="result-count" role="status">
              {resultsSummary(state.model, state.isRefreshing)}
            </p>

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

            <ReleasesPagination page={state.model.page} search={search} />
          </>
        ) : null}
      </div>
    </section>
  );
}
