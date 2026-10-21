import { useEffect, useId, useRef, useState } from "react";

import { platformIcon } from "../../shared/catalogue/taxonomy-icons";
import { GameMeter } from "../../shared/score/game-meter";
import { thermalBand, thermalLabels } from "../../shared/score/thermal-band";
import { SelectIcon } from "../../shared/ui/select-icon";
import type { GameDetails } from "../game-details/game-details-api";
import type { PersonalRating, RatingCommandError, RatingWrite } from "./personal-rating-api";
import { formatPersonalScore, platformOptions, type PlatformOption } from "./personal-score";
import { RatingKeypad } from "./rating-keypad";
import { useRatingCommand } from "./use-personal-rating";

/** The exposed contribution: the scored platforms' mean, half up to one decimal. */
function platformMean(values: readonly number[]): number {
  const sum = values.reduce((total, value) => total + value, 0);
  // Integer arithmetic keeps a half-way mean such as 8,25 exact before it rounds up.
  return Math.floor((20 * sum + values.length) / (2 * values.length)) / 10;
}

const failures: Record<RatingCommandError["kind"], string> = {
  authentication: "Tu sesión ha caducado. Vuelve a iniciar sesión para guardar tus plataformas.",
  csrf: "No se pudo verificar la solicitud. Vuelve a intentarlo; si persiste, recarga la página.",
  conflict:
    "Tu puntuación cambió desde otra sesión. Cierra y vuelve a abrir para ver la versión actual.",
  validation: "Revisa tu selección: cada nota debe ser un número entero del 1 al 10.",
  platform: "Una de las plataformas ya no figura en este juego. Quítala y vuelve a guardar.",
  ineligible: "Este juego ya no admite cambios de puntuación.",
  "rate-limited": "Demasiadas solicitudes seguidas. Espera un momento y vuelve a intentarlo.",
  unavailable: "No se pudo guardar. Tu puntuación anterior se conserva; inténtalo más tarde.",
  ambiguous:
    "No sabemos si el cambio se aplicó. Cierra y comprueba tu puntuación antes de repetirlo.",
};

/**
 * Rates one game platform by platform: which platforms the player played on and, optionally, a
 * score on each. A rating still casts one community vote, so the result shown is the player's
 * own Gameómetro score: the mean of the scored platforms, or the general score when none is
 * scored. Nothing is sent until **Guardar**, and the command always names the complete set.
 *
 * <p>A native modal dialog: a bottom sheet on phones, a centred sheet wider up. Its list is the
 * only part that scrolls, while the reading and the actions stay in reach. Escape and **Cancelar**
 * close it without a command; the caller returns focus to the reading that opened it.
 */
export function PlatformRatingDialog({
  game,
  rating,
  csrfToken,
  onClose,
}: {
  game: Pick<GameDetails, "gameId" | "canonicalTitle" | "releases">;
  rating: PersonalRating | null;
  csrfToken: string;
  onClose: (saved: PersonalRating | null) => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const leadId = useId();
  const feedbackId = useId();
  const keypadIdBase = useId();
  const options = platformOptions(game, rating);
  const [played, setPlayed] = useState<ReadonlyMap<string, number | null>>(
    () => new Map(rating?.platforms.map((entry) => [entry.platform.platformId, entry.value])),
  );
  const [general, setGeneral] = useState<number | null>(rating?.value ?? null);
  const [scoring, setScoring] = useState<string | null>(null);
  const [failure, setFailure] = useState<RatingCommandError | null>(null);
  const savedRef = useRef<PersonalRating | null>(null);
  const command = useRatingCommand(game.gameId);
  const busy = command.isPending;

  useEffect(() => {
    const element = dialog.current;
    if (!element || element.open) return;
    element.showModal?.();
    // Opening lands on the first platform rather than the close control.
    element.querySelector<HTMLInputElement>('input[type="checkbox"]')?.focus();
  }, []);

  const chosen = options.filter((option) => played.has(option.platformId));
  const scores = chosen.flatMap((option) => {
    const value = played.get(option.platformId);
    return value === null || value === undefined ? [] : [value];
  });
  const contribution = scores.length > 0 ? platformMean(scores) : general;
  const band = contribution === null ? null : thermalBand(contribution);
  const write: RatingWrite = {
    ...(scores.length === 0 && general !== null ? { value: general } : {}),
    platforms: chosen.map((option) => {
      const value = played.get(option.platformId);
      return value === null || value === undefined
        ? { platformId: option.platformId }
        : { platformId: option.platformId, value };
    }),
  };
  const unchanged =
    rating !== null &&
    (scores.length > 0 ? rating.value === null : rating.value === general) &&
    rating.platforms.length === chosen.length &&
    rating.platforms.every(
      (entry) => played.get(entry.platform.platformId) === entry.value,
    );
  const canSave = contribution !== null && !unchanged && !busy;

  function close() {
    dialog.current?.close();
  }

  function toggle(option: PlatformOption, checked: boolean) {
    setFailure(null);
    setPlayed((previous) => {
      const next = new Map(previous);
      if (checked) next.set(option.platformId, null);
      else next.delete(option.platformId);
      return next;
    });
    if (!checked && scoring === option.platformId) setScoring(null);
  }

  function score(platformId: string, value: number | null) {
    setFailure(null);
    setPlayed((previous) => new Map(previous).set(platformId, value));
    setScoring(null);
    // The score button the keypad belongs to takes focus back.
    const index = options.findIndex((option) => option.platformId === platformId);
    requestAnimationFrame(() => document.getElementById(`${keypadIdBase}-${index}-score`)?.focus());
  }

  function save() {
    if (!canSave) return;
    setFailure(null);
    command.mutate(
      // The precondition comes from the rating this sheet edits, wherever it was read.
      { type: "save", csrfToken, write, ...(rating === null ? {} : { currentRating: rating }) },
      {
        onSuccess: (outcome) => {
          if (outcome.type === "saved") savedRef.current = outcome.rating;
          close();
        },
        onError: (error) => setFailure(error),
      },
    );
  }

  const source =
    contribution === null
      ? "Elige una puntuación general o puntúa alguna plataforma."
      : scores.length === 0
        ? "Tu puntuación general"
        : scores.length === 1
          ? "La nota de tu plataforma puntuada"
          : `Media de tus ${scores.length} plataformas puntuadas`;
  // Scoring a platform replaces a saved general score: say so before it is saved.
  const replacesGeneral = scores.length > 0 && rating?.value !== null && rating?.value !== undefined;

  return (
    <dialog
      aria-describedby={leadId}
      aria-labelledby={titleId}
      className="platform-rating-dialog"
      onCancel={(event) => {
        if (busy) event.preventDefault();
      }}
      onClose={() => onClose(savedRef.current)}
      ref={dialog}
    >
      <div className="platform-rating-sheet" aria-busy={busy}>
        <header className="platform-rating-header">
          <h2 id={titleId}>Puntuar por plataforma</h2>
          <button
            aria-label="Cerrar sin guardar"
            className="platform-rating-close"
            disabled={busy}
            onClick={close}
            type="button"
          />
        </header>

        <div className="platform-rating-body">
          <p className="platform-rating-lead" id={leadId}>
            Marca dónde has jugado a {game.canonicalTitle}. Puntuar cada plataforma es opcional.
          </p>

          <fieldset className="platform-rating-options">
            <legend className="sr-only">Plataformas en las que has jugado</legend>
            <ul>
              {options.map((option, index) => {
                const checked = played.has(option.platformId);
                const value = played.get(option.platformId) ?? null;
                const keypadId = `${keypadIdBase}-${index}`;
                const open = scoring === option.platformId;
                const platformBand = value === null ? null : thermalBand(value);
                return (
                  <li
                    className="platform-rating-option"
                    data-checked={checked || undefined}
                    key={option.platformId}
                  >
                    <label className="platform-rating-check">
                      <input
                        checked={checked}
                        disabled={busy}
                        onChange={(event) => toggle(option, event.currentTarget.checked)}
                        type="checkbox"
                      />
                      <SelectIcon name={platformIcon(option.platformId, option.name)} />
                      <span>{option.name}</span>
                    </label>
                    {checked ? (
                      <button
                        aria-controls={open ? keypadId : undefined}
                        aria-expanded={open}
                        aria-label={
                          value === null
                            ? `Puntuar ${option.name}`
                            : `Nota en ${option.name}: ${value} de 10. Cambiar`
                        }
                        className="platform-rating-score"
                        id={`${keypadId}-score`}
                        data-thermal={platformBand ?? undefined}
                        disabled={busy}
                        onClick={() => setScoring(open ? null : option.platformId)}
                        type="button"
                      >
                        {value === null ? <span>Puntuar</span> : <strong>{value}</strong>}
                        <span aria-hidden="true" className="game-rating-caret" />
                      </button>
                    ) : null}
                    {checked && open ? (
                      <div className="platform-rating-keypad" id={keypadId}>
                        <RatingKeypad
                          label={`Nota en ${option.name} del 1 al 10`}
                          onPick={(picked) => score(option.platformId, picked)}
                          value={value}
                        />
                        {value === null ? null : (
                          <button
                            className="button rating-remove"
                            onClick={() => score(option.platformId, null)}
                            type="button"
                          >
                            Quitar la nota de {option.name}
                          </button>
                        )}
                      </div>
                    ) : null}
                  </li>
                );
              })}
            </ul>
          </fieldset>

          {scores.length === 0 ? (
            <section aria-labelledby={`${titleId}-general`} className="platform-rating-general">
              <h3 id={`${titleId}-general`}>Tu puntuación general</h3>
              <RatingKeypad
                disabled={busy}
                label="Tu puntuación general del 1 al 10"
                onPick={(picked) => {
                  setFailure(null);
                  setGeneral(picked);
                }}
                value={general}
              />
            </section>
          ) : null}
        </div>

        <footer className="platform-rating-footer">
          <div className="platform-rating-result" data-thermal={band ?? undefined}>
            <GameMeter className="platform-rating-meter" value={contribution} />
            <p aria-live="polite">
              <span className="platform-rating-result-label">
                Tu puntuación {band ? <b>{thermalLabels[band]}</b> : null}
              </span>
              <strong>
                {contribution === null ? "—" : `${formatPersonalScore(contribution)}/10`}
              </strong>
              <span className="platform-rating-result-source">
                {source}
                {replacesGeneral ? ". Sustituye a tu puntuación general" : ""}
              </span>
            </p>
          </div>
          {failure ? (
            <div
              className="game-rating-feedback game-rating-feedback-error"
              id={feedbackId}
              role="alert"
            >
              <p>{failures[failure.kind]}</p>
              {failure.correlationId ? (
                <p className="game-rating-footnote">Referencia: {failure.correlationId}</p>
              ) : null}
            </div>
          ) : null}
          {busy ? (
            <p className="sr-only" role="status">
              Guardando tus plataformas…
            </p>
          ) : null}
          <div className="platform-rating-actions">
            <button className="button" disabled={busy} onClick={close} type="button">
              Cancelar
            </button>
            <button
              aria-describedby={failure ? feedbackId : undefined}
              className="button button-primary"
              disabled={!canSave}
              onClick={save}
              type="button"
            >
              Guardar
            </button>
          </div>
        </footer>
      </div>
    </dialog>
  );
}
