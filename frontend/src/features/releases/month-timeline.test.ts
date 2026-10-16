import { describe, expect, it } from "vitest";

import {
  daysInMonth,
  describeMonthScale,
  elapsedPosition,
  msUntilNextMadridDay,
  scalePosition,
  toMonthTimeline,
  toReleaseMark,
} from "./month-timeline";

describe("month timeline", () => {
  it("counts the real length of every month, leap years included", () => {
    expect(daysInMonth(2026, 1)).toBe(31);
    expect(daysInMonth(2026, 2)).toBe(28);
    expect(daysInMonth(2026, 4)).toBe(30);
    expect(daysInMonth(2028, 2)).toBe(29);
    expect(daysInMonth(2100, 2)).toBe(28);
    expect(daysInMonth(2000, 2)).toBe(29);
    expect(daysInMonth(2026, 12)).toBe(31);
  });

  it("reads the phase and today only from the trusted evaluation date", () => {
    expect(toMonthTimeline("2026-10", "2026-10-16")).toEqual({
      month: "2026-10",
      days: 31,
      phase: "current",
      today: 16,
    });
    expect(toMonthTimeline("2026-09", "2026-10-16")).toEqual({
      month: "2026-09",
      days: 30,
      phase: "past",
      today: null,
    });
    expect(toMonthTimeline("2026-11", "2026-10-16")).toEqual({
      month: "2026-11",
      days: 30,
      phase: "future",
      today: null,
    });
    expect(toMonthTimeline("2028-02", "2028-02-29")).toEqual({
      month: "2028-02",
      days: 29,
      phase: "current",
      today: 29,
    });
    expect(toMonthTimeline("2026-13", "2026-10-16")).toBeNull();
    expect(toMonthTimeline("2026-10", "16/10/2026")).toBeNull();
  });

  it("spaces the days evenly from the first to the last day of the month", () => {
    expect(scalePosition(1, 31)).toBe(0);
    expect(scalePosition(31, 31)).toBe(1);
    expect(scalePosition(16, 31)).toBeCloseTo(0.5);
    expect(scalePosition(15, 29)).toBeCloseTo(0.5);
    expect(scalePosition(28, 28)).toBe(1);
  });

  it("lights the whole past month, today's share of the current one and none of a future one", () => {
    const october = toMonthTimeline("2026-10", "2026-10-16");
    const september = toMonthTimeline("2026-09", "2026-10-16");
    const november = toMonthTimeline("2026-11", "2026-10-16");
    if (october === null || september === null || november === null) throw new Error("Invalid month");
    expect(elapsedPosition(october)).toBeCloseTo(0.5);
    expect(elapsedPosition(september)).toBe(1);
    expect(elapsedPosition(november)).toBe(0);
  });

  it("places a release at its day and never invents one finer than the contract states", () => {
    expect(toReleaseMark({ precision: "day", value: "2026-10-29" }, "2026-10")).toEqual({
      kind: "day",
      day: 29,
    });
    expect(toReleaseMark({ precision: "month", value: "2026-10" }, "2026-10")).toEqual({
      kind: "span",
      open: false,
    });
    expect(toReleaseMark({ precision: "quarter", value: "2026-Q4" }, "2026-10")).toEqual({
      kind: "span",
      open: true,
    });
    expect(toReleaseMark({ precision: "year", value: "2026" }, "2026-10")).toEqual({
      kind: "span",
      open: true,
    });
    expect(toReleaseMark({ precision: "unknown", value: null }, "2026-10")).toEqual({
      kind: "unknown",
    });
  });

  it("keeps a release outside the month off its scale", () => {
    expect(toReleaseMark({ precision: "day", value: "2026-09-30" }, "2026-10")).toEqual({
      kind: "before",
    });
    expect(toReleaseMark({ precision: "month", value: "2026-11" }, "2026-10")).toEqual({
      kind: "after",
    });
    expect(toReleaseMark({ precision: "quarter", value: "2026-Q3" }, "2026-10")).toEqual({
      kind: "before",
    });
    expect(toReleaseMark({ precision: "year", value: "2027" }, "2026-10")).toEqual({
      kind: "after",
    });
    expect(toReleaseMark({ precision: "day", value: "2026-10-32" }, "2026-10")).toEqual({
      kind: "unknown",
    });
  });

  it("describes the progress and the release in words for assistive technology", () => {
    const october = toMonthTimeline("2026-10", "2026-10-16");
    const september = toMonthTimeline("2026-09", "2026-10-16");
    const november = toMonthTimeline("2026-11", "2026-10-16");
    if (october === null || september === null || november === null) throw new Error("Invalid month");
    expect(describeMonthScale(october, { kind: "day", day: 29 }, "octubre de 2026")).toBe(
      "Calendario de octubre de 2026: hoy es el día 16 de 31; lanzamiento el día 29.",
    );
    expect(describeMonthScale(september, { kind: "day", day: 15 }, "septiembre de 2026")).toBe(
      "Calendario de septiembre de 2026: mes completo, 30 de 30 días transcurridos; lanzamiento el día 15.",
    );
    expect(describeMonthScale(november, { kind: "span", open: false }, "noviembre de 2026")).toBe(
      "Calendario de noviembre de 2026: el mes aún no ha empezado; lanzamiento sin día confirmado.",
    );
    expect(describeMonthScale(november, { kind: "unknown" }, "noviembre de 2026")).toBe(
      "Calendario de noviembre de 2026: el mes aún no ha empezado; fecha de lanzamiento por confirmar.",
    );
  });

  it("waits until the next day in Europe/Madrid, whatever the browser's own time zone", () => {
    // 23:59:00 in Madrid during summer time (UTC+2).
    expect(msUntilNextMadridDay(new Date("2026-10-16T21:59:00Z"))).toBe(60_000);
    // 23:30:00 in Madrid during winter time (UTC+1).
    expect(msUntilNextMadridDay(new Date("2026-12-01T22:30:00Z"))).toBe(30 * 60_000);
    // Just after midnight: nearly a whole day ahead.
    expect(msUntilNextMadridDay(new Date("2026-12-01T23:00:00.500Z"))).toBe(24 * 3_600_000 - 500);
  });
});
