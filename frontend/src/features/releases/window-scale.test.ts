import { describe, expect, it } from "vitest";

import { dayIndex, toWindowScale } from "./window-scale";

describe("toWindowScale", () => {
  it("lights every day of a recent window that ends on the evaluation date", () => {
    const scale = toWindowScale("2026-10-11", "2026-10-17", "2026-10-17");

    expect(scale?.days.map((day) => day.day)).toEqual([11, 12, 13, 14, 15, 16, 17]);
    expect(scale?.days.every((day) => day.elapsed && day.labelled)).toBe(true);
    expect(scale?.today).toBe(6);
  });

  it("places today just before an upcoming window and leaves its days unlit", () => {
    const scale = toWindowScale("2026-10-18", "2026-10-31", "2026-10-17");

    expect(scale?.days).toHaveLength(14);
    expect(scale?.days.some((day) => day.elapsed)).toBe(false);
    expect(scale?.today).toBe(-1);
  });

  it("names the month where the scale starts and where a new month begins", () => {
    const scale = toWindowScale("2026-09-28", "2026-10-25", "2026-10-25");

    expect(scale?.days.filter((day) => day.month !== null).map((day) => [day.date, day.month])).toEqual([
      ["2026-09-28", "sep"],
      ["2026-10-01", "oct"],
    ]);
  });

  it("prints one numeral a week, and the last day, on a four-week window", () => {
    const scale = toWindowScale("2026-09-20", "2026-10-17", "2026-10-17");

    expect(scale?.days.filter((day) => day.labelled).map((day) => day.day)).toEqual([20, 27, 4, 11, 17]);
  });

  it("crosses a leap day and a year end without inventing or losing days", () => {
    expect(toWindowScale("2028-02-26", "2028-03-03", "2028-03-03")?.days.map((day) => day.day)).toEqual([
      26, 27, 28, 29, 1, 2, 3,
    ]);
    expect(toWindowScale("2026-12-29", "2027-01-04", "2026-12-31")?.today).toBe(2);
  });

  it("refuses malformed, reversed or unbounded windows", () => {
    expect(toWindowScale("2026-10-11", "2026-02-30", "2026-10-17")).toBeNull();
    expect(toWindowScale("2026-10-17", "2026-10-11", "2026-10-17")).toBeNull();
    expect(toWindowScale("2026-01-01", "2026-12-31", "2026-10-17")).toBeNull();
    expect(toWindowScale("2026-10-11", "2026-10-17", "hoy")).toBeNull();
  });
});

describe("dayIndex", () => {
  it("finds a calendar day inside the window and nothing outside it", () => {
    const scale = toWindowScale("2026-10-11", "2026-10-17", "2026-10-17");
    if (scale === null) throw new Error("Expected a scale");

    expect(dayIndex(scale, "2026-10-14")).toBe(3);
    expect(dayIndex(scale, "2026-10-18")).toBeNull();
    expect(dayIndex(scale, "2026-10")).toBeNull();
  });
});
