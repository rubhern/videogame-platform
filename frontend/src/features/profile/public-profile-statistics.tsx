import { useQuery } from "@tanstack/react-query";
import { GameMeter } from "../../shared/score/game-meter";
import { thermalBand, thermalLabels } from "../../shared/score/thermal-band";
import { formatPersonalScore } from "../ratings/personal-score";
import { RatingDistribution } from "../ratings/rating-insights-charts";
import { getPublicProfileStatistics, PUBLIC_PROFILES_KEY } from "./public-profile-api";
import { ProfileError } from "./profile-api";

const formatCount = new Intl.NumberFormat("es-ES");

export function PublicProfileStatisticsPanel({ alias }: { alias: string }) {
  const { data, error, isPending, refetch } = useQuery({
    queryKey: [...PUBLIC_PROFILES_KEY, alias, "statistics"],
    queryFn: ({ signal }) => getPublicProfileStatistics(alias, signal),
    gcTime: 0,
    staleTime: 0,
    retry: false,
  });

  if (isPending) return <p className="result-count" role="status">Cargando estadísticas…</p>;
  if (error instanceof ProfileError && error.status === 404) {
    return <p className="notice notice-info" role="status">Estas estadísticas ya no están disponibles.</p>;
  }
  if (error || !data) {
    return <div className="notice notice-danger" role="alert">
      <p>No se pudieron cargar las estadísticas.</p>
      <button type="button" className="button" onClick={() => void refetch()}>Reintentar</button>
    </div>;
  }

  const { ratings, wishlistCount } = data;
  const mean = ratings?.mean ?? null;
  const band = mean === null ? null : thermalBand(mean);

  return <div className="public-profile-statistics">
    <div className="public-profile-statistics-overview">
      <div className="public-profile-statistics-totals">
        <h2>El perfil en cifras</h2>
        <dl>
          {ratings ? <div><dt>Juegos puntuados</dt><dd>{formatCount.format(ratings.total)}</dd></div> : null}
          {wishlistCount !== undefined ? <div><dt>Juegos deseados</dt><dd>{formatCount.format(wishlistCount)}</dd></div> : null}
          {ratings?.highest !== undefined ? <div><dt>Nota más alta</dt><dd>{formatPersonalScore(ratings.highest)}</dd></div> : null}
          {ratings?.lowest !== undefined ? <div><dt>Nota más baja</dt><dd>{formatPersonalScore(ratings.lowest)}</dd></div> : null}
        </dl>
      </div>
      {mean === null ? null : <div className="public-profile-statistics-mean" data-thermal={band ?? undefined}>
        <GameMeter value={mean} />
        <div>
          <span>Puntuación media</span>
          <strong>{formatPersonalScore(mean)}<small aria-hidden="true">/10</small><span className="sr-only"> de 10</span></strong>
          {band ? <b>{thermalLabels[band]}</b> : null}
        </div>
      </div>}
    </div>
    {ratings && ratings.total > 0 ? (
      <RatingDistribution audience="visitor" distribution={ratings.distribution} mean={mean} total={ratings.total} />
    ) : ratings ? <p className="notice notice-empty">Aún no hay puntuaciones.</p> : null}
  </div>;
}
