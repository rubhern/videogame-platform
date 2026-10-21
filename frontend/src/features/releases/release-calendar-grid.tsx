import { useId, useRef, useState, type KeyboardEvent } from "react";

import { calendarDay } from "./releases-search";
import {
  WEEKDAY_HEADERS,
  HYPE_LABELS,
  countLabel,
  type CalendarDay,
  type ReleaseCalendarModel,
} from "./release-calendar-model";

type ReleaseCalendarGridProps = {
  model: ReleaseCalendarModel;
  /** The chosen day, shown pressed; null when none is. */
  selectedDay: string | null;
  onSelectDay: (date: string) => void;
};

function dayName(day: CalendarDay): string {
  return `${day.label}: ${day.gameCount === 0 ? "sin lanzamientos" : countLabel(day.gameCount)}${
    day.today ? ", hoy" : ""
  }`;
}

/** Monday 0 to Sunday 6. */
function weekdayIndex(date: string): number {
  return (new Date(calendarDay(date) ?? 0).getUTCDay() + 6) % 7;
}

/**
 * The month as a traditional Monday-to-Sunday calendar (#250), active from the API's evaluation
 * date on. Every day from today is a native button that shows its day below the calendar; its
 * count is stated in words, and its colour represents the summed Hype of highlighted games, with a qualitative text description. Days of the current month that
 * have gone by keep their number, inert and quiet, with no count; days of neighbouring months stay
 * empty, so a weekday column never holds another month. One tab stop enters the month (the chosen
 * day, else today, else the first active day); arrow keys move by day and by week, Home and End to
 * the week's ends, without choosing; Enter or Space chooses.
 */
export function ReleaseCalendarGrid({ model, selectedDay, onSelectDay }: ReleaseCalendarGridProps) {
  const table = useRef<HTMLTableElement>(null);
  const hintId = useId();
  const days = model.activeDays;
  const [focusDate, setFocusDate] = useState<string | null>(null);
  const anchor = selectedDay ?? days[0]?.date ?? null;
  const tabStop = days.some((day) => day.date === focusDate) ? focusDate : anchor;

  function move(event: KeyboardEvent<HTMLTableElement>) {
    const current = days.findIndex((day) => day.date === tabStop);
    const currentDay = days[current];
    if (currentDay === undefined) return;
    const weekday = weekdayIndex(currentDay.date);
    const target = {
      ArrowRight: current + 1,
      ArrowLeft: current - 1,
      ArrowDown: current + 7,
      ArrowUp: current - 7,
      Home: current - weekday,
      End: current + (6 - weekday),
    }[event.key];
    if (target === undefined) return;
    event.preventDefault();
    const date = days[Math.min(Math.max(target, 0), days.length - 1)]?.date;
    if (date === undefined) return;
    setFocusDate(date);
    table.current?.querySelector<HTMLButtonElement>(`[data-date="${date}"]`)?.focus();
  }

  return (
    <>
      <p className="sr-only" id={hintId}>
        Las flechas recorren los días y las semanas; Intro o Espacio muestra los lanzamientos del
        día.
      </p>
      <table aria-describedby={hintId} className="calendar-grid" onKeyDown={move} ref={table}>
        <caption className="sr-only">{`Lanzamientos de ${model.monthLabel}`}</caption>
        <thead>
          <tr>
            {WEEKDAY_HEADERS.map((weekday) => (
              <th abbr={weekday.name} key={weekday.name} scope="col">
                <span className="calendar-weekday-name">{weekday.name}</span>
                <span aria-hidden="true" className="calendar-weekday-letter">
                  {weekday.letter}
                </span>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {model.weeks.map((week, weekIndex) => (
            <tr key={week.find((day) => day !== null)?.date ?? weekIndex}>
              {week.map((day, dayIndex) =>
                day === null ? (
                  <td aria-hidden="true" className="calendar-cell calendar-cell-outside" key={`outside-${dayIndex}`} />
                ) : day.past ? (
                  <td className="calendar-cell" key={day.date}>
                    <span className="calendar-day calendar-day-past">
                      <span aria-hidden="true" className="calendar-day-head">
                        <span className="calendar-day-number">{day.day}</span>
                      </span>
                      <span className="sr-only">{`${day.label}, ya pasado`}</span>
                    </span>
                  </td>
                ) : (
                  <td className="calendar-cell" key={day.date}>
                    <button
                      aria-label={dayName(day)}
                      aria-pressed={day.date === selectedDay}
                      className={`calendar-day${day.today ? " calendar-day-today" : ""}`}
                      data-date={day.date}
                      data-density={day.density}
                      onClick={() => {
                        setFocusDate(day.date);
                        onSelectDay(day.date);
                      }}
                      onFocus={() => setFocusDate(day.date)}
                      tabIndex={day.date === tabStop ? 0 : -1}
                      title={day.gameCount > 0 ? HYPE_LABELS[day.density] : undefined}
                      type="button"
                    >
                      <span aria-hidden="true" className="calendar-day-head">
                        <span className="calendar-day-number">{day.day}</span>
                        {day.today ? <span className="calendar-day-hoy">hoy</span> : null}
                      </span>
                      {day.gameCount === 0 ? null : (
                        <span aria-hidden="true" className="calendar-day-count">
                          <span className="calendar-day-dot" />
                          <span className="calendar-day-count-text">{countLabel(day.gameCount)}</span>
                          <span className="calendar-day-count-number">{day.gameCount}</span>
                        </span>
                      )}
                    </button>
                  </td>
                ),
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}
