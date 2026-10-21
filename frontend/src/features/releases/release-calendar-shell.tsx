import { Link, useNavigate } from "react-router-dom";

import { HeroTitle } from "../../shared/ui/hero-title";
import {
  clearedPlatforms,
  type Interests,
  type PlatformFilter,
} from "../platform-preferences/interests";
import { useInterestsSwitch } from "../platform-preferences/interests-switch";
import { ReleaseCalendarDay, type CalendarDayPreview } from "./release-calendar-day";
import { ReleaseCalendarGrid } from "./release-calendar-grid";
import { WEEKDAY_HEADERS, type ReleaseCalendarModel } from "./release-calendar-model";
import { ReleaseCalendarMonths } from "./release-calendar-months";
import {
  calendarSearchPath,
  dayExplorerPath,
  type CalendarSearch,
} from "./release-calendar-search";
import { hasEffectiveFilters } from "./release-filter-paths";
import { ReleaseViewSwitch } from "./release-view-switch";
import type { ReleasesFailureState } from "./releases-failure";
import { ReleasesFilters } from "./releases-filters";
import { releasesSearchPath, defaultReleasesSearch } from "./releases-search";

export type ReleaseCalendarShellState =
  | { status: "loading" }
  | { status: "ready"; model: ReleaseCalendarModel }
  | ReleasesFailureState;

type ReleaseCalendarShellProps = {
  search: CalendarSearch;
  state: ReleaseCalendarShellState;
  /** The last served month while another one loads, so its filters stay operable. */
  previousModel: ReleaseCalendarModel | null;
  selectedDay: string | null;
  dayPreview: CalendarDayPreview;
  onRetry: () => void;
  onRetryDay: () => void;
  interests: Interests;
  platformFilter: PlatformFilter;
};

export const CALENDAR_TITLE = "Calendario de destacados";

/** The month's frame while its first read is under way, shaped like the calendar it replaces. */
function CalendarLoading() {
  return (
    <div className="calendar-loading">
      <p className="result-count" role="status">
        Cargando calendario…
      </p>
      <div aria-hidden="true" className="calendar-grid calendar-grid-pending">
        <div className="calendar-pending-weekdays">
          {WEEKDAY_HEADERS.map((weekday) => (
            <span key={weekday.name}>
              <span className="calendar-weekday-name">{weekday.name}</span>
              <span className="calendar-weekday-letter">{weekday.letter}</span>
            </span>
          ))}
        </div>
        <div className="calendar-pending-days">
          {Array.from({ length: 35 }, (_, index) => (
            <span className="skeleton calendar-pending-day" key={index} />
          ))}
        </div>
      </div>
    </div>
  );
}

/**
 * The release calendar (#250): one month at a time, from the current month on, as a
 * Monday-to-Sunday calendar, active from today, whose days state how many games release on them and
 * take the colour of that density. Choosing a day shows its leading games beneath; releases known
 * only to a month, quarter or year are never placed on a day. Month, day and filters live in the
 * URL; preferred platforms apply as in the explorer.
 */
export function ReleaseCalendarShell({
  search,
  state,
  previousModel,
  selectedDay,
  dayPreview,
  onRetry,
  onRetryDay,
  interests,
  platformFilter,
}: ReleaseCalendarShellProps) {
  const navigate = useNavigate();
  const interestsSwitch = useInterestsSwitch();
  const model = state.status === "ready" ? state.model : null;
  const filtersModel = model ?? previousModel;
  const monthPath = (month: string) => calendarSearchPath(search, { month, day: null });
  const listPath = releasesSearchPath(defaultReleasesSearch, {
    ...(model === null ? {} : { from: model.window.from, to: model.window.to }),
    platformIds: search.platformIds,
    regionIds: search.regionIds,
  });
  const clearedPath = calendarSearchPath(search, { platformIds: [], regionIds: [] });
  const selected =
    model === null || selectedDay === null
      ? null
      : (model.days.find((day) => day.date === selectedDay) ?? null);

  function setAsideInterests() {
    const cleared = clearedPlatforms(interests);
    if (cleared.interestsEnabled !== null) interestsSwitch.set(cleared.interestsEnabled);
  }

  function selectDay(date: string) {
    void navigate(calendarSearchPath(search, { month: model?.month ?? search.month, day: date }), {
      preventScrollReset: true,
      replace: true,
    });
  }

  return (
    <section aria-labelledby="calendar-title" className="hero-page releases-page calendar-page">
      <div className="page-container releases-section">
        <header className="page-head calendar-heading">
          <div className="page-head-row">
            <HeroTitle id="calendar-title" title={CALENDAR_TITLE} />
            <ReleaseViewSwitch
              calendarPath={calendarSearchPath(search, {})}
              current="calendar"
              listPath={listPath}
            />
          </div>
          {model === null ? (
            state.status === "loading" ? (
              <span aria-hidden="true" className="release-period release-period-pending" />
            ) : null
          ) : (
            <p className="release-period calendar-period">{model.monthLabel}</p>
          )}
        </header>

        <div className="calendar-toolbar">
          {filtersModel === null ? (
            state.status === "loading" ? (
              <div aria-hidden="true" className="release-filters release-filters-selects">
                <div className="release-select-row">
                  <span className="release-select-skeleton" />
                  <span className="release-select-skeleton" />
                </div>
              </div>
            ) : (
              <span />
            )
          ) : (
            <ReleasesFilters
              interests={interests}
              pathFor={(change) => calendarSearchPath(search, { ...change, day: null })}
              platformFilter={platformFilter}
              platforms={filtersModel.platforms}
              regionIds={search.regionIds}
              regions={filtersModel.regions}
            />
          )}
          {state.status === "loading" || state.status === "ready" ? (
            <ReleaseCalendarMonths model={model ?? previousModel} pathFor={monthPath} />
          ) : null}
        </div>

        {state.status === "loading" ? <CalendarLoading /> : null}

        {state.status === "catalogue-not-ready" ? (
          <div className="notice notice-info" role="status">
            <p className="notice-kicker">Datos en preparación</p>
            <h3>Los juegos todavía no están disponibles</h3>
            <p>Aún no tenemos datos válidos de juegos, pero tranquilo que llegarán</p>
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
            <h3>No se pudo cargar el calendario</h3>
            <p>{state.message}</p>
            <button className="button button-danger" onClick={onRetry} type="button">
              Reintentar
            </button>
            {state.correlationId === null ? null : (
              <p className="notice-reference">Referencia para soporte: {state.correlationId}</p>
            )}
          </div>
        ) : null}

        {model === null ? null : (
          <>
            <ReleaseCalendarGrid model={model} onSelectDay={selectDay} selectedDay={selectedDay} />


            {model.daysWithReleases === 0 ? (
              <div className="notice notice-empty" role="status">
                <p className="notice-kicker">Sin resultados</p>
                <h3>
                  {platformFilter.mode === "on"
                    ? "Sin destacados para tus plataformas"
                    : "Sin destacados con día confirmado"}
                </h3>
                <p>
                  {`No tenemos destacados con día confirmado en ${model.monthLabel.toLowerCase()} para estos filtros`}
                </p>
                {platformFilter.mode === "on" ? (
                  <button
                    className="button button-primary"
                    onClick={() => interestsSwitch.set(false)}
                    type="button"
                  >
                    Ver todas las plataformas
                  </button>
                ) : hasEffectiveFilters(search, platformFilter) ? (
                  <Link className="button button-primary" onClick={setAsideInterests} to={clearedPath}>
                    Quitar filtros
                  </Link>
                ) : null}
              </div>
            ) : selected === null ? (
              <p className="calendar-day-hint">Elige un día para ver sus destacados.</p>
            ) : (
              <ReleaseCalendarDay
                date={selected.date}
                explorerPath={dayExplorerPath(search, selected.date)}
                gameCount={selected.gameCount}
                onRetry={onRetryDay}
                preview={dayPreview}
              />
            )}
          </>
        )}
      </div>
    </section>
  );
}
