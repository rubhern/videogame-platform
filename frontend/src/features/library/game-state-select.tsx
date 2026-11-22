import { useId, type ReactNode } from "react";

import { LIBRARY_STATES, libraryStateLabels, type LibraryState } from "./library-api";
import { useGameStateCommand } from "./use-game-states";

const NO_STATE = "";

type GameStateSelectProps = {
  gameId: string;
  title: string;
  /** Undefined while unknown; null when the game is not in the library. */
  state: LibraryState | null | undefined;
  csrfToken: string | null;
  /** Without a release that has occurred, only Deseado can be chosen. */
  released?: boolean;
  /** Offered, never applied, after the player marks the game Completado or Abandonado. */
  ratingOffer?: ReactNode;
  onChanged?: (state: LibraryState | null) => void;
};

/**
 * One native select per game: the player's private state, or none. Choosing «Sin estado»
 * removes the game from the library, including from Mis deseados. A game that has not come out
 * offers only Deseado; a state it already has stays shown.
 */
export function GameStateSelect({
  gameId, title, state, csrfToken, released = true, ratingOffer, onChanged,
}: GameStateSelectProps) {
  const id = useId();
  const hintId = useId();
  const command = useGameStateCommand(onChanged);
  if (csrfToken === null) return null;
  const pending = command.isPending ? command.variables.state : undefined;
  const shown = pending !== undefined ? pending : state;
  const blocked = state === undefined || command.isPending;
  const finished = command.isSuccess && (command.variables.state === "completed" || command.variables.state === "abandoned");
  return (
    <div className="library-state" aria-busy={command.isPending || undefined}>
      <label className="library-state-label" htmlFor={id}>
        Estado<span className="sr-only"> de {title}</span>
      </label>
      <select
        aria-disabled={blocked || undefined}
        aria-describedby={released ? undefined : hintId}
        className="library-state-select"
        data-state={shown ?? undefined}
        id={id}
        value={shown ?? NO_STATE}
        onChange={(event) => {
          if (blocked) return;
          const next = event.target.value === NO_STATE ? null : event.target.value as LibraryState;
          if (next !== state) command.mutate({ gameId, csrfToken, state: next });
        }}
      >
        <option value={NO_STATE}>{state === undefined ? "Comprobando…" : "Sin estado"}</option>
        {LIBRARY_STATES.map((value) => (
          <option key={value} disabled={!released && value !== "wished" && value !== shown} value={value}>
            {libraryStateLabels[value]}
          </option>
        ))}
      </select>
      {released ? null : <span className="sr-only" id={hintId}>Solo Deseado hasta que salga.</span>}
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
