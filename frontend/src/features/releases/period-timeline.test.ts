import { describe, expect, it } from "vitest";

import {
  daysInMonth,
  describePeriodScale,
  elapsedPosition,
  msUntilNextMadridDay,
  scalePosition,
  toPeriodTimeline,
  toReleaseMark,
  type PeriodTimeline,
} from "./period-timeline";

function month(from: string, to: string, evaluatedOn = "2026-10-16"): PeriodTimeline {
  const timeline = toPeriodTimeline("month", { from, to }, evaluatedOn);
  if (timeline === null) throw new Error("Invalid month");
  return timeline;
}

function week(from: string, to: string, evaluatedOn = "2026-10-16"): PeriodTimeline {
  const timeline = toPeriodTimeline("week", { from, to }, evaluatedOn);
  if (timeline === null) throw new Error("Invalid week");
  return timeline;
}

describe("period timeline", () => {
  it("counts the real length of every month, leap years included", () => {
    expect(daysInMonth(2026, 1)).toBe(31);
    expect(daysInMonth(2026, 2)).toBe(28);
    expect(daysInMonth(2026, 4)).toBe(30);
    expect(daysInMonth(2028, 2)).toBe(29);
    expect(daysInMonth(2100, 2)).toBe(28);
    expect(daysInMonth(2000, 2)).toBe(29);
    expect(daysInMonth(2026, 12)).toBe(31);
  });

  it("reads a month's phase and today only from the trusted evaluation date", () => {
    expect(month("2026-10-01", "2026-10-31")).toMatchObject({
      granularity: "month",
      days: 31,
      phase: "current",
      today: 16,
    });
    expect(month("2026-09-01", "2026-09-30")).toMatchObject({ days: 30, phase: "past", today: null });
    expect(month("2026-11-01", "2026-11-30")).toMatchObject({ phase: "future", today: null });
    expect(month("2028-02-01", "2028-02-29", "2028-02-29")).toMatchObject({
      days: 29,
      phase: "current",
      today: 29,
    });
    expect(toPeriodTimeline("month", { from: "2026-10-01", to: "2026-10-31" }, "16/10/2026")).toBeNull();
    expect(toPeriodTimeline("month", { from: "2026-10-31", to: "2026-10-01" }, "2026-10-16")).toBeNull();
  });

  it("divides a week into its seven days, Monday to Sunday, across months and years", () => {
    const crossing = week("2026-09-28", "2026-10-04", "2026-10-01");
    expect(crossing).toMatchObject({ granularity: "week", days: 7, phase: "current", today: 4 });
    expect(crossing.divisions.map((division) => division.day)).toEqual([28, 29, 30, 1, 2, 3, 4]);
    expect(crossing.divisions.map((division) => division.weekday)).toEqual([
      "lunes",
      "martes",
      "miércoles",
      "jueves",
      "viernes",
      "sábado",
      "domingo",
    ]);
    // Only the day that starts the new month names it.
    expect(crossing.divisions.map((division) => division.month)).toEqual([
      null,
      null,
      null,
      "oct",
      null,
      null,
      null,
    ]);

    // ISO week 53 of 2026 runs into 2027.
    const yearEnd = week("2026-12-28", "2027-01-03", "2026-10-16");
    expect(yearEnd.phase).toBe("future");
    expect(yearEnd.divisions.map((division) => division.date)).toEqual([
      "2026-12-28",
      "2026-12-29",
      "2026-12-30",
      "2026-12-31",
      "2027-01-01",
      "2027-01-02",
      "2027-01-03",
    ]);
    expect(yearEnd.divisions[4]?.month).toBe("ene");
    expect(week("2026-10-05", "2026-10-11").phase).toBe("past");
    // A week is always seven days.
    expect(toPeriodTimeline("week", { from: "2026-10-01", to: "2026-10-31" }, "2026-10-16")).toBeNull();
  });

  it("spaces the divisions evenly from the period's first day to its last", () => {
    expect(scalePosition(1, 31)).toBe(0);
    expect(scalePosition(31, 31)).toBe(1);
    expect(scalePosition(16, 31)).toBeCloseTo(0.5);
    expect(scalePosition(15, 29)).toBeCloseTo(0.5);
    expect(scalePosition(4, 7)).toBeCloseTo(0.5);
    expect(scalePosition(7, 7)).toBe(1);
  });

  it("lights the whole past period, today's share of the current one and none of a future one", () => {
    expect(elapsedPosition(month("2026-10-01", "2026-10-31"))).toBeCloseTo(0.5);
    expect(elapsedPosition(month("2026-09-01", "2026-09-30"))).toBe(1);
    expect(elapsedPosition(month("2026-11-01", "2026-11-30"))).toBe(0);
    expect(elapsedPosition(week("2026-10-12", "2026-10-18", "2026-10-15"))).toBeCloseTo(0.5);
    expect(elapsedPosition(week("2026-10-05", "2026-10-11", "2026-10-15"))).toBe(1);
    expect(elapsedPosition(week("2026-10-19", "2026-10-25", "2026-10-15"))).toBe(0);
  });

  it("places a release at its day and never invents one finer than the contract states", () => {
    const october = month("2026-10-01", "2026-10-31");
    expect(toReleaseMark({ precision: "day", value: "2026-10-29" }, october)).toEqual({
      kind: "day",
      day: 29,
    });
    expect(toReleaseMark({ precision: "month", value: "2026-10" }, october)).toEqual({
      kind: "span",
      open: false,
    });
    expect(toReleaseMark({ precision: "quarter", value: "2026-Q4" }, october)).toEqual({
      kind: "span",
      open: true,
    });
    expect(toReleaseMark({ precision: "year", value: "2026" }, october)).toEqual({
      kind: "span",
      open: true,
    });
    expect(toReleaseMark({ precision: "unknown", value: null }, october)).toEqual({
      kind: "unknown",
    });
  });

  it("places a week's release on its weekday and never narrows a month to a week's day", () => {
    const crossing = week("2026-09-28", "2026-10-04");
    expect(toReleaseMark({ precision: "day", value: "2026-10-01" }, crossing)).toEqual({
      kind: "day",
      day: 4,
    });
    expect(toReleaseMark({ precision: "day", value: "2026-09-28" }, crossing)).toEqual({
      kind: "day",
      day: 1,
    });
    // A month overlapping the week reaches beyond it: the whole scale, open, never a day.
    expect(toReleaseMark({ precision: "month", value: "2026-10" }, crossing)).toEqual({
      kind: "span",
      open: true,
    });
    expect(toReleaseMark({ precision: "month", value: "2026-09" }, crossing)).toEqual({
      kind: "span",
      open: true,
    });
    expect(toReleaseMark({ precision: "month", value: "2026-08" }, crossing)).toEqual({
      kind: "before",
    });
    expect(toReleaseMark({ precision: "day", value: "2026-10-05" }, crossing)).toEqual({
      kind: "after",
    });
  });

  it("keeps a release outside the period off its scale", () => {
    const october = month("2026-10-01", "2026-10-31");
    expect(toReleaseMark({ precision: "day", value: "2026-09-30" }, october)).toEqual({
      kind: "before",
    });
    expect(toReleaseMark({ precision: "month", value: "2026-11" }, october)).toEqual({
      kind: "after",
    });
    expect(toReleaseMark({ precision: "quarter", value: "2026-Q3" }, october)).toEqual({
      kind: "before",
    });
    expect(toReleaseMark({ precision: "year", value: "2027" }, october)).toEqual({
      kind: "after",
    });
    expect(toReleaseMark({ precision: "day", value: "2026-10-32" }, october)).toEqual({
      kind: "unknown",
    });
  });

  it("describes a month's progress and the release in words for assistive technology", () => {
    const october = month("2026-10-01", "2026-10-31");
    const september = month("2026-09-01", "2026-09-30");
    const november = month("2026-11-01", "2026-11-30");
    expect(describePeriodScale(october, { kind: "day", day: 29 }, "octubre de 2026")).toBe(
      "Calendario de octubre de 2026: hoy es el día 16 de 31; lanzamiento el día 29.",
    );
    expect(describePeriodScale(september, { kind: "day", day: 15 }, "septiembre de 2026")).toBe(
      "Calendario de septiembre de 2026: mes completo, 30 de 30 días transcurridos; lanzamiento el día 15.",
    );
    expect(describePeriodScale(november, { kind: "span", open: false }, "noviembre de 2026")).toBe(
      "Calendario de noviembre de 2026: el mes aún no ha empezado; lanzamiento sin día confirmado.",
    );
    expect(describePeriodScale(november, { kind: "unknown" }, "noviembre de 2026")).toBe(
      "Calendario de noviembre de 2026: el mes aún no ha empezado; fecha de lanzamiento por confirmar.",
    );
  });

  it("describes a week's progress and the release's weekday in words", () => {
    const phrase = "la semana del 12 al 18 de octubre de 2026";
    expect(
      describePeriodScale(week("2026-10-12", "2026-10-18", "2026-10-17"), { kind: "day", day: 4 }, phrase),
    ).toBe(
      "Calendario de la semana del 12 al 18 de octubre de 2026: hoy es sábado 17, día 6 de 7; lanzamiento el jueves 15.",
    );
    expect(
      describePeriodScale(week("2026-10-12", "2026-10-18", "2026-10-20"), { kind: "day", day: 1 }, phrase),
    ).toBe(
      "Calendario de la semana del 12 al 18 de octubre de 2026: semana completa, 7 de 7 días transcurridos; lanzamiento el lunes 12.",
    );
    expect(
      describePeriodScale(week("2026-10-12", "2026-10-18", "2026-10-01"), { kind: "after" }, phrase),
    ).toBe(
      "Calendario de la semana del 12 al 18 de octubre de 2026: la semana aún no ha empezado; lanzamiento después de esta semana.",
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
