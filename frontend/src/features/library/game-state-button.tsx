import { useEffect, useId, useRef, useState, type ReactNode } from "react";

import { LIBRARY_STATES, libraryStateLabels, type LibraryState } from "./library-api";
import { useGameStateCommand } from "./use-game-states";

/** The state one press gives a game without a state, once it has come out. */
const QUICK_STATE: LibraryState = "playing";
/** Until a release has occurred, Deseado is the only state. */
const UNRELEASED_STATES: readonly LibraryState[] = ["wished"];

type GameStateButtonProps = {
  gameId: string;
  title: string;
  /** Undefined while unknown; null when the game is not in the library. */
  state: LibraryState | null | undefined;
  csrfToken: string | null;
  /** The game has a release that has occurred; otherwise only Deseado can be chosen. */
  released: boolean;
  /** Offered, never applied, after the player marks the game Completado or Abandonado. */
  ratingOffer?: ReactNode;
  /** A row's chip: the state's colour and a caret instead of the cover-wide pill and pencil. */
  compact?: boolean;
  onChanged?: (state: LibraryState | null) => void;
};

function PencilIcon() {
  return (
    <svg aria-hidden="true" className="library-state-icon" fill="none" viewBox="0 0 20 20">
      <path d="M13.5 3.5 16.5 6.5 7 16H4v-3l9.5-9.5Z" stroke="currentColor" strokeLinejoin="round" strokeWidth="1.5" />
    </svg>
  );
}

function CaretIcon() {
  return (
    <svg aria-hidden="true" className="library-state-icon" fill="none" viewBox="0 0 20 20">
      <path d="m5.5 8 4.5 4.5L14.5 8" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.75" />
    </svg>
  );
}

function CheckIcon() {
  return (
    <svg aria-hidden="true" className="library-state-icon" fill="none" viewBox="0 0 20 20">
      <path d="m4.5 10.5 3.5 3.5 7.5-8" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" />
    </svg>
  );
}

function TrashIcon() {
  return (
    <svg aria-hidden="true" className="library-state-icon" fill="none" viewBox="0 0 20 20">
      <path d="M4 6h12M8 6V4h4v2m-6 0 .7 10h6.6L14 6M8.5 9v4.5m3-4.5v4.5" stroke="currentColor"
        strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.5" />
    </svg>
  );
}

/**
 * The game page's state control under the cover. Without a state, the main button marks the
 * game Jugando in one press and its caret opens every state; with a state, the button names it
 * and opens the same choice, which can also remove it. Before the game comes out the one press
 * marks it Deseado, there is no caret, and the choice offers only Deseado.
 */
export function GameStateButton({
  gameId, title, state, csrfToken, released, ratingOffer, compact = false, onChanged,
}: GameStateButtonProps) {
  const titleId = useId();
  const dialog = useRef<HTMLDialogElement>(null);
  const main = useRef<HTMLButtonElement>(null);
  const [open, setOpen] = useState(false);
  const command = useGameStateCommand(onChanged);

  useEffect(() => {
    const element = dialog.current;
    if (!open || !element || element.open) return;
    element.showModal?.();
    // Opening lands on the current state, or on the first one without a state.
    (element.querySelector<HTMLButtonElement>("[aria-pressed='true']")
      ?? element.querySelector<HTMLButtonElement>(".library-state-option"))?.focus();
  }, [open]);

  if (csrfToken === null) return null;
  const quick = released ? QUICK_STATE : "wished";
  // A state the game already has stays listed so the current choice is always visible.
  const options = released
    ? LIBRARY_STATES
    : LIBRARY_STATES.filter((value) => UNRELEASED_STATES.includes(value) || value === state);
  const pending = command.isPending ? command.variables.state : undefined;
  const shown = pending !== undefined ? pending : state;
  const blocked = state === undefined || command.isPending;

  function choose(next: LibraryState | null) {
    dialog.current?.close();
    if (blocked || csrfToken === null || next === state) return;
    command.mutate({ gameId, csrfToken, state: next });
  }

  const finished = command.isSuccess && (command.variables.state === "completed" || command.variables.state === "abandoned");

  return (
    <div className={compact ? "library-state-control library-state-control-compact" : "library-state-control"}
      aria-busy={command.isPending || undefined}>
      {/* The main button stays one element across states, so focus never falls off the page. */}
      <div className="library-state-split" data-state={shown ?? undefined}>
        <button
          ref={main}
          aria-disabled={(shown ? command.isPending : blocked) || undefined}
          aria-haspopup={shown ? "dialog" : undefined}
          aria-label={shown
            ? `${libraryStateLabels[shown]}: cambiar el estado de ${title}`
            : `${libraryStateLabels[quick]}: marcar ${title}`}
          className="library-state-main"
          type="button"
          onClick={() => {
            if (shown) { if (!command.isPending) setOpen(true); }
            else choose(quick);
          }}
        >
          {shown && !compact ? <PencilIcon /> : null}
          {shown && compact ? <span aria-hidden="true" className="library-state-dot" /> : null}
          {libraryStateLabels[shown ?? quick]}
          {shown && compact ? <CaretIcon /> : null}
        </button>
        {shown || !released ? null : (
          <button
            aria-disabled={blocked || undefined}
            aria-haspopup="dialog"
            aria-label={`Elegir el estado de ${title}`}
            className="library-state-caret"
            type="button"
            onClick={() => { if (!blocked) setOpen(true); }}
          >
            <CaretIcon />
          </button>
        )}
      </div>

      {open ? (
        <dialog
          aria-labelledby={titleId}
          className="library-state-dialog"
          ref={dialog}
          onClose={() => {
            setOpen(false);
            // Return focus only if nothing has claimed it since: a completed change may already
            // have moved it elsewhere (for example to the count when the row leaves the view).
            requestAnimationFrame(() => {
              const active = document.activeElement;
              if (active === null || active === document.body || active === main.current) main.current?.focus();
            });
          }}
        >
          <div className="library-state-sheet">
            <header className="library-state-dialog-header">
              <h2 id={titleId}>Elige el estado de este juego</h2>
              <button aria-label="Cerrar" className="library-state-close" type="button"
                onClick={() => dialog.current?.close()} />
            </header>
            <ul className="library-state-options">
              {options.map((value) => (
                <li key={value}>
                  <button
                    aria-pressed={value === state}
                    className="library-state-option"
                    type="button"
                    onClick={() => choose(value)}
                  >
                    {value === state ? <CheckIcon /> : null}
                    {libraryStateLabels[value]}
                  </button>
                </li>
              ))}
            </ul>
            {released ? null : (
              <p className="library-state-note">Podrás marcar otros estados cuando el juego salga.</p>
            )}
            {state ? (
              <button className="library-state-remove" type="button" onClick={() => choose(null)}>
                <TrashIcon />
                Quitar estado
              </button>
            ) : null}
          </div>
        </dialog>
      ) : null}

      {command.isError ? (
        <p className="library-state-error" role="alert">
          No se pudo guardar el estado. Comprueba el estado antes de volver a intentarlo.
        </p>
      ) : (
        <p className="sr-only" role="status">
          {command.isSuccess
            ? command.variables.state === null
              ? `${title} ya no tiene estado.`
              : `${title}: ${libraryStateLabels[command.variables.state]}.`
            : ""}
        </p>
      )}
      {finished && ratingOffer ? <div className="library-state-offer">{ratingOffer}</div> : null}
    </div>
  );
}
