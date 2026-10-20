import { formatCalendarDay, shortMonthName } from "../../shared/catalogue/release-date";
import { MAX_RANGE_DAYS, calendarDay } from "./releases-search";

/**
 * The release explorer's day bar (#285): a frame of consecutive days around the explored range,
 * which of them have gone by the API's trusted evaluation date, and which the range holds. Pure
 * calendar arithmetic over contract strings; the browser's clock never decides "today".
 */

export type DayRange = { from: string; to: string };

export type BarDay = {
  /** `YYYY-MM-DD`. */
  date: string;
  /** Day of the month. */
  day: number;
  /** Short Spanish month name where the frame starts or a new month begins; null elsewhere. */
  month: string | null;
  /** One-letter weekday, `L` to `D`. */
  weekday: string;
  /** The full day as assistive technology reads it: `martes, 20 de octubre de 2026`. */
  label: string;
  /** True for every day up to and including the evaluation date. */
  elapsed: boolean;
  today: boolean;
  inRange: boolean;
};

export type DayBar = {
  frame: DayRange;
  days: BarDay[];
  /** The one selected day when the range is a single day; null for a longer range. */
  selectedDay: string | null;
};

const DAY_MS = 86_400_000;
const WEEKDAYS = ["domingo", "lunes", "martes", "miércoles", "jueves", "viernes", "sábado"];
const WEEKDAY_LETTERS = ["D", "L", "M", "X", "J", "V", "S"];

function toIso(time: number): string {
  return new Date(time).toISOString().slice(0, 10);
}

/** A calendar day moved by whole days. */
export function shiftDay(date: string, days: number): string | null {
  const time = calendarDay(date);
  return time === null ? null : toIso(time + days * DAY_MS);
}

function validRange(range: DayRange | null): range is DayRange {
  if (range === null) return false;
  const start = calendarDay(range.from);
  const end = calendarDay(range.to);
  return start !== null && end !== null && end >= start && (end - start) / DAY_MS < MAX_RANGE_DAYS;
}

/**
 * The days the bar shows for `range`. A frame the visitor already had stays while it still holds
 * the range, so choosing one day of a month keeps the month's scale. Otherwise a range of a week
 * or more is its own frame, and a shorter one widens to its Monday-to-Sunday weeks so the days
 * around it stay at hand.
 */
export function barFrame(range: DayRange, kept: DayRange | null): DayRange | null {
  if (!validRange(range)) return null;
  if (validRange(kept) && kept.from <= range.from && range.to <= kept.to) return kept;
  const start = calendarDay(range.from) ?? 0;
  const end = calendarDay(range.to) ?? 0;
  if ((end - start) / DAY_MS >= 6) return range;
  const monday = start - ((new Date(start).getUTCDay() + 6) % 7) * DAY_MS;
  const sunday = end + ((7 - new Date(end).getUTCDay()) % 7) * DAY_MS;
  return { from: toIso(monday), to: toIso(sunday) };
}

/** The frame's days, inclusive, as the bar presents them; null for an invalid frame. */
export function toDayBar(frame: DayRange, range: DayRange, evaluatedOn: string): DayBar | null {
  const start = calendarDay(frame.from);
  const end = calendarDay(frame.to);
  const today = calendarDay(evaluatedOn);
  if (!validRange(frame) || start === null || end === null || today === null) return null;
  const count = Math.round((end - start) / DAY_MS) + 1;
  const days = Array.from({ length: count }, (_, index): BarDay => {
    const time = start + index * DAY_MS;
    const value = new Date(time);
    const date = toIso(time);
    const day = value.getUTCDate();
    return {
      date,
      day,
      month: index === 0 || day === 1 ? shortMonthName(String(value.getUTCMonth() + 1)) : null,
      weekday: WEEKDAY_LETTERS[value.getUTCDay()] ?? "",
      label: `${WEEKDAYS[value.getUTCDay()] ?? ""}, ${formatCalendarDay(date)}`,
      elapsed: time <= today,
      today: time === today,
      inRange: range.from <= date && date <= range.to,
    };
  });
  return { frame, days, selectedDay: range.from === range.to ? range.from : null };
}

/**
 * The history state that keeps the bar's frame across the explorer's own navigations and a return
 * from a game page. It is presentation context only; the URL alone states what is explored.
 */
export type DayFrameState = { dayFrame: DayRange };

/** The frame a history entry keeps, validated, or null. */
export function readDayFrame(state: unknown): DayRange | null {
  if (typeof state !== "object" || state === null || !("dayFrame" in state)) return null;
  const frame: unknown = state.dayFrame;
  if (typeof frame !== "object" || frame === null || !("from" in frame) || !("to" in frame)) {
    return null;
  }
  const { from, to } = frame;
  if (typeof from !== "string" || typeof to !== "string") return null;
  const range = { from, to };
  return validRange(range) ? range : null;
}
