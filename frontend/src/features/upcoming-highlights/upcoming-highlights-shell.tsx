import { useState } from "react";
import { Link } from "react-router-dom";
import { PlatformNavigation } from "../platforms/platform-navigation";

import { formatCalendarDay } from "../../shared/catalogue/release-date";
import { useGameLinkState } from "../../shared/navigation/use-game-link-state";
import { StageLight } from "../../shared/ui/stage-light";
import { platformCataloguePath } from "../platform-catalogue/platform-catalogue-search";
import { PLATFORMS_PATH, type PlatformContext } from "../platforms/platform-context";
import { FeaturedArtFrame } from "../releases/featured-art";
import { platformUpcomingPath } from "./upcoming-highlights-path";
import type { UpcomingHighlight, UpcomingHighlightsViewModel } from "./upcoming-highlights-view-model";
import { UpcomingHorizon } from "./upcoming-horizon";

export type UpcomingHighlightsState =
  | { status: "loading" }
  | { status: "ready"; model: UpcomingHighlightsViewModel }
  | { status: "catalogue-not-ready" }
  | { status: "error"; correlationId: string | null };

type UpcomingHighlightsShellProps = {
  context: PlatformContext;
  /** The other platforms the directory holds games for; null while it is read or unavailable. */
  others: readonly PlatformContext[] | null;
  state: UpcomingHighlightsState;
  onRetry: () => void;
};


/** The arrival in words for assistive technology: platform, day at its precision and region. */
function arrivalPhrase(item: UpcomingHighlight): string {
  const on = item.when.precision === "day" ? "el" : "en";
  return `Llega a ${item.platformName} ${on} ${item.when.full}, ${item.region}.`;
}

/** The compact readout of an arrival: the fire needle, the date at its precision and how near. */
function Arrival({ item }: { item: UpcomingHighlight }) {
  return (
    <p className="upcoming-when">
      <span className="sr-only">{arrivalPhrase(item)}</span>
      <span aria-hidden="true" className="upcoming-when-readout">
        <span className="upcoming-when-needle" />
        <span className="upcoming-when-date">{item.when.short}</span>
        {item.when.countdown === null ? null : (
          <span className="upcoming-when-countdown">{item.when.countdown}</span>
        )}
      </span>
    </p>
  );
}

function LeadEntry({
  item,
  family,
  onActive,
}: {
  item: UpcomingHighlight;
  family: boolean;
  onActive: (position: number | null) => void;
}) {
  const linkState = useGameLinkState();
  return (
    <li
      className="upcoming-lead"
      onBlur={() => onActive(null)}
      onFocus={() => onActive(item.position)}
      onPointerEnter={() => onActive(item.position)}
      onPointerLeave={() => onActive(null)}
    >
      <div className="upcoming-lead-frame">
        <FeaturedArtFrame art={item.art} scale="hero" />
      </div>
      <span aria-hidden="true" className="upcoming-rank upcoming-rank-lead">
        1
      </span>
      <div className="upcoming-lead-copy">
        <p className="upcoming-lead-kicker">El más esperado</p>
        <h3 className="upcoming-lead-title">
          <Link className="upcoming-link" state={linkState} to={item.path}>
            {item.title}
          </Link>
        </h3>
        <Arrival item={item} />
        <p aria-hidden="true" className="upcoming-facts">
          {family ? <span>{item.platformName}</span> : null}
          <span>{item.region}</span>
          {item.genres.map((genre) => (
            <span className="upcoming-genre" key={genre}>
              {genre}
            </span>
          ))}
        </p>
        {item.genres.length === 0 ? null : (
          <p className="sr-only">Géneros: {item.genres.join(", ")}.</p>
        )}
      </div>
    </li>
  );
}

function Entry({
  item,
  family,
  onActive,
}: {
  item: UpcomingHighlight;
  family: boolean;
  onActive: (position: number | null) => void;
}) {
  const linkState = useGameLinkState();
  return (
    <li
      className="upcoming-entry"
      onBlur={() => onActive(null)}
      onFocus={() => onActive(item.position)}
      onPointerEnter={() => onActive(item.position)}
      onPointerLeave={() => onActive(null)}
    >
      <span aria-hidden="true" className="upcoming-rank">
        {item.position}
      </span>
      <div className="upcoming-entry-frame">
        <FeaturedArtFrame art={item.art} scale="card" />
      </div>
      <div className="upcoming-entry-body">
        <h3 className="upcoming-entry-title">
          <Link className="upcoming-link" state={linkState} title={item.title} to={item.path}>
            {item.title}
          </Link>
        </h3>
        <Arrival item={item} />
        {family ? (
          <p aria-hidden="true" className="upcoming-facts upcoming-entry-facts">
            <span>{item.platformName}</span>
          </p>
        ) : null}
      </div>
    </li>
  );
}

function Loading() {
  return (
    <div className="upcoming-loading">
      <p className="sr-only" role="status">
        Cargando los próximos destacados…
      </p>
      <div aria-hidden="true" className="upcoming-horizon upcoming-horizon-placeholder">
        <div className="skeleton upcoming-skeleton-readout" />
        <div className="skeleton upcoming-skeleton-rail" />
      </div>
      <div aria-hidden="true" className="upcoming-list">
        <div className="upcoming-lead upcoming-placeholder">
          <div className="skeleton upcoming-lead-frame" />
          <div className="upcoming-lead-copy">
            <div className="skeleton skeleton-meta skeleton-meta-short" />
            <div className="skeleton skeleton-title" />
            <div className="skeleton skeleton-meta" />
          </div>
        </div>
        {Array.from({ length: 6 }, (_, index) => (
          <div className="upcoming-entry upcoming-placeholder" key={index}>
            <span className="upcoming-rank" />
            <div className="skeleton upcoming-entry-frame" />
            <div className="upcoming-entry-body">
              <div className="skeleton skeleton-title" />
              <div className="skeleton skeleton-meta skeleton-meta-short" />
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

/** Nothing is ranked: say why in the platform's own terms and leave the ways onward open. */
function EmptyHorizon({
  context,
  model,
}: {
  context: PlatformContext;
  model: UpcomingHighlightsViewModel;
}) {
  const unranked = model.selection === "popularity-unavailable";
  return (
    <div className={`notice ${unranked ? "notice-info" : "notice-empty"} upcoming-notice`} role="status">
      <p className="notice-kicker">{unranked ? "Sin datos de Hype" : "Próximos seis meses"}</p>
      <h2>
        {unranked
          ? `Aún no hay Hype para los próximos juegos de ${context.name}`
          : `No conocemos próximos lanzamientos de ${context.name} con fecha en los próximos seis meses`}
      </h2>
      <p>
        {unranked
          ? `Gameómetro conoce juegos que llegan a ${context.name} antes del ${formatCalendarDay(model.window.to)}, pero IGDB aún no registra Hype para ninguno.`
          : "Puede que aún no tengan fecha o que Gameómetro todavía no los conozca."}
      </p>
      <div className="upcoming-notice-links">
        <Link className="button button-primary" to={platformCataloguePath(context.key, undefined, { state: "upcoming" })}>
          Ver todos los próximos juegos
        </Link>
        <Link className="text-link" to={PLATFORMS_PATH}>
          Ver todas las plataformas
        </Link>
      </div>
    </div>
  );
}

/**
 * **Próximos destacados** of one platform (#388): the games still to come on it in the next six
 * months that draw the most attention, by IGDB Hype. Shared platform navigation opens the page; the
 * horizon's readout and scale show the six months and where each game lands, the most anticipated
 * game leads with its art, and the rest follow as a compact numbered run. Attention is never a
 * score.
 */
export function UpcomingHighlightsShell({ context, others, state, onRetry }: UpcomingHighlightsShellProps) {
  const [active, setActive] = useState<number | null>(null);
  const model = state.status === "ready" ? state.model : null;
  const family = context.platformIds.length > 1;
  const upcomingCatalogue = platformCataloguePath(context.key, undefined, { state: "upcoming" });
  const ranked = model !== null && model.lead !== null;

  return (
    <section aria-labelledby="upcoming-title" className="hero-page upcoming-page">
      <StageLight images={model?.lights ?? []} />
      <div className="page-container upcoming">
        <header className="upcoming-head platform-identity-box" data-logo={context.logo}>
          <PlatformNavigation context={context} active="upcoming" others={others}
            pathForPlatform={(platform) => platformUpcomingPath(platform.key)} />
          <h1 className="upcoming-title" id="upcoming-title">
            <span className="sr-only">{`Próximos destacados de ${context.name}`}</span>
            <span aria-hidden="true" className="upcoming-title-words">
              Próximos destacados
            </span>
          </h1>
        </header>

        {state.status === "loading" ? <Loading /> : null}

        {state.status === "catalogue-not-ready" ? (
          <div className="notice notice-info upcoming-notice" role="status">
            <p className="notice-kicker">Datos en preparación</p>
            <h2>Gameómetro aún está preparando sus juegos</h2>
            <p>Vuelve a intentarlo en unos minutos.</p>
            <button className="button button-primary" onClick={onRetry} type="button">
              Reintentar
            </button>
          </div>
        ) : null}

        {state.status === "error" ? (
          <div className="notice notice-danger upcoming-notice" role="alert">
            <p className="notice-kicker">Error de carga</p>
            <h2>No se pudieron leer los próximos destacados</h2>
            <p>A Gameómetro le está costando un poco. Prueba más tarde</p>
            {state.correlationId === null ? null : (
              <p className="notice-reference">Referencia para soporte: {state.correlationId}</p>
            )}
            <button className="button button-danger" onClick={onRetry} type="button">
              Reintentar
            </button>
          </div>
        ) : null}

        {model === null ? null : (
          <>
            <div className="upcoming-horizon">
              <p className="upcoming-window">
                <span className="upcoming-window-label">Próximos 6 meses</span>
                <span aria-hidden="true" className="upcoming-window-range">
                  {model.windowLabel}
                </span>
                <span className="sr-only">{model.windowPhrase}</span>
              </p>
              {ranked && model.horizon !== null ? (
                <UpcomingHorizon active={active} marks={model.horizon.marks} months={model.horizon.months} />
              ) : null}
            </div>

            {model.lead === null ? (
              <EmptyHorizon context={context} model={model} />
            ) : (
              <section aria-labelledby="upcoming-ranking-title" className="upcoming-ranking">
                <h2 className="sr-only" id="upcoming-ranking-title">
                  {`Los más esperados en ${context.name}`}
                </h2>
                <ol aria-labelledby="upcoming-ranking-title" className="upcoming-list">
                  <LeadEntry family={family} item={model.lead} onActive={setActive} />
                  {model.others.map((item) => (
                    <Entry family={family} item={item} key={item.gameId} onActive={setActive} />
                  ))}
                </ol>
                <footer className="upcoming-close">
                  <Link className="button upcoming-cta" to={upcomingCatalogue}>
                    Ver todos los próximos juegos <span aria-hidden="true">→</span>
                  </Link>
                </footer>
              </section>
            )}
          </>
        )}
      </div>
    </section>
  );
}
