/** The number of chosen platforms in words, announced as the choice changes. */
export function selectionSummary(count: number): string {
  if (count === 0) return "Ninguna plataforma elegida";
  return count === 1 ? "1 plataforma elegida" : `${count} plataformas elegidas`;
}
