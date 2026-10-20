import { useEffect, useId, useRef, useState } from "react";
import { Link, useNavigate } from "react-router-dom";

import { formatCalendarDay, formatDayRange } from "../../shared/catalogue/release-date";
import { CatalogueLoading } from "../../shared/ui/catalogue-loading";
import { HeroTitle } from "../../shared/ui/hero-title";
import { barFrame, toDayBar, type DayFrameState, type DayRange } from "./day-bar";
import { ReleaseCard } from "./release-card";
import { ReleaseDayBar } from "./release-day-bar";
import { ReleasesApproximateDates } from "./releases-approximate-dates";
import {
  clearedPlatforms,
  type Interests,
  type PlatformFilter,
} from "../platform-preferences/interests";
import { useInterestsSwitch } from "../platform-preferences/interests-switch";
import { hasEffectiveFilters } from "./release-filter-paths";
import { ReleasesFilters } from "./releases-filters";
import { ReleasesPagination } from "./releases-pagination";
import { ReleasesRange } from "./releases-range";
import { releaseSortLabels, releasesSearchPath, type ReleasesSearch } from "./releases-search";
import { ReleasesSort } from "./releases-sort";
import { RELEASES_TITLE, type ReleasesViewModel } from "./releases-view-model";

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
  /** The day bar's frame the history entry keeps, when the visitor chose a day inside it. */
  keptFrame?: DayRange | null;
  /** The visitor's preferred platforms and the platform filter they resolve to (#295). */
  interests?: Interests;
  platformFilter?: PlatformFilter;
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

// The approved empty-state copy is shared by every range and date-precision selection.
function emptyExplanation(): string {
  return "No tenemos lanzamientos para estos filtros, pero afinando la búsqueda seguro que los encontramos";
}

/**
 * What the folded phone controls hold: the order, preferences, the active filters and approximate
 * dates. Preferred platforms read as **Mis plataformas**, never as a count of hidden filters.
 */
function controlsSummary(
  search: ReleasesSearch,
  approximate: boolean,
  platformFilter: PlatformFilter,
): string {
  const interestsOn = platformFilter.mode === "on";
  const filters = (interestsOn ? 0 : platformFilter.platformIds.length) + search.regionIds.length;
  return [
    releaseSortLabels[search.sort],
    ...(interestsOn ? ["Mis plataformas"] : []),
    ...(filters === 0 ? [] : [filters === 1 ? "1 filtro" : `${filters} filtros`]),
    ...(approximate ? ["Fechas aproximadas"] : []),
  ].join(" · ");
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

/**
 * Explorar lanzamientos (#285): one explorer over an inclusive range, from the current week by
 * default to any past, future or mixed range. The day bar picks a single day, the date fields set
 * any range, and the order, filters and approximate dates refine it; every choice lives in the URL
 * and returns to the first page.
 */
const NO_INTERESTS: Interests = { status: "unavailable" };

export function ReleasesShell({
  search,
  state,
  onRetry,
  keptFrame = null,
  interests = NO_INTERESTS,
  platformFilter = { mode: "inactive", platformIds: search.platformIds },
}: ReleasesShellProps) {
  const navigate = useNavigate();
  const interestsSwitch = useInterestsSwitch();
  const resultsHeadingRef = useRef<HTMLHeadingElement>(null);
  const previousPage = useRef(search.page);
  // The release a visitor points at or focuses lifts its needle on the day bar.
  const [activeDay, setActiveDay] = useState<string | null>(null);
  // Phones fold the controls under one toggle so the covers stay near the top; wider screens
  // always show them and never draw the toggle.
  const [controlsOpen, setControlsOpen] = useState(false);
  const controlsId = useId();

  useEffect(() => {
    if (previousPage.current === search.page) {
      return;
    }
    previousPage.current = search.page;
    resultsHeadingRef.current?.focus({ preventScroll: true });
  }, [search.page]);

  const model = state.status === "ready" ? state.model : null;
  const isTransitioning = state.status === "ready" && state.isPlaceholderData;
  const shown = model !== null && !isTransitioning ? model : null;
  // The range and its bar wait as quiet placeholders only while a read is under way.
  const waiting = state.status === "loading" || isTransitioning;
  const isBeyondLastPage =
    model !== null &&
    model.page.totalItems > 0 &&
    model.page.totalPages > 0 &&
    model.page.number > model.page.totalPages;
  const frame = shown === null ? null : barFrame(shown.window, keptFrame);
  const bar = shown === null || frame === null ? null : toDayBar(frame, shown.window, shown.evaluatedOn);
  const releaseDays = (shown?.items ?? []).flatMap((item) => item.releaseGroups[0]?.day ?? []);
  // Only the API knows today; until it answers, the URL's own choice stands.
  const evaluatedOn = model?.evaluatedOn ?? null;
  const reachesFuture = (to: string | null) =>
    evaluatedOn === null || to === null || to > evaluatedOn;
  // Without a read the choice cannot be placed against today, but an active one stays removable.
  const approximateOffered =
    model === null ? search.includeApproximateDates : reachesFuture(search.to ?? model.window.to);
  const includesApproximate = search.includeApproximateDates && approximateOffered;
  // Filters, order and pages keep the bar's frame; a typed range draws its own.
  const frameState: DayFrameState | undefined = frame === null ? undefined : { dayFrame: frame };

  function explore(
    change: Partial<ReleasesSearch>,
    navigationState?: DayFrameState,
    interestsEnabled: boolean | null = null,
  ) {
    if (interestsEnabled !== null) interestsSwitch.set(interestsEnabled);
    void navigate(releasesSearchPath(search, { ...change, page: 1 }), {
      preventScrollReset: true,
      state: navigationState,
    });
  }

  /** Every platform and region, with preferences kept but set aside. */
  const clearedPath = releasesSearchPath(search, { platformIds: [], regionIds: [], page: 1 });
  function setAsideInterests() {
    const cleared = clearedPlatforms(interests);
    if (cleared.interestsEnabled !== null) interestsSwitch.set(cleared.interestsEnabled);
  }

  function exploreRange(from: string, to: string, navigationState?: DayFrameState) {
    explore(
      { from, to, includeApproximateDates: includesApproximate && reachesFuture(to) },
      navigationState,
    );
  }

  return (
    <section aria-labelledby="releases-title" className="hero-page releases-page">
      <div className="page-container releases-section">
        <header className="page-head releases-heading">
          <div className="page-head-row">
            <HeroTitle id="releases-title" title={RELEASES_TITLE} />
            {shown === null ? (
              waiting ? <span aria-hidden="true" className="release-period release-period-pending" /> : null
            ) : (
              <p className="release-period">
                <span aria-hidden="true">{shown.windowRange}</span>
                <span className="sr-only">{shown.windowDescription}</span>
              </p>
            )}
          </div>
          {bar === null ? (
            waiting ? <div aria-hidden="true" className="window-scale window-scale-pending" /> : null
          ) : (
            <ReleaseDayBar
              activeDay={activeDay}
              bar={bar}
              onSelectDay={(date) => exploreRange(date, date, { dayFrame: bar.frame })}
              releaseDays={releaseDays}
            />
          )}
          {bar === null ||
          shown === null ||
          (bar.frame.from === shown.window.from && bar.frame.to === shown.window.to) ? null : (
            <button
              className="filter-reset day-bar-whole"
              onClick={() => exploreRange(bar.frame.from, bar.frame.to, { dayFrame: bar.frame })}
              type="button"
            >
              Ver todo el periodo
              <span aria-hidden="true">{`\u00a0·\u00a0${formatDayRange(bar.frame.from, bar.frame.to)}`}</span>
              <span className="sr-only">
                , del {formatCalendarDay(bar.frame.from)} al {formatCalendarDay(bar.frame.to)}
              </span>
            </button>
          )}
        </header>

        <button
          aria-controls={controlsId}
          aria-expanded={controlsOpen}
          aria-label={`Filtros y orden: ${controlsSummary(search, includesApproximate, platformFilter)}`}
          className="releases-controls-toggle"
          onClick={() => setControlsOpen((open) => !open)}
          type="button"
        >
          <span className="releases-controls-toggle-label">Filtros y orden</span>
          <span className="releases-controls-toggle-summary">
            {controlsSummary(search, includesApproximate, platformFilter)}
          </span>
          <span aria-hidden="true" className="releases-controls-toggle-chevron" />
        </button>

        {/* The range and its precision share the first row; order and filters the next. */}
        <div
          className={`releases-controls${controlsOpen ? " releases-controls-open" : ""}`}
          id={controlsId}
        >
          <div className="releases-range-row">
            <ReleasesRange
              evaluated={shown?.window ?? null}
              key={`${search.from ?? ""}|${search.to ?? ""}|${shown?.window.from ?? ""}|${shown?.window.to ?? ""}`}
              onApply={(from, to) => exploreRange(from, to)}
              search={search}
            />
            {approximateOffered ? (
              <ReleasesApproximateDates
                checked={includesApproximate}
                onChange={(checked) => explore({ includeApproximateDates: checked }, frameState)}
              />
            ) : null}
          </div>
          <div className="releases-toolbar">
            <ReleasesSort onChange={(sort) => explore({ sort }, frameState)} sort={search.sort} />
            {/* Filter choices come from the response, so a failed read shows none rather than
                empty placeholders that look like broken controls. */}
            {state.status === "loading" ? <LoadingFilters /> : null}
            {model === null ? null : (
              <ReleasesFilters
                interests={interests}
                platformFilter={platformFilter}
                platforms={model.platforms}
                regions={model.regions}
                search={search}
                state={frameState}
              />
            )}
          </div>
        </div>

        <h2 className="sr-only" ref={resultsHeadingRef} tabIndex={-1}>
          Resultados
        </h2>

        {state.status === "loading" ? <CatalogueLoading message="Cargando lanzamientos…" /> : null}

        {state.status === "catalogue-not-ready" ? (
          <div className="notice notice-info" role="status">
            <p className="notice-kicker">Datos en preparación</p>
            <h3>Los juegos todavía no están disponibles</h3>
            <p>
              Aún no tenemos datos válidos de juegos, pero tranquilo que llegarán
            </p>
            <button className="button" onClick={onRetry} type="button">
              Reintentar
            </button>
            <p className="notice-footnote">Puedes usar la búsqueda de juegos</p>
          </div>
        ) : null}

        {state.status === "unsupported-filters" ? (
          <div className="notice notice-warning" role="alert">
            <p className="notice-kicker">Revisa la selección</p>
            <h3>Filtro no admitido</h3>
            <p>{state.message}</p>
            <Link className="button button-primary" onClick={setAsideInterests} to={clearedPath}>
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
                    : platformFilter.mode === "on"
                      ? "Sin lanzamientos para tus plataformas"
                      : "Sin lanzamientos para esta selección"}
                </h3>
                <p>
                  {isBeyondLastPage
                    ? "Ay, esta página ya no está disponible"
                    : emptyExplanation()}
                </p>
                {platformFilter.mode === "on" && state.model.page.totalItems === 0 ? (
                  <button
                    className="button button-primary"
                    onClick={() => explore({}, frameState, false)}
                    type="button"
                  >
                    Ver todas las plataformas
                  </button>
                ) : hasEffectiveFilters(search, platformFilter) &&
                  state.model.page.totalItems === 0 ? (
                  <Link
                    className="button button-primary"
                    onClick={setAsideInterests}
                    state={frameState}
                    to={clearedPath}
                  >
                    Quitar filtros
                  </Link>
                ) : null}
              </div>
            ) : (
              <ul aria-label={RELEASES_TITLE} className="release-grid">
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
            <ReleasesPagination
              page={state.model.page}
              search={search}
              showPosition={false}
              state={frameState}
            />
          </div>
        ) : null}
      </div>
    </section>
  );
}
