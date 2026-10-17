import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";

import { CatalogueLoading } from "../../shared/ui/catalogue-loading";
import { HeroTitle } from "../../shared/ui/hero-title";
import { StageLight } from "../../shared/ui/stage-light";
import { ReleaseCard } from "./release-card";
import { ReleaseWindowScale } from "./release-window-scale";
import { ReleasesApproximateDates } from "./releases-approximate-dates";
import { ReleasesFilters } from "./releases-filters";
import { ReleasesPagination } from "./releases-pagination";
import { ReleasesWeeks } from "./releases-weeks";
import {
  hasActiveFilters,
  releasesSearchPath,
  type ReleaseView,
  type ReleasesSearch,
} from "./releases-search";
import { releaseViewTitle, type ReleasesViewModel } from "./releases-view-model";
import { toWindowScale } from "./window-scale";

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

const contexts: Record<ReleaseView, string> = {
  recent: "Ya disponibles",
  upcoming: "En calendario",
};

function resultsSummary(model: ReleasesViewModel, isRefreshing: boolean): string {
  if (isRefreshing) {
    return "Actualizando lanzamientos…";
  }
  const { totalItems, number, totalPages } = model.page;
  const count = totalItems === 1 ? "1 juego" : `${totalItems} juegos`;
  if (totalPages > 0 && number > totalPages) {
    return `${count} · La página ${number} ya no está disponible`;
  }
  return totalPages > 0 ? `${count} · Página ${number} de ${totalPages}` : count;
}

// Exact-only upcoming can be empty while approximate dates exist, so the notice names the opt-in.
function emptyExplanation(search: ReleasesSearch): string {
  return search.view === "upcoming" && !search.includeApproximateDates
    ? "Ningún lanzamiento con fecha exacta coincide con esta ventana y estos filtros. Activa «Incluir fechas aproximadas» para ver también los anunciados por mes, trimestre, año o sin fecha confirmada."
    : "Ningún lanzamiento del catálogo local coincide con esta ventana y estos filtros.";
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
  // The release a visitor points at or focuses lifts its needle on the window's scale.
  const [activeDay, setActiveDay] = useState<string | null>(null);

  useEffect(() => {
    if (previousPage.current === search.page) {
      return;
    }
    previousPage.current = search.page;
    resultsHeadingRef.current?.focus();
  }, [search.page]);

  const model = state.status === "ready" ? state.model : null;
  const isTransitioning = state.status === "ready" && state.isPlaceholderData;
  const shown = model !== null && !isTransitioning ? model : null;
  // The period and its scale wait as quiet placeholders only while a read is under way.
  const waiting = state.status === "loading" || isTransitioning;
  const isBeyondLastPage =
    model !== null &&
    model.page.totalItems > 0 &&
    model.page.totalPages > 0 &&
    model.page.number > model.page.totalPages;
  const scale =
    shown === null ? null : toWindowScale(shown.window.from, shown.window.to, shown.evaluatedOn);
  const releaseDays = (shown?.items ?? []).flatMap((item) => item.releaseGroups[0]?.day ?? []);
  // The opening is lit by the first games the page presents, never by a placeholder image.
  const lights = (shown?.items ?? []).flatMap((item) => (item.cover.kind === "fallback" ? [] : [item.cover.url]));

  return (
    <section aria-labelledby="releases-title" className="hero-page releases-page">
      <StageLight images={lights} />
      <div className="page-container releases-section">
        <header className="page-head releases-heading">
          <p className="eyebrow">{contexts[search.view]}</p>
          <div className="page-head-row">
            <HeroTitle id="releases-title" title={releaseViewTitle(search.view)} />
            {shown === null ? (
              waiting ? <span aria-hidden="true" className="release-period release-period-pending" /> : null
            ) : (
              <p className="release-period">
                <span aria-hidden="true">{shown.windowRange}</span>
                <span className="sr-only">{shown.windowDescription}</span>
              </p>
            )}
          </div>
          {scale === null ? (
            waiting ? <div aria-hidden="true" className="window-scale window-scale-pending" /> : null
          ) : (
            <ReleaseWindowScale activeDay={activeDay} releaseDays={releaseDays} scale={scale} />
          )}
        </header>

        <div className="releases-controls">
          <div className="releases-toolbar">
            <ReleasesWeeks search={search} />
            {/* Filter choices come from the response, so a failed read shows none rather than
                empty placeholders that look like broken controls. */}
            {state.status === "loading" ? <LoadingFilters /> : null}
            {model === null ? null : <ReleasesFilters platforms={model.platforms} regions={model.regions} search={search} />}
          </div>
          {/* The precision choice is URL state, so it stays operable whatever the read state. */}
          {search.view === "upcoming" ? <ReleasesApproximateDates search={search} /> : null}
        </div>

        <h2 className="sr-only" ref={resultsHeadingRef} tabIndex={-1}>
          Resultados
        </h2>

        {state.status === "loading" ? <CatalogueLoading message="Cargando lanzamientos…" /> : null}

        {state.status === "catalogue-not-ready" ? (
          <div className="notice notice-info" role="status">
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
            <p className="notice-kicker">Revisa la selección</p>
            <h3>Filtro no admitido</h3>
            <p>{state.message}</p>
            <Link
              className="button button-primary"
              to={releasesSearchPath(search, { platformIds: [], regionIds: [], page: 1 })}
            >
              Quitar filtros
            </Link>
          </div>
        ) : null}

        {state.status === "error" ? (
          <div className="notice notice-danger" role="alert">
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
                <p className="notice-kicker">Sin resultados</p>
                <h3>
                  {isBeyondLastPage
                    ? "Esta página ya no está disponible"
                    : "Sin lanzamientos para esta selección"}
                </h3>
                <p>
                  {isBeyondLastPage
                    ? "La página solicitada ya no está disponible para estos resultados."
                    : emptyExplanation(search)}
                </p>
                {hasActiveFilters(search) && state.model.page.totalItems === 0 ? (
                  <Link
                    className="button button-primary"
                    to={releasesSearchPath(search, { platformIds: [], regionIds: [], page: 1 })}
                  >
                    Quitar filtros
                  </Link>
                ) : null}
              </div>
            ) : (
              <ul aria-label={releaseViewTitle(state.model.view)} className="release-grid">
                {state.model.items.map((item) => (
                  <li className="release-grid-cell" key={item.gameId}>
                    <ReleaseCard item={item} onPoint={setActiveDay} />
                  </li>
                ))}
              </ul>
            )}
          </>
        ) : null}

        {state.status === "ready" && !state.isPlaceholderData ? (
          <div className="releases-footer">
            <p className="result-count" role="status">
              {resultsSummary(state.model, state.isRefreshing)}
            </p>
            <ReleasesPagination page={state.model.page} search={search} showPosition={false} />
          </div>
        ) : null}
      </div>
    </section>
  );
}
