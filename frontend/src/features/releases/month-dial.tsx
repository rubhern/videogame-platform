import { useId, type CSSProperties } from "react";

import { DIAL_VIEWBOX, MONTH_DIAL_GEOMETRY, type DialPoint } from "./month-dial-geometry";
import { scalePosition, type PeriodTimeline, type ReleaseMark } from "./period-timeline";

type MonthDialProps = {
  timeline: PeriodTimeline;
  mark: ReleaseMark;
  /** The release's compact date, written beside a mark that has no single day. */
  markLabel: string;
  width: number;
  height: number;
  /** The hero's G carries numerals and a finer scale; the phone emblem keeps its markers. */
  detail: "full" | "emblem";
  /**
   * The release's compact date, set inside the bowl as the instrument's own readout: its leading
   * part (the day, or the coarser period) over the rest. The readout replaces the marker's label.
   */
  readout?: DialReadout;
  className?: string;
};

/** A date without a numeral, such as `Por confirmar`, reads quietly: both lines at the small size. */
export type DialReadout = { lead: string; rest: string | null; quiet: boolean };

/** The bowl's reading centre in the fixed viewport, clear of the arm and both rims. */
const READOUT_X = 214;
const READOUT_Y = 206;

const round = (value: number) => Math.round(value * 100) / 100;

/**
 * Divisions that carry a major mark and, on the hero's G, a numeral: every day of a week, and of
 * a month the first, every fifth and the last.
 */
function isMajor(timeline: PeriodTimeline, day: number): boolean {
  return timeline.granularity === "week" || day === 1 || day === timeline.days || day % 5 === 0;
}

/** A division's numeral: its day of the month, with the month where a week enters a new one. */
function numeralText(timeline: PeriodTimeline, day: number): string {
  const division = timeline.divisions[day - 1];
  if (division === undefined) return String(day);
  return division.month === null ? String(division.day) : `${division.day} ${division.month}`;
}

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
 * Gameómetro's temporal G: the represented week or month as the brand mark's stroke. The crossbar
 * opens the period, the bowl runs clockwise from its first day to its last (Monday to Sunday, or
 * the month's 28 to 31 days), cold light fills the days already gone by the API's evaluated date,
 * and the fire needle marks the featured release's day. The silhouette never changes with the
 * period. It is decorative: the hero states the same progress and date in words.
 */
export function MonthDial({
  timeline,
  mark,
  markLabel,
  width,
  height,
  detail,
  readout,
  className,
}: MonthDialProps) {
  const id = useId();
  const geometry = MONTH_DIAL_GEOMETRY;
  const full = detail === "full";

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
  // Numerals stand outside the band, where the markers' own labels go; they give way to them. A
  // week's divisions stand far enough apart that only the marked day itself gives way.
  const reach = timeline.granularity === "week" ? 0 : 2;
  const nearMarker = (day: number) =>
    (launchDay !== null && Math.abs(day - launchDay) <= reach) ||
    (timeline.phase === "current" && today !== null && Math.abs(day - today) <= reach);

  // Both lengths are centred on the temporal path, inset from either rim.
  let elapsedTicks = "";
  let pendingTicks = "";
  let minorTicks = "";
  for (let day = 1; day <= days; day += 1) {
    const point = geometry.pointAt(at(day));
    const half = point.half;
    const major = isMajor(timeline, day);
    if (!major && !full) continue;
    const reach = half * (major ? 0.62 : 0.25);
    const tick = segment(out(point, -reach), out(point, reach));
    if (!major) minorTicks += tick;
    else if (isElapsed(day)) elapsedTicks += tick;
    else pendingTicks += tick;
  }

  const numerals = full
    ? Array.from({ length: days }, (_, index) => index + 1)
        // A fifth day right before the last one keeps its mark but lends its numeral to the last.
        .filter(
          (day) =>
            isMajor(timeline, day) &&
            !nearMarker(day) &&
            (day === days || days - day > reach),
        )
        .map((day) => {
          const point = geometry.pointAt(at(day));
          return {
            day,
            text: numeralText(timeline, day),
            point: out(point, point.half + 11),
            // The last day sits next to the terminal's cut on the top, where time runs to the
            // right: its numeral ends at its mark instead of reaching past the cut.
            anchor: day === days ? ("end" as const) : anchorFor(point),
          };
        })
    : [];

  const todayPoint =
    timeline.phase === "current" && today !== null ? geometry.pointAt(at(today)) : null;
  const half = todayPoint?.half ?? 14;
  const labelGap = full ? 13 : 9;
  // Next to the launch day, "hoy" steps out past the needle's own label instead of covering it.
  const todayLabelOffset =
    launchDay !== null && today !== null && Math.abs(today - launchDay) <= reach
      ? half + (full ? 44 : 30)
      : half + labelGap;
  const start = geometry.pointAt(geometry.scaleStart);
  const end = geometry.pointAt(geometry.scaleEnd);

  return (
    <svg
      aria-hidden="true"
      className={`month-dial month-dial-${detail} month-dial-${timeline.phase}${className ? ` ${className}` : ""}`}
      focusable="false"
      height={round(height)}
      viewBox={DIAL_VIEWBOX}
      preserveAspectRatio="xMidYMid meet"
      width={round(width)}
    >
      <defs>
        <clipPath id={`${id}-band`}><path d={geometry.outline} /></clipPath>
        <linearGradient gradientUnits="userSpaceOnUse" id={`${id}-arm-fade`} x1="343" x2="418">
          <stop offset="0" stopColor="black" />
          <stop offset="0.5" stopColor="#666" />
          <stop offset="1" stopColor="white" />
        </linearGradient>
        <mask id={`${id}-soft-arm`} maskUnits="userSpaceOnUse" x="0" y="0" width="450" height="382">
          <rect width="450" height="382" fill="white" />
          <rect x="330" y="199" width="88" height="13" fill={`url(#${id}-arm-fade)`} />
        </mask>
        {/* Ice lighting the days already gone: one light for every phase, so a whole past month
            reads as the same instrument as the current one, never as a white glare. */}
        <linearGradient
          gradientUnits="userSpaceOnUse"
          id={`${id}-ice`}
          x1="0"
          x2="0"
          y1="27"
          y2="356"
        >
          <stop className="month-dial-ice-top" offset="0" />
          <stop className="month-dial-ice-middle" offset="0.46" />
          <stop className="month-dial-ice-bottom" offset="1" />
        </linearGradient>
        <linearGradient id={`${id}-glass`} x1="0" y1="0" x2="80%" y2="100%">
          <stop offset="0" stopColor="#b9dcef" stopOpacity="0.22" />
          <stop offset="0.25" stopColor="#122b3c" stopOpacity="0.7" />
          <stop offset="0.52" stopColor="#8cb5ce" stopOpacity="0.12" />
          <stop offset="0.76" stopColor="#0b1a26" stopOpacity="0.7" />
          <stop offset="1" stopColor="#9acbe6" stopOpacity="0.24" />
        </linearGradient>
        {/* A close, faint glow: the rail looks lit without a halo reaching the copy. */}
        <filter height="140%" id={`${id}-lume`} width="140%" x="-20%" y="-20%">
          <feGaussianBlur in="SourceGraphic" result="blur" stdDeviation={full ? 2 : 1} />
          <feColorMatrix in="blur" result="glow" type="matrix" values="1 0 0 0 0 0 1 0 0 0 0 0 1 0 0 0 0 0 0.4 0" />
          <feMerge>
            <feMergeNode in="glow" />
            <feMergeNode in="SourceGraphic" />
          </feMerge>
        </filter>
      </defs>

      {/* All visible rail layers use the traced silhouette, never a widened centreline. */}
      <g className="month-dial-band" mask={`url(#${id}-soft-arm)`}>
        <path className="month-dial-keyline" d={geometry.outline} strokeWidth="3" />
        <path className="month-dial-track" d={geometry.outline} />
        <path className="month-dial-glass" d={geometry.outline} fill={`url(#${id}-glass)`} />
        <g clipPath={`url(#${id}-band)`}>
          {lit > 0 ? (
            <path
              className="month-dial-progress"
              d={geometry.path}
              filter={`url(#${id}-lume)`}
              stroke={`url(#${id}-ice)`}
              strokeDasharray={`${round(lit)} ${round(geometry.length + 2)}`}
              strokeWidth="64"
              style={{ "--dial-length": `${round(geometry.length + 2)}px` } as CSSProperties}
            />
          ) : null}
        </g>
        <path className="month-dial-edge" d={geometry.outline} strokeWidth="0.8" />
      </g>
      <g clipPath={`url(#${id}-band)`}>
        <path className="month-dial-ticks month-dial-ticks-minor" d={minorTicks} />
        <path className="month-dial-ticks month-dial-ticks-pending" d={pendingTicks} />
        <path className="month-dial-ticks month-dial-ticks-elapsed" d={elapsedTicks} />
      </g>
      {numerals.map(({ day, text, point, anchor }) => (
        <text
          className={`month-dial-numeral${isElapsed(day) ? " month-dial-numeral-elapsed" : ""}`}
          dominantBaseline="central"
          key={day}
          textAnchor={anchor}
          x={point.x}
          y={point.y}
        >
          {text}
        </text>
      ))}

      {todayPoint === null ? null : (
        <g className="month-dial-today">
          <path d={segment(out(todayPoint, -half - 2), out(todayPoint, half + (full ? 5 : 3)))} />
          <text
            dominantBaseline="central"
            textAnchor={anchorFor(todayPoint)}
            x={out(todayPoint, todayLabelOffset).x}
            y={out(todayPoint, todayLabelOffset).y}
          >
            hoy
          </text>
        </g>
      )}

      {mark.kind === "day" ? (
        <LaunchNeedle full={full} half={geometry.pointAt(at(mark.day)).half} label={readout === undefined ? numeralText(timeline, mark.day) : null} point={geometry.pointAt(at(mark.day))} />
      ) : null}

      {mark.kind === "span" ? (
        <g className={`month-dial-span${mark.open ? " month-dial-span-open" : ""}`}>
          <path d={geometry.alongside(geometry.scaleStart, geometry.scaleEnd, half + 4)} />
          {mark.open ? null : (
            <path
              d={`${segment(out(start, half + 1), out(start, half + 9))}${segment(out(end, half + 1), out(end, half + 9))}`}
            />
          )}
          {readout === undefined ? (
            <MarkLabel offset={half + labelGap + 6} point={end} text={markLabel} />
          ) : null}
        </g>
      ) : null}

      {mark.kind === "before" || mark.kind === "after" ? (
        <OutsideMark
          direction={mark.kind}
          half={half}
          label={readout === undefined ? markLabel : null}
          point={mark.kind === "before" ? start : end}
        />
      ) : null}

      {mark.kind === "unknown" ? (
        <g className="month-dial-unknown">
          <circle cx={out(end, half + 10).x} cy={out(end, half + 10).y} r={full ? 6 : 4} />
          {readout === undefined ? (
            <MarkLabel offset={half + 24} point={end} text="Por confirmar" />
          ) : null}
        </g>
      ) : null}

      {readout === undefined ? null : (
        <text
          className="month-dial-readout"
          textAnchor="middle"
          x={READOUT_X}
          y={READOUT_Y - (readout.quiet ? 22 : 0)}
        >
          <tspan
            className={readout.quiet ? "month-dial-readout-rest" : "month-dial-readout-lead"}
            // A day keeps the full size; a longer period narrows to stay inside the bowl.
            fontSize={
              readout.quiet ? undefined : round(Math.min(150, 330 / Math.max(readout.lead.length, 1)))
            }
            x={READOUT_X}
          >
            {readout.lead}
          </tspan>
          {readout.rest === null ? null : (
            <tspan className="month-dial-readout-rest" x={READOUT_X} y={READOUT_Y + (readout.quiet ? 30 : 66)}>
              {readout.rest}
            </tspan>
          )}
        </text>
      )}
    </svg>
  );
}

/** The G alone, unlit and unmarked: the placeholder the hero's dial arrives in. */
export function DialSilhouette({
  width,
  height,
  className,
}: {
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
      viewBox={DIAL_VIEWBOX}
      preserveAspectRatio="xMidYMid meet"
      width={round(width)}
    >
      <path className="month-dial-track" d={MONTH_DIAL_GEOMETRY.outline} />
    </svg>
  );
}

/** A tapered blade and circular pivot share the canonical mark's needle proportions. */
function LaunchNeedle({ point, half, label, full }: {
  point: DialPoint;
  half: number;
  label: string | null;
  full: boolean;
}) {
  const id = useId();
  const hub = out(point, -half - (full ? 1.5 : 2));
  const length = half * 2 + (full ? 13 : 15);
  const radius = full ? 3.4 : 4;
  const labelPoint = out(point, half + (full ? 23 : 26));
  const angle = Math.atan2(point.ny, point.nx) * 180 / Math.PI;
  return (
    <g className="month-dial-launch">
      <g
        style={{ "--needle-paint": `url(#${id}-fire)` } as CSSProperties}
        transform={`translate(${hub.x} ${hub.y}) rotate(${round(angle)})`}
      >
        <defs>
          <linearGradient gradientUnits="userSpaceOnUse" id={`${id}-fire`} x1="0" x2={length} y1="0" y2="0">
            <stop offset="0" stopColor="#ff4b3e" />
            <stop offset="0.55" stopColor="#ff8c3a" />
            <stop offset="1" stopColor="#ffd36e" />
          </linearGradient>
        </defs>
        <path className="month-dial-needle" d={`M-1 ${-radius * 0.55}L${round(length)} 0L-1 ${radius * 0.55}Z`} />
        <circle className="month-dial-needle" r={radius} />
        <circle className="month-dial-needle-core" r={radius * 0.34} />
      </g>
      {label === null ? null : (
        <text dominantBaseline="central" textAnchor={anchorFor(point)} x={labelPoint.x} y={labelPoint.y}>
          {label}
        </text>
      )}
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

/** A release known to fall outside the period: a chevron at the scale's end it lies beyond. */
function OutsideMark({
  point,
  half,
  direction,
  label,
}: {
  point: DialPoint;
  half: number;
  direction: "before" | "after";
  label: string | null;
}) {
  const centre = out(point, half + 9);
  // Time runs along the tangent; a release before the period points back against it.
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
      {label === null ? null : <MarkLabel offset={half + 24} point={point} text={label} />}
    </g>
  );
}
