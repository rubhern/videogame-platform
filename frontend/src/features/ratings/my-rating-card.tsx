import { useId, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { CatalogueCover } from "../../shared/ui/catalogue-cover";
import type { MyRatingItem } from "./my-ratings-api";
import { useRatingCommand } from "./use-personal-rating";
import type { RatingCommandError } from "./personal-rating-api";

const failures: Record<RatingCommandError["kind"], string> = {
  authentication: "Tu sesión ha caducado. Puedes iniciar sesión al puntuar desde la ficha del juego.",
  csrf: "No se pudo verificar la solicitud. Actualiza la página antes de volver a intentarlo.",
  conflict: "Tu puntuación cambió en otra sesión. Actualiza los resultados y revisa la nota antes de volver a intentarlo.",
  validation: "Elige una nota entera del 1 al 10.",
  ineligible: "Este juego ya no admite cambios de nota. Puedes eliminar tu puntuación.",
  "rate-limited": "Espera un momento antes de volver a intentarlo.",
  unavailable: "No se pudo aplicar el cambio. Tu puntuación anterior se conserva.",
  ambiguous: "No sabemos si se aplicó el cambio. Actualiza los resultados antes de volver a intentarlo.",
};

const formatTimestamp = (value: string) => new Intl.DateTimeFormat("es-ES", {
  dateStyle: "medium", timeStyle: "short",
}).format(new Date(value));

export function MyRatingCard({ item, csrfToken, onChanged }: {
  item: MyRatingItem; csrfToken: string; onChanged: (message: string) => void;
}) {
  const id = useId();
  const [editing, setEditing] = useState(false);
  const [selected, setSelected] = useState(item.personalRating.value);
  const [failure, setFailure] = useState<RatingCommandError | null>(null);
  const editButton = useRef<HTMLButtonElement>(null);
  const command = useRatingCommand(item.game.gameId);
  const { game, personalRating } = item;
  const path = `/games/${game.gameId}/${game.slug}`;
  const cover = "attribution" in game.primaryCover
    ? { ...game.primaryCover, kind: "provider" as const }
    : { ...game.primaryCover, kind: "fallback" as const };
  const mustRefresh = failure?.kind === "ambiguous" || failure?.kind === "conflict";
  function finishEditing() {
    setEditing(false);
    editButton.current?.focus();
  }
  return <article className="my-rating-card" aria-labelledby={id} aria-busy={command.isPending}>
    <CatalogueCover cover={cover} to={path} />
    <div className="my-rating-body">
      <h2 className="card-title" id={id}>{game.canonicalTitle}</h2>
      <p className="my-rating-value">Tu puntuación: <strong>{personalRating.value}/10</strong></p>
      <dl className="my-rating-dates">
        <div><dt>Puntuado</dt><dd><time dateTime={personalRating.createdAt}>{formatTimestamp(personalRating.createdAt)}</time></dd></div>
        <div><dt>Actualizado</dt><dd><time dateTime={personalRating.updatedAt}>{formatTimestamp(personalRating.updatedAt)}</time></dd></div>
      </dl>
      <Link className="card-action" to={path}>Ver ficha<span className="sr-only"> de {game.canonicalTitle}</span> →</Link>
      {editing ? <form className="my-rating-edit" onSubmit={(event) => {
        event.preventDefault();
        if (command.isPending || mustRefresh) return;
        setFailure(null);
        command.mutate({ type: "save", value: selected, csrfToken, currentRating: personalRating }, {
          onSuccess: () => { finishEditing(); onChanged(`Puntuación de ${game.canonicalTitle} guardada.`); },
          onError: setFailure,
        });
      }}>
        <label htmlFor={`${id}-value`}>Nueva puntuación</label>
        <select id={`${id}-value`} autoFocus value={selected}
          disabled={command.isPending || mustRefresh}
          onChange={(event) => setSelected(Number(event.target.value))}>
          {Array.from({ length: 10 }, (_, index) => index + 1).map(value =>
            <option key={value} value={value}>{value}/10</option>)}
        </select>
        <button className="button button-primary" disabled={command.isPending || mustRefresh}>Guardar cambios</button>
        <button className="button" type="button" disabled={command.isPending} onClick={finishEditing}>Cancelar</button>
      </form> : null}
      <div className="my-rating-actions">
        <button className="button" ref={editButton} type="button" disabled={command.isPending || mustRefresh}
          aria-expanded={editing} onClick={() => { setSelected(personalRating.value); setEditing(true); }}>
          Editar puntuación
        </button>
        <button className="button rating-remove" type="button" disabled={command.isPending || mustRefresh}
          onClick={() => {
            setFailure(null);
            command.mutate({ type: "delete", csrfToken, currentRating: personalRating }, {
              onSuccess: () => onChanged(`Puntuación de ${game.canonicalTitle} eliminada.`),
              onError: setFailure,
            });
          }}>Eliminar puntuación</button>
      </div>
      {command.isPending ? <p role="status">Guardando cambio…</p> : null}
      {failure ? <div className="game-rating-feedback game-rating-feedback-error" role="alert">
        <p>{failures[failure.kind]}</p>
        {failure.correlationId ? <p>Referencia: {failure.correlationId}</p> : null}
      </div> : null}
    </div>
  </article>;
}
