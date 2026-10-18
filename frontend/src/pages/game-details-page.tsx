import { useQuery } from "@tanstack/react-query";
import { Link, useParams } from "react-router-dom";

import {
  GameDetailsApiError,
  gameDetailsQueryOptions,
} from "../features/game-details/game-details-api";
import { GameDetailsContent } from "../features/game-details/game-details-content";
import { GameDetailsLoading } from "../features/game-details/game-details-loading";

function BackToReleases() {
  return (
    <Link className="button view-action view-action-back" to="/">
      <span aria-hidden="true">←</span> Volver a lanzamientos
    </Link>
  );
}

export function GameDetailsPage() {
  const { gameId = "" } = useParams();
  const query = useQuery({
    ...gameDetailsQueryOptions(gameId),
    refetchOnMount: false,
    retry: false,
    staleTime: 0,
  });
  const error = query.error instanceof GameDetailsApiError ? query.error : null;
  const missing = error?.code === "GAME_NOT_FOUND";
  const notReady = error?.code === "CATALOGUE_NOT_READY";
  const informational = missing || notReady;

  return (
    <section className="game-details-section">
      <div className="page-container">
        {query.isPending ? (
          <>
            {/* The placeholder already draws the title's frame; the heading stays for
                assistive technology until the record arrives. */}
            <h1 className="sr-only">Detalle del juego</h1>
            <GameDetailsLoading message="Cargando el juego…" />
          </>
        ) : query.isError ? (
          <>
            <div
              className={`notice ${informational ? "notice-info" : "notice-danger"}`}
              role="alert"
            >
              <p className="notice-kicker">Ficha del catálogo</p>
              <h1>
                {missing
                  ? "Juego no encontrado"
                  : notReady
                    ? "El catálogo todavía no está disponible"
                    : "No se pudo cargar el juego"}
              </h1>
              <p>
                {missing
                  ? "Este juego no forma parte del catálogo local disponible."
                  : notReady
                    ? "Aún no existe una publicación local válida. Inténtalo de nuevo más tarde."
                    : "No pudimos leer los datos locales. Inténtalo de nuevo más tarde."}
              </p>
              <div className="notice-actions">
                {!missing ? (
                  <button
                    className={`button ${informational ? "button-primary" : "button-danger"}`}
                    onClick={() => {
                      void query.refetch();
                    }}
                    type="button"
                  >
                    Reintentar
                  </button>
                ) : null}
                <BackToReleases />
              </div>
              {error?.correlationId ? (
                <p className="notice-reference">
                  Referencia para soporte: {error.correlationId}
                </p>
              ) : null}
            </div>
          </>
        ) : (
          <GameDetailsContent key={gameId} game={query.data} />
        )}
        {query.isError ? null : (
          <div className="game-details-back">
            <BackToReleases />
          </div>
        )}
      </div>
    </section>
  );
}
