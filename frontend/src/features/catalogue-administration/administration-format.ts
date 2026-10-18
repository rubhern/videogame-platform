import type { FinishedRun } from "./administration-api";
import type { CoverageState, CoverageSummary } from "./coverage-calendar";

const DAY_MS = 86_400_000;

const dateTime = new Intl.DateTimeFormat("es-ES", {
  dateStyle: "medium", timeStyle: "short", timeZone: "Europe/Madrid",
});
// Release dates are calendar days, never instants: format them without a time zone shift.
const calendarDay = new Intl.DateTimeFormat("es-ES", { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" });
const monthName = new Intl.DateTimeFormat("es-ES", { month: "long", timeZone: "UTC" });

export function formatInstant(iso: string): string {
  return dateTime.format(new Date(iso));
}

export function formatDay(isoDate: string): string {
  return calendarDay.format(new Date(`${isoDate}T00:00:00Z`));
}

export function formatInterval(from: string, to: string): string {
  return from === to ? formatDay(from) : `${formatDay(from)} – ${formatDay(to)}`;
}

export function formatMonth(year: number, month: number): string {
  const name = monthName.format(new Date(Date.UTC(year, month - 1, 1)));
  return name.charAt(0).toUpperCase() + name.slice(1);
}

export function formatCount(value: number): string {
  return value.toLocaleString("es-ES");
}

export function formatDuration(startedAt: string, completedAt: string): string {
  const seconds = Math.max(0, Math.round((Date.parse(completedAt) - Date.parse(startedAt)) / 1000));
  if (seconds < 60) return `${seconds} s`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes} min ${seconds % 60} s`;
  return `${Math.floor(minutes / 60)} h ${minutes % 60} min`;
}

export interface RunReading {
  label: string;
  /** Shape of the marker, so the result never depends on colour alone. */
  tone: "complete" | "partial" | "failed";
  explanation: string;
}

/** A finished run's result in words, with what it means for coverage per ADR-0017. */
export function runReading(run: FinishedRun): RunReading {
  switch (run.result) {
    case "succeeded":
      return { label: "Completada", tone: "complete", explanation: "Leyó todo el intervalo sin juegos fallidos." };
    case "partial":
      if (run.outcomeCode === "SYNCHRONIZATION_INTERRUPTED") {
        return { label: "Interrumpida", tone: "partial",
          explanation: "Se detuvo antes de leer todas las páginas: publicó algunos juegos, pero no registra cobertura." };
      }
      if (run.outcomeCode === "SYNCHRONIZATION_COMPLETED_WITH_FAILURES") {
        return { label: "Completa con juegos fallidos", tone: "partial",
          explanation: "Leyó todo el intervalo; los juegos fallidos conservan su último estado válido." };
      }
      return { label: "Parcial", tone: "partial", explanation: "Publicó parte de los juegos del intervalo." };
    case "failed":
      return { label: "Fallida", tone: "failed",
        explanation: "No publicó ningún juego y no registra cobertura; se conserva el estado anterior." };
    case "abandoned":
      return { label: "Abandonada", tone: "failed",
        explanation: "El proceso dejó de dar señales; no registra cobertura ni contadores." };
  }
}

export const coverageLabels: Record<CoverageState, string> = {
  complete: "Cubierto",
  partial: "Parcialmente cubierto",
  never: "Nunca cubierto",
  unknown: "Sin describir",
};

export function coveredShare(summary: CoverageSummary): string {
  return `${formatCount(summary.coveredDays)} de ${formatCount(summary.totalDays)} días`;
}

/** Freshness under the page's presentation policy; covered data never expires on the server. */
export function freshnessLabel(summary: CoverageSummary, now: Date): string {
  if (summary.recent === null || summary.oldestScan === null) return "Sin escaneos";
  if (summary.recent) return "Reciente";
  const days = Math.floor((now.getTime() - Date.parse(summary.oldestScan)) / DAY_MS);
  return `Hace ${formatCount(days)} días`;
}
