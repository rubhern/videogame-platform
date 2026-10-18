import { describe, expect, it } from "vitest";

import type { SynchronizationCoverage } from "./administration-api";
import { answers, lastDayOfMonth, monthSummaries, periodsWithin, summarize, yearSummaries } from "./coverage-calendar";

const now = new Date("2026-10-18T10:00:00Z");
const scan = (completedAt: string, failedGames = 0) => ({
  runId: "run", scanStartedAt: completedAt, scanCompletedAt: completedAt, failedGames,
});

const coverage: SynchronizationCoverage = {
  from: "1997-01-01",
  to: "1997-12-31",
  periods: [
    { from: "1997-01-01", to: "1997-02-14", status: "never_covered" },
    { from: "1997-02-15", to: "1997-03-31", status: "covered", lastScan: scan("2026-10-15T04:00:00Z") },
    { from: "1997-04-01", to: "1997-04-30", status: "covered", lastScan: scan("2026-01-10T04:00:00Z", 3) },
    { from: "1997-05-01", to: "1997-12-31", status: "never_covered" },
  ],
};

describe("coverage calendar", () => {
  it("distinguishes never covered, partly covered, completely covered and recently refreshed months", () => {
    const months = monthSummaries(coverage, 1997, now);

    expect(months[0]).toMatchObject({ state: "never", coveredDays: 0, recent: null, incomplete: false });
    expect(months[1]).toMatchObject({ state: "partial", totalDays: 28, coveredDays: 14, recent: true });
    expect(months[2]).toMatchObject({ state: "complete", coveredDays: 31, recent: true, failedGames: false });
    // Covered long ago stays covered; only the freshness reading changes.
    expect(months[3]).toMatchObject({ state: "complete", recent: false, failedGames: true,
      oldestScan: "2026-01-10T04:00:00.000Z" });
    expect(months.slice(4).every((month) => month.state === "never")).toBe(true);
  });

  it("judges a span by its oldest covering scan", () => {
    const spring = summarize(coverage, "1997-03-01", "1997-04-30", now);

    expect(spring).toMatchObject({ state: "complete", coveredDays: 61, recent: false,
      oldestScan: "2026-01-10T04:00:00.000Z", newestScan: "2026-10-15T04:00:00.000Z" });
  });

  it("marks the days beyond a bounded answer as not described rather than never covered", () => {
    const bounded: SynchronizationCoverage = {
      from: "1990-01-01", to: "1999-12-31", continuesFrom: "1994-07-01",
      periods: [
        { from: "1990-01-01", to: "1993-12-31", status: "never_covered" },
        { from: "1994-01-01", to: "1994-06-30", status: "covered", lastScan: scan("2026-10-17T04:00:00Z") },
      ],
    };
    const years = yearSummaries(bounded, 1990, now);

    expect(years[3]).toMatchObject({ state: "never", incomplete: false });
    expect(years[4]).toMatchObject({ state: "partial", coveredDays: 181, incomplete: true });
    expect(years[5]).toMatchObject({ state: "unknown", incomplete: true });
    expect(answers(bounded, "1994-01-01", "1994-06-30")).toBe(true);
    expect(answers(bounded, "1994-01-01", "1994-12-31")).toBe(false);
    expect(answers(coverage, "1997-01-01", "1997-12-31")).toBe(true);
  });

  it("clips periods to a month and knows leap years", () => {
    expect(lastDayOfMonth(1996, 2)).toBe("1996-02-29");
    expect(lastDayOfMonth(1997, 2)).toBe("1997-02-28");
    expect(periodsWithin(coverage.periods, "1997-02-01", "1997-02-28")).toEqual([
      { from: "1997-02-01", to: "1997-02-14", status: "never_covered" },
      { from: "1997-02-15", to: "1997-02-28", status: "covered", lastScan: scan("2026-10-15T04:00:00Z") },
    ]);
  });
});
