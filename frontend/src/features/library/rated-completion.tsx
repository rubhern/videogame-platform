import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";

import { completeRatedGames, LIBRARY_KEY } from "./library-api";

const games = (count: number) => (count === 1 ? "1 juego" : `${count} juegos`);

type RatedCompletionProps = {
  /** Rated games without a state that the action would mark. */
  count: number;
  csrfToken: string;
};

/**
 * Mis juegos' offer to bring the player's rated games in (UC-026): one confirmed action marks the
 * rated games without a state Completado, and each one can be changed afterwards. It never runs on
 * its own and never touches a game that already has a state.
 */
export function RatedCompletion({ count, csrfToken }: RatedCompletionProps) {
  const client = useQueryClient();
  const [confirming, setConfirming] = useState(false);
  const outcome = useRef<HTMLParagraphElement>(null);
  const confirm = useRef<HTMLButtonElement>(null);
  const complete = useMutation({
    mutationFn: () => completeRatedGames(csrfToken),
    retry: false,
    onSuccess: async () => {
      setConfirming(false);
      await client.invalidateQueries({ queryKey: LIBRARY_KEY });
    },
  });

  useEffect(() => {
    if (confirming) confirm.current?.focus();
  }, [confirming]);
  useEffect(() => {
    // The offer leaves with its buttons, so the outcome takes the focus.
    if (complete.isSuccess) outcome.current?.focus();
  }, [complete.isSuccess]);

  if (count === 0 && !complete.isSuccess) return null;
  return (
    <section aria-label="Tus juegos puntuados" className="library-rated" aria-busy={complete.isPending || undefined}>
      {complete.isSuccess ? (
        <p ref={outcome} className="library-rated-outcome" role="status" tabIndex={-1}>
          {complete.data === 1 ? "1 juego marcado" : `${complete.data} juegos marcados`} como Completado.
          {count > 0 ? ` Quedan ${games(count)} puntuados sin estado.` : " Cambia el estado de los que dejaste a medias."}
        </p>
      ) : null}
      {count > 0 ? (
        <>
          <div className="library-rated-text">
            <h2>
              Tienes {games(count)} {count === 1 ? "puntuado" : "puntuados"} sin estado
            </h2>
            <p>Márcalos como Completado y luego cambia los que abandonaste o sigues jugando.</p>
          </div>
          {confirming ? (
            <div className="library-rated-actions" role="group" aria-label="Confirmar">
              <button ref={confirm} className="button button-primary" disabled={complete.isPending} type="button"
                onClick={() => complete.mutate()}>
                {complete.isPending ? "Marcando…" : `Marcar ${games(count)} como Completado`}
              </button>
              <button className="button" disabled={complete.isPending} type="button" onClick={() => setConfirming(false)}>
                Cancelar
              </button>
            </div>
          ) : (
            <div className="library-rated-actions">
              <button className="button button-primary" type="button" onClick={() => setConfirming(true)}>
                Marcar como Completado
              </button>
            </div>
          )}
          {complete.isError ? (
            <p className="library-state-error" role="alert">No se pudieron marcar. Inténtalo de nuevo.</p>
          ) : null}
        </>
      ) : null}
    </section>
  );
}
