import { useQuery } from "@tanstack/react-query";
import { Link, useParams } from "react-router-dom";

import {
  GameDetailsApiError,
  gameDetailsQueryKey,
  getGameDetails,
} from "../features/game-details/game-details-api";
import { GameDetailsContent } from "../features/game-details/game-details-content";
import { CatalogueLoading } from "../shared/ui/catalogue-loading";

export function GameDetailsPage() {
  const { gameId = "" } = useParams();
  const query = useQuery({
    queryKey: gameDetailsQueryKey(gameId),
    queryFn: ({ signal }) => getGameDetails(gameId, undefined, signal),
    retry: false,
    staleTime: 0,
  });
  const error = query.error instanceof GameDetailsApiError ? query.error : null;
  const missing = error?.code === "GAME_NOT_FOUND";
  const notReady = error?.code === "CATALOGUE_NOT_READY";

  return (
    <section className="game-details-section">
      <div className="page-container">
        {query.isPending ? (
          <>
            <p className="eyebrow eyebrow-dot">Ficha del catálogo</p>
            <h1 className="page-title">Detalle del juego</h1>
            <CatalogueLoading message="Cargando el juego…" />
          </>
        ) : query.isError ? (
          <>
            <p className="eyebrow eyebrow-dot">Ficha del catálogo</p>
            <h1 className="page-title">
              {missing
                ? "Juego no encontrado"
                : notReady
                  ? "El catálogo todavía no está disponible"
                  : "No se pudo cargar el juego"}
            </h1>
            <div
              className={`notice ${notReady || missing ? "notice-info" : "notice-danger"}`}
              role="alert"
            >
              <span
                aria-hidden="true"
                className={`notice-symbol ${notReady || missing ? "" : "notice-symbol-danger"}`}
              >
                {missing ? "?" : notReady ? "i" : "!"}
              </span>
              <h2>
                {missing
                  ? "Este juego no está en el catálogo"
                  : notReady
                    ? "Publicación local pendiente"
                    : "Error de lectura"}
              </h2>
              <p>
                {missing
                  ? "Este juego no forma parte del catálogo local disponible."
                  : notReady
                    ? "Aún no existe una publicación local válida. Inténtalo de nuevo más tarde."
                    : "No pudimos leer los datos locales. Inténtalo de nuevo más tarde."}
              </p>
              {error?.correlationId ? (
                <p className="notice-footnote">
                  Referencia para soporte: {error.correlationId}
                </p>
              ) : null}
              {!missing ? (
                <button
                  className="button button-primary"
                  onClick={() => {
                    void query.refetch();
                  }}
                >
                  Reintentar
                </button>
              ) : null}
            </div>
          </>
        ) : (
          <GameDetailsContent key={gameId} game={query.data} />
        )}
        <Link
          aria-label="Volver a lanzamientos"
          className="button game-details-back"
          to="/"
        >
          ← Volver a lanzamientos
        </Link>
      </div>
    </section>
  );
}
