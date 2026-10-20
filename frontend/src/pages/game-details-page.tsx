import { useQuery } from "@tanstack/react-query";
import { Link, useLocation, useParams } from "react-router-dom";

import {
  GameDetailsApiError,
  gameDetailsQueryOptions,
} from "../features/game-details/game-details-api";
import { GameDetailsContent } from "../features/game-details/game-details-content";
import { GameDetailsLoading } from "../features/game-details/game-details-loading";
import { DEFAULT_RETURN, readReturnContext } from "../shared/navigation/return-context";

/**
 * The game page's way back names the screen it was opened from and returns to it with the same
 * period, filters, order, search and page (#285). The origin is the history entry's validated
 * state; a direct link, a reload after leaving the product or any unexpected state returns to
 * TOPómetro. The return restores the origin entry's reading position.
 */
export function BackToOrigin() {
  const origin = readReturnContext(useLocation().state);
  return (
    <Link
      className="button view-action view-action-back"
      state={origin?.state}
      to={origin?.path ?? DEFAULT_RETURN.path}
    >
      <span aria-hidden="true">←</span> {origin?.label ?? DEFAULT_RETURN.label}
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
              <p className="notice-kicker">Ficha del juego</p>
              <h1>
                {missing
                  ? "Juego no encontrado"
                  : notReady
                    ? "Los juegos todavía no están disponibles"
                    : "No se pudo cargar el juego"}
              </h1>
              <p>
                {missing
                  ? "Este juego no está disponible en Gameómetro, aún."
                  : notReady
                    ? "No tenemos una publicación aún. Prueba más tarde"
                    : "Estamos teniendo algún problema de lectura, inténtalo más tarde que seguro ya lo habremos arreglado"}
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
                <BackToOrigin />
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
            <BackToOrigin />
          </div>
        )}
      </div>
    </section>
  );
}
