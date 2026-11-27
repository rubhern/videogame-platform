import { useId, type CSSProperties } from "react";

import {
  METER_BAND_EDGES as BAND_EDGES,
  METER_HEAD as HEAD,
  METER_NEEDLE as NEEDLE,
  METER_RADIUS as RADIUS,
  METER_TRACK as TRACK,
  meterArc as arc,
  meterPoint as point,
  meterTurn,
  roundMeter as round,
} from "./game-meter-geometry";
import { thermalBand } from "./thermal-band";

/**
 * A score read on the Gameómetro: the needle and its trail take the score's temperature. It is
 * decorative; the number beside it carries the accessible reading.
 */
export function GameMeter({ value, className }: { value: number | null; className?: string }) {
  const headId = useId();
  const band = value === null ? null : thermalBand(value);
  const turn = band === null || value === null ? null : meterTurn(value);
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
        const [x1, y1] = point(RADIUS - 3.6, meterTurn(edge));
        const [x2, y2] = point(RADIUS + 3.6, meterTurn(edge));
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
