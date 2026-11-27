// The G-meter shares the brand mark's geometry: a G on a 64-unit canvas pivoting at (32, 32).
// Its scale runs clockwise from the crossbar (0) round to the G's upper terminal (10), so the
// needle climbs as a game heats up. The brand mark is the meter read beyond 10: its needle has
// left through the G's mouth. The SVG meter and the exported images both draw from this geometry.
export const METER_RADIUS = 19.5;
export const METER_SWEEP = 280;
export const METER_TRACK = "M35.39 12.8 A19.5 19.5 0 1 0 51.5 32 L32 32";
export const METER_NEEDLE = "M32 29.6 L57.6 32 L32 34.4 Z";
export const METER_BAND_EDGES = [2, 4, 6, 8] as const;
/** The trail keeps a quiet base along its length and lights up over its last stretch. */
export const METER_HEAD = 100;

export const roundMeter = (value: number) => Math.round(value * 100) / 100;

/** A point on the meter at `turn` degrees clockwise from the crossbar. */
export function meterPoint(radius: number, turn: number) {
  const radians = (-turn * Math.PI) / 180;
  return [roundMeter(32 + radius * Math.cos(radians)), roundMeter(32 - radius * Math.sin(radians))] as const;
}

/** The arc of the scale between two turns, as SVG path data. */
export function meterArc(from: number, to: number) {
  const [x0, y0] = meterPoint(METER_RADIUS, from);
  const [x1, y1] = meterPoint(METER_RADIUS, to);
  return `M${x0} ${y0} A${METER_RADIUS} ${METER_RADIUS} 0 ${to - from > 180 ? 1 : 0} 1 ${x1} ${y1}`;
}

/** The needle's turn for a score on the 0–10 scale. */
export function meterTurn(value: number) {
  return (METER_SWEEP * value) / 10;
}
