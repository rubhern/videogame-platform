import { describe, expect, it } from "vitest";
import { platformDistribution } from "./platform-distribution";

describe("platform association percentages", () => {
  it("keeps five fixed categories, uses associations and allocates rounding ties in display order", () => {
    const { total, rows } = platformDistribution({ ps5: 1, switch2: 1, xboxSeries: 0, windowsPc: 1, other: 0 });
    expect(total).toBe(3);
    expect(rows.map(row => row.label)).toEqual(["PS5", "Switch 2", "Xbox Series X|S", "PC Windows", "Otras"]);
    expect(rows.map(row => row.percentage)).toEqual([33.4, 33.3, 0, 33.3, 0]);
    expect(rows.reduce((sum, row) => sum + row.tenths, 0)).toBe(1000);
  });
  it("does not invent a percentage in an empty distribution", () => {
    const result = platformDistribution({ ps5: 0, switch2: 0, xboxSeries: 0, windowsPc: 0, other: 0 });
    expect(result.total).toBe(0);
    expect(result.rows.every(row => row.percentage === 0)).toBe(true);
  });
  it("gives a single category the full circle and retains tiny nonzero counts without minimum-sized arcs", () => {
    expect(platformDistribution({ ps5: 0, switch2: 0, xboxSeries: 0, windowsPc: 0, other: 8 }).rows[4]?.percentage).toBe(100);
    const { rows } = platformDistribution({ ps5: 1, switch2: 1_000_000, xboxSeries: 0, windowsPc: 0, other: 0 });
    expect(rows[0]?.count).toBe(1);
    expect(rows[0]?.percentage).toBe(0);
    expect(rows[1]?.percentage).toBe(100);
  });
});
