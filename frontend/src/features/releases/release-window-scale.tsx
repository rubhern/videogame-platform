import type { CSSProperties } from "react";

import type { WindowScale } from "./window-scale";

type ReleaseWindowScaleProps = {
  scale: WindowScale;
  /** Calendar days of the releases the page shows; each day gets one fire needle. */
  releaseDays: readonly string[];
  /** The day of the release a visitor is pointing at or focusing, lit above the others. */
  activeDay: string | null;
};

type Cell = {
  key: string;
  date: string | null;
  numeral: number | null;
  month: string | null;
  elapsed: boolean;
  today: boolean;
};

const at = (position: number) => ({ "--at": position }) as CSSProperties;

/**
 * The release window as Gameómetro's compact scale: one division per day, the elapsed days in the
 * instrument's cold light, today named `hoy`, and a fire needle on each day the page's releases
 * fall on. An upcoming window, which starts tomorrow, opens with today as a lit lead-in so the
 * scale shows where the wait begins. Pointing at a release lifts its needle. It is decorative:
 * the heading states the window and every card states its own date.
 */
export function ReleaseWindowScale({ scale, releaseDays, activeDay }: ReleaseWindowScaleProps) {
  const lead: Cell[] =
    scale.today === -1
      ? [{ key: "today", date: null, numeral: null, month: null, elapsed: true, today: true }]
      : [];
  const cells: Cell[] = [
    ...lead,
    ...scale.days.map((day, index) => ({
      key: day.date,
      date: day.date,
      numeral: day.labelled ? day.day : null,
      month: day.month,
      elapsed: day.elapsed,
      today: index === scale.today,
    })),
  ];
  const count = cells.length;
  const centre = (index: number) => (index + 0.5) / count;
  const elapsed = cells.filter((cell) => cell.elapsed).length;
  const days = new Set(releaseDays);

  return (
    <div
      aria-hidden="true"
      className={`window-scale${activeDay === null ? "" : " window-scale-pointing"}`}
      style={{ "--lit": elapsed / count } as CSSProperties}
    >
      <div className="window-scale-rail">
        <span className="window-scale-lit" />
        {cells.map((cell, index) => (
          <span
            className={`window-scale-tick${cell.elapsed ? " window-scale-tick-elapsed" : ""}${cell.numeral === null ? "" : " window-scale-tick-major"}`}
            key={cell.key}
            style={at(centre(index))}
          />
        ))}
        {cells.map((cell, index) =>
          cell.date !== null && days.has(cell.date) ? (
            <span
              className={`window-scale-needle${cell.date === activeDay ? " window-scale-needle-active" : ""}`}
              key={`needle-${cell.key}`}
              style={at(centre(index))}
            />
          ) : null,
        )}
        {cells.map((cell, index) =>
          cell.today ? <span className="window-scale-today" key="today" style={at(centre(index))} /> : null,
        )}
      </div>
      <div className="window-scale-numerals">
        {cells.map((cell, index) =>
          cell.today ? (
            <span className="window-scale-numeral window-scale-numeral-today" key={cell.key} style={at(centre(index))}>
              hoy
            </span>
          ) : cell.numeral === null ? null : (
            <span
              className={`window-scale-numeral${cell.date === activeDay ? " window-scale-numeral-active" : ""}`}
              key={cell.key}
              style={at(centre(index))}
            >
              {cell.numeral}
              {cell.month === null ? null : <small>{cell.month}</small>}
            </span>
          ),
        )}
      </div>
    </div>
  );
}
