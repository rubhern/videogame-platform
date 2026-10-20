import { Link } from "react-router-dom";

import { HeroTitle } from "../../shared/ui/hero-title";
import { FeaturedCard } from "./featured-card";
import { FeaturedHero } from "./featured-hero";
import { FeaturedPeriodSelector } from "./featured-period-selector";
import {
  formatRequestedPeriodPhrase,
  type FeaturedReleasesViewModel,
} from "./featured-releases-view-model";
import type { FeaturedGranularity, FeaturedSearch } from "./featured-search";
import { DialSilhouette } from "./month-dial";
import { DIAL_HEIGHT, DIAL_WIDTH } from "./month-dial-geometry";
import { releasesRangePath } from "./releases-search";

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

/** The period's featured release and up to five more; the row never fills artificial slots. */
const OTHER_PLACEHOLDERS = 5;

/**
 * The page title names TOPómetro and the granularity the URL requests, before any response
 * arrives. The name never states the ranking criterion (#341).
 */
const titles: Record<FeaturedGranularity, string> = {
  week: "TOPómetro de la semana",
  month: "TOPómetro del mes",
};

function liveStatus(state: FeaturedShellState, search: FeaturedSearch): string {
  if (state.status === "loading" || (state.status === "ready" && state.isPlaceholderData)) {
    const phrase =
      search.period === null
        ? null
        : formatRequestedPeriodPhrase(search.granularity, search.period);
    return phrase === null
      ? "Cargando el TOPómetro…"
      : `Cargando el TOPómetro de ${phrase}…`;
  }
  if (state.status === "ready") {
    if (state.isRefreshing) {
      return "Actualizando el TOPómetro…";
    }
    switch (state.model.selection.status) {
      case "ranked":
        return `TOPómetro de ${state.model.periodPhrase}`;
      case "popularity-unavailable":
        return `Aún no hay TOPómetro de ${state.model.periodPhrase}`;
      case "no-qualifying-releases":
        return `No hay lanzamientos en ${state.model.periodPhrase}`;
    }
  }
  return state.status === "catalogue-not-ready" ? "Los juegos todavía no están disponibles" : "";
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
  const week = model.granularity === "week";
  return (
    <div className={`notice ${unranked ? "notice-info" : "notice-empty"}`}>
      <p className="notice-kicker">{unranked ? "Sin datos de Hype" : "Sin lanzamientos"}</p>
      <h2>
        {unranked
          ? `Aún no hay TOPómetro de ${model.periodPhrase}`
          : `No hay lanzamientos en ${model.periodPhrase}`}
      </h2>
      <p>
        {unranked
          ? `De momento, Gameómetro no tiene Hype para lanzamientos de ${week ? "esta semana" : "este mes"}`
          : week
            ? "Gameómetro no tiene lanzamientos con día confirmado dentro de esta semana."
            : "Gameómetro no tiene lanzamientos con fecha dentro de este mes."}
      </p>
      <Link
        className="button button-primary"
        to={releasesRangePath(model.window.from, model.window.to)}
      >
        Explorar lanzamientos
      </Link>
    </div>
  );
}

export function FeaturedReleasesShell({ search, state, onRetry }: FeaturedReleasesShellProps) {
  const model = state.status === "ready" ? state.model : null;
  const showsModel = state.status === "ready" && !state.isPlaceholderData;

  return (
    <section aria-labelledby="featured-title" className="hero-page featured-page">
      <div className="page-container featured-section">
        <div className="featured-heading">
          <HeroTitle id="featured-title" title={titles[search.granularity]} />
          <FeaturedPeriodSelector model={showsModel ? model : null} search={search} />
        </div>

        <p className="sr-only" role="status">
          {liveStatus(state, search)}
        </p>

        {state.status === "loading" ||
        (state.status === "ready" && state.isPlaceholderData) ? (
          <FeaturedLoading />
        ) : null}

        {state.status === "catalogue-not-ready" ? (
          <div className="notice notice-info">
            <p className="notice-kicker">Datos en preparación</p>
            <h2>Los juegos todavía no están disponibles</h2>
            <p>
              Aún no tenemos datos válidos de juegos, pero tranquilo que llegarán
            </p>
            <button className="button" onClick={onRetry} type="button">
              Reintentar
            </button>
            <p className="notice-footnote">Puedes usar la búsqueda de juegos</p>
          </div>
        ) : null}

        {state.status === "error" ? (
          <div className="notice notice-danger" role="alert">
            <p className="notice-kicker">Error de carga</p>
            <h2>Ups, parece que TOPómetro se ha tomado unas vacaciones. Prueba de nuevo más tarde</h2>
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
              granularity={model.granularity}
              lead={model.lead}
              periodPhrase={model.periodPhrase}
              timeline={model.timeline}
            />
            {model.others.length === 0 ? null : (
              <section aria-labelledby="featured-others-title" className="featured-others">
                <div className="featured-others-heading">
                  <h2 className="featured-others-title" id="featured-others-title">
                    Más lanzamientos del TOPómetro
                  </h2>
                  {/* The explorer opens on exactly the represented week or month (#285). */}
                  <Link
                    className="featured-all-link view-action"
                    to={releasesRangePath(model.window.from, model.window.to)}
                  >
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
