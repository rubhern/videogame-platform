import type { CoveragePeriod, SynchronizationCoverage } from "./administration-api";

/**
 * Presentation policy for freshness: a covered span reads as recently refreshed while its oldest
 * complete scan is at most this many days old. Coverage itself never expires.
 */
export const RECENT_SCAN_DAYS = 7;

const DAY_MS = 86_400_000;

/** `never` and `partial` describe answered days only; `unknown` had no answered day at all. */
export type CoverageState = "complete" | "partial" | "never" | "unknown";

export interface CoverageSummary {
  from: string;
  to: string;
  totalDays: number;
  coveredDays: number;
  state: CoverageState;
  /** Part of the span lies beyond the server's bounded answer and was not described. */
  incomplete: boolean;
  /** Completion of the oldest and newest complete scans that cover any of its days. */
  oldestScan: string | null;
  newestScan: string | null;
  /** Null without covered days; otherwise whether its oldest covering scan is recent. */
  recent: boolean | null;
  /** A covering run reported failed Games; the count describes that run, not this span. */
  failedGames: boolean;
}

export function dayNumber(isoDate: string): number {
  const [year, month, day] = isoDate.split("-").map(Number);
  return Date.UTC(year ?? 1970, (month ?? 1) - 1, day ?? 1) / DAY_MS;
}

export function isoDate(day: number): string {
  return new Date(day * DAY_MS).toISOString().slice(0, 10);
}

export function lastDayOfMonth(year: number, month: number): string {
  return isoDate(Date.UTC(year, month, 0) / DAY_MS);
}

/** The covered and never-covered periods clipped to an inclusive span, in date order. */
export function periodsWithin(periods: readonly CoveragePeriod[], from: string, to: string): CoveragePeriod[] {
  const start = dayNumber(from);
  const end = dayNumber(to);
  return periods.flatMap((period) => {
    const clippedFrom = Math.max(dayNumber(period.from), start);
    const clippedTo = Math.min(dayNumber(period.to), end);
    return clippedFrom > clippedTo ? [] : [{ ...period, from: isoDate(clippedFrom), to: isoDate(clippedTo) }];
  });
}

/** Summarizes one inclusive span from a coverage answer, judging freshness against `now`. */
export function summarize(coverage: SynchronizationCoverage, from: string, to: string, now: Date): CoverageSummary {
  const totalDays = dayNumber(to) - dayNumber(from) + 1;
  const within = periodsWithin(coverage.periods, from, to);
  let answeredDays = 0;
  let coveredDays = 0;
  let oldest: number | null = null;
  let newest: number | null = null;
  let failedGames = false;
  for (const period of within) {
    const days = dayNumber(period.to) - dayNumber(period.from) + 1;
    answeredDays += days;
    if (period.status !== "covered" || !period.lastScan) continue;
    coveredDays += days;
    const completed = Date.parse(period.lastScan.scanCompletedAt);
    oldest = oldest === null ? completed : Math.min(oldest, completed);
    newest = newest === null ? completed : Math.max(newest, completed);
    failedGames ||= period.lastScan.failedGames > 0;
  }
  const state: CoverageState = answeredDays === 0 ? "unknown"
    : coveredDays === 0 ? "never"
      : coveredDays === totalDays ? "complete" : "partial";
  return {
    from,
    to,
    totalDays,
    coveredDays,
    state,
    incomplete: answeredDays < totalDays,
    oldestScan: oldest === null ? null : new Date(oldest).toISOString(),
    newestScan: newest === null ? null : new Date(newest).toISOString(),
    recent: oldest === null ? null : now.getTime() - oldest <= RECENT_SCAN_DAYS * DAY_MS,
    failedGames,
  };
}

export function decadeOf(year: number): number {
  return Math.floor(year / 10) * 10;
}

/** The ten years of a decade, each summarized from one coverage answer. */
export function yearSummaries(coverage: SynchronizationCoverage, decade: number, now: Date): CoverageSummary[] {
  return Array.from({ length: 10 }, (_, index) =>
    summarize(coverage, `${decade + index}-01-01`, `${decade + index}-12-31`, now));
}

/** The twelve months of a year, each summarized from one coverage answer. */
export function monthSummaries(coverage: SynchronizationCoverage, year: number, now: Date): CoverageSummary[] {
  return Array.from({ length: 12 }, (_, index) => {
    const month = String(index + 1).padStart(2, "0");
    return summarize(coverage, `${year}-${month}-01`, lastDayOfMonth(year, index + 1), now);
  });
}

/** Whether an answer describes every day of the span, so no further request is needed. */
export function answers(coverage: SynchronizationCoverage, from: string, to: string): boolean {
  return dayNumber(coverage.from) <= dayNumber(from) && dayNumber(coverage.to) >= dayNumber(to)
    && (coverage.continuesFrom === undefined || dayNumber(coverage.continuesFrom) > dayNumber(to));
}
