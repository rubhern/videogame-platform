/**
 * Fixed tracing of the owner-approved temporal G reference (525 × 382 raster).
 * Coordinates are reference pixels, retained in a 450 × 382 viewBox. The outer and inner
 * cubic rims define one band; their midpoint defines the temporal path. Neither content,
 * calendar state nor viewport dimensions are inputs to this geometry.
 */
type Point = readonly [number, number];
type Cubic = readonly [Point, Point, Point, Point];

export const DIAL_VIEWBOX = "0 0 450 382";
export const DIAL_WIDTH = 450;
export const DIAL_HEIGHT = 382;

/** Clockwise from the right-hand corner: lower right, lower left, upper left. */
const REFERENCE_RIMS = [
  [[[432.0, 199.0], [418.157, 291.287], [351.643, 356.0], [251.0, 356.0]], [[403.0, 212.0], [391.789, 286.742], [328.362, 329.0], [250.0, 329.0]]],
  [[[251.0, 356.0], [135.424, 356.0], [59.0, 277.742], [59.0, 193.0]], [[250.0, 329.0], [162.466, 329.0], [89.0, 274.277], [89.0, 194.0]]],
  [[[59.0, 193.0], [59.0, 89.466], [148.757, 15.876], [260.0, 27.0]], [[89.0, 194.0], [89.0, 103.075], [167.237, 39.724], [260.0, 49.0]]],
] as const satisfies readonly (readonly [Cubic, Cubic])[];
// Refine the reference band around its existing centreline; the silhouette, day positions
// and arc length stay independent of both content and calendar state.
const BAND_SHARE = 0.56;
const RIMS: readonly (readonly [Cubic, Cubic])[] = REFERENCE_RIMS.map(([outer, inner]) => {
  const rim = (sign: number): Cubic => {
    const at = (index: number): Point => {
      const point = sampleAt<Point>(outer, index);
      const other = sampleAt<Point>(inner, index);
      return [(point[0] + other[0]) / 2 + sign * (point[0] - other[0]) * BAND_SHARE / 2,
        (point[1] + other[1]) / 2 + sign * (point[1] - other[1]) * BAND_SHARE / 2];
    };
    return [at(0), at(1), at(2), at(3)];
  };
  return [rim(1), rim(-1)];
});
const ARM_OUTER: Point = [343, 205.5 - 6.5 * BAND_SHARE];
const ARM_INNER: Point = [343, 205.5 + 6.5 * BAND_SHARE];
const SAMPLES_PER_CURVE = 256;
const round = (value: number) => Math.round(value * 1000) / 1000;
const pair = (point: Point) => `${point[0]} ${point[1]}`;
const command = (curve: Cubic) => `C${curve.slice(1).map(pair).join(" ")}`;
const midpoint = (a: Point, b: Point): Point => [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2];

function cubic(curve: Cubic, t: number): Point {
  const u = 1 - t;
  const coordinate = (axis: 0 | 1) =>
    u ** 3 * curve[0][axis] + 3 * u * u * t * curve[1][axis] +
    3 * u * t * t * curve[2][axis] + t ** 3 * curve[3][axis];
  return [coordinate(0), coordinate(1)];
}

export type DialPoint = { x: number; y: number; nx: number; ny: number; half: number };

export type DialGeometry = {
  /** The closed, traced silhouette used by every visible band layer. */
  outline: string;
  /** The centreline used to measure distance, progress and every day's position. */
  path: string;
  length: number;
  barLength: number;
  scaleStart: number;
  scaleEnd: number;
  pointAt: (distance: number) => DialPoint;
  alongside: (from: number, to: number, offset: number) => string;
};

/** Sample indices are bounded by construction; fail explicitly if the trace is edited wrongly. */
function sampleAt<T>(samples: readonly T[], index: number): T {
  const sample = samples[index];
  if (sample === undefined) throw new Error("Temporal G sample index is out of bounds");
  return sample;
}

function buildGeometry(): DialGeometry {
  const first = sampleAt(RIMS, 0);
  const last = sampleAt(RIMS, 2);
  const centreCurves = RIMS.map(([outer, inner]) =>
    [midpoint(outer[0], inner[0]), midpoint(outer[1], inner[1]),
      midpoint(outer[2], inner[2]), midpoint(outer[3], inner[3])] as Cubic,
  );
  const arm = midpoint(ARM_OUTER, ARM_INNER);
  const points: Point[] = [arm];
  const widths: number[] = [ARM_INNER[1] - ARM_OUTER[1]];
  for (const [outer, inner] of RIMS) {
    for (let index = 0; index <= SAMPLES_PER_CURVE; index += 1) {
      if (index === 0 && points.length > 1) continue;
      const a = cubic(outer, index / SAMPLES_PER_CURVE);
      const b = cubic(inner, index / SAMPLES_PER_CURVE);
      points.push(midpoint(a, b));
      widths.push(Math.hypot(a[0] - b[0], a[1] - b[1]));
    }
  }
  const lengths = [0];
  for (let index = 1; index < points.length; index += 1) {
    const from = sampleAt(points, index - 1);
    const to = sampleAt(points, index);
    lengths.push(sampleAt(lengths, index - 1) + Math.hypot(to[0] - from[0], to[1] - from[1]));
  }
  const length = sampleAt(lengths, lengths.length - 1);
  const barLength = sampleAt(lengths, 1);

  function pointAt(distance: number): DialPoint {
    const target = Math.min(Math.max(distance, 0), length);
    let low = 0;
    let high = lengths.length - 1;
    while (high - low > 1) {
      const middle = (low + high) >> 1;
      if (sampleAt(lengths, middle) <= target) low = middle;
      else high = middle;
    }
    const from = sampleAt(points, low);
    const to = sampleAt(points, high);
    const span = sampleAt(lengths, high) - sampleAt(lengths, low);
    const share = span === 0 ? 0 : (target - sampleAt(lengths, low)) / span;
    const chord = Math.hypot(to[0] - from[0], to[1] - from[1]) || 1;
    return {
      x: from[0] + (to[0] - from[0]) * share,
      y: from[1] + (to[1] - from[1]) * share,
      nx: (to[1] - from[1]) / chord,
      ny: -(to[0] - from[0]) / chord,
      half: (sampleAt(widths, low) + (sampleAt(widths, high) - sampleAt(widths, low)) * share) / 2,
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
    outline: `M${pair(ARM_OUTER)}L${pair(first[0][0])}` +
      RIMS.map(([outer]) => command(outer)).join("") + `L${pair(last[1][3])}` +
      [...RIMS].reverse().map(([, inner]) => command([inner[3], inner[2], inner[1], inner[0]])).join("") +
      `L${pair(ARM_INNER)}Z`,
    path: `M${pair(arm)}L${pair(sampleAt(centreCurves, 0)[0])}` + centreCurves.map(command).join(""),
    length,
    barLength,
    scaleStart: barLength + 18,
    scaleEnd: length - 5,
    pointAt,
    alongside,
  };
}

/** Computed once: every phase, placeholder and responsive presentation shares this definition. */
export const MONTH_DIAL_GEOMETRY = buildGeometry();
