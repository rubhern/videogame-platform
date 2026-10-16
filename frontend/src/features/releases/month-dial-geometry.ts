/**
 * Geometry of the temporal G, drawn from the brand mark: one stroke that starts with a crossbar
 * inside the bowl, turns down at the G's right side and runs clockwise to a terminal at the top,
 * leaving the mouth open to the upper right. The bowl is a superellipse, so it can hold a block of
 * copy while keeping the mark's round shoulders, and every position on it is measured along the
 * band, so equal stretches of time take equal lengths of the G.
 */

export type DialShape = {
  /** Centre of the bowl. */
  cx: number;
  cy: number;
  /** Half extents of the band's centreline. */
  a: number;
  b: number;
  /** Superellipse exponent: 2 is an ellipse, higher values square the shoulders. */
  exponent: number;
  /** Band thickness. */
  band: number;
  /** How far the crossbar reaches inside the band's inner edge. */
  bar: number;
};

/** A point on the stroke's centreline with the unit normal that points away from the bowl. */
export type DialPoint = { x: number; y: number; nx: number; ny: number };

export type DialGeometry = {
  /** The whole stroke: crossbar, then the bowl to its terminal. */
  path: string;
  length: number;
  /** Where the crossbar meets the G's right side. */
  barLength: number;
  /** Arc lengths of the first and the last day of the month. */
  scaleStart: number;
  scaleEnd: number;
  pointAt: (distance: number) => DialPoint;
  /** A line alongside the stroke, `offset` away from its centreline, between two arc lengths. */
  alongside: (from: number, to: number, offset: number) => string;
};

/** The terminal stops a fifth of the half-width right of the centre, as the mark's terminal does. */
const TERMINAL_X = 0.2;
const SAMPLES = 240;

type Point = { x: number; y: number };

function onBowl(shape: DialShape, turn: number): Point {
  const cos = Math.cos(turn);
  const sin = Math.sin(turn);
  const power = 2 / shape.exponent;
  return {
    x: shape.cx + shape.a * Math.sign(cos) * Math.abs(cos) ** power,
    y: shape.cy + shape.b * Math.sign(sin) * Math.abs(sin) ** power,
  };
}

const round = (value: number) => Math.round(value * 100) / 100;

export function dialGeometry(shape: DialShape): DialGeometry {
  // Screen coordinates grow downwards, so increasing turns run clockwise from the crossbar.
  const terminalTurn = 2 * Math.PI - Math.acos(TERMINAL_X ** (shape.exponent / 2));
  const points: Point[] = [{ x: shape.cx + shape.a - shape.band / 2 - shape.bar, y: shape.cy }];
  for (let index = 0; index <= SAMPLES; index += 1) {
    points.push(onBowl(shape, (terminalTurn * index) / SAMPLES));
  }

  const lengths = [0];
  for (let index = 1; index < points.length; index += 1) {
    const from = points[index - 1] as Point;
    const to = points[index] as Point;
    lengths.push((lengths[index - 1] ?? 0) + Math.hypot(to.x - from.x, to.y - from.y));
  }
  const length = lengths[lengths.length - 1] ?? 0;
  const barLength = lengths[1] ?? 0;

  function pointAt(distance: number): DialPoint {
    const target = Math.min(Math.max(distance, 0), length);
    let low = 0;
    let high = lengths.length - 1;
    while (high - low > 1) {
      const middle = (low + high) >> 1;
      if ((lengths[middle] ?? 0) <= target) low = middle;
      else high = middle;
    }
    const from = points[low] as Point;
    const to = points[high] as Point;
    const span = (lengths[high] ?? 0) - (lengths[low] ?? 0);
    const share = span === 0 ? 0 : (target - (lengths[low] ?? 0)) / span;
    const chord = Math.hypot(to.x - from.x, to.y - from.y) || 1;
    const tangentX = (to.x - from.x) / chord;
    const tangentY = (to.y - from.y) / chord;
    // Turning the clockwise tangent a quarter turn anticlockwise points out of the bowl.
    return {
      x: from.x + (to.x - from.x) * share,
      y: from.y + (to.y - from.y) * share,
      nx: tangentY,
      ny: -tangentX,
    };
  }

  function alongside(from: number, to: number, offset: number): string {
    const steps = Math.max(1, Math.ceil(Math.abs(to - from) / 4));
    let line = "";
    for (let index = 0; index <= steps; index += 1) {
      const point = pointAt(from + ((to - from) * index) / steps);
      line += `${index === 0 ? "M" : "L"}${round(point.x + point.nx * offset)} ${round(point.y + point.ny * offset)}`;
    }
    return line;
  }

  return {
    path: points.map((point, index) => `${index === 0 ? "M" : "L"}${round(point.x)} ${round(point.y)}`).join(""),
    length,
    barLength,
    // Day 1 sits just past the corner the crossbar makes with the right side; the last day sits
    // just before the terminal's cut, so no tick lands on an edge.
    scaleStart: barLength + shape.band / 2 + 3,
    scaleEnd: length - 4,
    pointAt,
    alongside,
  };
}

/** Proportions of the G that holds the hero's copy. */
export const ENCLOSING_DIAL = {
  exponent: 4,
  band: 24,
  bar: 34,
  /** Room between the copy's sides and the band's inner edge at the G's waist. */
  gapX: 60,
  /** Room outside the band for the markers' labels. */
  margin: 20,
  /** Extra room between the copy's corners and the band. */
  clearance: 4,
  /** The bowl never gets flatter than this, so a short copy still sits in a G. */
  minRatio: 0.62,
} as const;

export type EnclosedDial = {
  shape: DialShape;
  width: number;
  height: number;
  /** Space between the dial's box and the copy it encloses. */
  padX: number;
  padY: number;
};

/**
 * The G sized around a block of copy: the copy keeps its width, the bowl's waist leaves room for
 * the crossbar, and its height grows until every corner of the copy clears the band.
 */
export function encloseDial(copyWidth: number, copyHeight: number): EnclosedDial {
  const { exponent, band, bar, gapX, margin, clearance, minRatio } = ENCLOSING_DIAL;
  const innerA = copyWidth / 2 + gapX;
  const sideShare = (copyWidth / 2 / innerA) ** exponent;
  const innerB = Math.max(
    (copyHeight / 2) * (1 - sideShare) ** (-1 / exponent) + clearance,
    innerA * minRatio,
  );
  const a = innerA + band / 2;
  const b = innerB + band / 2;
  const halfWidth = a + band / 2 + margin;
  const halfHeight = b + band / 2 + margin;
  return {
    shape: { cx: halfWidth, cy: halfHeight, a, b, exponent, band, bar },
    width: halfWidth * 2,
    height: halfHeight * 2,
    padX: halfWidth - copyWidth / 2,
    padY: halfHeight - copyHeight / 2,
  };
}
