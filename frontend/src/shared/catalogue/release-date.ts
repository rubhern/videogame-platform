import type { MethodResponse } from "openapi-fetch";

import type { ProductApiClient } from "../api/product-api-client";

// Derive the actual client response: openapi-fetch's Readable transformation can
// omit a null-only property. Both catalogue reads use this date representation.
type CataloguePage = MethodResponse<ProductApiClient, "get", "/games">;
type ReleaseDate = CataloguePage["items"][number]["releaseContext"][number]["releaseDate"];

const spanishMonths = [
  "enero",
  "febrero",
  "marzo",
  "abril",
  "mayo",
  "junio",
  "julio",
  "agosto",
  "septiembre",
  "octubre",
  "noviembre",
  "diciembre",
] as const;

function monthName(month: string): string | null {
  const number = Number(month);
  return Number.isInteger(number) && number >= 1 && number <= 12
    ? (spanishMonths[number - 1] ?? null)
    : null;
}

/**
 * Formats a calendar day the API already resolved, such as the evaluated date and
 * the window boundaries. It never derives a day the contract did not provide.
 */
export function formatCalendarDay(value: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  const month = match === null ? null : monthName(match[2] ?? "");
  return match === null || month === null
    ? "Fecha no disponible"
    : `${Number(match[3])} de ${month} de ${match[1]}`;
}

/**
 * A window between two API-provided calendar days, as compact as the shared parts allow:
 * `11–17 oct 2026`, `28 sep – 25 oct 2026` or `29 dic 2026 – 4 ene 2027`.
 */
export function formatDayRange(from: string, to: string): string {
  const start = /^(\d{4})-(\d{2})-(\d{2})$/.exec(from);
  const end = /^(\d{4})-(\d{2})-(\d{2})$/.exec(to);
  const startMonth = start === null ? null : shortMonthName(start[2] ?? "");
  const endMonth = end === null ? null : shortMonthName(end[2] ?? "");
  if (start === null || end === null || startMonth === null || endMonth === null) {
    return "Fecha no disponible";
  }
  const [, startYear, , startDay] = start;
  const [, endYear, , endDay] = end;
  const last = `${Number(endDay)} ${endMonth} ${endYear}`;
  if (startYear !== endYear) return `${Number(startDay)} ${startMonth} ${startYear} – ${last}`;
  if (startMonth !== endMonth) return `${Number(startDay)} ${startMonth} – ${last}`;
  return `${Number(startDay)}–${last}`;
}

const shortMonths = [
  "ene",
  "feb",
  "mar",
  "abr",
  "may",
  "jun",
  "jul",
  "ago",
  "sep",
  "oct",
  "nov",
  "dic",
] as const;

export function shortMonthName(month: string): string | null {
  const number = Number(month);
  return Number.isInteger(number) && number >= 1 && number <= 12
    ? (shortMonths[number - 1] ?? null)
    : null;
}

/**
 * Spanish apocopates `primero` and `tercero` before a masculine singular noun, so
 * the first and third quarters abbreviate as `1.er` and `3.er` rather than `.º`.
 */
function quarterOrdinal(quarter: string): string {
  return quarter === "1" || quarter === "3" ? `${quarter}.er` : `${quarter}.º`;
}

export function formatReleaseDate(releaseDate: ReleaseDate): string {
  const value = "value" in releaseDate ? releaseDate.value : null;

  if (releaseDate.precision === "unknown" || value === null) {
    return "Fecha por confirmar";
  }

  if (releaseDate.precision === "day") {
    return formatCalendarDay(value);
  }

  if (releaseDate.precision === "month") {
    const match = /^(\d{4})-(\d{2})$/.exec(value);
    const month = match === null ? null : monthName(match[2] ?? "");
    return match === null || month === null
      ? "Fecha no disponible"
      : `${month} de ${match[1]}`;
  }

  if (releaseDate.precision === "quarter") {
    const match = /^(\d{4})-Q([1-4])$/.exec(value);
    return match === null
      ? "Fecha no disponible"
      : `${quarterOrdinal(match[2] ?? "")} trimestre de ${match[1]}`;
  }

  if (releaseDate.precision === "year" && /^\d{4}$/.test(value)) {
    return value;
  }

  return "Fecha no disponible";
}

/**
 * Compact form of the same release date for a chip over a cover (`25 sep 2026`, `sep 2026`,
 * `T3 2026`, `2026`). It keeps the contract precision and never implies a finer one; the full
 * wording from `formatReleaseDate` stays available beside it.
 */
export function formatReleaseDateShort(releaseDate: ReleaseDate): string {
  const value = "value" in releaseDate ? releaseDate.value : null;

  if (releaseDate.precision === "unknown" || value === null) {
    return "Por confirmar";
  }

  if (releaseDate.precision === "day") {
    const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
    const month = match === null ? null : shortMonthName(match[2] ?? "");
    return match === null || month === null
      ? "Fecha no disponible"
      : `${Number(match[3])} ${month} ${match[1]}`;
  }

  if (releaseDate.precision === "month") {
    const match = /^(\d{4})-(\d{2})$/.exec(value);
    const month = match === null ? null : shortMonthName(match[2] ?? "");
    return match === null || month === null ? "Fecha no disponible" : `${month} ${match[1]}`;
  }

  if (releaseDate.precision === "quarter") {
    const match = /^(\d{4})-Q([1-4])$/.exec(value);
    return match === null ? "Fecha no disponible" : `T${match[2]} ${match[1]}`;
  }

  if (releaseDate.precision === "year" && /^\d{4}$/.test(value)) {
    return value;
  }

  return "Fecha no disponible";
}
