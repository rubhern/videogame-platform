import type { RankingSource } from "./ranking-search";

const count = new Intl.NumberFormat("es-ES", { useGrouping: "always" });

/**
 * Which games a TOPómetro holds (#538), in one line: released (on the platform, when there is
 * one) with at least the server's own minimum of ratings from the chosen source. The minimum is
 * the one the ranking was read with, never a remembered default, so no stale rule is shown.
 */
export function RankingEntryRule({
  source,
  minimumRatings,
  platformName,
}: {
  source: RankingSource;
  minimumRatings: number;
  platformName?: string | undefined;
}) {
  const ratings =
    source === "gameometro"
      ? minimumRatings === 1 ? "puntuación" : "puntuaciones"
      : minimumRatings === 1 ? "valoración" : "valoraciones";
  return (
    <p className="ranking-source-note">
      Juegos ya lanzados{platformName === undefined ? "" : ` en ${platformName}`} con al menos{" "}
      <strong>
        {count.format(minimumRatings)} {ratings}
      </strong>{" "}
      {source === "gameometro" ? "de la comunidad" : "de jugadores en IGDB"}.
    </p>
  );
}
