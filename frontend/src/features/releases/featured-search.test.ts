import { describe, expect, it } from "vitest";

import {
  DEFAULT_FEATURED_GRANULARITY,
  featuredSearchPath,
  isCanonicalFeaturedSearch,
  readFeaturedSearch,
} from "./featured-search";

function read(query: string) {
  return readFeaturedSearch(new URLSearchParams(query));
}

describe("featured releases navigable state", () => {
  it("opens on the current week, the single default granularity", () => {
    expect(DEFAULT_FEATURED_GRANULARITY).toBe("week");
    expect(read("")).toEqual({ granularity: "week", period: null });
    expect(featuredSearchPath({ granularity: "week", period: null })).toBe("/");
  });

  it("reads a named week or month, or the current period of a named granularity", () => {
    expect(read("week=2026-W42")).toEqual({ granularity: "week", period: "2026-W42" });
    expect(read("week=2026-W53")).toEqual({ granularity: "week", period: "2026-W53" });
    expect(read("month=2026-10")).toEqual({ granularity: "month", period: "2026-10" });
    expect(read("granularity=month")).toEqual({ granularity: "month", period: null });
    expect(read("granularity=week")).toEqual({ granularity: "week", period: null });
    expect(read("granularity=week&week=2026-W42")).toEqual({
      granularity: "week",
      period: "2026-W42",
    });
  });

  it("never sends a value outside the contract shape or a contradiction to the API", () => {
    for (const invalid of ["2026-13", "0000-01", "2026-1", "26-10", "2026-10-01", ""]) {
      expect(read(`month=${invalid}`)).toEqual({ granularity: "week", period: null });
    }
    for (const invalid of ["2026-W54", "2026-W00", "2026-W5", "2026W42", "0000-W01", ""]) {
      expect(read(`week=${invalid}`)).toEqual({ granularity: "week", period: null });
    }
    expect(read("week=2026-W42&month=2026-10")).toEqual({ granularity: "week", period: null });
    expect(read("granularity=month&week=2026-W42")).toEqual({
      granularity: "week",
      period: null,
    });
    expect(read("granularity=day")).toEqual({ granularity: "week", period: null });
  });

  it("keeps one canonical route for every state", () => {
    expect(featuredSearchPath({ granularity: "month", period: null })).toBe("/?granularity=month");
    expect(featuredSearchPath({ granularity: "month", period: "2026-09" })).toBe("/?month=2026-09");
    expect(featuredSearchPath({ granularity: "week", period: "2027-W01" })).toBe("/?week=2027-W01");

    for (const canonical of ["", "week=2026-W42", "month=2026-09", "granularity=month"]) {
      expect(isCanonicalFeaturedSearch(new URLSearchParams(canonical))).toBe(true);
    }
    for (const other of [
      "granularity=week",
      "granularity=month&month=2026-09",
      "month=2026-13",
      "week=2026-W42&month=2026-10",
      "utm=feed",
    ]) {
      expect(isCanonicalFeaturedSearch(new URLSearchParams(other))).toBe(false);
    }
  });
});
