import { describe, expect, it } from "vitest";

import { urlSignals } from "./url-signals";

// Monday 9 November 2026 in Madrid: ISO week 2026-W46, month 2026-11.
const NOW = new Date("2026-11-09T10:00:00Z");

describe("urlSignals", () => {
  it("reports a Destacados granularity switch", () => {
    expect(urlSignals("featured", "", "?granularity=month", NOW)).toEqual([
      { name: "featured_period", params: { period: "month" } },
    ]);
  });

  it("reports navigation depth relative to the current week or month", () => {
    expect(urlSignals("featured", "", "?week=2026-W45", NOW)).toEqual([
      { name: "featured_navigate", params: { period: "week", direction: "previous", offset: "-1" } },
    ]);
    expect(urlSignals("featured", "?week=2026-W47", "?week=2026-W48", NOW)).toEqual([
      { name: "featured_navigate", params: { period: "week", direction: "next", offset: "+2" } },
    ]);
    expect(urlSignals("featured", "?granularity=month", "?month=2027-04", NOW)).toEqual([
      { name: "featured_navigate", params: { period: "month", direction: "next", offset: "beyond" } },
    ]);
    expect(urlSignals("featured", "?month=2026-12", "?granularity=month", NOW)).toEqual([
      { name: "featured_navigate", params: { period: "month", direction: "current", offset: "0" } },
    ]);
  });

  it("counts week offsets across a year boundary", () => {
    expect(urlSignals("featured", "", "?week=2026-W53", new Date("2027-01-05T10:00:00Z"))).toEqual([
      { name: "featured_navigate", params: { period: "week", direction: "previous", offset: "-1" } },
    ]);
  });

  it("reports calendar month changes and day selections without their values", () => {
    expect(urlSignals("calendar", "?month=2026-11", "?month=2026-12", NOW)).toEqual([
      { name: "calendar_interaction", params: { action: "month_change" } },
    ]);
    expect(urlSignals("calendar", "?month=2026-11", "?month=2026-11&day=2026-11-20", NOW)).toEqual([
      { name: "calendar_interaction", params: { action: "day_select" } },
    ]);
  });

  it("reports which explorer filters changed, never their values", () => {
    expect(
      urlSignals("explore", "?sort=date", "?sort=hype&platformIds=pc&from=2026-11-01&to=2026-11-07", NOW),
    ).toEqual([
      { name: "explore_filter", params: { filter: "range" } },
      { name: "explore_filter", params: { filter: "sort" } },
      { name: "explore_filter", params: { filter: "platform" } },
    ]);
  });

  it("reports nothing for other routes", () => {
    expect(urlSignals("search", "?q=zelda", "?q=mario", NOW)).toEqual([]);
  });
});
