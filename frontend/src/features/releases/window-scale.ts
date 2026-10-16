import { shortMonthName } from "../../shared/catalogue/release-date";

/**
 * The release window as a compact day scale: the days the API's window states, which of them
 * have gone by the API's trusted evaluation date, and where today stands against the window.
 * Pure calendar arithmetic over contract strings; the browser's clock never decides "today".
 */

export type WindowDay = {
  /** `YYYY-MM-DD`. */
  date: string;
  /** Day of the month. */
  day: number;
  /** Short Spanish month name where the scale starts or a new month begins; null elsewhere. */
  month: string | null;
  /** True for every day up to and including the evaluation date. */
  elapsed: boolean;
  /** Whether the scale prints this day's numeral. */
  labelled: boolean;
};

export type WindowScale = {
  days: WindowDay[];
  /**
   * Where today stands: a day's index inside the window, `-1` just before its first day and
   * `days.length` just after its last.
   */
  today: number;
};

const DAY = /^(\d{4})-(\d{2})-(\d{2})$/;
const DAY_MS = 86_400_000;
/** The windows the contract offers are one, two or four weeks; anything longer is not a scale. */
const MAX_DAYS = 62;
/** Up to two weeks every day carries its numeral; longer windows print one a week. */
const EVERY_DAY_LABELS = 14;

function toUtc(value: string): number | null {
  const match = DAY.exec(value);
  if (match === null) return null;
  const [, year, month, day] = match.map(Number) as [number, number, number, number];
  const time = Date.UTC(year, month - 1, day);
  const date = new Date(time);
  return date.getUTCMonth() === month - 1 && date.getUTCDate() === day ? time : null;
}

function toIso(time: number): string {
  return new Date(time).toISOString().slice(0, 10);
}

/** The window's days from `from` to `to`, inclusive, as the scale presents them. */
export function toWindowScale(from: string, to: string, evaluatedOn: string): WindowScale | null {
  const start = toUtc(from);
  const end = toUtc(to);
  const today = toUtc(evaluatedOn);
  if (start === null || end === null || today === null || end < start) return null;
  const count = Math.round((end - start) / DAY_MS) + 1;
  if (count > MAX_DAYS) return null;

  const days = Array.from({ length: count }, (_, index): WindowDay => {
    const time = start + index * DAY_MS;
    const date = new Date(time);
    const day = date.getUTCDate();
    return {
      date: toIso(time),
      day,
      month: index === 0 || day === 1 ? shortMonthName(String(date.getUTCMonth() + 1)) : null,
      elapsed: time <= today,
      labelled: count <= EVERY_DAY_LABELS || index % 7 === 0 || index === count - 1,
    };
  });
  const position = today < start ? -1 : today > end ? count : Math.round((today - start) / DAY_MS);
  return { days, today: position };
}

/** A day's index on the scale, or null when the date is not a calendar day inside the window. */
export function dayIndex(scale: WindowScale, date: string): number | null {
  const index = scale.days.findIndex((day) => day.date === date);
  return index === -1 ? null : index;
}
