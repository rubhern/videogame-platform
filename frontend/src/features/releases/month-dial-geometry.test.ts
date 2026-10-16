import { describe, expect, it } from "vitest";

import { dialGeometry, encloseDial } from "./month-dial-geometry";

const shape = { cx: 300, cy: 240, a: 260, b: 190, exponent: 3.2, band: 28, bar: 40 };

describe("month dial geometry", () => {
  it("opens with the crossbar reaching inside the G before day 1", () => {
    const geometry = dialGeometry(shape);
    const barStart = geometry.pointAt(0);
    const corner = geometry.pointAt(geometry.barLength);
    // The bar runs level, from inside the bowl out to the G's right side, like the brand mark.
    expect(barStart.y).toBeCloseTo(shape.cy);
    expect(corner.y).toBeCloseTo(shape.cy);
    expect(corner.x).toBeCloseTo(shape.cx + shape.a);
    expect(barStart.x).toBeCloseTo(shape.cx + shape.a - shape.band / 2 - shape.bar);
    expect(geometry.scaleStart).toBeGreaterThan(geometry.barLength);
  });

  it("runs clockwise from the crossbar and stops short of a closed ring, as a G does", () => {
    const geometry = dialGeometry(shape);
    const quarter = geometry.pointAt(geometry.scaleStart + (geometry.scaleEnd - geometry.scaleStart) * 0.2);
    // Going down the right side first: clockwise on screen.
    expect(quarter.y).toBeGreaterThan(shape.cy);
    const terminal = geometry.pointAt(geometry.length);
    // The terminal ends at the top, right of the centre, leaving the mouth open to the upper right.
    expect(terminal.y).toBeLessThan(shape.cy - shape.b * 0.9);
    expect(terminal.x).toBeGreaterThan(shape.cx);
    expect(terminal.x).toBeLessThan(shape.cx + shape.a * 0.4);
  });

  it("spaces equal stretches of time as equal lengths of the band", () => {
    const geometry = dialGeometry(shape);
    const days = 31;
    const positions = Array.from({ length: days }, (_, index) =>
      geometry.pointAt(geometry.scaleStart + ((geometry.scaleEnd - geometry.scaleStart) * index) / (days - 1)),
    );
    const steps = positions.slice(1).map((point, index) => {
      const previous = positions[index];
      return previous === undefined ? 0 : Math.hypot(point.x - previous.x, point.y - previous.y);
    });
    // Chords of equal arcs: nearly equal along gentle curves, never off by a visible amount.
    expect(Math.max(...steps) - Math.min(...steps)).toBeLessThan(1.5);
  });

  it("points every normal away from the bowl", () => {
    const geometry = dialGeometry(shape);
    for (const fraction of [0, 0.25, 0.5, 0.75, 1]) {
      const point = geometry.pointAt(geometry.scaleStart + (geometry.scaleEnd - geometry.scaleStart) * fraction);
      const outward = (point.x - shape.cx) * point.nx + (point.y - shape.cy) * point.ny;
      expect(outward).toBeGreaterThan(0);
    }
  });

  it("encloses a copy block of any size inside the bowl, clear of the crossbar", () => {
    for (const [width, height] of [[450, 250], [450, 340], [400, 420], [420, 180]] as const) {
      const dial = encloseDial(width, height);
      const { a, b, exponent, band, bar } = dial.shape;
      const innerA = a - band / 2;
      const innerB = b - band / 2;
      // Every corner of the copy stays inside the band's inner edge.
      const corner = (width / 2 / innerA) ** exponent + (height / 2 / innerB) ** exponent;
      expect(corner).toBeLessThan(1);
      // The crossbar's tip stops before the copy's right edge.
      expect(innerA - bar).toBeGreaterThan(width / 2);
      // The copy sits centred in the dial's box, which reserves room for the labels outside.
      expect(dial.width / 2 - dial.padX).toBeCloseTo(width / 2);
      expect(dial.height / 2 - dial.padY).toBeCloseTo(height / 2);
      // A short copy never flattens the G into a band: it keeps a recognisable bowl.
      expect(innerB / innerA).toBeGreaterThanOrEqual(0.6);
    }
  });
});
