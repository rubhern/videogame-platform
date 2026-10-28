import type { CalendarDay, ReleaseCalendarModel } from "../releases/release-calendar-model";

/** How many days with highlighted releases the home's calendar preview offers to choose from. */
export const HOME_CALENDAR_DAYS = 7;

/** How many of a chosen day's leading games the preview shows, in the calendar's Hype order. */
export const HOME_DAY_GAMES = 4;

function highlightedDays(model: ReleaseCalendarModel): CalendarDay[] {
  return model.activeDays.filter((day) => day.gameCount > 0);
}

/**
 * Whether the served month alone leaves the preview short of days while the API offers the month
 * after it. Only the API's navigation names that month: the browser never decides the calendar.
 */
export function needsFollowingMonth(current: ReleaseCalendarModel, limit = HOME_CALENDAR_DAYS): boolean {
  return current.next !== null && highlightedDays(current).length < limit;
}

/**
 * The next days, from the API's evaluation date on, that hold at least one highlighted release
 * under `UC-016`, across the served month and, when read, the following one. Days without
 * highlights are left out rather than presented as empty slots; fewer days mean fewer choices.
 */
export function upcomingHighlightDays(
  current: ReleaseCalendarModel,
  following: ReleaseCalendarModel | null,
  limit = HOME_CALENDAR_DAYS,
): CalendarDay[] {
  const later = following === null || following.month <= current.month ? [] : highlightedDays(following);
  return [...highlightedDays(current), ...later].slice(0, limit);
}

/**
 * The day the preview opens on: the one with the most highlighted games, the earliest among equals,
 * so the first view shows a full row of covers rather than whichever day happens to come first.
 */
export function busiestDay(days: readonly CalendarDay[]): CalendarDay | null {
  return days.reduce<CalendarDay | null>(
    (best, day) => (best === null || day.gameCount > best.gameCount ? day : best),
    null,
  );
}
