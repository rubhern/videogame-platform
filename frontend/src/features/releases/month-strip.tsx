import type { CSSProperties } from "react";

import {
  elapsedPosition,
  scalePosition,
  type MonthTimeline,
  type ReleaseMark,
} from "./month-timeline";

type MonthStripProps = {
  timeline: MonthTimeline;
  mark: ReleaseMark;
  /** The release's compact date, at its own precision. */
  label: string;
};

function labelPosition(mark: ReleaseMark, days: number): number {
  switch (mark.kind) {
    case "day":
      return scalePosition(mark.day, days);
    case "before":
      return 0;
    case "after":
      return 1;
    default:
      return 0.5;
  }
}

/**
 * The temporal G unrolled under a card: the same month, the same cold light for the days gone by
 * and the same fire marker, quieter than the hero's G. It starts with a short upright stroke, the
 * G's crossbar, and it is decorative: the card states its release date in words.
 */
export function MonthStrip({ timeline, mark, label }: MonthStripProps) {
  const { days } = timeline;
  const lit = elapsedPosition(timeline);
  const isElapsed = (day: number) =>
    timeline.phase === "past" || (timeline.today !== null && day <= timeline.today);
  const position = labelPosition(mark, days);
  const align = position < 0.2 ? "start" : position > 0.8 ? "end" : "middle";

  return (
    <div
      aria-hidden="true"
      className={`month-strip month-strip-${timeline.phase} month-strip-mark-${mark.kind}${mark.kind === "span" && mark.open ? " month-strip-open" : ""}`}
      style={{ "--strip-lit": lit, "--strip-mark": position } as CSSProperties}
    >
      <svg
        className="month-strip-scale"
        focusable="false"
        overflow="visible"
        preserveAspectRatio="none"
        viewBox={`0 0 ${days - 1} 12`}
      >
        <rect className="month-strip-track" height="3" width={days - 1} x="0" y="8" />
        {lit > 0 ? (
          <rect className="month-strip-lit" height="3" width={(days - 1) * lit} x="0" y="8" />
        ) : null}
        {Array.from({ length: days }, (_, index) => {
          const day = index + 1;
          const major = day === days || day % 5 === 0;
          return (
            <line
              className={
                day === 1
                  ? "month-strip-origin"
                  : `month-strip-tick${isElapsed(day) ? " month-strip-tick-elapsed" : ""}`
              }
              key={day}
              vectorEffect="non-scaling-stroke"
              x1={index}
              x2={index}
              y1={day === 1 ? 0 : major ? 2.5 : 5}
              y2={day === 1 ? 11 : 8}
            />
          );
        })}
      </svg>
      {timeline.phase === "current" ? <span className="month-strip-today" /> : null}
      {mark.kind === "day" ? (
        <>
          <span className="month-strip-needle" />
          <span className="month-strip-needle-hub" />
        </>
      ) : null}
      {mark.kind === "span" ? <span className="month-strip-span" /> : null}
      <span className={`month-strip-label month-strip-label-${align}`}>{label}</span>
    </div>
  );
}
