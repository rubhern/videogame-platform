import { useEffect, useId, useLayoutEffect, useRef, useState } from "react";

import { assignLocation } from "../../shared/browser/navigate";
import { thermalBand, thermalLabels } from "../../shared/score/thermal-band";
import { SelectIcon } from "../../shared/ui/select-icon";
import { trackEvent } from "../analytics/events";
import { markSignInStart } from "../analytics/sign-in-intent";
import type { GameDetails } from "../game-details/game-details-api";
import type { RatingCommandError } from "../ratings/personal-rating-api";
import { contributionSource, formatPersonalScore } from "../ratings/personal-score";
import { RatingKeypad } from "../ratings/rating-keypad";
import { ratingIntentStartUrl } from "../session/rating-intent";
import type { RankedGame } from "./game-ranking-view-model";
import { quickRateFailures } from "./quick-rate-failures";
import { usePlatformRatingGame } from "./use-platform-rating-game";
import { useQuickRatingCommand, type ListPersonalRating } from "./use-ranking-personal-ratings";

/** The gap between the stamp and the card, and the card's distance from the viewport's edges. */
const GAP = 12;
const EDGE = 16;
const WIDE = "(min-width: 620px)";

/**
 * Places the card beside the stamp that opened it, on the side with room, centred on the stamp
 * and kept inside the viewport. Phones leave it to the stylesheet: a sheet at the screen's foot.
 */
function anchorTo(card: HTMLDialogElement, anchor: HTMLElement | null) {
  if (anchor === null || !window.matchMedia?.(WIDE).matches) {
    delete card.dataset.side;
    card.style.removeProperty("left");
    card.style.removeProperty("top");
    card.style.removeProperty("--pointer-y");
    return;
  }
  const stamp = anchor.getBoundingClientRect();
  const { width, height } = card.getBoundingClientRect();
  const fitsLeft = stamp.left - GAP - width >= EDGE;
  const left = fitsLeft
    ? stamp.left - GAP - width
    : Math.min(stamp.right + GAP, window.innerWidth - width - EDGE);
  const centre = stamp.top + stamp.height / 2;
  const top = Math.min(Math.max(centre - height / 2, EDGE), Math.max(EDGE, window.innerHeight - height - EDGE));
  card.dataset.side = fitsLeft ? "left" : "right";
  card.style.left = `${Math.round(left)}px`;
  card.style.top = `${Math.round(top)}px`;
  // The pointer keeps aiming at the stamp when the card is clamped to the viewport.
  card.style.setProperty("--pointer-y", `${Math.round(Math.min(Math.max(centre - top, 20), height - 20))}px`);
}

/**
 * Scores one ranked game in a single press (#574): a small card beside the player's stamp on wide
 * screens and a short sheet at the foot of phones, holding only the keypad. A pick saves the
 * game's general score and closes the card; picking the saved value again closes it without a
 * command. **Por plataforma** hands over to the platform sheet and **Puntuar varios** to the sheet that
 * rates the list one game after another, both starting at this game.
 *
 * <p>A game rated platform by platform keeps its keypad closed: a general pick can never replace
 * platform scores, so its platforms are edited instead. A visitor's pick starts the existing
 * rating sign-in. Escape, the close control or the backdrop close the card; the caller returns
 * focus to the stamp.
 */
export function QuickRatePop({
  game,
  anchor,
  state,
  csrfToken,
  returnTo,
  onClose,
  onPlatforms,
  onRun,
}: {
  game: RankedGame;
  anchor: HTMLElement | null;
  state: ListPersonalRating;
  csrfToken: string | null;
  /** The list itself, where a visitor comes back after signing in. */
  returnTo: string;
  onClose: (changed: boolean) => void;
  onPlatforms: (details: GameDetails) => void;
  onRun: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const command = useQuickRatingCommand();
  const platformGame = usePlatformRatingGame();
  const [failure, setFailure] = useState<RatingCommandError | null>(null);

  const rating = state.status === "known" ? state.rating : null;
  const byPlatform = rating?.mode === "platforms";
  const checking = state.status === "checking";
  const busy = command.isPending || platformGame.loading;
  const value = rating?.contribution ?? null;
  const band = value === null ? null : thermalBand(value);
  const source = rating === null ? null : contributionSource(rating);

  useLayoutEffect(() => {
    const element = dialog.current;
    if (!element) return;
    if (!element.open) element.showModal?.();
    anchorTo(element, anchor);
    const follow = () => anchorTo(element, anchor);
    window.addEventListener("resize", follow);
    return () => window.removeEventListener("resize", follow);
  }, [anchor]);

  // The card opens on its keypad, at the saved score or the scale's start.
  useEffect(() => {
    const element = dialog.current;
    if (!element) return;
    const target =
      element.querySelector<HTMLButtonElement>('.rating-option[tabindex="0"]:not(:disabled)') ??
      element.querySelector<HTMLButtonElement>(".quick-pop-actions button:not(:disabled)");
    target?.focus();
  }, [checking]);

  function leave() {
    // Leave the modal layer first: while it is open the page is inert and cannot take focus.
    dialog.current?.close();
  }

  function close(changed: boolean) {
    if (!changed) trackEvent("quick_rating", { list: "topometro", action: "close" });
    leave();
    onClose(changed);
  }

  function pick(picked: number) {
    if (busy || byPlatform) return;
    if (csrfToken === null) {
      trackEvent("quick_rating", { list: "topometro", action: "sign_in" });
      trackEvent("rating_sign_in");
      markSignInStart("rating");
      assignLocation(ratingIntentStartUrl(game.gameId, game.slug, picked, returnTo));
      return;
    }
    if (rating !== null && rating.value === picked) {
      close(false);
      return;
    }
    setFailure(null);
    command.mutate(
      { type: "save", gameId: game.gameId, csrfToken, current: rating, value: picked },
      {
        onSuccess: () => {
          trackEvent("quick_rating", { list: "topometro", action: rating === null ? "create" : "update" });
          close(true);
        },
        onError: setFailure,
      },
    );
  }

  function remove() {
    if (csrfToken === null || rating === null || busy) return;
    setFailure(null);
    command.mutate(
      { type: "delete", gameId: game.gameId, csrfToken, current: rating },
      {
        onSuccess: () => {
          trackEvent("quick_rating", { list: "topometro", action: "delete" });
          close(true);
        },
        onError: setFailure,
      },
    );
  }

  async function platforms() {
    if (busy) return;
    trackEvent("quick_rating", { list: "topometro", action: "platforms" });
    const details = await platformGame.load(game.gameId);
    if (details === null) return;
    leave();
    onPlatforms(details);
  }

  function run() {
    if (busy) return;
    trackEvent("quick_rating", { list: "topometro", action: "run" });
    leave();
    onRun();
  }

  const status = command.isPending
    ? command.variables?.type === "delete"
      ? "Eliminando tu puntuación…"
      : "Guardando tu puntuación…"
    : platformGame.loading
      ? "Cargando sus plataformas…"
      : checking
        ? "Comprobando tu puntuación…"
        : "";

  return (
    <dialog
      aria-labelledby={titleId}
      className="quick-rate-dialog quick-pop"
      onCancel={(event) => {
        event.preventDefault();
        if (!busy) close(false);
      }}
      onClick={(event) => {
        // A press on the backdrop lands on the dialog itself, outside its card.
        if (event.target === event.currentTarget && !busy) close(false);
      }}
      ref={dialog}
    >
      <div className="quick-pop-card" data-thermal={band ?? undefined}>
        <header className="quick-pop-header">
          <p className="quick-rate-kicker">Puesto {game.position}</p>
          <h2 id={titleId}>{game.title}</h2>
          <button aria-label="Cerrar" className="quick-rate-close" onClick={() => close(false)} type="button" />
        </header>

        {value !== null ? (
          <div className="quick-pop-mine">
            <p>
              Tu puntuación
              {band ? <b>{thermalLabels[band]}</b> : null}
              {source === null ? null : <span>{source}</span>}
            </p>
            <strong key={value}>{formatPersonalScore(value)}</strong>
            {byPlatform || csrfToken === null ? null : (
              <button
                aria-label={`Eliminar tu puntuación de ${game.title}`}
                className="quick-rate-remove"
                disabled={busy}
                onClick={remove}
                type="button"
              >
                Eliminar
              </button>
            )}
          </div>
        ) : null}

        {byPlatform ? (
          <p className="quick-rate-hint">La puntuaste por plataforma: cámbiala desde sus plataformas.</p>
        ) : (
          <RatingKeypad
            disabled={busy || checking}
            label={`Tu puntuación de ${game.title}, del 1 al 10`}
            onPick={pick}
            value={rating?.value ?? null}
          />
        )}
        {csrfToken === null ? (
          <p className="quick-rate-hint">Al elegir tu puntuación te pediremos iniciar sesión y la guardaremos.</p>
        ) : null}

        <p className="quick-rate-status" role="status">
          {status}
        </p>
        {failure === null ? null : (
          <div className="quick-rate-failure" role="alert">
            <p>{quickRateFailures[failure.kind]}</p>
            {failure.correlationId ? (
              <p className="quick-rate-reference">Referencia para soporte: {failure.correlationId}</p>
            ) : null}
          </div>
        )}
        {platformGame.failed ? (
          <p className="quick-rate-failure" role="alert">
            No se pudieron cargar sus plataformas. Vuelve a intentarlo.
          </p>
        ) : null}

        <footer className="quick-pop-actions">
          <button
            className="quick-pop-action"
            data-emphasis={byPlatform ? "primary" : undefined}
            disabled={busy || checking}
            onClick={() => void platforms()}
            type="button"
          >
            <SelectIcon name="platform" />
            {byPlatform ? "Editar plataformas" : "Por plataforma"}
          </button>
          <button className="quick-pop-action" disabled={busy} onClick={run} type="button">
            Puntuar varios
            <span aria-hidden="true" className="quick-rate-chevron" />
          </button>
        </footer>
      </div>
    </dialog>
  );
}
