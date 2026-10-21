import { describe, expect, it } from "vitest";

import {
  calendarFromExplorerPath,
  calendarSearchPath,
  dayExplorerPath,
  dayPreviewSearch,
  readCalendarSearch,
  toCalendarQuery,
} from "./release-calendar-search";

const read = (query: string) => readCalendarSearch(new URLSearchParams(query));

describe("release calendar URL state", () => {
  it("round-trips a month, a day and the filters", () => {
    const search = read("month=2026-11&day=2026-11-22&platformIds=ps5&platformIds=pc&regionIds=eu");

    expect(search).toEqual({
      month: "2026-11",
      day: "2026-11-22",
      platformIds: ["ps5", "pc"],
      regionIds: ["eu"],
    });
    expect(calendarSearchPath(search, {})).toBe(
      "/lanzamientos/calendario?month=2026-11&day=2026-11-22&platformIds=ps5&platformIds=pc&regionIds=eu",
    );
  });

  it("lets a day imply its month and drops a day outside the named month", () => {
    expect(read("day=2027-01-08")).toMatchObject({ month: "2027-01", day: "2027-01-08" });
    expect(read("month=2026-11&day=2026-12-01")).toMatchObject({ month: "2026-11", day: null });
  });

  it("ignores malformed months and impossible days", () => {
    expect(read("month=2026-13&day=2027-02-29")).toMatchObject({ month: null, day: null });
    expect(read("month=0000-01")).toMatchObject({ month: null });
    expect(read("day=2028-02-29")).toMatchObject({ month: "2028-02", day: "2028-02-29" });
  });

  it("asks the API for the named month and the resolved platforms only", () => {
    expect(toCalendarQuery(read(""), [])).toEqual({});
    expect(toCalendarQuery(read("month=2026-11&day=2026-11-02&regionIds=eu"), ["ps5"])).toEqual({
      month: "2026-11",
      platformIds: ["ps5"],
      regionIds: ["eu"],
    });
  });

  it("previews a day with the explorer's Hype order and opens the explorer on it", () => {
    const search = read("month=2026-11&platformIds=ps5");

    expect(dayPreviewSearch(search, "2026-11-13", 6)).toMatchObject({
      from: "2026-11-13",
      to: "2026-11-13",
      sort: "hype",
      includeApproximateDates: false,
      page: 1,
      pageSize: 3,
      platformIds: ["ps5"],
    });
    expect(dayExplorerPath(search, "2026-11-13")).toBe(
      "/lanzamientos?from=2026-11-13&to=2026-11-13&sort=hype&platformIds=ps5",
    );
  });

  it("caps the preview by the highlighted count, keeping the list unrestricted", () => {
    const search = read("month=2026-11");
    expect(dayPreviewSearch(search, "2026-11-13", 1).pageSize).toBe(1);
    expect(dayPreviewSearch(search, "2026-11-13", 2).pageSize).toBe(2);
    expect(dayPreviewSearch(search, "2026-11-13", 8).pageSize).toBe(3);
    expect(dayPreviewSearch(search, "2026-11-13", 0).pageSize).toBe(1);
    expect(dayExplorerPath(search, "2026-11-13")).not.toContain("featuredOnly");
  });

  it("opens the calendar from the explorer on a future month or the current one", () => {
    expect(calendarFromExplorerPath({ platformIds: [], regionIds: [] }, "2026-12-07", "2026-10-21")).toBe(
      "/lanzamientos/calendario?month=2026-12",
    );
    expect(calendarFromExplorerPath({ platformIds: ["pc"], regionIds: [] }, "2026-09-01", "2026-10-21")).toBe(
      "/lanzamientos/calendario?platformIds=pc",
    );
    expect(calendarFromExplorerPath({ platformIds: [], regionIds: [] }, null, null)).toBe(
      "/lanzamientos/calendario",
    );
  });
});
