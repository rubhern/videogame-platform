import { useLayoutEffect, useRef, useState, type CSSProperties, type KeyboardEvent } from "react";

import type { DayBar } from "./day-bar";

type ReleaseDayBarProps = {
  bar: DayBar;
  /** Calendar days of the releases the page shows; each day gets one fire needle. */
  releaseDays: readonly string[];
  /** The day of the release a visitor is pointing at or focusing, lit above the others. */
  activeDay: string | null;
  /** Explores exactly one day. */
  onSelectDay: (date: string) => void;
};

const at = (position: number) => ({ "--at": position }) as CSSProperties;

/** Centres one day in the bar's own horizontal scroll, never scrolling the page. */
function reveal(scroller: HTMLElement | null, date: string) {
  const day = scroller?.querySelector<HTMLElement>(`[data-date="${date}"]`);
  if (!scroller || !day) return;
  scroller.scrollLeft = day.offsetLeft - (scroller.clientWidth - day.offsetWidth) / 2;
}

/**
 * The explored range on Gameómetro's day scale (#285): one division per day of the frame, the days
 * gone by the API's evaluation date in the instrument's cold light, today marked, the explored
 * range raised in ice and a fire needle on each day the page's releases fall on. Every day is a
 * button that explores that day alone, so the scale doubles as a day picker: a toolbar with one tab
 * stop whose arrow keys, Home and End move between days. A frame wider than the screen scrolls
 * sideways inside the bar. Releases known only to a month, quarter or year take no needle; the
 * scale never invents a day.
 */
export function ReleaseDayBar({ bar, releaseDays, activeDay, onSelectDay }: ReleaseDayBarProps) {
  const scroller = useRef<HTMLDivElement>(null);
  const { days, selectedDay } = bar;
  const count = days.length;
  const centre = (index: number) => (index + 0.5) / count;
  const elapsed = days.filter((day) => day.elapsed).length;
  const firstInRange = days.findIndex((day) => day.inRange);
  const lastInRange = days.length - 1 - [...days].reverse().findIndex((day) => day.inRange);
  const anchor =
    selectedDay ??
    days.find((day) => day.today && day.inRange)?.date ??
    days[Math.max(firstInRange, 0)]?.date ??
    null;
  const [focusDate, setFocusDate] = useState<string | null>(null);
  const tabStop = days.some((day) => day.date === focusDate) ? focusDate : anchor;
  const needles = new Set(releaseDays);
  const weekLetters = count <= 14;

  // The bar scrolls to what it explores whenever the range or frame changes, never the page.
  useLayoutEffect(() => {
    if (anchor !== null) reveal(scroller.current, anchor);
  }, [anchor, bar.frame.from, bar.frame.to]);

  function move(event: KeyboardEvent<HTMLDivElement>) {
    const current = days.findIndex((day) => day.date === tabStop);
    const target =
      event.key === "ArrowRight"
        ? Math.min(current + 1, count - 1)
        : event.key === "ArrowLeft"
          ? Math.max(current - 1, 0)
          : event.key === "Home"
            ? 0
            : event.key === "End"
              ? count - 1
              : null;
    const date = target === null ? undefined : days[target]?.date;
    if (date === undefined) return;
    event.preventDefault();
    setFocusDate(date);
    reveal(scroller.current, date);
    scroller.current?.querySelector<HTMLButtonElement>(`[data-date="${date}"]`)?.focus({
      preventScroll: true,
    });
  }

  return (
    <div className={`day-bar${activeDay === null ? "" : " window-scale-pointing"}`}>
      <div className="day-bar-scroller" ref={scroller}>
        <div className="day-bar-track" style={{ "--days": count } as CSSProperties}>
          <div
            aria-hidden="true"
            className="window-scale-rail"
            style={{ "--lit": elapsed / count } as CSSProperties}
          >
            <span className="window-scale-lit" />
            {firstInRange === -1 ? null : (
              <span
                className="day-bar-range"
                style={
                  {
                    "--from": firstInRange / count,
                    "--to": (lastInRange + 1) / count,
                  } as CSSProperties
                }
              />
            )}
            {days.map((day, index) => (
              <span
                className={`window-scale-tick window-scale-tick-major${day.elapsed ? " window-scale-tick-elapsed" : ""}`}
                key={day.date}
                style={at(centre(index))}
              />
            ))}
            {days.map((day, index) =>
              needles.has(day.date) ? (
                <span
                  className={`window-scale-needle${day.date === activeDay ? " window-scale-needle-active" : ""}`}
                  key={`needle-${day.date}`}
                  style={at(centre(index))}
                />
              ) : null,
            )}
            {days.map((day, index) =>
              day.today ? (
                <span className="window-scale-today" key="today" style={at(centre(index))} />
              ) : null,
            )}
          </div>
          <div
            aria-label="Elegir un día del periodo"
            className="day-bar-days"
            onKeyDown={move}
            role="toolbar"
          >
            {days.map((day) => {
              const selected = day.date === selectedDay;
              return (
                <button
                  aria-label={`${day.label}${day.today ? ", hoy" : ""}${
                    !selected && selectedDay === null && day.inRange ? ", en el periodo" : ""
                  }`}
                  aria-pressed={selected}
                  className={`day-bar-day${day.inRange ? " day-bar-day-in-range" : ""}${
                    selected ? " day-bar-day-selected" : ""
                  }${day.today ? " day-bar-day-today" : ""}${
                    day.date === activeDay ? " day-bar-day-active" : ""
                  }`}
                  data-date={day.date}
                  key={day.date}
                  onClick={() => {
                    setFocusDate(day.date);
                    onSelectDay(day.date);
                  }}
                  onFocus={() => setFocusDate(day.date)}
                  tabIndex={day.date === tabStop ? 0 : -1}
                  type="button"
                >
                  {weekLetters ? (
                    <span aria-hidden="true" className="day-bar-weekday">
                      {day.weekday}
                    </span>
                  ) : null}
                  <span aria-hidden="true" className="day-bar-numeral">
                    {day.day}
                    {day.today ? <small>hoy</small> : day.month === null ? null : <small>{day.month}</small>}
                  </span>
                </button>
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
}
