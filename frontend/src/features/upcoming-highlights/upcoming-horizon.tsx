import type { CSSProperties } from "react";

import type { HorizonMark, HorizonMonth } from "./upcoming-highlights-view-model";

type UpcomingHorizonProps = {
  months: HorizonMonth[];
  marks: HorizonMark[];
  /** The place a visitor points at or focuses in the ranking, whose arrival lifts on the scale. */
  active: number | null;
};

/**
 * The six months ahead unrolled as one line: the months as divisions from today on, and where each
 * ranked game lands. An exact day takes a fire needle, the most anticipated a taller one with its
 * hub; a month or quarter spans its part of the line and is never pinned to a day. The scale is
 * decorative: the readout above it and every entry state the same dates in words.
 */
export function UpcomingHorizon({ months, marks, active }: UpcomingHorizonProps) {
  return (
    <div aria-hidden="true" className="upcoming-horizon-scale" data-active={active ?? undefined}>
      <div className="upcoming-horizon-rail">
        {months.map((month) => (
          <span
            className="upcoming-horizon-month"
            data-first={month.start === 0 ? "" : undefined}
            key={month.key}
            style={{ "--start": month.start, "--end": month.end } as CSSProperties}
          >
            {month.labelled ? (
              <span className="upcoming-horizon-month-label">
                {month.label}
                {month.year === null ? null : (
                  <span className="upcoming-horizon-year"> ’{month.year.slice(2)}</span>
                )}
              </span>
            ) : null}
          </span>
        ))}
        <span className="upcoming-horizon-today">hoy</span>
        {marks.map((mark) =>
          mark.kind === "day" ? (
            <span
              className="upcoming-horizon-needle"
              data-active={mark.position === active ? "" : undefined}
              data-lead={mark.position === 1 ? "" : undefined}
              key={`${mark.position}-day`}
              style={{ "--at": mark.at } as CSSProperties}
            >
              <span className="upcoming-horizon-needle-place">{mark.position}</span>
            </span>
          ) : (
            <span
              className="upcoming-horizon-span"
              data-active={mark.position === active ? "" : undefined}
              data-lead={mark.position === 1 ? "" : undefined}
              key={`${mark.position}-span`}
              style={{ "--start": mark.start, "--end": mark.end } as CSSProperties}
            >
              <span className="upcoming-horizon-needle-place">{mark.position}</span>
            </span>
          ),
        )}
      </div>
    </div>
  );
}
