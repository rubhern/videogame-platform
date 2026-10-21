import { describe, expect, it } from "vitest";

import type { ReleaseCalendar } from "./release-calendar-api";
import {
  chosenDay,
  dayHeading,
  defaultDay,
  densityLevel,
  formatMonth,
  monthWindow,
  monthsBetween,
  shiftMonth,
  toReleaseCalendarModel,
} from "./release-calendar-model";

function calendar(overrides: Partial<ReleaseCalendar> = {}): ReleaseCalendar {
  return {
    month: "2026-11",
    evaluatedOn: "2026-10-21",
    window: { from: "2026-11-01", to: "2026-11-30" },
    navigation: { first: "2026-10", last: "2027-03", previous: "2026-10", next: "2026-12" },
    activeFilters: { platformIds: [], regionIds: [] },
    availableFilters: { platforms: [], regions: [] },
    days: [],
    ...overrides,
  };
}

const dates = (weeks: ReturnType<typeof toReleaseCalendarModel>["weeks"]) =>
  weeks.map((week) => week.map((day) => day?.day ?? null));

describe("release calendar model", () => {
  it.each([
    [0, 0], [9, 0], [10, 1], [29, 1], [30, 2], [99, 2],
    [100, 3], [149, 3], [150, 4], [1102, 4],
  ])("places total Hype %i at colour level %i", (hype, level) => {
    expect(densityLevel(hype)).toBe(level);
  });

  it("colours by total Hype independently of the release count", () => {
    const model = toReleaseCalendarModel(calendar({ days: [
      { date: "2026-11-01", gameCount: 1, totalHype: 1102 },
      { date: "2026-11-02", gameCount: 4, totalHype: 40 },
      { date: "2026-11-03", gameCount: 1, totalHype: 40 },
    ] }));
    expect(model.days[0]).toMatchObject({ gameCount: 1, density: 4 });
    expect(model.days[1]).toMatchObject({ gameCount: 4, density: 2 });
    expect(model.days[2]).toMatchObject({ gameCount: 1, density: 2 });
  });

  it("lays a month out in Monday-to-Sunday weeks without days of other months", () => {
    // 1 November 2026 is a Sunday: six empty weekdays lead, and the 30th, a Monday, closes.
    const model = toReleaseCalendarModel(calendar());

    expect(dates(model.weeks)[0]).toEqual([null, null, null, null, null, null, 1]);
    expect(dates(model.weeks)[1]).toEqual([2, 3, 4, 5, 6, 7, 8]);
    expect(dates(model.weeks).at(-1)).toEqual([30, null, null, null, null, null, null]);
    expect(model.weeks).toHaveLength(6);
    expect(new Set(model.days.map((day) => day.date)).size).toBe(30);
  });

  it("holds a leap February to the 29th and a February starting on Monday in four weeks", () => {
    const leap = toReleaseCalendarModel(
      calendar({ month: "2028-02", window: { from: "2028-02-01", to: "2028-02-29" } }),
    );
    const plain = toReleaseCalendarModel(
      calendar({ month: "2027-02", window: { from: "2027-02-01", to: "2027-02-28" } }),
    );

    expect(leap.days.at(-1)?.date).toBe("2028-02-29");
    // 1 February 2028 is a Tuesday.
    expect(dates(leap.weeks)[0]).toEqual([null, 1, 2, 3, 4, 5, 6]);
    expect(plain.weeks).toHaveLength(4);
    expect(dates(plain.weeks)[0]).toEqual([1, 2, 3, 4, 5, 6, 7]);
  });

  it("is active from the API's evaluation date: earlier days are inert and never chosen", () => {
    const model = toReleaseCalendarModel(
      calendar({
        month: "2026-10",
        evaluatedOn: "2026-10-21",
        window: { from: "2026-10-01", to: "2026-10-31" },
        days: [
          { date: "2026-10-21", gameCount: 17, totalHype: 170 },
          { date: "2026-10-23", gameCount: 35, totalHype: 350 },
          { date: "2026-10-31", gameCount: 5, totalHype: 50 },
        ],
      }),
    );
    const day = (date: string) => model.days.find((candidate) => candidate.date === date);

    expect(day("2026-10-20")).toMatchObject({ past: true, gameCount: 0 });
    expect(day("2026-10-21")).toMatchObject({ gameCount: 17, density: 4, past: false, today: true });
    expect(day("2026-10-21")?.label).toBe("miércoles, 21 de octubre de 2026");
    expect(day("2026-10-23")).toMatchObject({ density: 4, past: false, today: false });
    expect(model.activeDays.map((active) => active.day)).toEqual([21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31]);
    expect(model.daysWithReleases).toBe(3);
    expect(defaultDay(model)).toBe("2026-10-21");
    expect(chosenDay(model, "2026-10-05")).toBe("2026-10-21");
    expect(chosenDay(model, "2026-10-31")).toBe("2026-10-31");
    expect(chosenDay(model, "2026-11-02")).toBe("2026-10-21");
  });

  it("opens on the first day with releases, or on none", () => {
    expect(
      defaultDay(toReleaseCalendarModel(calendar({ days: [{ date: "2026-11-13", gameCount: 1, totalHype: 10 }] }))),
    ).toBe("2026-11-13");
    expect(defaultDay(toReleaseCalendarModel(calendar()))).toBeNull();
  });

  it("steps months across years and windows the requestable ones", () => {
    expect(shiftMonth("2026-12", 1)).toBe("2027-01");
    expect(shiftMonth("2027-01", -1)).toBe("2026-12");
    expect(monthsBetween("2026-10", "2027-03")).toEqual([
      "2026-10",
      "2026-11",
      "2026-12",
      "2027-01",
      "2027-02",
      "2027-03",
    ]);
    const months = monthsBetween("2026-10", "2027-03");
    expect(monthWindow(months, "2026-10")).toEqual(["2026-10", "2026-11", "2026-12", "2027-01"]);
    expect(monthWindow(months, "2026-12")).toEqual(["2026-11", "2026-12", "2027-01", "2027-02"]);
    expect(monthWindow(months, "2027-03")).toEqual(["2026-12", "2027-01", "2027-02", "2027-03"]);
    expect(monthWindow(["2026-10"], "2026-10")).toEqual(["2026-10"]);
  });

  it("names months and days in Spanish", () => {
    expect(formatMonth("2027-01")).toBe("Enero 2027");
    expect(dayHeading("2026-11-22")).toBe("Domingo, 22 de noviembre");
  });
});
