import { useQuery } from "@tanstack/react-query";
import { useId, useState } from "react";
import { useSearchParams } from "react-router-dom";

import {
  getPendingRatingIntent,
  ratingIntentStartUrl,
} from "../../features/session/rating-intent";
import { assignLocation } from "../../shared/browser/navigate";
import type { GameDetails } from "./game-details-api";

const RATING_VALUES = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10] as const;

type Outcome = "expired" | "cancelled" | "invalid";

const outcomeNotices: Record<Outcome, string> = {
  expired:
    "La selección para puntuar caducó. Elige de nuevo tu puntuación e inicia sesión.",
  cancelled:
    "No se completó el inicio de sesión. Tu puntuación no se ha registrado.",
  invalid:
    "La puntuación seleccionada no era válida. Elige un valor del 1 al 10.",
};

function isOutcome(value: string | null): value is Outcome {
  return value === "expired" || value === "cancelled" || value === "invalid";
}

/**
 * Minimal, accessible rating entry point used to exercise the authentication boundary.
 *
 * <p>Selecting a value and pressing "Puntuar" starts authentication at the rating boundary; it
 * never executes or persists a rating command. After returning from authentication the recovered
 * value is shown as a pending, non-persisted selection. The final rating experience is owned by a
 * later slice.
 */
export function GameRatingEntry({ game }: { game: GameDetails }) {
  const selectId = useId();
  const [searchParams] = useSearchParams();
  const [value, setValue] = useState("");

  const marker = searchParams.get("rating-intent");

  // The recovered selection lives in the single-use server context; the query consumes it once.
  // A reload issues a fresh, empty read, so a pending selection is never resumed twice.
  const resume = useQuery({
    queryKey: ["rating-intent", game.gameId],
    queryFn: ({ signal }) => getPendingRatingIntent(signal),
    enabled: marker === "resumed",
    retry: false,
    staleTime: Infinity,
  });
  const recovered = marker === "resumed" ? (resume.data?.value ?? null) : null;
  const notice = isOutcome(marker) ? marker : null;

  function start(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const selected = Number(value);
    if (!Number.isInteger(selected) || selected < 1 || selected > 10) {
      return;
    }
    assignLocation(ratingIntentStartUrl(game.gameId, game.slug, selected));
  }

  return (
    <div className="game-rating-entry">
      {recovered !== null ? (
        <p className="game-rating-pending" role="status">
          Sesión iniciada. Tu puntuación seleccionada es {recovered}/10
          (pendiente, todavía sin guardar).
        </p>
      ) : null}
      {notice !== null ? (
        <p className="game-rating-notice" role="status">
          {outcomeNotices[notice]}
        </p>
      ) : null}
      <form className="game-rating-form" onSubmit={start}>
        <label htmlFor={selectId}>Tu puntuación (1-10)</label>
        <select
          id={selectId}
          name="rating-value"
          value={value}
          onChange={(event) => setValue(event.target.value)}
        >
          <option value="" disabled>
            Elige un valor
          </option>
          {RATING_VALUES.map((option) => (
            <option key={option} value={option}>
              {option}
            </option>
          ))}
        </select>
        <button
          className="button button-primary"
          type="submit"
          disabled={value === ""}
        >
          Puntuar
        </button>
      </form>
      <p className="game-rating-hint">
        Para puntuar necesitas iniciar sesión. Al continuar te llevaremos a
        iniciar sesión y volverás a este juego.
      </p>
    </div>
  );
}
