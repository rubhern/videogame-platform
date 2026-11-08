import { useState } from "react";
import { Link } from "react-router-dom";

import { formatCalendarDay, formatDayRange } from "../../shared/catalogue/release-date";
import { PlatformMarks } from "../../shared/catalogue/platform-marks";
import { platformIcon } from "../../shared/catalogue/taxonomy-icons";
import { useGameLinkState } from "../../shared/navigation/use-game-link-state";
import { CatalogueCover } from "../../shared/ui/catalogue-cover";
import { explorerRequestSearch } from "../platform-preferences/discovery-requests";
import { resolvePlatformFilter } from "../platform-preferences/interests";
import { useInterestsSwitch } from "../platform-preferences/interests-switch";
import { useInterests } from "../platform-preferences/use-platform-preferences";
import {
  countLabel,
  dayHeading,
  HYPE_LABELS,
  type CalendarDay,
} from "../releases/release-calendar-model";
import {
  CALENDAR_PATH,
  calendarSearchPath,
  dayExplorerPath,
  dayPreviewSearch,
  defaultCalendarSearch,
  toCalendarQuery,
} from "../releases/release-calendar-search";
import { toFailureState } from "../releases/releases-failure";
import { RELEASES_PATH } from "../releases/releases-search";
import type { ReleaseListItem } from "../releases/releases-view-model";
import { useReleaseCalendarQuery } from "../releases/use-release-calendar-query";
import { useReleasesQuery } from "../releases/use-releases-query";
import {
  busiestDay,
  HOME_CALENDAR_DAYS,
  HOME_DAY_GAMES,
  needsFollowingMonth,
  upcomingHighlightDays,
} from "./home-calendar-model";
import { HomeNotice, HomeSectionHead } from "./home-section";

/** A day the explorer never reads, so a disabled preview keeps a stable, valid search. */
const NO_DAY = "0001-01-01";

const SHORT_WEEKDAYS: Record<string, string> = {
  lunes: "lun",
  martes: "mar",
  "miércoles": "mié",
  jueves: "jue",
  viernes: "vie",
  "sábado": "sáb",
  domingo: "dom",
};

const SHORT_MONTHS = ["ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic"];

/** `jue`, `29`, `oct` from the model's own day; the label keeps the full sentence. */
function dayParts(day: CalendarDay) {
  const weekday = day.label.split(",")[0] ?? "";
  const month = Number(day.date.slice(5, 7));
  return { weekday: SHORT_WEEKDAYS[weekday] ?? weekday, month: SHORT_MONTHS[month - 1] ?? "" };
}

function DayGame({ item }: { item: ReleaseListItem }) {
  const linkState = useGameLinkState();
  const gamePath = `/games/${item.gameId}/${item.slug}`;
  const [group] = item.releaseGroups;
  return (
    <article className="catalogue-card home-day-game">
      <CatalogueCover caption={false} cover={item.cover} linkState={linkState} to={gamePath} />
      <h4 className="card-title home-day-game-title">
        <Link state={linkState} title={item.title} to={gamePath}>
          {item.title}
        </Link>
      </h4>
      {group === undefined ? null : (
        <PlatformMarks
          label={`Plataformas de ${item.title}`}
          marks={group.platforms.map((name, index) => ({
            key: group.platformIds[index] ?? `${name}-${index}`,
            name,
            icon: platformIcon(group.platformIds[index] ?? "", name),
          }))}
          visible={3}
        />
      )}
    </article>
  );
}

function DayGamesLoading({ count }: { count: number }) {
  return (
    <ul aria-hidden="true" className="home-day-games">
      {Array.from({ length: count }, (_, index) => (
        <li className="home-day-game" key={index}>
          <span className="skeleton skeleton-cover" />
          <span className="skeleton skeleton-title" />
        </li>
      ))}
    </ul>
  );
}

export function CalendarLoading() {
  return (
    <div aria-hidden="true" className="home-calendar-loading">
      <ul className="home-days">
        {Array.from({ length: HOME_CALENDAR_DAYS }, (_, index) => (
          <li key={index}>
            <span className="skeleton home-day-skeleton" />
          </li>
        ))}
      </ul>
      <DayGamesLoading count={HOME_DAY_GAMES} />
    </div>
  );
}

/**
 * The Calendario de destacados in brief (#382, #250): the next days from today with highlighted
 * releases under `UC-016`, each with its count and the calendar's interest level, and the chosen
 * day's leading games in the calendar's own order. Preferred platforms apply as on the calendar
 * (#295). The full calendar and Explorar lanzamientos stay one step away; nothing here invents a
 * day for a release known only to a coarser period.
 */
export function HomeCalendarPreview() {
  const interests = useInterests();
  const { enabled } = useInterestsSwitch();
  const filter = resolvePlatformFilter([], enabled, interests);
  const ready = interests.status !== "pending";
  const current = useReleaseCalendarQuery(toCalendarQuery(defaultCalendarSearch, filter.platformIds), ready);
  const currentModel = current.data !== undefined && !current.isPlaceholderData ? current.data : null;
  // The month after the served one is read only when the served month runs short of days.
  const followingMonth = currentModel !== null && needsFollowingMonth(currentModel) ? currentModel.next : null;
  const following = useReleaseCalendarQuery(
    toCalendarQuery({ ...defaultCalendarSearch, month: followingMonth ?? currentModel?.month ?? null }, filter.platformIds),
    ready && followingMonth !== null,
  );
  const followingModel =
    followingMonth !== null && following.data !== undefined && !following.isPlaceholderData ? following.data : null;
  const days = currentModel === null ? [] : upcomingHighlightDays(currentModel, followingModel);
  const waitingForFollowing = followingMonth !== null && following.isPending && days.length === 0;

  const [chosen, setChosen] = useState<string | null>(null);
  const selected = days.find((day) => day.date === chosen) ?? busiestDay(days);
  const preview = useReleasesQuery(
    explorerRequestSearch(
      dayPreviewSearch(defaultCalendarSearch, selected?.date ?? NO_DAY, selected?.gameCount ?? 0, HOME_DAY_GAMES),
      enabled,
      interests,
    ),
    ready && selected !== null,
  );

  const first = days[0];
  const last = days.at(-1);
  const interestsOn = filter.mode === "on";

  let body;
  if (current.isPending || current.isPlaceholderData || waitingForFollowing) {
    body = <CalendarLoading />;
  } else if (current.isError) {
    const failure = toFailureState(current.error);
    body =
      failure.status === "catalogue-not-ready" ? (
        <HomeNotice
          actions={
            <button className="button" onClick={() => void current.refetch()} type="button">
              Reintentar
            </button>
          }
          kicker="Datos en preparación"
          title="Los juegos todavía no están disponibles"
          tone="info"
        >
          <p>Aún no tenemos datos válidos de juegos, pero tranquilo que llegarán.</p>
        </HomeNotice>
      ) : (
        <HomeNotice
          actions={
            <button className="button button-danger" onClick={() => void current.refetch()} type="button">
              Reintentar
            </button>
          }
          correlationId={failure.status === "error" ? failure.correlationId : null}
          kicker="Error de carga"
          title="El calendario no ha podido cargarse"
          tone="danger"
        >
          <p>{failure.message}</p>
        </HomeNotice>
      );
  } else if (selected === null) {
    body = (
      <HomeNotice
        actions={
          <Link className="button" to={RELEASES_PATH}>
            Explorar lanzamientos
          </Link>
        }
        kicker="Sin destacados"
        title={interestsOn ? "Tus plataformas no tienen lanzamientos destacados a la vista" : "No hay lanzamientos destacados a la vista"}
        tone="empty"
      >
        <p>
          Gameómetro no tiene lanzamientos destacados con día confirmado en lo que queda de mes
          {followingMonth === null ? "" : " ni en el siguiente"}. El explorador sigue teniendo todos los lanzamientos.
        </p>
      </HomeNotice>
    );
  } else {
    let games;
    if (preview.isError) {
      games = (
        <HomeNotice
          actions={
            <button className="button button-danger" onClick={() => void preview.refetch()} type="button">
              Reintentar
            </button>
          }
          correlationId={preview.error.correlationId}
          kicker="Error de carga"
          title="No hemos podido leer los juegos de ese día"
          tone="danger"
        />
      );
    } else if (preview.data === undefined || preview.isPlaceholderData) {
      games = <DayGamesLoading count={Math.min(HOME_DAY_GAMES, selected.gameCount)} />;
    } else {
      games = (
        <ul aria-label={`Lanzamientos destacados del ${dayHeading(selected.date).toLowerCase()}`} className="home-day-games">
          {preview.data.items.map((item) => (
            <li key={item.gameId}>
              <DayGame item={item} />
            </li>
          ))}
        </ul>
      );
    }
    body = (
      <>
        <ul aria-label="Días con lanzamientos destacados" className="home-days">
          {days.map((day) => {
            const parts = dayParts(day);
            const pressed = day.date === selected.date;
            return (
              <li key={day.date}>
                <button
                  aria-label={`${day.label}: ${countLabel(day.gameCount)}${day.today ? ", hoy" : ""}`}
                  aria-pressed={pressed}
                  className="home-day"
                  data-density={day.density}
                  data-today={day.today ? "true" : undefined}
                  onClick={() => setChosen(day.date)}
                  title={HYPE_LABELS[day.density]}
                  type="button"
                >
                  <span className="home-day-weekday">{day.today ? "hoy" : parts.weekday}</span>
                  <span className="home-day-number">{day.day}</span>
                  <span className="home-day-month">{parts.month}</span>
                  <span className="home-day-count">
                    <span className="home-day-dot" />
                    {day.gameCount}
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
        <div className="home-day-panel">
          <div className="home-day-head">
            <h3 className="home-day-title">{dayHeading(selected.date)}</h3>
            <p className="home-day-total">{countLabel(selected.gameCount)}</p>
          </div>
          {games}
          <div className="home-day-actions">
            {/* The explorer on that day, in the preview's order and beyond the highlights. */}
            <Link className="view-action home-day-action" to={dayExplorerPath(defaultCalendarSearch, selected.date)}>
              Ver todos los lanzamientos del día <span aria-hidden="true">→</span>
            </Link>
            <Link className="text-link home-day-calendar" to={calendarSearchPath(defaultCalendarSearch, { day: selected.date })}>
              Abrir este día en el calendario
            </Link>
          </div>
        </div>
      </>
    );
  }

  return (
    <section aria-labelledby="home-calendar-title" className="home-calendar">
      <HomeSectionHead
        action={{ label: "Ver calendario", to: CALENDAR_PATH }}
        id="home-calendar-title"
        lead="Elige un día y mira qué sale"
        readout={
          first === undefined || last === undefined ? undefined : (
            <>
              <span aria-hidden="true">{formatDayRange(first.date, last.date)}</span>
              <span className="sr-only">{`Del ${formatCalendarDay(first.date)} al ${formatCalendarDay(last.date)}`}</span>
            </>
          )
        }
        title="Calendario de destacados"
      />
      {body}
    </section>
  );
}
