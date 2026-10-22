const integerFormatter = new Intl.NumberFormat("es-ES", { useGrouping: "always" });
const decimalFormatter = new Intl.NumberFormat("es-ES", { minimumFractionDigits: 1, maximumFractionDigits: 1 });
const percentFormatter = new Intl.NumberFormat("es-ES", { style: "percent", maximumFractionDigits: 1 });
const compactFormatter = new Intl.NumberFormat("es-ES", { notation: "compact", maximumFractionDigits: 1 });
export const number = (value: number) => integerFormatter.format(value);
export const decimal = (value: number) => decimalFormatter.format(value);
export const percent = (part: number, total: number) => percentFormatter.format(total === 0 ? 0 : part / total);
/** For a narrow chart label only; an exact reading of the same value is always available. */
export const compact = (value: number) => value < 10_000 ? number(value) : compactFormatter.format(value);
export const games = (count: number) => `${number(count)} ${count === 1 ? "juego" : "juegos"}`;
