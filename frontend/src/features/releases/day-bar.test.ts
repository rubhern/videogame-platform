import { describe, expect, it } from "vitest";

import { barFrame, readDayFrame, shiftDay, toDayBar } from "./day-bar";

describe("release explorer day bar", () => {
  it("frames a week or longer range by itself", () => {
    expect(barFrame({ from: "2026-08-01", to: "2026-08-31" }, null)).toEqual({
      from: "2026-08-01",
      to: "2026-08-31",
    });
    expect(barFrame({ from: "2026-08-10", to: "2026-08-16" }, null)).toEqual({
      from: "2026-08-10",
      to: "2026-08-16",
    });
  });

  it("widens a shorter range to its Monday-to-Sunday weeks, across a year end", () => {
    expect(barFrame({ from: "2026-08-13", to: "2026-08-13" }, null)).toEqual({
      from: "2026-08-10",
      to: "2026-08-16",
    });
    expect(barFrame({ from: "2026-12-31", to: "2027-01-01" }, null)).toEqual({
      from: "2026-12-28",
      to: "2027-01-03",
    });
    // A Sunday and the next Monday span two weeks.
    expect(barFrame({ from: "2026-08-16", to: "2026-08-17" }, null)).toEqual({
      from: "2026-08-10",
      to: "2026-08-23",
    });
  });

  it("keeps the visitor's frame while it holds the range, and only then", () => {
    const month = { from: "2026-08-01", to: "2026-08-31" };
    expect(barFrame({ from: "2026-08-19", to: "2026-08-19" }, month)).toEqual(month);
    expect(barFrame({ from: "2026-09-02", to: "2026-09-02" }, month)).toEqual({
      from: "2026-08-31",
      to: "2026-09-06",
    });
    expect(barFrame({ from: "2026-08-19", to: "2026-08-19" }, { from: "2026-01-01", to: "2026-12-31" }))
      .toEqual({ from: "2026-08-17", to: "2026-08-23" });
  });

  it("marks elapsed days, today, the explored range and a selected day", () => {
    const bar = toDayBar(
      { from: "2026-08-10", to: "2026-08-16" },
      { from: "2026-08-14", to: "2026-08-14" },
      "2026-08-13",
    );

    expect(bar?.days.map((day) => day.day)).toEqual([10, 11, 12, 13, 14, 15, 16]);
    expect(bar?.days.map((day) => day.weekday)).toEqual(["L", "M", "X", "J", "V", "S", "D"]);
    expect(bar?.days.filter((day) => day.elapsed)).toHaveLength(4);
    expect(bar?.days.find((day) => day.today)?.date).toBe("2026-08-13");
    expect(bar?.days.filter((day) => day.inRange).map((day) => day.date)).toEqual(["2026-08-14"]);
    expect(bar?.selectedDay).toBe("2026-08-14");
    expect(bar?.days[4]?.label).toBe("viernes, 14 de agosto de 2026");
  });

  it("names the month where the frame starts and where a new one begins", () => {
    const bar = toDayBar(
      { from: "2026-08-30", to: "2026-09-05" },
      { from: "2026-08-30", to: "2026-09-05" },
      "2026-08-13",
    );

    expect(bar?.days.map((day) => day.month)).toEqual(["ago", null, "sep", null, null, null, null]);
    expect(bar?.selectedDay).toBeNull();
    expect(bar?.days.every((day) => !day.elapsed)).toBe(true);
  });

  it("refuses frames that are not calendar days or exceed the widest range", () => {
    expect(toDayBar({ from: "2026-02-30", to: "2026-03-01" }, { from: "2026-03-01", to: "2026-03-01" }, "2026-03-01")).toBeNull();
    expect(barFrame({ from: "2026-01-01", to: "2026-12-31" }, null)).toBeNull();
    expect(readDayFrame({ dayFrame: { from: "2026-01-01", to: "2026-12-31" } })).toBeNull();
    expect(readDayFrame({ dayFrame: { from: "2026-08-01", to: "2026-08-31" } })).toEqual({
      from: "2026-08-01",
      to: "2026-08-31",
    });
    expect(readDayFrame({ dayFrame: "2026-08" })).toBeNull();
    expect(shiftDay("2026-12-31", 1)).toBe("2027-01-01");
  });
});
