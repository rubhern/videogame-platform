import { describe, expect, it } from "vitest";

import { regionLabel } from "./region-label";

describe("region presentation", () => {
  it.each([
    ["Europe", "Europa"],
    ["Japan", "Japón"],
    ["North America", "Norteamérica"],
    ["Worldwide", "Mundial"],
    ["Unknown", "Sin región confirmada"],
  ])("presents the canonical %s label in Spanish", (source, expected) => {
    expect(regionLabel(source)).toBe(expected);
  });

  it("preserves a catalogue label it does not recognise", () => {
    expect(regionLabel("Asia-Pacific")).toBe("Asia-Pacific");
  });
});
