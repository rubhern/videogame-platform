import { useQuery } from "@tanstack/react-query";
import { useEffect, useId, useRef, useState } from "react";
import { useSearchParams } from "react-router-dom";

import { assignLocation } from "../../shared/browser/navigate";
import { GameMeter } from "../../shared/score/game-meter";
import { thermalBand } from "../../shared/score/thermal-band";
import type { GameDetails } from "../game-details/game-details-api";
import {
  getPendingRatingIntent,
  ratingIntentStartUrl,
} from "../session/rating-intent";
import { useSession } from "../session/use-session";
import type { RatingCommandError } from "./personal-rating-api";
import { RatingKeypad } from "./rating-keypad";
import { useRatingCommand, usePersonalRating } from "./use-personal-rating";

const ineligibleReasons: Record<
  GameDetails["ratingEligibility"]["reason"],
  string
> = {
  ELIGIBLE_RELEASE_FOUND: "Selecciona una nota",
  NO_COMMERCIAL_RELEASE:
    "Todavía no se puede puntuar: no hay un lanzamiento comercial registrado.",
  RELEASE_NOT_OCCURRED:
    "Todavía no se puede puntuar: el lanzamiento aún no ha ocurrido.",
  RELEASE_CANCELLED:
    "No se puede puntuar: los lanzamientos registrados están cancelados.",
  RELEASE_DATE_UNCERTAIN:
    "Todavía no se puede puntuar: la fecha de lanzamiento es incierta.",
  RELEASE_REVIEW_REQUIRED:
    "Todavía no se puede puntuar: la información del lanzamiento está pendiente de revisión.",
};

type Outcome = "expired" | "cancelled" | "invalid";

const outcomeNotices: Record<Outcome, string> = {
  expired: "La selección para puntuar caducó. Vuelve a elegir tu nota.",
  cancelled: "No se completó el inicio de sesión. Tu nota no se ha guardado.",
  invalid: "La nota seleccionada no era válida. Elige un valor del 1 al 10.",
};

function isOutcome(value: string | null): value is Outcome {
  return value === "expired" || value === "cancelled" || value === "invalid";
}

const failureMessages: Record<RatingCommandError["kind"], string> = {
  authentication:
    "Tu sesión ha caducado. Vuelve a pulsar la nota para iniciar sesión de nuevo.",
  csrf: "No se pudo verificar la solicitud. Vuelve a intentarlo; si persiste, recarga la página.",
  conflict:
    "Tu nota cambió desde otra sesión. Se muestra la versión actual; revísala y vuelve a intentarlo.",
  validation: "La nota debe ser un número entero del 1 al 10.",
  ineligible: "Este juego ya no admite nuevas puntuaciones.",
  "rate-limited":
    "Demasiadas solicitudes seguidas. Espera un momento y vuelve a intentarlo.",
  unavailable:
    "No se pudo guardar el cambio. Tu nota anterior se conserva; inténtalo de nuevo más tarde.",
  ambiguous:
    "No sabemos si el cambio se aplicó. Comprueba tu nota actual antes de volver a intentarlo.",
};

type Feedback =
  | { tone: "success"; text: string }
  | { tone: "error"; error: RatingCommandError };

/**
 * Inline personal-rating experience for one game (UC-005, UC-006, UC-007).
 *
 * <p>The rating belongs to the game, never to a release: the platform/region selection of the
 * page does not affect it. Eligibility is expressed through the enabled or disabled control. An
 * anonymous selection starts authentication at the rating boundary; after returning, the recovered
 * value is persisted once automatically. Pressing a value saves it immediately: there is no
 * separate confirmation action, and arrow keys only move focus. Commands are conditional
 * (`If-None-Match: *` / `If-Match`), never retried automatically, and a rejected or ambiguous
 * command keeps the last valid personal and community state visible.
 */
export function GameRatingPanel({ game }: { game: GameDetails }) {
  const titleId = useId();
  const [searchParams, setSearchParams] = useSearchParams();
  const session = useSession();
  const csrfToken =
    session.data?.authenticated === true ? session.data.csrfToken : null;
  const authenticated = csrfToken !== null;
  const personal = usePersonalRating(game.gameId, authenticated);
  const command = useRatingCommand(game.gameId);
  const [feedback, setFeedback] = useState<Feedback | null>(null);
  const scaleRef = useRef<HTMLDivElement>(null);
  const resumedOnce = useRef(false);

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

  const eligible = game.ratingEligibility.eligible;
  const persisted = authenticated ? (personal.data ?? null) : null;
  const busy = command.isPending;
  const checking = session.isPending || (authenticated && personal.isPending);
  const inFlight =
    busy && command.variables?.type === "save" ? command.variables.value : null;
  // Pressed value: the one being saved, else the recovered one awaiting its automatic save,
  // else the persisted rating.
  const selected = inFlight ?? recovered ?? persisted?.value ?? null;
  const selectedBand = selected === null ? null : thermalBand(selected);

  function clearMarker() {
    if (marker === null) return;
    const next = new URLSearchParams(searchParams);
    next.delete("rating-intent");
    setSearchParams(next, { replace: true, preventScrollReset: true });
  }

  function save(value: number) {
    if (busy) return;
    if (csrfToken === null) {
      assignLocation(ratingIntentStartUrl(game.gameId, game.slug, value));
      return;
    }
    if (value === persisted?.value) return;
    command.mutate(
      { type: "save", csrfToken, value },
      {
        onSuccess: (outcome) => {
          if (outcome.type !== "saved") return;
          clearMarker();
          setFeedback({
            tone: "success",
            text: `Puntuación guardada: ${outcome.rating.value}/10.`,
          });
        },
        onError: (error) => setFeedback({ tone: "error", error }),
      },
    );
  }

  // The visitor already chose this value before authenticating: persist it once on return. The
  // marker is cleared as the command starts so the effect cannot fire twice; a value equal to the
  // existing rating needs no command. Failures surface like any other command.
  const readyToResume = recovered !== null && csrfToken !== null && !checking;
  useEffect(() => {
    if (!readyToResume || recovered === null || resumedOnce.current) return;
    resumedOnce.current = true;
    clearMarker();
    if (recovered !== persisted?.value) save(recovered);
    // The command captures its own inputs; re-running on later renders must not repeat it.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [readyToResume]);

  function remove() {
    if (csrfToken === null || persisted === null || busy) return;
    setFeedback(null);
    command.mutate(
      { type: "delete", csrfToken },
      {
        onSuccess: () => {
          clearMarker();
          setFeedback({ tone: "success", text: "Puntuación eliminada." });
          // The delete action disappears with the rating: keep focus in the control.
          scaleRef.current?.querySelector("button")?.focus();
        },
        onError: (error) => setFeedback({ tone: "error", error }),
      },
    );
  }

  function checkCurrentRating() {
    setFeedback(null);
    void personal.refetch();
  }

  const subtitle = !eligible
    ? ineligibleReasons[game.ratingEligibility.reason]
    : busy
      ? "Guardando tu nota…"
      : checking
        ? "Comprobando tu nota…"
        : authenticated && personal.isError
          ? "No se pudo comprobar si ya tenías una nota. Puedes puntuar igualmente."
          : "Selecciona una nota";

  return (
    <section
      className="game-personal-rating"
      aria-labelledby={titleId}
      aria-busy={busy}
      data-thermal={selectedBand ?? undefined}
    >
      <div className="game-rating-heading">
        {/* The panel's meter swings to the pressed value as it is saved. */}
        <GameMeter className="game-rating-meter" value={selected} />
        <div>
          <h2 id={titleId} className="game-rating-title">
            Tu puntuación
          </h2>
          <p className="game-rating-subtitle" role="status">
            {subtitle}
          </p>
        </div>
      </div>

      {notice !== null ? (
        <p className="game-rating-notice" role="status">
          {outcomeNotices[notice]}
        </p>
      ) : null}

      <RatingKeypad
        ref={scaleRef}
        value={selected}
        disabled={!eligible}
        describedBy={feedback ? "rating-feedback" : undefined}
        onPick={(value) => {
          setFeedback(null);
          save(value);
        }}
      />

      {feedback?.tone === "success" ? (
        // The pressed value already shows the result; announce it without visible text.
        <p id="rating-feedback" className="sr-only" role="status">
          {feedback.text}
        </p>
      ) : null}

      {feedback?.tone === "error" ? (
        <div
          id="rating-feedback"
          className="game-rating-feedback game-rating-feedback-error"
          role="alert"
        >
          <p>{failureMessages[feedback.error.kind]}</p>
          {feedback.error.kind === "ambiguous" ? (
            <button
              type="button"
              className="button"
              onClick={checkCurrentRating}
            >
              Comprobar mi nota
            </button>
          ) : null}
          {feedback.error.correlationId ? (
            <p className="game-rating-footnote">
              Referencia para soporte: {feedback.error.correlationId}
            </p>
          ) : null}
        </div>
      ) : null}

      {persisted !== null ? (
        <div className="game-rating-actions">
          <button
            type="button"
            className="button rating-remove"
            disabled={busy}
            onClick={remove}
          >
            Eliminar puntuación
          </button>
        </div>
      ) : null}
    </section>
  );
}
