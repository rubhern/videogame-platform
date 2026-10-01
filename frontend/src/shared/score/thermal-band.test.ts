import { describe, expect, it } from "vitest";

import { THERMAL_BANDS, thermalBand, thermalLabels } from "./thermal-band";

describe("thermalBand", () => {
  it("starts the scale frozen at its minimum", () => {
    expect(thermalBand(0)).toBe("freeze");
    // The lowest score the rating contract allows.
    expect(thermalBand(1)).toBe("freeze");
  });

  it.each([
    [2, "freeze", 2.1, "cold"],
    [4, "cold", 4.1, "warm"],
    [6, "warm", 6.1, "hot"],
    [8, "hot", 8.1, "burn"],
  ] as const)("keeps %s in %s and moves %s to %s", (edge, below, above, next) => {
    expect(thermalBand(edge)).toBe(below);
    expect(thermalBand(above)).toBe(next);
  });

  it("keeps the maximum score in the burn band", () => {
    expect(thermalBand(9)).toBe("burn");
    expect(thermalBand(10)).toBe("burn");
  });

  it("gives each band two personal rating values", () => {
    expect(Array.from({ length: 10 }, (_, index) => thermalBand(index + 1))).toEqual([
      "freeze",
      "freeze",
      "cold",
      "cold",
      "warm",
      "warm",
      "hot",
      "hot",
      "burn",
      "burn",
    ]);
  });

  it.each([-0.1, 10.1, Number.NaN, Number.POSITIVE_INFINITY])(
    "gives no band to %s, outside the 0–10 scale",
    (value) => {
      expect(thermalBand(value)).toBeNull();
    },
  );
});

describe("thermalLabels", () => {
  it("names every band in Spanish, from ice to fire", () => {
    expect(THERMAL_BANDS.map((band) => thermalLabels[band])).toEqual([
      "Congelado",
      "Frío",
      "Templado",
      "Caliente",
      "Ardiendo",
    ]);
  });
});
