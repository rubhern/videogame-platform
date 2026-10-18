import { shortMonthName } from "../../shared/catalogue/release-date";

/**
 * The calendar behind Gameómetro's temporal G: one featured period, an ISO week or a calendar
 * month exactly as the API's window states it, how much of it has passed by the API's trusted
 * evaluation date (computed in `Europe/Madrid`), and where a presented release falls on it. This
 * is pure calendar arithmetic over contract strings: the browser's clock and time zone never
 * decide what "today" is or where a period starts, and a release is never placed on a day the
 * contract does not state.
 */

export type PeriodGranularity = "week" | "month";

export type PeriodPhase = "past" | "current" | "future";

/** One division of the scale: a calendar day of the period. */
export type PeriodDay = {
  /** `YYYY-MM-DD`. */
  date: string;
  /** Day of the month. */
  day: number;
  /** Lower-case Spanish weekday name. */
  weekday: string;
  /** Short Spanish month name where a week's later day starts a new month; null elsewhere. */
  month: string | null;
};

export type PeriodTimeline = {
  granularity: PeriodGranularity;
  /** The API window's first and last day, inclusive. */
  from: string;
  to: string;
  /** Seven for a week; the real length of a month, 28 to 31 days, otherwise. */
  days: number;
  divisions: PeriodDay[];
  phase: PeriodPhase;
  /** The evaluated day's division, from 1, while the period is current; null otherwise. */
  today: number | null;
};

/** Where a presented release sits on its period's scale, at the precision the contract states. */
export type ReleaseMark =
  /** Its division, from 1. */
  | { kind: "day"; day: number }
  /**
   * Known only to a month, quarter or year that overlaps this period: the whole scale, never a
   * day. `open` when that interval reaches beyond the period.
   */
  | { kind: "span"; open: boolean }
  | { kind: "before" }
  | { kind: "after" }
  | { kind: "unknown" };

const DAY = /^(\d{4})-(\d{2})-(\d{2})$/;
const MONTH = /^(\d{4})-(0[1-9]|1[0-2])$/;
const QUARTER = /^(\d{4})-Q([1-4])$/;
const YEAR = /^(\d{4})$/;
const DAY_MS = 86_400_000;
/** The longest featured period, a 31-day month; anything longer is not a featured window. */
const MAX_DAYS = 31;

const weekdays = ["domingo", "lunes", "martes", "miércoles", "jueves", "viernes", "sábado"] as const;

function toUtc(value: string): number | null {
  const match = DAY.exec(value);
  if (match === null) return null;
  const [, year, month, day] = match.map(Number) as [number, number, number, number];
  const date = new Date(0);
  date.setUTCFullYear(year, month - 1, day);
  return date.getUTCMonth() === month - 1 && date.getUTCDate() === day ? date.getTime() : null;
}

function toIso(time: number): string {
  const date = new Date(time);
  const year = String(date.getUTCFullYear()).padStart(4, "0");
  const month = String(date.getUTCMonth() + 1).padStart(2, "0");
  const day = String(date.getUTCDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

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
 * The period as the temporal G presents it, read from the API's window and evaluated date of the
 * same response, so the scale always agrees with the selection shown. Null for a malformed window.
 */
export function toPeriodTimeline(
  granularity: PeriodGranularity,
  window: { from: string; to: string },
  evaluatedOn: string,
): PeriodTimeline | null {
  const start = toUtc(window.from);
  const end = toUtc(window.to);
  const today = toUtc(evaluatedOn);
  if (start === null || end === null || today === null || end < start) return null;
  const days = Math.round((end - start) / DAY_MS) + 1;
  if (days > MAX_DAYS || (granularity === "week" && days !== 7)) return null;

  const divisions = Array.from({ length: days }, (_, index): PeriodDay => {
    const date = new Date(start + index * DAY_MS);
    const day = date.getUTCDate();
    return {
      date: toIso(date.getTime()),
      day,
      weekday: weekdays[date.getUTCDay()] ?? "",
      month:
        granularity === "week" && index > 0 && day === 1
          ? shortMonthName(String(date.getUTCMonth() + 1))
          : null,
    };
  });
  const base = { granularity, from: window.from, to: window.to, days, divisions };
  if (today < start) return { ...base, phase: "future", today: null };
  if (today > end) return { ...base, phase: "past", today: null };
  return { ...base, phase: "current", today: Math.round((today - start) / DAY_MS) + 1 };
}

/** A division's place on the scale, from 0 on the period's first day to 1 on its last. */
export function scalePosition(day: number, days: number): number {
  return (day - 1) / (days - 1);
}

/** How far the period's light reaches: all of a past one, up to today, none of a future one. */
export function elapsedPosition(timeline: PeriodTimeline): number {
  if (timeline.phase === "past") return 1;
  if (timeline.phase === "future" || timeline.today === null) return 0;
  return scalePosition(timeline.today, timeline.days);
}

/** The first and last day a coarse release date stands for. */
function coarsePeriod(precision: string, value: string): { from: string; to: string } | null {
  switch (precision) {
    case "month": {
      const match = MONTH.exec(value);
      if (match === null) return null;
      const days = daysInMonth(Number(match[1]), Number(match[2]));
      return { from: `${value}-01`, to: `${value}-${String(days).padStart(2, "0")}` };
    }
    case "quarter": {
      const match = QUARTER.exec(value);
      if (match === null) return null;
      const first = (Number(match[2]) - 1) * 3 + 1;
      const last = first + 2;
      const lastDays = daysInMonth(Number(match[1]), last);
      return {
        from: `${match[1]}-${String(first).padStart(2, "0")}-01`,
        to: `${match[1]}-${String(last).padStart(2, "0")}-${String(lastDays).padStart(2, "0")}`,
      };
    }
    case "year":
      return YEAR.test(value) ? { from: `${value}-01-01`, to: `${value}-12-31` } : null;
    default:
      return null;
  }
}

/** Places a presented release on the period's scale without ever inventing a finer precision. */
export function toReleaseMark(
  releaseDate: { precision: string; value?: string | null },
  timeline: PeriodTimeline,
): ReleaseMark {
  const value = releaseDate.value ?? null;
  if (value === null) return { kind: "unknown" };
  if (releaseDate.precision === "day") {
    if (toUtc(value) === null) return { kind: "unknown" };
    if (value < timeline.from) return { kind: "before" };
    if (value > timeline.to) return { kind: "after" };
    const index = timeline.divisions.findIndex((division) => division.date === value);
    return index === -1 ? { kind: "unknown" } : { kind: "day", day: index + 1 };
  }
  const period = coarsePeriod(releaseDate.precision, value);
  if (period === null) return { kind: "unknown" };
  if (period.to < timeline.from) return { kind: "before" };
  if (period.from > timeline.to) return { kind: "after" };
  return { kind: "span", open: period.from !== timeline.from || period.to !== timeline.to };
}

function describeProgress(timeline: PeriodTimeline): string {
  const week = timeline.granularity === "week";
  if (timeline.phase === "past") {
    return `${week ? "semana completa" : "mes completo"}, ${timeline.days} de ${timeline.days} días transcurridos`;
  }
  if (timeline.phase === "future" || timeline.today === null) {
    return week ? "la semana aún no ha empezado" : "el mes aún no ha empezado";
  }
  if (week) {
    const today = timeline.divisions[timeline.today - 1];
    return `hoy es ${today?.weekday ?? ""} ${today?.day ?? ""}, día ${timeline.today} de 7`;
  }
  return `hoy es el día ${timeline.today} de ${timeline.days}`;
}

function describeMark(timeline: PeriodTimeline, mark: ReleaseMark): string {
  const week = timeline.granularity === "week";
  switch (mark.kind) {
    case "day": {
      if (!week) return `lanzamiento el día ${mark.day}`;
      const division = timeline.divisions[mark.day - 1];
      return `lanzamiento el ${division?.weekday ?? ""} ${division?.day ?? ""}`;
    }
    case "span":
      return "lanzamiento sin día confirmado";
    case "before":
      return week ? "lanzamiento antes de esta semana" : "lanzamiento antes de este mes";
    case "after":
      return week ? "lanzamiento después de esta semana" : "lanzamiento después de este mes";
    case "unknown":
      return "fecha de lanzamiento por confirmar";
  }
}

/**
 * The scale in words, so the G is never the only way to read the period's progress or where the
 * release falls. The release's own date keeps its full wording elsewhere in the hero.
 */
export function describePeriodScale(
  timeline: PeriodTimeline,
  mark: ReleaseMark,
  periodPhrase: string,
): string {
  return `Calendario de ${periodPhrase}: ${describeProgress(timeline)}; ${describeMark(timeline, mark)}.`;
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
