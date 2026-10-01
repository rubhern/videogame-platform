import { useId, type CSSProperties } from "react";

import { thermalBand } from "./thermal-band";

// The G-meter shares the brand mark's geometry: a G on a 64-unit canvas pivoting at (32, 32).
// Its scale runs clockwise from the crossbar (0) round to the G's upper terminal (10), so the
// needle climbs as a game heats up. The brand mark is the meter read beyond 10: its needle has
// left through the G's mouth.
const RADIUS = 19.5;
const SWEEP = 280;
const TRACK = "M35.39 12.8 A19.5 19.5 0 1 0 51.5 32 L32 32";
const NEEDLE = "M32 29.6 L57.6 32 L32 34.4 Z";
const BAND_EDGES = [2, 4, 6, 8];
// The trail keeps a quiet base along its length and lights up over its last stretch.
const HEAD = 100;

const round = (value: number) => Math.round(value * 100) / 100;

/** A point on the meter at `turn` degrees clockwise from the crossbar. */
function point(radius: number, turn: number) {
  const radians = (-turn * Math.PI) / 180;
  return [round(32 + radius * Math.cos(radians)), round(32 - radius * Math.sin(radians))] as const;
}

function arc(from: number, to: number) {
  const [x0, y0] = point(RADIUS, from);
  const [x1, y1] = point(RADIUS, to);
  return `M${x0} ${y0} A${RADIUS} ${RADIUS} 0 ${to - from > 180 ? 1 : 0} 1 ${x1} ${y1}`;
}

/**
 * A score read on the Gameómetro: the needle and its trail take the score's temperature. It is
 * decorative; the number beside it carries the accessible reading.
 */
export function GameMeter({ value, className }: { value: number | null; className?: string }) {
  const headId = useId();
  const band = value === null ? null : thermalBand(value);
  const turn = band === null || value === null ? null : (SWEEP * value) / 10;
  const headFrom = turn === null ? 0 : Math.max(0, turn - HEAD);
  const [headX1, headY1] = point(RADIUS, headFrom);
  const [headX2, headY2] = point(RADIUS, turn ?? 0);

  return (
    <svg
      aria-hidden="true"
      className={className ? `game-meter ${className}` : "game-meter"}
      data-thermal={band ?? undefined}
      focusable="false"
      style={turn === null ? undefined : ({ "--meter-turn": `${round(turn)}deg` } as CSSProperties)}
      viewBox="0 0 64 64"
    >
      <path className="game-meter-track" d={TRACK} />
      {BAND_EDGES.map((edge) => {
        const [x1, y1] = point(RADIUS - 3.6, (SWEEP * edge) / 10);
        const [x2, y2] = point(RADIUS + 3.6, (SWEEP * edge) / 10);
        return <path className="game-meter-tick" d={`M${x1} ${y1} L${x2} ${y2}`} key={edge} />;
      })}
      {turn === null ? null : (
        <>
          <defs>
            <linearGradient
              gradientUnits="userSpaceOnUse"
              id={headId}
              x1={headX1}
              x2={headX2}
              y1={headY1}
              y2={headY2}
            >
              <stop className="game-meter-head-stop" offset="0" stopOpacity="0" />
              <stop className="game-meter-head-stop" offset="1" />
            </linearGradient>
          </defs>
          {turn > 0 ? (
            <>
              <path className="game-meter-trail" d={arc(0, turn)} />
              <path className="game-meter-head" d={arc(headFrom, turn)} stroke={`url(#${headId})`} />
            </>
          ) : null}
          <g className="game-meter-needle">
            <path d={NEEDLE} />
            <circle cx="32" cy="32" r="4.4" />
            <circle className="game-meter-core" cx="32" cy="32" r="1.5" />
          </g>
        </>
      )}
    </svg>
  );
}
