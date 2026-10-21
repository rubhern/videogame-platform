import { useQuery } from "@tanstack/react-query";
import { useEffect, useId, useRef, useState } from "react";
import { useLocation, useSearchParams } from "react-router-dom";

import { assignLocation } from "../../shared/browser/navigate";
import { platformIcon } from "../../shared/catalogue/taxonomy-icons";
import { GameMeter } from "../../shared/score/game-meter";
import { thermalBand, thermalLabels } from "../../shared/score/thermal-band";
import type { GameDetails } from "../game-details/game-details-api";
import {
  getPendingRatingIntent,
  ratingIntentStartUrl,
} from "../session/rating-intent";
import { useSession } from "../session/use-session";
import { SelectIcon } from "../../shared/ui/select-icon";
import type { PersonalRating, RatingCommandError } from "./personal-rating-api";
import {
  contributionSource,
  formatPersonalScore,
  platformOptions,
  scoredPlatforms,
} from "./personal-score";
import { PlatformRatingDialog } from "./platform-rating-dialog";
import { RatingKeypad } from "./rating-keypad";
import { useRatingCommand, usePersonalRating } from "./use-personal-rating";

const ineligibleReasons: Record<
  GameDetails["ratingEligibility"]["reason"],
  string
> = {
  ELIGIBLE_RELEASE_FOUND: "Todavía no se puede puntuar este juego.",
  NO_COMMERCIAL_RELEASE:
    "Puntuación no disponible: Aún no tenemos un lanzamiento",
  RELEASE_NOT_OCCURRED:
    "Puntuación no disponible: Aún no ha salido",
  RELEASE_CANCELLED:
    "Puntuación no disponible: Juego cancelado",
  RELEASE_DATE_UNCERTAIN:
    "Puntuación no disponible: Fecha incierta",
  RELEASE_REVIEW_REQUIRED:
    "Puntuación no disponible: El juego necesita revisión",
};

type Outcome = "expired" | "cancelled" | "invalid";

const outcomeNotices: Record<Outcome | "by-platform", string> = {
  expired: "La selección para puntuar caducó. Vuelve a elegir tu puntuación.",
  cancelled: "No se completó el inicio de sesión. Tu puntuación no se ha guardado.",
  invalid: "La puntuación seleccionada no era válida. Elige un valor del 1 al 10.",
  "by-platform":
    "Ya tenías este juego puntuado por plataforma, así que no hemos cambiado nada. Revisa tus plataformas si quieres otra puntuación.",
};

function isOutcome(value: string | null): value is Outcome {
  return value === "expired" || value === "cancelled" || value === "invalid";
}

const failureMessages: Record<RatingCommandError["kind"], string> = {
  authentication:
    "Tu sesión ha caducado. Vuelve a elegir la puntuación para iniciar sesión de nuevo.",
  csrf: "No se pudo verificar la solicitud. Vuelve a intentarlo; si persiste, recarga la página.",
  conflict:
    "Tu puntuación cambió desde otra sesión. Se muestra la versión actual; revísala y vuelve a intentarlo.",
  validation: "La puntuación debe ser un número entero del 1 al 10.",
  platform: "Una de tus plataformas ya no figura en este juego. Revisa tus plataformas.",
  ineligible: "Este juego ya no admite nuevas puntuaciones.",
  "rate-limited":
    "Demasiadas solicitudes seguidas. Espera un momento y vuelve a intentarlo.",
  unavailable:
    "No se pudo guardar el cambio. Tu puntuación anterior se conserva; inténtalo de nuevo más tarde.",
  ambiguous:
    "No sabemos si el cambio se aplicó. Comprueba tu puntuación actual antes de volver a intentarlo.",
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
 *
 * <p>Post-MVP (#359) a signed-in player may also rate by platform. Picking on the keypad stays the
 * quick general score and never touches saved platforms. **Puntuar por plataforma** opens a modal
 * sheet for the played platforms and their optional scores; once any platform is scored, the
 * reading is their mean and the panel shows that breakdown instead of the keypad, so a general
 * pick can never silently replace platform scores. A recovered pick after sign-in is likewise
 * not applied over platform scores; a notice says so.
 */
export function GameRatingPanel({ game }: { game: GameDetails }) {
  const titleId = useId();
  const panelId = useId();
  const statusId = useId();
  const feedbackId = useId();
  const [searchParams, setSearchParams] = useSearchParams();
  const location = useLocation();
  const session = useSession();
  const csrfToken =
    session.data?.authenticated === true ? session.data.csrfToken : null;
  const authenticated = csrfToken !== null;
  const personal = usePersonalRating(game.gameId, authenticated);
  const command = useRatingCommand(game.gameId);
  const [feedback, setFeedback] = useState<Feedback | null>(null);
  const [open, setOpen] = useState(false);
  const [editingPlatforms, setEditingPlatforms] = useState(false);
  const [platformsSaved, setPlatformsSaved] = useState(false);
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
  // Whether this visit returned from sign-in, kept after the single-use marker is consumed.
  const [returnedFromSignIn] = useState(marker === "resumed");
  const notice = isOutcome(marker) ? marker : null;

  const eligibility = game.ratingEligibility;
  const eligible = eligibility.eligible;
  const persisted = authenticated ? (personal.data ?? null) : null;
  const busy = command.isPending;
  const checking = session.isPending || (authenticated && personal.isPending);
  const inFlight =
    busy && command.variables?.type === "save" ? (command.variables.write.value ?? null) : null;
  const byPlatform = persisted?.mode === "platforms";
  // A pick made before sign-in is never applied over platform scores; the visit says so instead.
  const skippedResume =
    returnedFromSignIn && byPlatform && (resume.data?.value ?? null) !== null && !platformsSaved;
  // The reading: the general value being saved, else the recovered one awaiting its automatic
  // save, else the persisted rating's one contribution.
  const selected =
    inFlight ?? (byPlatform ? null : recovered) ?? persisted?.contribution ?? null;
  const band = selected === null ? null : thermalBand(selected);
  // An ineligible game without a rating has nothing to open: the reading states why instead.
  const usable = eligible || persisted !== null;
  const reason = eligible ? null : ineligibleReasons[eligibility.reason];
  // Platform rating needs a session and a game with known platforms, or one already saved.
  const platforms = authenticated && !checking ? platformOptions(game, persisted) : [];

  function clearMarker() {
    if (marker === null) return;
    const next = new URLSearchParams(searchParams);
    next.delete("rating-intent");
    // Consuming the marker keeps the entry's origin, so the way back still names it (#285).
    setSearchParams(next, { replace: true, preventScrollReset: true, state: location.state });
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
      // Only the general score: saved platforms stay as they are.
      { type: "save", csrfToken, write: { value } },
      {
        onSuccess: (outcome) => {
          if (outcome.type !== "saved") return;
          clearMarker();
          setFeedback({
            tone: "success",
            text: `Puntuación guardada: ${formatPersonalScore(outcome.rating.contribution)}/10.`,
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
    if (!byPlatform && recovered !== persisted?.value) save(recovered);
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

  function editPlatforms() {
    setFeedback(null);
    close(false);
    setEditingPlatforms(true);
  }

  function platformsClosed(saved: PersonalRating | null) {
    setEditingPlatforms(false);
    trigger.current?.focus();
    if (saved !== null) {
      setPlatformsSaved(true);
      setFeedback({
        tone: "success",
        text: `Puntuación guardada: ${formatPersonalScore(saved.contribution)}/10.`,
      });
    }
  }

  function checkCurrentRating() {
    setFeedback(null);
    void personal.refetch();
  }

  const status = reason
    ? reason
    : busy
      ? command.variables?.type === "delete"
        ? "Eliminando tu puntuación…"
        : "Guardando tu puntuación…"
      : checking
        ? "Comprobando tu puntuación…"
        : authenticated && personal.isError
          ? "No se pudo comprobar si ya tenías una puntuación. Puedes puntuar igualmente."
          : "";
  // The name follows the visible reading, without its decorative separator, then the action.
  const source = persisted && selected === persisted.contribution ? contributionSource(persisted) : null;
  const reading =
    selected !== null
      ? `Tu puntuación ${band ? `${thermalLabels[band]} ` : ""}${formatPersonalScore(selected)}/10${source ? `, ${source.toLocaleLowerCase("es-ES")}` : ""}`
      : checking
        ? "Tu puntuación: comprobando"
        : "Tu puntuación: sin puntuación";
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
          <strong className="game-rating-value">{formatPersonalScore(selected)}/10</strong>
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

      {notice !== null || skippedResume ? (
        <p className="game-rating-notice" role="status">
          {outcomeNotices[skippedResume ? "by-platform" : (notice ?? "invalid")]}
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
              Comprobar mi puntuación
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
          {byPlatform && persisted !== null ? (
            <PlatformBreakdown rating={persisted} reason={reason} />
          ) : (
            <>
              <p className="game-rating-prompt">{reason ?? "Selecciona una puntuación"}</p>
              <RatingKeypad
                describedBy={feedback?.tone === "error" ? feedbackId : undefined}
                disabled={!eligible || busy}
                onPick={pick}
                value={selected}
              />
            </>
          )}
          {platforms.length > 0 ? (
            <div className="game-rating-platforms">
              {byPlatform || persisted === null ? null : <PlayedPlatforms rating={persisted} />}
              <button
                className="button game-rating-platforms-edit"
                disabled={!eligible || busy}
                onClick={editPlatforms}
                type="button"
              >
                {persisted !== null && persisted.platforms.length > 0
                  ? "Editar plataformas"
                  : "Puntuar por plataforma"}
              </button>
            </div>
          ) : null}
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

      {editingPlatforms && csrfToken !== null ? (
        <PlatformRatingDialog
          csrfToken={csrfToken}
          game={game}
          onClose={platformsClosed}
          rating={persisted}
        />
      ) : null}
    </section>
  );
}

/** A platform-derived rating in the panel: where its mean comes from, platform by platform. */
function PlatformBreakdown({ rating, reason }: { rating: PersonalRating; reason: string | null }) {
  return (
    <div className="game-rating-breakdown">
      <p className="game-rating-prompt">
        {reason ?? "Tu puntuación es la media de las plataformas que has puntuado."}
      </p>
      <ul aria-label="Tus notas por plataforma">
        {scoredPlatforms(rating).map((entry) => {
          const band = thermalBand(entry.value);
          return (
            <li key={entry.platform.platformId}>
              <SelectIcon name={platformIcon(entry.platform.platformId, entry.platform.name)} />
              <span>{entry.platform.name}</span>
              <strong data-thermal={band ?? undefined}>{entry.value}</strong>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

/** The platforms a direct rating was played on, as one quiet line. */
function PlayedPlatforms({ rating }: { rating: PersonalRating }) {
  if (rating.platforms.length === 0) return null;
  return (
    <p className="game-rating-played">
      <span>Jugado en</span> {rating.platforms.map((entry) => entry.platform.name).join(" · ")}
    </p>
  );
}
