import { formatCalendarDay, shortMonthName } from "../../shared/catalogue/release-date";
import type { ReleaseCalendar } from "./release-calendar-api";
import { calendarDay } from "./releases-search";

/**
 * The release calendar's month (#250) as the page presents it: Monday-to-Sunday weeks of the API's
 * month, each day with its game count and total-Hype colour. The calendar is active from the API's
 * evaluation date on: earlier days of the current month are shown inert, never counted. Pure
 * calendar arithmetic over contract strings; the browser's clock never decides "today".
 */

/** 0 is a day without releases; 1 to 4 run from lowest to highest daily total Hype. */
export type DensityLevel = 0 | 1 | 2 | 3 | 4;

/** Fixed daily total-Hype bands: blue, yellow, orange, red; independent of game count. */
export const DENSITY_THRESHOLDS = [10, 30, 100, 150] as const;

/** Qualitative equivalent of colour, also exposed as the day's accessible description. */
export const HYPE_LABELS = ["Sin destacados", "Interés bajo", "Interés moderado", "Interés alto", "Interés muy alto"] as const;

export function densityLevel(totalHype: number): DensityLevel {
  let level: DensityLevel = 0;
  DENSITY_THRESHOLDS.forEach((threshold, index) => {
    if (totalHype >= threshold) level = (index + 1) as DensityLevel;
  });
  return level;
}

export type CalendarDay = {
  /** `YYYY-MM-DD`. */
  date: string;
  day: number;
  gameCount: number;
  totalHype: number;
  density: DensityLevel;
  /** Before the evaluation date: the day has gone by and the calendar shows it inert. */
  past: boolean;
  today: boolean;
  /** The full day as assistive technology reads it: `domingo, 22 de noviembre de 2026`. */
  label: string;
};

/** A week row: a day of the month, or `null` for a day of a neighbouring month. */
export type CalendarWeek = (CalendarDay | null)[];

export type ReleaseCalendarModel = {
  month: string;
  evaluatedOn: string;
  window: { from: string; to: string };
  /** `Noviembre 2026`. */
  monthLabel: string;
  weeks: CalendarWeek[];
  days: CalendarDay[];
  /** The days that can be chosen: today and later. */
  activeDays: CalendarDay[];
  /** Days holding at least one release. */
  daysWithReleases: number;
  /** Months that may be requested, oldest first, and the adjacent ones. */
  months: string[];
  previous: string | null;
  next: string | null;
  platforms: { id: string; name: string }[];
  regions: { id: string; name: string }[];
};

const DAY_MS = 86_400_000;
const WEEKDAYS = ["domingo", "lunes", "martes", "miércoles", "jueves", "viernes", "sábado"];
const MONTHS = [
  "Enero",
  "Febrero",
  "Marzo",
  "Abril",
  "Mayo",
  "Junio",
  "Julio",
  "Agosto",
  "Septiembre",
  "Octubre",
  "Noviembre",
  "Diciembre",
];

/** Monday-first column headers: the full weekday and its one-letter abbreviation. */
export const WEEKDAY_HEADERS = [
  { name: "Lunes", letter: "L" },
  { name: "Martes", letter: "M" },
  { name: "Miércoles", letter: "X" },
  { name: "Jueves", letter: "J" },
  { name: "Viernes", letter: "V" },
  { name: "Sábado", letter: "S" },
  { name: "Domingo", letter: "D" },
] as const;

function iso(time: number): string {
  return new Date(time).toISOString().slice(0, 10);
}

function monthParts(month: string): { year: number; month: number } | null {
  const match = /^(\d{4})-(\d{2})$/.exec(month);
  if (match === null) return null;
  const value = Number(match[2]);
  return value >= 1 && value <= 12 ? { year: Number(match[1]), month: value } : null;
}

/** `Noviembre 2026`. */
export function formatMonth(month: string): string {
  const parts = monthParts(month);
  return parts === null ? "Mes no disponible" : `${MONTHS[parts.month - 1] ?? ""} ${parts.year}`;
}

/** `nov 2026`, for the month chips. */
export function formatShortMonth(month: string): string {
  const parts = monthParts(month);
  const name = parts === null ? null : shortMonthName(String(parts.month));
  return parts === null || name === null ? "—" : `${name} ${parts.year}`;
}

/** A month moved by whole months. */
export function shiftMonth(month: string, offset: number): string | null {
  const parts = monthParts(month);
  if (parts === null) return null;
  const index = parts.year * 12 + parts.month - 1 + offset;
  const year = Math.floor(index / 12);
  return `${String(year).padStart(4, "0")}-${String((index % 12) + 1).padStart(2, "0")}`;
}

/** Every month from `first` to `last`, inclusive; empty for a reversed or malformed pair. */
export function monthsBetween(first: string, last: string): string[] {
  const months: string[] = [];
  for (let month: string | null = first; month !== null && month <= last; month = shiftMonth(month, 1)) {
    months.push(month);
    if (months.length > 1200) break;
  }
  return months;
}

/** Up to `size` consecutive requestable months around `month`, for the chips. */
export function monthWindow(months: readonly string[], month: string, size = 4): string[] {
  const index = Math.max(0, months.indexOf(month));
  const start = Math.max(0, Math.min(index - 1, months.length - size));
  return months.slice(start, start + size);
}

/** `1 lanzamiento`, `12 lanzamientos`: each counts one game's releases of that day. */
export function countLabel(gameCount: number): string {
  return gameCount === 1 ? "1 lanzamiento" : `${gameCount} lanzamientos`;
}

/** `Domingo, 22 de noviembre`: a chosen day's heading, without the year the page already states. */
export function dayHeading(date: string): string {
  const time = calendarDay(date);
  if (time === null) return "Fecha no disponible";
  const weekday = WEEKDAYS[new Date(time).getUTCDay()] ?? "";
  const full = formatCalendarDay(date);
  return `${weekday.charAt(0).toUpperCase()}${weekday.slice(1)}, ${full.replace(/ de \d{4}$/u, "")}`;
}

/** The month's days in Monday-to-Sunday weeks, with neighbouring months' days left empty. */
export function monthWeeks(days: readonly CalendarDay[]): CalendarWeek[] {
  const first = days[0];
  if (first === undefined) return [];
  const time = calendarDay(first.date) ?? 0;
  const leading = (new Date(time).getUTCDay() + 6) % 7;
  const cells: (CalendarDay | null)[] = [...Array<null>(leading).fill(null), ...days];
  while (cells.length % 7 !== 0) cells.push(null);
  const weeks: CalendarWeek[] = [];
  for (let index = 0; index < cells.length; index += 7) weeks.push(cells.slice(index, index + 7));
  return weeks;
}

/** The day the calendar opens on when the URL names none: its first day with releases, if any. */
export function defaultDay(model: Pick<ReleaseCalendarModel, "activeDays">): string | null {
  return model.activeDays.find((day) => day.gameCount > 0)?.date ?? null;
}

/** A day the URL names, kept only while it is an active day of the served month. */
export function chosenDay(model: Pick<ReleaseCalendarModel, "activeDays">, day: string | null): string | null {
  return model.activeDays.some((candidate) => candidate.date === day) ? day : defaultDay(model);
}

export function toReleaseCalendarModel(calendar: ReleaseCalendar): ReleaseCalendarModel {
  const start = calendarDay(calendar.window.from) ?? 0;
  const end = calendarDay(calendar.window.to) ?? start;
  const today = calendarDay(calendar.evaluatedOn);
  const counts = new Map(calendar.days.map((day) => [day.date, day]));
  const days = Array.from({ length: Math.round((end - start) / DAY_MS) + 1 }, (_, index) => {
    const time = start + index * DAY_MS;
    const date = iso(time);
    const gameCount = counts.get(date)?.gameCount ?? 0;
    const totalHype = counts.get(date)?.totalHype ?? 0;
    return {
      date,
      day: new Date(time).getUTCDate(),
      gameCount,
      totalHype,
      density: densityLevel(totalHype),
      past: today !== null && time < today,
      today: time === today,
      label: `${WEEKDAYS[new Date(time).getUTCDay()] ?? ""}, ${formatCalendarDay(date)}`,
    } satisfies CalendarDay;
  });
  const { first, last, previous, next } = calendar.navigation;
  return {
    month: calendar.month,
    evaluatedOn: calendar.evaluatedOn,
    window: calendar.window,
    monthLabel: formatMonth(calendar.month),
    weeks: monthWeeks(days),
    days,
    activeDays: days.filter((day) => !day.past),
    daysWithReleases: calendar.days.length,
    months: monthsBetween(first, last),
    previous: previous ?? null,
    next: next ?? null,
    platforms: calendar.availableFilters.platforms.map((platform) => ({
      id: platform.platformId,
      name: platform.name,
    })),
    regions: calendar.availableFilters.regions.map((region) => ({
      id: region.regionId,
      name: region.name,
    })),
  };
}
