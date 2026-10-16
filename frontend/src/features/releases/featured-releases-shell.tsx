import { Link } from "react-router-dom";

import { HeroTitle } from "../../shared/ui/hero-title";
import { FeaturedCard } from "./featured-card";
import { FeaturedHero } from "./featured-hero";
import { FeaturedMonthSelector } from "./featured-month-selector";
import {
  formatMonthPhrase,
  type FeaturedReleasesViewModel,
} from "./featured-releases-view-model";
import type { FeaturedSearch } from "./featured-search";
import { DialSilhouette } from "./month-dial";
import { DIAL_HEIGHT, DIAL_WIDTH } from "./month-dial-geometry";
import { readReleasesSearch, releasesSearchPath } from "./releases-search";

export type FeaturedShellState =
  | { status: "loading" }
  | {
      status: "ready";
      model: FeaturedReleasesViewModel;
      isRefreshing: boolean;
      isPlaceholderData: boolean;
    }
  | { status: "catalogue-not-ready" }
  | { status: "error"; message: string; correlationId: string | null };

type FeaturedReleasesShellProps = {
  search: FeaturedSearch;
  state: FeaturedShellState;
  onRetry: () => void;
};

/** The row's link and an unranked or empty month's notice open the release lists, on recent. */
const releasesPath = releasesSearchPath(readReleasesSearch(new URLSearchParams()), {
  view: "recent",
});

/** The month's featured release and up to five more; the row never fills artificial slots. */
const OTHER_PLACEHOLDERS = 5;

function liveStatus(state: FeaturedShellState, month: string | null): string {
  if (state.status === "loading" || (state.status === "ready" && state.isPlaceholderData)) {
    return month === null
      ? "Cargando lanzamientos destacados…"
      : `Cargando lanzamientos destacados de ${formatMonthPhrase(month)}…`;
  }
  if (state.status === "ready") {
    if (state.isRefreshing) {
      return "Actualizando lanzamientos destacados…";
    }
    switch (state.model.selection.status) {
      case "ranked":
        return `Lanzamientos destacados de ${state.model.monthPhrase}`;
      case "popularity-unavailable":
        return `Aún no hay lanzamientos destacados en ${state.model.monthPhrase}`;
      case "no-qualifying-releases":
        return `No hay lanzamientos en ${state.model.monthPhrase}`;
    }
  }
  return state.status === "catalogue-not-ready" ? "El catálogo todavía no está disponible" : "";
}

function FeaturedLoading() {
  return (
    <div aria-hidden="true" className="featured-loading">
      <div className="featured-hero featured-hero-placeholder">
        <div className="featured-hero-visual">
          <span className="skeleton featured-skeleton-art" />
        </div>
        <div className="featured-dial">
          <DialSilhouette
            className="featured-dial-g"
            height={DIAL_HEIGHT}
            width={DIAL_WIDTH}
          />
          <div className="featured-hero-body">
            <span className="skeleton featured-skeleton-badge" />
            <span className="skeleton featured-skeleton-title" />
            <span className="skeleton featured-skeleton-title featured-skeleton-title-short" />
            <span className="skeleton skeleton-meta" />
          </div>
        </div>
      </div>
      <div className="featured-others">
        <span className="skeleton featured-skeleton-heading" />
        <div className="featured-row">
          {Array.from({ length: OTHER_PLACEHOLDERS }, (_, index) => (
            <div className="loading-card" key={index}>
              <div className="skeleton featured-skeleton-art" />
              <div className="skeleton featured-skeleton-strip" />
              <div className="skeleton skeleton-title" />
              <div className="skeleton skeleton-meta" />
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

function UnrankedNotice({ model }: { model: FeaturedReleasesViewModel }) {
  const unranked = model.selection.status === "popularity-unavailable";
  return (
    <div className={`notice ${unranked ? "notice-info" : "notice-empty"}`}>
      <span aria-hidden="true" className="notice-symbol">
        {unranked ? "◷" : <span className="search-icon" />}
      </span>
      <p className="notice-kicker">{unranked ? "Sin datos de atención" : "Sin lanzamientos"}</p>
      <h2>
        {unranked
          ? `Aún no hay lanzamientos destacados en ${model.monthPhrase}`
          : `No hay lanzamientos en ${model.monthPhrase}`}
      </h2>
      <p>
        {unranked
          ? "Este mes tiene lanzamientos, pero el catálogo local todavía no tiene la señal de atención necesaria para destacarlos."
          : "El catálogo local no tiene lanzamientos con fecha dentro de este mes."}
      </p>
      <Link className="button button-primary" to={releasesPath}>
        Ver lanzamientos recientes
      </Link>
    </div>
  );
}

export function FeaturedReleasesShell({ search, state, onRetry }: FeaturedReleasesShellProps) {
  const model = state.status === "ready" ? state.model : null;
  const showsModel = state.status === "ready" && !state.isPlaceholderData;
  // The URL names any month other than the current one before its data arrives.
  const displayMonth = search.month ?? model?.month ?? null;

  return (
    <section aria-labelledby="featured-title" className="hero-page featured-page">
      <div className="page-container featured-section">
        <div className="featured-heading">
          <HeroTitle accent="del mes" id="featured-title" lead="Lanzamientos" />
          <FeaturedMonthSelector currentMonth={model?.currentMonth ?? null} month={displayMonth} />
        </div>

        <p className="sr-only" role="status">
          {liveStatus(state, displayMonth)}
        </p>

        {state.status === "loading" ||
        (state.status === "ready" && state.isPlaceholderData) ? (
          <FeaturedLoading />
        ) : null}

        {state.status === "catalogue-not-ready" ? (
          <div className="notice notice-info">
            <span aria-hidden="true" className="notice-symbol">
              ◷
            </span>
            <p className="notice-kicker">Catálogo en preparación</p>
            <h2>El catálogo todavía no está disponible</h2>
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

        {state.status === "error" ? (
          <div className="notice notice-danger" role="alert">
            <span aria-hidden="true" className="notice-symbol notice-symbol-danger">
              ×
            </span>
            <p className="notice-kicker">Error de carga</p>
            <h2>No se pudieron cargar los lanzamientos destacados</h2>
            <p>{state.message}</p>
            <button className="button button-danger" onClick={onRetry} type="button">
              Reintentar
            </button>
            {state.correlationId === null ? null : (
              <p className="notice-reference">Referencia para soporte: {state.correlationId}</p>
            )}
          </div>
        ) : null}

        {showsModel && model !== null && model.lead === null ? (
          <UnrankedNotice model={model} />
        ) : null}

        {showsModel && model !== null && model.lead !== null ? (
          <>
            <FeaturedHero
              key={model.lead.gameId}
              lead={model.lead}
              monthPhrase={model.monthPhrase}
              timeline={model.timeline}
            />
            {model.others.length === 0 ? null : (
              <section aria-labelledby="featured-others-title" className="featured-others">
                <div className="featured-others-heading">
                  <h2 className="featured-others-title" id="featured-others-title">
                    Otros lanzamientos <span className="title-accent">destacados</span>
                  </h2>
                  <Link className="featured-all-link view-action" to={releasesPath}>
                    Ver todos los lanzamientos <span aria-hidden="true">→</span>
                  </Link>
                </div>
                <ul className="featured-row">
                  {model.others.map((item) => (
                    <li className="min-w-0" key={item.gameId}>
                      <FeaturedCard item={item} timeline={model.timeline} />
                    </li>
                  ))}
                </ul>
              </section>
            )}
          </>
        ) : null}
      </div>
    </section>
  );
}
