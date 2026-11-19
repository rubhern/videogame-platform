import { useEffect, useId, useRef, useState } from "react";
import { Link } from "react-router-dom";

import { assignLocation } from "../../shared/browser/navigate";
import { useGameLinkState } from "../../shared/navigation/use-game-link-state";
import { thermalBand, thermalLabels } from "../../shared/score/thermal-band";
import { SelectIcon } from "../../shared/ui/select-icon";
import { trackEvent } from "../analytics/events";
import { markSignInStart } from "../analytics/sign-in-intent";
import type { GameDetails } from "../game-details/game-details-api";
import type { RatingCommandError } from "../ratings/personal-rating-api";
import { PlatformRatingDialog } from "../ratings/platform-rating-dialog";
import { contributionSource, formatPersonalScore } from "../ratings/personal-score";
import { RatingKeypad } from "../ratings/rating-keypad";
import { ratingIntentStartUrl } from "../session/rating-intent";
import { readingCount, type RankedGame } from "./game-ranking-view-model";
import { quickRateFailures } from "./quick-rate-failures";
import { usePlatformRatingGame } from "./use-platform-rating-game";
import { useQuickRatingCommand, type ListPersonalRating } from "./use-ranking-personal-ratings";

const decimal = new Intl.NumberFormat("es-ES", { minimumFractionDigits: 1, maximumFractionDigits: 1 });


/**
 * Rates a ranking without leaving it (#574): a native modal sheet, at the foot of the screen on
 * phones and centred wider up, that holds one game at a time over the Gameómetro keypad.
 *
 * <p>A pick saves the game's general score at once and the sheet stays on that game, its new score
 * shown in place; **Anterior** and **Siguiente** browse the list in its order and **Eliminar**
 * removes a score. **Por plataforma** opens the
 * platform sheet over this one for the game shown; a game rated platform by platform keeps its
 * keypad closed, since a general pick can never replace platform scores. A visitor's pick starts the existing rating sign-in and is saved on the
 * game's page on return. Escape, the close control or the backdrop close the sheet; the caller
 * returns focus to the stamp of the game last shown.
 */
export function QuickRateSheet({
  games,
  startGameId,
  personalOf,
  csrfToken,
  returnTo,
  onClose,
}: {
  games: readonly RankedGame[];
  startGameId: string;
  personalOf: (gameId: string) => ListPersonalRating;
  csrfToken: string | null;
  /** The list itself, where a visitor comes back after signing in. */
  returnTo: string;
  onClose: (lastGameId: string, changed: boolean) => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const linkState = useGameLinkState();
  const command = useQuickRatingCommand();
  const [index, setIndex] = useState(() => Math.max(0, games.findIndex((game) => game.gameId === startGameId)));
  const [failure, setFailure] = useState<RatingCommandError | null>(null);
  const changed = useRef(false);
  const platformGame = usePlatformRatingGame();
  const [editingPlatforms, setEditingPlatforms] = useState<GameDetails | null>(null);

  const game = games[Math.min(index, games.length - 1)];
  const state = game === undefined ? { status: "checking" as const } : personalOf(game.gameId);
  const rating = state.status === "known" ? state.rating : null;
  const byPlatform = rating?.mode === "platforms";
  const busy = command.isPending || platformGame.loading;
  const checking = state.status === "checking";
  const value = rating?.contribution ?? null;
  const band = value === null ? null : thermalBand(value);

  useEffect(() => {
    const element = dialog.current;
    if (!element || element.open) return;
    element.showModal?.();
  }, []);

  // Each game the sheet shows lands on its keypad, at the current score or the scale's start.
  useEffect(() => {
    const element = dialog.current;
    if (!element) return;
    const target =
      element.querySelector<HTMLButtonElement>('.rating-option[tabindex="0"]:not(:disabled)') ??
      element.querySelector<HTMLElement>(".quick-rate-body a, .quick-rate-body button:not(:disabled)");
    target?.focus();
  }, [game?.gameId, checking]);

  if (game === undefined) return null;
  const current = game;

  function close() {
    trackEvent("quick_rating", { list: "topometro", action: "close" });
    // Leave the modal layer first: while it is open the page is inert and cannot take focus.
    dialog.current?.close();
    onClose(current.gameId, changed.current);
  }

  function show(next: number) {
    setFailure(null);
    platformGame.reset();
    setIndex(next);
  }

  async function platforms() {
    if (busy) return;
    trackEvent("quick_rating", { list: "topometro", action: "platforms" });
    setFailure(null);
    const details = await platformGame.load(current.gameId);
    if (details !== null) setEditingPlatforms(details);
  }

  function platformsClosed(saved: unknown) {
    setEditingPlatforms(null);
    if (saved !== null) changed.current = true;
    // Back on the sheet, at the control that opened the platforms.
    requestAnimationFrame(() => dialog.current?.querySelector<HTMLButtonElement>(".quick-rate-by-platform")?.focus());
  }

  function pick(picked: number) {
    if (busy || byPlatform) return;
    if (csrfToken === null) {
      trackEvent("quick_rating", { list: "topometro", action: "sign_in" });
      trackEvent("rating_sign_in");
      markSignInStart("rating");
      assignLocation(ratingIntentStartUrl(current.gameId, current.slug, picked, returnTo));
      return;
    }
    const known = state.status === "known" ? state.rating : null;
    // The same score again: nothing to save.
    if (known !== null && known.value === picked) return;
    setFailure(null);
    command.mutate(
      { type: "save", gameId: current.gameId, csrfToken, current: known, value: picked },
      {
        onSuccess: () => {
          changed.current = true;
          trackEvent("quick_rating", { list: "topometro", action: known === null ? "create" : "update" });
        },
        onError: setFailure,
      },
    );
  }

  function remove() {
    if (csrfToken === null || rating === null || busy) return;
    setFailure(null);
    command.mutate(
      { type: "delete", gameId: current.gameId, csrfToken, current: rating },
      {
        onSuccess: () => {
          changed.current = true;
          trackEvent("quick_rating", { list: "topometro", action: "delete" });
        },
        onError: setFailure,
      },
    );
  }

  const previous = index > 0 ? games[index - 1] : undefined;
  const following = index < games.length - 1 ? games[index + 1] : undefined;
  const gamePath = `/games/${current.gameId}/${current.slug}`;
  const source = rating === null ? null : contributionSource(rating);
  const announcement = busy
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
      className="quick-rate-dialog"
      onCancel={(event) => {
        event.preventDefault();
        if (editingPlatforms === null && !busy) close();
      }}
      onClick={(event) => {
        // A press on the backdrop lands on the dialog itself, outside its sheet.
        if (event.target === event.currentTarget && editingPlatforms === null && !busy) close();
      }}
      ref={dialog}
    >
      <div className="quick-rate-sheet" data-thermal={band ?? undefined}>
        <header className="quick-rate-header">
          <img alt="" className="quick-rate-cover" height="72" src={current.cover.url} width="54" />
          <div className="quick-rate-identity">
            <p className="quick-rate-kicker">Puesto {current.position}</p>
            <h2 id={titleId}>{current.title}</h2>
            <p className="quick-rate-reading">
              {current.reading.source === "gameometro" ? "Comunidad" : "Jugadores IGDB"}{" "}
              <b>{decimal.format(current.reading.value)}</b> · {readingCount(current.reading)}
            </p>
          </div>
          <button aria-label="Cerrar" className="quick-rate-close" onClick={close} type="button" />
        </header>

        <div className="quick-rate-body">
          <div className="quick-rate-mine">
            <p className="quick-rate-mine-label">
              Tu puntuación{band ? <b> · {thermalLabels[band]}</b> : null}
            </p>
            <p className="quick-rate-mine-value">
              {value !== null ? (
                <>
                  <strong key={`${current.gameId}-${value}`}>{formatPersonalScore(value)}</strong>
                  <span>/10</span>
                </>
              ) : (
                <span className="quick-rate-mine-empty">{checking ? "…" : "Sin puntuar"}</span>
              )}
            </p>
            {source === null ? null : <p className="quick-rate-mine-source">{source}</p>}
          </div>

          {byPlatform ? (
            <p className="quick-rate-hint">La puntuaste por plataforma: cámbiala desde sus plataformas.</p>
          ) : (
            <RatingKeypad
              disabled={busy || checking}
              key={current.gameId}
              label={`Tu puntuación de ${current.title}, del 1 al 10`}
              onPick={pick}
              value={rating?.value ?? null}
            />
          )}
          {csrfToken === null ? (
            <p className="quick-rate-hint">Al elegir tu puntuación te pediremos iniciar sesión y la guardaremos.</p>
          ) : null}
          <button
            className="quick-pop-action quick-rate-by-platform"
            data-emphasis={byPlatform ? "primary" : undefined}
            disabled={busy || checking}
            onClick={() => void platforms()}
            type="button"
          >
            <SelectIcon name="platform" />
            {byPlatform ? "Editar plataformas" : "Puntuar por plataforma"}
          </button>

          <p className="quick-rate-status" role="status">
            {announcement}
          </p>
          {failure !== null ? (
            <div className="quick-rate-failure" role="alert">
              <p>{quickRateFailures[failure.kind]}</p>
              {failure.correlationId ? (
                <p className="quick-rate-reference">Referencia para soporte: {failure.correlationId}</p>
              ) : null}
            </div>
          ) : null}
          {platformGame.failed ? (
            <p className="quick-rate-failure" role="alert">
              No se pudieron cargar sus plataformas. Vuelve a intentarlo.
            </p>
          ) : null}
        </div>

        <footer className="quick-rate-footer">
          <button
            aria-label={previous ? `Anterior: ${previous.title}` : "Anterior"}
            className="quick-rate-step"
            data-direction="previous"
            disabled={previous === undefined || busy}
            onClick={() => show(index - 1)}
            type="button"
          >
            <span aria-hidden="true" className="quick-rate-chevron" />
            <span>Anterior</span>
          </button>
          {rating !== null && !byPlatform && csrfToken !== null ? (
            <button
              aria-label={`Eliminar tu puntuación de ${current.title}`}
              className="quick-rate-remove"
              disabled={busy}
              onClick={remove}
              type="button"
            >
              Eliminar
            </button>
          ) : (
            <Link className="quick-rate-page" state={linkState} to={gamePath}>
              Ver ficha
            </Link>
          )}
          <button
            aria-label={following ? `Siguiente: ${following.title}` : "Siguiente"}
            className="quick-rate-step"
            data-direction="next"
            disabled={following === undefined || busy}
            onClick={() => show(index + 1)}
            type="button"
          >
            <span>Siguiente</span>
            <span aria-hidden="true" className="quick-rate-chevron" />
          </button>
        </footer>
      </div>
      {editingPlatforms === null ? null : (
        <PlatformRatingDialog
          csrfToken={csrfToken}
          game={editingPlatforms}
          onClose={platformsClosed}
          rating={rating}
          returnTo={returnTo}
        />
      )}
    </dialog>
  );
}
