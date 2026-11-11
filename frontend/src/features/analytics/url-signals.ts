import { readCalendarSearch } from "../releases/release-calendar-search";
import { readFeaturedSearch, type FeaturedGranularity } from "../releases/featured-search";
import type { FeaturedOffset, ProductEventName, ProductEvents } from "./events";
import type { RouteName } from "./route-names";

export type Signal = {
  [Name in ProductEventName]: { name: Name; params: ProductEvents[Name] };
}[ProductEventName];

const EXPLORE_FILTERS: ReadonlyArray<
  readonly [ProductEvents["explore_filter"]["filter"], readonly string[]]
> = [
  ["range", ["from", "to"]],
  ["sort", ["sort"]],
  ["platform", ["platformIds"]],
  ["region", ["regionIds"]],
  ["approximate_dates", ["includeApproximateDates"]],
  ["page", ["page"]],
];

function madridDate(now: Date): { year: number; month: number; day: number } {
  const [year = "0", month = "1", day = "1"] = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Europe/Madrid",
  })
    .format(now)
    .split("-");
  return { year: Number(year), month: Number(month), day: Number(day) };
}

/** A monotonic index of the ISO week holding a calendar date (weeks start on Monday). */
function isoWeekIndex(year: number, month: number, day: number): number {
  const date = Date.UTC(year, month - 1, day);
  const weekday = (new Date(date).getUTCDay() + 6) % 7;
  return Math.floor((date - weekday * 86_400_000) / (7 * 86_400_000));
}

function weekIndex(period: string): number | null {
  const match = /^(\d{4})-W(\d{2})$/.exec(period);
  if (match === null) return null;
  // ISO week 1 holds 4 January; step from that week's Monday.
  return isoWeekIndex(Number(match[1]), 1, 4) + Number(match[2]) - 1;
}

function monthIndex(period: string): number | null {
  const match = /^(\d{4})-(\d{2})$/.exec(period);
  return match === null ? null : Number(match[1]) * 12 + Number(match[2]) - 1;
}

/** Periods between the current one (in `Europe/Madrid`) and the named one; null if unreadable. */
function periodOffset(
  granularity: FeaturedGranularity,
  period: string | null,
  now: Date,
): number | null {
  if (period === null) return 0;
  const today = madridDate(now);
  if (granularity === "week") {
    const index = weekIndex(period);
    return index === null ? null : index - isoWeekIndex(today.year, today.month, today.day);
  }
  const index = monthIndex(period);
  return index === null ? null : index - (today.year * 12 + today.month - 1);
}

function offsetBucket(offset: number): FeaturedOffset {
  if (offset < -3 || offset > 3) return "beyond";
  return (offset > 0 ? `+${offset}` : `${offset}`) as FeaturedOffset;
}

function changed(previous: URLSearchParams, next: URLSearchParams, keys: readonly string[]) {
  return keys.some(
    (key) => previous.getAll(key).join("\u0000") !== next.getAll(key).join("\u0000"),
  );
}

/**
 * Product interactions that live in a page's URL state (Destacados's period, the calendar's month
 * and day, the explorer's filters). They are read from the change itself, so the screens carry no
 * analytics code; nothing from the query string is reported beyond these bounded labels.
 */
export function urlSignals(
  route: RouteName,
  previousSearch: string,
  nextSearch: string,
  now: Date,
): Signal[] {
  const previous = new URLSearchParams(previousSearch);
  const next = new URLSearchParams(nextSearch);
  if (route === "featured") {
    const before = readFeaturedSearch(previous);
    const after = readFeaturedSearch(next);
    if (before.granularity !== after.granularity) {
      return [{ name: "featured_period", params: { period: after.granularity } }];
    }
    if (before.period === after.period) return [];
    const from = periodOffset(before.granularity, before.period, now);
    const to = periodOffset(after.granularity, after.period, now);
    if (to === null) return [];
    const direction = to === 0 ? "current" : from !== null && to < from ? "previous" : "next";
    return [
      {
        name: "featured_navigate",
        params: { period: after.granularity, direction, offset: offsetBucket(to) },
      },
    ];
  }
  if (route === "calendar") {
    const before = readCalendarSearch(previous);
    const after = readCalendarSearch(next);
    const signals: Signal[] = [];
    if (before.month !== after.month) {
      signals.push({ name: "calendar_interaction", params: { action: "month_change" } });
    }
    if (after.day !== null && before.day !== after.day) {
      signals.push({ name: "calendar_interaction", params: { action: "day_select" } });
    }
    return signals;
  }
  if (route === "explore") {
    return EXPLORE_FILTERS.filter(([, keys]) => changed(previous, next, keys)).map(
      ([filter]): Signal => ({ name: "explore_filter", params: { filter } }),
    );
  }
  return [];
}
