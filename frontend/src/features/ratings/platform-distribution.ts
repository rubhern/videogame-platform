import type { RatingInsights } from "./rating-insights-api";

type Distribution = NonNullable<RatingInsights["platforms"]["distribution"]>;
const categories = [
  ["ps5", "PS5"], ["switch2", "Switch 2"], ["xboxSeries", "Xbox Series X|S"],
  ["windowsPc", "PC Windows"], ["other", "Otras"],
] as const;

/** Largest remainders at one decimal: visible shares total 100%, with fixed-order ties.
 * Arc geometry always uses the exact counts. Zero categories cannot acquire rounding units.
 */
export function platformDistribution(distribution: Distribution) {
  const total = categories.reduce((sum, [key]) => sum + distribution[key], 0);
  const rows = categories.map(([key, label]) => {
    const exact = total === 0 ? 0 : distribution[key] / total * 1000;
    return { key, label, count: distribution[key], tenths: Math.floor(exact), remainder: exact % 1 };
  });
  const missing = total === 0 ? 0 : 1000 - rows.reduce((sum, row) => sum + row.tenths, 0);
  [...rows].sort((a, b) => b.remainder - a.remainder).slice(0, missing).forEach(row => row.tenths++);
  return { total, rows: rows.map(row => ({ ...row, percentage: row.tenths / 10 })) };
}
