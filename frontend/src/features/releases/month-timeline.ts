/**
 * The calendar behind Gameómetro's temporal G: one calendar month, how much of it has passed by
 * the API's trusted evaluation date (computed in `Europe/Madrid`), and where a presented release
 * falls on it. This is pure calendar arithmetic over contract strings: the browser's clock and
 * time zone never decide what "today" is, and a release is never placed on a day the contract
 * does not state.
 */

export type MonthPhase = "past" | "current" | "future";

export type MonthTimeline = {
  /** `YYYY-MM`. */
  month: string;
  /** The real length of the month: 28 to 31 days, leap years included. */
  days: number;
  phase: MonthPhase;
  /** The evaluated day while the month is current; null once it has passed or before it starts. */
  today: number | null;
};

/** Where a presented release sits on its month's scale, at the precision the contract states. */
export type ReleaseMark =
  | { kind: "day"; day: number }
  /**
   * Known only to the month, quarter or year that holds this month: the whole month, never a day.
   * `open` when that interval reaches beyond this month.
   */
  | { kind: "span"; open: boolean }
  | { kind: "before" }
  | { kind: "after" }
  | { kind: "unknown" };

const MONTH = /^(\d{4})-(0[1-9]|1[0-2])$/;
const DAY = /^(\d{4})-(0[1-9]|1[0-2])-(\d{2})$/;
const QUARTER = /^(\d{4})-Q([1-4])$/;
const YEAR = /^(\d{4})$/;

function isLeapYear(year: number): boolean {
  return (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0;
}

/** Days in a calendar month, `month` from 1 to 12. */
export function daysInMonth(year: number, month: number): number {
  if (month === 2) {
    return isLeapYear(year) ? 29 : 28;
  }
  return [4, 6, 9, 11].includes(month) ? 30 : 31;
}

/**
 * The month as the temporal G presents it. Its phase compares the month with the evaluated date
 * the API states for the same response, so the scale always agrees with the selection shown.
 */
export function toMonthTimeline(month: string, evaluatedOn: string): MonthTimeline | null {
  const monthMatch = MONTH.exec(month);
  const dayMatch = DAY.exec(evaluatedOn);
  if (monthMatch === null || dayMatch === null) {
    return null;
  }
  const days = daysInMonth(Number(monthMatch[1]), Number(monthMatch[2]));
  const evaluatedMonth = evaluatedOn.slice(0, 7);
  if (month < evaluatedMonth) {
    return { month, days, phase: "past", today: null };
  }
  if (month > evaluatedMonth) {
    return { month, days, phase: "future", today: null };
  }
  const today = Number(dayMatch[3]);
  return today >= 1 && today <= days ? { month, days, phase: "current", today } : null;
}

/** A day's place on the scale, from 0 on the first day of the month to 1 on its last day. */
export function scalePosition(day: number, days: number): number {
  return (day - 1) / (days - 1);
}

/** How far the month's light reaches: all of a past month, up to today, none of a future one. */
export function elapsedPosition(timeline: MonthTimeline): number {
  if (timeline.phase === "past") return 1;
  if (timeline.phase === "future" || timeline.today === null) return 0;
  return scalePosition(timeline.today, timeline.days);
}

function compare(left: string, right: string): "before" | "after" {
  return left < right ? "before" : "after";
}

/** Places a presented release on `month`'s scale without ever inventing a finer precision. */
export function toReleaseMark(
  releaseDate: { precision: string; value?: string | null },
  month: string,
): ReleaseMark {
  const value = releaseDate.value ?? null;
  const monthMatch = MONTH.exec(month);
  if (value === null || monthMatch === null) {
    return { kind: "unknown" };
  }
  const year = monthMatch[1] ?? "";
  const monthNumber = Number(monthMatch[2]);

  switch (releaseDate.precision) {
    case "day": {
      const match = DAY.exec(value);
      const day = Number(match?.[3]);
      if (match === null || day < 1 || day > daysInMonth(Number(match[1]), Number(match[2]))) {
        return { kind: "unknown" };
      }
      return value.slice(0, 7) === month ? { kind: "day", day } : { kind: compare(value, month) };
    }
    case "month":
      if (!MONTH.test(value)) return { kind: "unknown" };
      return value === month ? { kind: "span", open: false } : { kind: compare(value, month) };
    case "quarter": {
      const match = QUARTER.exec(value);
      if (match === null) return { kind: "unknown" };
      const quarter = `${year}-Q${Math.ceil(monthNumber / 3)}`;
      return value === quarter ? { kind: "span", open: true } : { kind: compare(value, quarter) };
    }
    case "year":
      if (!YEAR.test(value)) return { kind: "unknown" };
      return value === year ? { kind: "span", open: true } : { kind: compare(value, year) };
    default:
      return { kind: "unknown" };
  }
}

function describeProgress(timeline: MonthTimeline): string {
  if (timeline.phase === "past") {
    return `mes completo, ${timeline.days} de ${timeline.days} días transcurridos`;
  }
  if (timeline.phase === "future" || timeline.today === null) {
    return "el mes aún no ha empezado";
  }
  return `hoy es el día ${timeline.today} de ${timeline.days}`;
}

function describeMark(mark: ReleaseMark): string {
  switch (mark.kind) {
    case "day":
      return `lanzamiento el día ${mark.day}`;
    case "span":
      return "lanzamiento sin día confirmado";
    case "before":
      return "lanzamiento antes de este mes";
    case "after":
      return "lanzamiento después de este mes";
    case "unknown":
      return "fecha de lanzamiento por confirmar";
  }
}

/**
 * The scale in words, so the G is never the only way to read the month's progress or where the
 * release falls. The release's own date keeps its full wording elsewhere in the hero.
 */
export function describeMonthScale(
  timeline: MonthTimeline,
  mark: ReleaseMark,
  monthPhrase: string,
): string {
  return `Calendario de ${monthPhrase}: ${describeProgress(timeline)}; ${describeMark(mark)}.`;
}

const madridClock = new Intl.DateTimeFormat("en-GB", {
  timeZone: "Europe/Madrid",
  hourCycle: "h23",
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
});

/**
 * Milliseconds until the next calendar day starts in `Europe/Madrid`, read from wall time there.
 * It only schedules a refresh; the refreshed response's own evaluated date stays the authority.
 * On a daylight-saving change it may be an hour early or late, which the next refresh absorbs.
 */
export function msUntilNextMadridDay(now: Date): number {
  const parts = Object.fromEntries(
    madridClock.formatToParts(now).map((part) => [part.type, Number(part.value)]),
  );
  const elapsed =
    (((parts.hour ?? 0) * 60 + (parts.minute ?? 0)) * 60 + (parts.second ?? 0)) * 1000 +
    now.getUTCMilliseconds();
  return 86_400_000 - elapsed;
}
