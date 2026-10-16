import { useId, type CSSProperties } from "react";

import { dialGeometry, type DialPoint, type DialShape } from "./month-dial-geometry";
import { scalePosition, type MonthTimeline, type ReleaseMark } from "./month-timeline";

type MonthDialProps = {
  timeline: MonthTimeline;
  mark: ReleaseMark;
  /** The release's compact date, written beside a mark that has no single day. */
  markLabel: string;
  shape: DialShape;
  width: number;
  height: number;
  /** The hero's G carries numerals and a finer scale; the phone emblem keeps its markers. */
  detail: "full" | "emblem";
  className?: string;
};

const round = (value: number) => Math.round(value * 100) / 100;

/** Labels outside the band lean away from it, whichever side of the G they sit on. */
function anchorFor(point: DialPoint): "start" | "middle" | "end" {
  if (point.nx > 0.45) return "start";
  if (point.nx < -0.45) return "end";
  return "middle";
}

/** A point `offset` away from the centreline along its outward normal (negative: inwards). */
function out(point: DialPoint, offset: number) {
  return { x: round(point.x + point.nx * offset), y: round(point.y + point.ny * offset) };
}

function segment(from: { x: number; y: number }, to: { x: number; y: number }): string {
  return `M${from.x} ${from.y}L${to.x} ${to.y}`;
}

/**
 * Gameómetro's temporal G: the month as the brand mark's stroke. The crossbar opens the month, the
 * bowl runs clockwise from day 1 to its last day, cold light fills the days already gone by the
 * API's evaluated date, and the fire needle marks the featured release's day. It is decorative:
 * the hero states the same progress and date in words.
 */
export function MonthDial({
  timeline,
  mark,
  markLabel,
  shape,
  width,
  height,
  detail,
  className,
}: MonthDialProps) {
  const id = useId();
  const geometry = dialGeometry(shape);
  const full = detail === "full";
  const half = shape.band / 2;
  const { days, today } = timeline;
  const at = (day: number) =>
    geometry.scaleStart + (geometry.scaleEnd - geometry.scaleStart) * scalePosition(day, days);
  const lit =
    timeline.phase === "past"
      ? geometry.length
      : timeline.phase === "current" && today !== null
        ? at(today)
        : 0;
  const isElapsed = (day: number) => timeline.phase === "past" || (today !== null && day <= today);
  const launchDay = mark.kind === "day" ? mark.day : null;
  const nearMarker = (day: number) =>
    (launchDay !== null && Math.abs(day - launchDay) <= 1) ||
    (timeline.phase === "current" && today !== null && Math.abs(day - today) <= 1);

  let elapsedTicks = "";
  let pendingTicks = "";
  for (let day = 1; day <= days; day += 1) {
    const point = geometry.pointAt(at(day));
    const major = day === 1 || day === days || day % 5 === 0;
    const reach = full ? (major ? 11 : 6) : major ? 5 : 3;
    const tick = segment(out(point, -half), out(point, -half + reach));
    if (isElapsed(day)) elapsedTicks += tick;
    else pendingTicks += tick;
  }

  const numerals = full
    ? [1, 5, 10, 15, 20, 25, days]
        .filter((day, index, list) => list.indexOf(day) === index && !nearMarker(day))
        .map((day) => ({ day, point: out(geometry.pointAt(at(day)), 3.5) }))
    : [];

  const todayPoint =
    timeline.phase === "current" && today !== null ? geometry.pointAt(at(today)) : null;
  const start = geometry.pointAt(geometry.scaleStart);
  const end = geometry.pointAt(geometry.scaleEnd);
  const labelGap = full ? 12 : 10;

  return (
    <svg
      aria-hidden="true"
      className={`month-dial month-dial-${detail} month-dial-${timeline.phase}${className ? ` ${className}` : ""}`}
      focusable="false"
      height={round(height)}
      viewBox={`0 0 ${round(width)} ${round(height)}`}
      width={round(width)}
    >
      <defs>
        {/* The brand mark's ice, lighting the days already gone. */}
        <linearGradient
          gradientUnits="userSpaceOnUse"
          id={`${id}-ice`}
          x1="0"
          x2="0"
          y1={round(shape.cy - shape.b)}
          y2={round(shape.cy + shape.b)}
        >
          <stop className="month-dial-ice-top" offset="0" />
          <stop className="month-dial-ice-middle" offset="0.46" />
          <stop className="month-dial-ice-bottom" offset="1" />
        </linearGradient>
        <linearGradient
          gradientUnits="userSpaceOnUse"
          id={`${id}-track`}
          x1="0"
          x2="0"
          y1={round(shape.cy - shape.b)}
          y2={round(shape.cy + shape.b)}
        >
          <stop className="month-dial-track-top" offset="0" />
          <stop className="month-dial-track-bottom" offset="1" />
        </linearGradient>
        <filter height="140%" id={`${id}-lume`} width="140%" x="-20%" y="-20%">
          <feGaussianBlur result="glow" stdDeviation={full ? 3.2 : 1.6} />
          <feMerge>
            <feMergeNode in="glow" />
            <feMergeNode in="SourceGraphic" />
          </feMerge>
        </filter>
      </defs>

      <path className="month-dial-track" d={geometry.path} stroke={`url(#${id}-track)`} strokeWidth={shape.band} />
      {full ? (
        <path className="month-dial-bevel" d={geometry.alongside(geometry.barLength, geometry.length, half - 0.75)} />
      ) : null}
      {lit > 0 ? (
        <path
          className="month-dial-progress"
          d={geometry.path}
          filter={`url(#${id}-lume)`}
          stroke={`url(#${id}-ice)`}
          strokeDasharray={`${round(lit)} ${round(geometry.length + 2)}`}
          strokeWidth={shape.band}
          style={{ "--dial-length": `${round(geometry.length + 2)}px` } as CSSProperties}
        />
      ) : null}
      <path className="month-dial-ticks month-dial-ticks-pending" d={pendingTicks} />
      <path className="month-dial-ticks month-dial-ticks-elapsed" d={elapsedTicks} />
      {numerals.map(({ day, point }) => (
        <text
          className={`month-dial-numeral${isElapsed(day) ? " month-dial-numeral-elapsed" : ""}`}
          dominantBaseline="central"
          key={day}
          textAnchor="middle"
          x={point.x}
          y={point.y}
        >
          {day}
        </text>
      ))}

      {todayPoint === null ? null : (
        <g className="month-dial-today">
          <path d={segment(out(todayPoint, -half - 2), out(todayPoint, half + (full ? 7 : 4)))} />
          <text
            dominantBaseline="central"
            textAnchor={anchorFor(todayPoint)}
            x={out(todayPoint, half + labelGap).x}
            y={out(todayPoint, half + labelGap).y}
          >
            hoy
          </text>
        </g>
      )}

      {mark.kind === "day" ? (
        <LaunchNeedle full={full} half={half} label={String(mark.day)} point={geometry.pointAt(at(mark.day))} />
      ) : null}

      {mark.kind === "span" ? (
        <g className={`month-dial-span${mark.open ? " month-dial-span-open" : ""}`}>
          <path d={geometry.alongside(geometry.scaleStart, geometry.scaleEnd, half + 5)} />
          {mark.open ? null : (
            <path
              d={`${segment(out(start, half + 1), out(start, half + 10))}${segment(out(end, half + 1), out(end, half + 10))}`}
            />
          )}
          <MarkLabel offset={half + labelGap + 6} point={end} text={markLabel} />
        </g>
      ) : null}

      {mark.kind === "before" || mark.kind === "after" ? (
        <OutsideMark
          direction={mark.kind}
          half={half}
          label={markLabel}
          point={mark.kind === "before" ? start : end}
        />
      ) : null}

      {mark.kind === "unknown" ? (
        <g className="month-dial-unknown">
          <circle cx={out(end, half + 10).x} cy={out(end, half + 10).y} r={full ? 6 : 4} />
          <MarkLabel offset={half + 24} point={end} text="Por confirmar" />
        </g>
      ) : null}
    </svg>
  );
}

/** The G alone, unlit and unmarked: the placeholder the hero's dial arrives in. */
export function DialSilhouette({
  shape,
  width,
  height,
  className,
}: {
  shape: DialShape;
  width: number;
  height: number;
  className?: string;
}) {
  return (
    <svg
      aria-hidden="true"
      className={`month-dial month-dial-silhouette${className ? ` ${className}` : ""}`}
      focusable="false"
      height={round(height)}
      viewBox={`0 0 ${round(width)} ${round(height)}`}
      width={round(width)}
    >
      <path className="month-dial-track" d={dialGeometry(shape).path} strokeWidth={shape.band} />
    </svg>
  );
}

/** The brand mark's fire needle, crossing the band at the release's day. */
function LaunchNeedle({
  point,
  half,
  label,
  full,
}: {
  point: DialPoint;
  half: number;
  label: string;
  full: boolean;
}) {
  const base = out(point, -half + 3);
  const tip = out(point, half + (full ? 13 : 9));
  const width = full ? 4.4 : 2.8;
  // The tangent is the normal turned a quarter turn clockwise.
  const tangentX = -point.ny;
  const tangentY = point.nx;
  const needle = `M${round(base.x + tangentX * width)} ${round(base.y + tangentY * width)}L${tip.x} ${tip.y}L${round(base.x - tangentX * width)} ${round(base.y - tangentY * width)}Z`;
  const labelPoint = out(point, half + (full ? 23 : 17));
  return (
    <g className="month-dial-launch">
      <path className="month-dial-needle" d={needle} />
      <circle className="month-dial-hub" cx={base.x} cy={base.y} r={full ? 5.2 : 3.4} />
      <circle className="month-dial-hub-core" cx={base.x} cy={base.y} r={full ? 1.9 : 1.2} />
      <text dominantBaseline="central" textAnchor={anchorFor(point)} x={labelPoint.x} y={labelPoint.y}>
        {label}
      </text>
    </g>
  );
}

function MarkLabel({ point, offset, text }: { point: DialPoint; offset: number; text: string }) {
  const position = out(point, offset);
  return (
    <text
      className="month-dial-mark-label"
      dominantBaseline="central"
      textAnchor={anchorFor(point)}
      x={position.x}
      y={position.y}
    >
      {text}
    </text>
  );
}

/** A release known to fall outside the month: a chevron at the scale's end it lies beyond. */
function OutsideMark({
  point,
  half,
  direction,
  label,
}: {
  point: DialPoint;
  half: number;
  direction: "before" | "after";
  label: string;
}) {
  const centre = out(point, half + 9);
  // Time runs along the tangent; a release before the month points back against it.
  const sign = direction === "after" ? 1 : -1;
  const tangentX = -point.ny * sign;
  const tangentY = point.nx * sign;
  const tip = { x: round(centre.x + tangentX * 5), y: round(centre.y + tangentY * 5) };
  const arm = (side: 1 | -1) => ({
    x: round(centre.x - tangentX * 3 + point.nx * 5 * side),
    y: round(centre.y - tangentY * 3 + point.ny * 5 * side),
  });
  return (
    <g className="month-dial-outside">
      <path d={`M${arm(1).x} ${arm(1).y}L${tip.x} ${tip.y}L${arm(-1).x} ${arm(-1).y}`} />
      <MarkLabel offset={half + 24} point={point} text={label} />
    </g>
  );
}
