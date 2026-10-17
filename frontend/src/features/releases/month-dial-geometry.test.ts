import { describe, expect, it } from "vitest";

import { MONTH_DIAL_GEOMETRY as geometry } from "./month-dial-geometry";
import { scalePosition } from "./period-timeline";

describe("reference-traced month dial", () => {
  it("keeps the reference arm and terminal rather than closing the ring", () => {
    expect(geometry.pointAt(0)).toMatchObject({ x: 343, y: 205.5 });
    expect(geometry.pointAt(geometry.barLength)).toMatchObject({ x: 417.5, y: 205.5 });
    expect(geometry.pointAt(geometry.length)).toMatchObject({ x: 260, y: 38 });
    expect(geometry.scaleStart).toBeGreaterThan(geometry.barLength);
    expect(geometry.scaleEnd).toBeLessThan(geometry.length);
  });

  it("has a curved lower bowl instead of the old flattened superellipse", () => {
    const points = Array.from({ length: 1001 }, (_, index) =>
      geometry.pointAt(geometry.barLength + (geometry.length - geometry.barLength) * index / 1000),
    );
    const lowest = points.reduce((a, b) => a.y > b.y ? a : b);
    expect(lowest.x).toBeCloseTo(250.5, -1);
    expect(lowest.y).toBeCloseTo(342.5, 1);
    // Reference landmarks: the lower arc rises on both sides of its single minimum.
    for (const x of [160, 340]) {
      const side = points.filter((p) => Math.abs(p.x - x) < 1 && p.y > 200);
      expect(side.length).toBeGreaterThan(0);
      expect(Math.max(...side.map((p) => p.y))).toBeLessThan(lowest.y - 15);
    }
  });

  it.each([28, 29, 30, 31])("places %i days at equal distances along the same curve", (days) => {
    const positions = Array.from({ length: days }, (_, index) =>
      geometry.pointAt(geometry.scaleStart + (geometry.scaleEnd - geometry.scaleStart) * scalePosition(index + 1, days)),
    );
    const chords = positions.slice(1).map((p, index) => {
      const previous = positions[index];
      if (previous === undefined) throw new Error("Missing previous day");
      return Math.hypot(p.x - previous.x, p.y - previous.y);
    });
    expect(Math.max(...chords) - Math.min(...chords)).toBeLessThan(0.4);
    expect(positions[0]).toEqual(geometry.pointAt(geometry.scaleStart));
    expect(positions.at(-1)).toEqual(geometry.pointAt(geometry.scaleEnd));
  });

  it("spaces a week's seven days evenly by arc length and far enough apart to name each", () => {
    const distances = Array.from(
      { length: 7 },
      (_, index) => geometry.scaleStart + (geometry.scaleEnd - geometry.scaleStart) * scalePosition(index + 1, 7),
    );
    const steps = distances.slice(1).map((distance, index) => distance - (distances[index] ?? 0));
    expect(Math.max(...steps) - Math.min(...steps)).toBeLessThan(1e-9);
    const points = distances.map((distance) => geometry.pointAt(distance));
    const chords = points.slice(1).map((p, index) => {
      const previous = points[index];
      if (previous === undefined) throw new Error("Missing previous day");
      return Math.hypot(p.x - previous.x, p.y - previous.y);
    });
    expect(Math.min(...chords)).toBeGreaterThan(90);
    expect(points[0]).toEqual(geometry.pointAt(geometry.scaleStart));
    expect(points.at(-1)).toEqual(geometry.pointAt(geometry.scaleEnd));
  });

  it("keeps normals unit length and outward across all three Bezier sections", () => {
    for (let index = 0; index <= 100; index += 1) {
      const p = geometry.pointAt(geometry.scaleStart + (geometry.scaleEnd - geometry.scaleStart) * index / 100);
      expect(Math.hypot(p.nx, p.ny)).toBeCloseTo(1, 8);
      expect((p.x - 245) * p.nx + (p.y - 193) * p.ny).toBeGreaterThan(0);
      expect(p.half).toBeGreaterThan(5.5);
      expect(p.half).toBeLessThan(14);
    }
  });
});
