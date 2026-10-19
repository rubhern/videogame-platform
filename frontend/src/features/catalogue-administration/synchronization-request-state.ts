import type { AcceptedRun, FinishedRun, SynchronizationStatus } from "./administration-api";

/** The contract's interval bounds; the server still decides every request. */
export const MAX_INTERVAL_DAYS = 184;
export const EARLIEST = "1950-01-01";
export const LATEST = "2099-12-31";
const DAY_MS = 86_400_000;

/** An accepted request and when it was accepted, so a later status read can say it is missing. */
export interface RequestedRun {
  run: AcceptedRun;
  acceptedAt: number;
}

export type RequestedRunReading =
  | { phase: "accepted" }
  | { phase: "running"; stalled: boolean }
  | { phase: "finished"; run: FinishedRun }
  | { phase: "missing" };

/**
 * Where the requested run is, read from the ordinary status: it is the run in flight, a retained
 * finished run, or not yet visible. A status read after acceptance that lacks it means bounded
 * history no longer holds it.
 */
export function readRequestedRun(requested: RequestedRun, status: SynchronizationStatus | undefined,
  statusReadAt: number): RequestedRunReading {
  if (status?.currentRun?.runId === requested.run.runId) {
    return { phase: "running", stalled: status.currentRun.stalled };
  }
  const finished = status?.recentRuns.find((run) => run.runId === requested.run.runId);
  if (finished) return { phase: "finished", run: finished };
  return status && statusReadAt > requested.acceptedAt ? { phase: "missing" } : { phase: "accepted" };
}

export type Field = "from" | "to";
export type FieldErrors = Partial<Record<Field, string>>;

const dayNumber = (isoDate: string) => Date.parse(`${isoDate}T00:00:00Z`) / DAY_MS;

/** The contract's rules in words, before any request; ISO dates compare as text. */
export function validateWindow(from: string, to: string): FieldErrors {
  const errors: FieldErrors = {};
  if (!from) errors.from = "Indica la fecha de inicio.";
  else if (from < EARLIEST || from > LATEST) errors.from = "Usa una fecha entre 1950 y 2099.";
  if (!to) errors.to = "Indica la fecha final.";
  else if (to < EARLIEST || to > LATEST) errors.to = "Usa una fecha entre 1950 y 2099.";
  else if (from && !errors.from && to < from) errors.to = "No puede ser anterior a la fecha de inicio.";
  else if (from && !errors.from && dayNumber(to) - dayNumber(from) >= MAX_INTERVAL_DAYS) {
    errors.to = `El intervalo admite como máximo ${MAX_INTERVAL_DAYS} días.`;
  }
  return errors;
}
