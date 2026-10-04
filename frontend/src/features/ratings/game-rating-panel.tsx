import { useQuery } from "@tanstack/react-query";
import { useEffect, useId, useRef, useState } from "react";
import { useSearchParams } from "react-router-dom";

import { assignLocation } from "../../shared/browser/navigate";
import { GameMeter } from "../../shared/score/game-meter";
import { thermalBand, thermalLabels } from "../../shared/score/thermal-band";
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
  ELIGIBLE_RELEASE_FOUND: "Todavía no se puede puntuar este juego.",
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
    "Tu sesión ha caducado. Vuelve a elegir la nota para iniciar sesión de nuevo.",
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
 * Personal rating for one game (UC-005, UC-006, UC-007) as a compact reading that opens the
 * Gameómetro keypad.
 *
 * <p>The rating belongs to the game, never to a release. The reading is a button with a caret: it
 * opens an anchored, non-modal panel that overlays what follows. Picking a value saves it at once
 * and closes the panel, so there is no separate confirmation, and arrow keys only move focus.
 * Escape, an outside press or moving focus away close the panel without a command. Outcomes stay
 * beside the reading — one live status and one alert — so they never depend on the panel being
 * open. An ineligible game keeps the reading disabled and states the reason, unless a rating
 * already exists, which can still be deleted. An anonymous pick starts authentication at the
 * rating boundary, and the recovered value is persisted once automatically on return. Commands are
 * conditional (`If-None-Match: *` / `If-Match`), never retried automatically, and a rejected or
 * ambiguous command keeps the last valid personal and community state visible.
 */
export function GameRatingPanel({ game }: { game: GameDetails }) {
  const titleId = useId();
  const panelId = useId();
  const statusId = useId();
  const feedbackId = useId();
  const [searchParams, setSearchParams] = useSearchParams();
  const session = useSession();
  const csrfToken =
    session.data?.authenticated === true ? session.data.csrfToken : null;
  const authenticated = csrfToken !== null;
  const personal = usePersonalRating(game.gameId, authenticated);
  const command = useRatingCommand(game.gameId);
  const [feedback, setFeedback] = useState<Feedback | null>(null);
  const [open, setOpen] = useState(false);
  const trigger = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLDivElement>(null);
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

  const eligibility = game.ratingEligibility;
  const eligible = eligibility.eligible;
  const persisted = authenticated ? (personal.data ?? null) : null;
  const busy = command.isPending;
  const checking = session.isPending || (authenticated && personal.isPending);
  const inFlight =
    busy && command.variables?.type === "save" ? command.variables.value : null;
  // The reading: the value being saved, else the recovered one awaiting its automatic save, else
  // the persisted rating.
  const selected = inFlight ?? recovered ?? persisted?.value ?? null;
  const band = selected === null ? null : thermalBand(selected);
  // An ineligible game without a rating has nothing to open: the reading states why instead.
  const usable = eligible || persisted !== null;
  const reason = eligible ? null : ineligibleReasons[eligibility.reason];

  function clearMarker() {
    if (marker === null) return;
    const next = new URLSearchParams(searchParams);
    next.delete("rating-intent");
    setSearchParams(next, { replace: true, preventScrollReset: true });
  }

  function close(returnFocus: boolean) {
    setOpen(false);
    if (returnFocus) trigger.current?.focus();
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

  // Opening lands on the pressed value (or the scale's first stop), so arrows browse from the
  // current rating; a disabled scale hands focus to the first action the panel offers.
  useEffect(() => {
    if (!open) return;
    const target =
      panel.current?.querySelector<HTMLButtonElement>('.rating-option[tabindex="0"]:not(:disabled)') ??
      panel.current?.querySelector<HTMLButtonElement>("button:not(:disabled)");
    (target ?? panel.current)?.focus();
  }, [open]);

  useEffect(() => {
    if (!open) return;
    function onKeyDown(event: KeyboardEvent) {
      if (event.key !== "Escape") return;
      setOpen(false);
      trigger.current?.focus();
    }
    function onPointerDown(event: PointerEvent) {
      const target = event.target;
      if (!(target instanceof Node)) return;
      if (!panel.current?.contains(target) && !trigger.current?.contains(target)) {
        setOpen(false);
      }
    }
    document.addEventListener("keydown", onKeyDown);
    document.addEventListener("pointerdown", onPointerDown);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      document.removeEventListener("pointerdown", onPointerDown);
    };
  }, [open]);

  function pick(value: number) {
    setFeedback(null);
    // A pick commits: the panel closes and the reading carries the outcome.
    close(true);
    save(value);
  }

  function remove() {
    if (csrfToken === null || persisted === null || busy) return;
    setFeedback(null);
    close(true);
    command.mutate(
      { type: "delete", csrfToken },
      {
        onSuccess: () => {
          clearMarker();
          setFeedback({ tone: "success", text: "Puntuación eliminada." });
        },
        onError: (error) => setFeedback({ tone: "error", error }),
      },
    );
  }

  function checkCurrentRating() {
    setFeedback(null);
    void personal.refetch();
  }

  const status = reason
    ? reason
    : busy
      ? command.variables?.type === "delete"
        ? "Eliminando tu nota…"
        : "Guardando tu nota…"
      : checking
        ? "Comprobando tu nota…"
        : authenticated && personal.isError
          ? "No se pudo comprobar si ya tenías una nota. Puedes puntuar igualmente."
          : "";
  // The name follows the visible reading, without its decorative separator, then the action.
  const reading =
    selected !== null
      ? `Tu puntuación ${band ? `${thermalLabels[band]} ` : ""}${selected}/10`
      : checking
        ? "Tu puntuación: comprobando"
        : "Tu puntuación: sin nota";
  const action = !usable
    ? "No disponible"
    : selected === null
      ? "Puntuar"
      : eligible
        ? "Cambiar o eliminar"
        : "Eliminar";

  return (
    <section
      aria-busy={busy}
      aria-labelledby={titleId}
      className="game-personal-rating"
      data-thermal={band ?? undefined}
      onBlur={(event) => {
        const next = event.relatedTarget;
        if (open && next instanceof Node && !event.currentTarget.contains(next)) close(false);
      }}
    >
      <h2 className="sr-only" id={titleId}>
        Tu puntuación
      </h2>
      {/* Unavailable stays focusable, so focus is never dropped when a rating disappears and the
          reason is read with the control. */}
      <button
        aria-controls={open ? panelId : undefined}
        aria-describedby={statusId}
        aria-disabled={usable ? undefined : true}
        aria-expanded={open}
        aria-haspopup="dialog"
        aria-label={`${reading}. ${action}`}
        className="game-rating-trigger"
        onClick={() => {
          if (!usable) return;
          if (open) close(false);
          else setOpen(true);
        }}
        ref={trigger}
        type="button"
      >
        {/* The reading's meter swings to the value being saved. */}
        <GameMeter className="game-rating-meter" value={selected} />
        <span className="game-rating-label">
          Tu puntuación {band ? <b>{thermalLabels[band]}</b> : null}
        </span>
        {selected !== null ? (
          <strong className="game-rating-value">{selected}/10</strong>
        ) : (
          <span className="game-rating-cta">
            {!usable ? "No disponible" : checking ? "—" : "Puntuar"}
          </span>
        )}
        {usable ? <span aria-hidden="true" className="game-rating-caret" /> : null}
      </button>

      <p className="game-rating-status" id={statusId} role="status">
        {status}
      </p>

      {notice !== null ? (
        <p className="game-rating-notice" role="status">
          {outcomeNotices[notice]}
        </p>
      ) : null}

      {feedback?.tone === "success" ? (
        // The reading already shows the result; announce it without visible text.
        <p className="sr-only" id={feedbackId} role="status">
          {feedback.text}
        </p>
      ) : null}

      {feedback?.tone === "error" ? (
        <div
          className="game-rating-feedback game-rating-feedback-error"
          id={feedbackId}
          role="alert"
        >
          <p>{failureMessages[feedback.error.kind]}</p>
          {feedback.error.kind === "ambiguous" ? (
            <button className="button" onClick={checkCurrentRating} type="button">
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

      {open ? (
        <div
          aria-label={`Tu puntuación de ${game.canonicalTitle}`}
          className="game-rating-panel"
          id={panelId}
          ref={panel}
          role="dialog"
          tabIndex={-1}
        >
          <p className="game-rating-prompt">{reason ?? "Selecciona una nota"}</p>
          <RatingKeypad
            describedBy={feedback?.tone === "error" ? feedbackId : undefined}
            disabled={!eligible || busy}
            onPick={pick}
            value={selected}
          />
          {persisted !== null ? (
            <div className="game-rating-actions">
              <button
                className="button rating-remove"
                disabled={busy}
                onClick={remove}
                type="button"
              >
                Eliminar puntuación
              </button>
            </div>
          ) : null}
        </div>
      ) : null}
    </section>
  );
}
