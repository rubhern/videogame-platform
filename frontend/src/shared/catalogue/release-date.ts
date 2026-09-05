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
