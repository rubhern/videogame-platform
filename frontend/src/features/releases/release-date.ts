import type { ReleasePage } from "./releases-api";

type ReleaseDate = ReleasePage["items"][number]["release"]["releaseDate"];

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

export function formatReleaseDate(releaseDate: ReleaseDate): string {
  const value = "value" in releaseDate ? releaseDate.value : null;

  if (releaseDate.precision === "unknown" || value === null) {
    return "Fecha por confirmar";
  }

  if (releaseDate.precision === "day") {
    const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
    const month = match === null ? null : monthName(match[2] ?? "");
    return match === null || month === null
      ? "Fecha no disponible"
      : `${Number(match[3])} de ${month} de ${match[1]}`;
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
      : `${match[2]}.º trimestre de ${match[1]}`;
  }

  if (releaseDate.precision === "year" && /^\d{4}$/.test(value)) {
    return value;
  }

  return "Fecha no disponible";
}
