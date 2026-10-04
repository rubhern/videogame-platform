import { describe, expect, it } from "vitest";

import { featuredSearchPath, readFeaturedSearch, shiftMonth } from "./featured-search";

function read(query: string) {
  return readFeaturedSearch(new URLSearchParams(query));
}

describe("featured releases navigable state", () => {
  it("represents the current month unless the URL names a valid one", () => {
    expect(read("")).toEqual({ month: null });
    expect(read("month=2026-10")).toEqual({ month: "2026-10" });
    // Values outside the contract shape never reach the API.
    for (const invalid of ["2026-13", "0000-01", "2026-1", "26-10", "2026-10-01", ""]) {
      expect(read(`month=${invalid}`)).toEqual({ month: null });
    }
  });

  it("keeps the landing route for the current month and names any other", () => {
    expect(featuredSearchPath(null)).toBe("/");
    expect(featuredSearchPath("2026-09")).toBe("/?month=2026-09");
  });

  it("steps across years and stops at the contract's month bounds", () => {
    expect(shiftMonth("2026-12", 1)).toBe("2027-01");
    expect(shiftMonth("2027-01", -1)).toBe("2026-12");
    expect(shiftMonth("2026-10", -1)).toBe("2026-09");
    expect(shiftMonth("0001-01", -1)).toBeNull();
    expect(shiftMonth("9999-12", 1)).toBeNull();
    expect(shiftMonth("not-a-month", 1)).toBeNull();
  });
});
