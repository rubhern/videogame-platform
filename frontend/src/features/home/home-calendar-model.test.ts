import { describe, expect, it } from "vitest";

import { homeCalendar, homeNextCalendar } from "../../test/home-api";
import { toReleaseCalendarModel } from "../releases/release-calendar-model";
import { busiestDay, needsFollowingMonth, upcomingHighlightDays } from "./home-calendar-model";
import { legacyHomeRedirect } from "./home-path";

const current = toReleaseCalendarModel(homeCalendar);
const following = toReleaseCalendarModel(homeNextCalendar);

describe("home calendar preview model", () => {
  it("offers the days with highlights from today on, across the month after when one is read", () => {
    expect(upcomingHighlightDays(current, following).map((day) => [day.date, day.gameCount])).toEqual([
      ["2026-10-28", 1],
      ["2026-10-29", 5],
      ["2026-10-30", 1],
      ["2026-11-03", 2],
      ["2026-11-05", 2],
    ]);
  });

  it("never offers a day before the API's evaluation date nor one without highlights", () => {
    const withPast = toReleaseCalendarModel({
      ...homeCalendar,
      days: [
        { date: "2026-10-20", gameCount: 4, totalHype: 80 },
        { date: "2026-10-30", gameCount: 3, totalHype: 40 },
      ],
    });
    // A day gone by is never offered, and a day the API leaves out is never an empty slot.
    expect(upcomingHighlightDays(withPast, null).map((day) => day.date)).toEqual(["2026-10-30"]);
  });

  it("stops at its limit and ignores a following month that is not later", () => {
    expect(upcomingHighlightDays(current, following, 2)).toHaveLength(2);
    expect(upcomingHighlightDays(current, current).map((day) => day.date)).toEqual([
      "2026-10-28",
      "2026-10-29",
      "2026-10-30",
    ]);
  });

  it("reads the following month only when the served one runs short and the API offers one", () => {
    expect(needsFollowingMonth(current)).toBe(true);
    expect(needsFollowingMonth(current, 3)).toBe(false);
    expect(needsFollowingMonth(toReleaseCalendarModel({ ...homeCalendar, navigation: { first: "2026-10", last: "2026-10" } }))).toBe(false);
  });

  it("opens on the busiest day, the earliest among equals", () => {
    const days = upcomingHighlightDays(current, following);
    expect(busiestDay(days)?.date).toBe("2026-10-29");
    expect(busiestDay(days.filter((day) => day.gameCount === 2))?.date).toBe("2026-11-03");
    expect(busiestDay([])).toBeNull();
  });
});

describe("former landing links", () => {
  it.each([
    ["week=2026-W42", "/lanzamientos/destacados?week=2026-W42"],
    ["month=2026-10", "/lanzamientos/destacados?month=2026-10"],
    ["granularity=month", "/lanzamientos/destacados?granularity=month"],
    // A malformed period still leaves for Destacados, which takes its canonical form.
    ["week=nope", "/lanzamientos/destacados?week=nope"],
    ["view=upcoming&platformIds=pc", "/lanzamientos?platformIds=pc"],
  ])("send ?%s to %s", (query, destination) => {
    expect(legacyHomeRedirect(new URLSearchParams(query))).toBe(destination);
  });

  it("leave the home itself where it is", () => {
    expect(legacyHomeRedirect(new URLSearchParams(""))).toBeNull();
    expect(legacyHomeRedirect(new URLSearchParams("utm_source=newsletter"))).toBeNull();
  });
});
