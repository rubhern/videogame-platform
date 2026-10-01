/**
 * Gameómetro reads a score as a temperature. The band is a presentation of the existing 1–10
 * score, never a new score: it does not change ratings, aggregates or their meaning, and the
 * number stays authoritative wherever a band is shown.
 */
export type ThermalBand = "freeze" | "cold" | "warm" | "hot" | "burn";

/** From ice to fire. */
export const THERMAL_BANDS: readonly ThermalBand[] = ["freeze", "cold", "warm", "hot", "burn"];

export const thermalLabels: Record<ThermalBand, string> = {
  freeze: "Congelado",
  cold: "Frío",
  warm: "Templado",
  hot: "Caliente",
  burn: "Ardiendo",
};

// Upper-inclusive bands over the closed 0–10 scale: [0, 2] (2, 4] (4, 6] (6, 8] (8, 10], so each
// band holds two personal values (1–2, 3–4, 5–6, 7–8, 9–10) and a mean of 8,1 already burns.
const UPPER_BOUNDS: readonly (readonly [number, ThermalBand])[] = [
  [2, "freeze"],
  [4, "cold"],
  [6, "warm"],
  [8, "hot"],
];

/** The band of a score on the 0–10 scale, or `null` for a value outside it. */
export function thermalBand(score: number): ThermalBand | null {
  if (!Number.isFinite(score) || score < 0 || score > 10) return null;
  return UPPER_BOUNDS.find(([upper]) => score <= upper)?.[1] ?? "burn";
}
