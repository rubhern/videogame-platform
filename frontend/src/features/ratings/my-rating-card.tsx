import { useEffect, useId, useRef, useState, type CSSProperties } from "react";
import { Link } from "react-router-dom";
import { GameGenres } from "../../shared/catalogue/game-genres";
import { GameMeter } from "../../shared/score/game-meter";
import { thermalBand, thermalLabels } from "../../shared/score/thermal-band";
import { CatalogueCover } from "../../shared/ui/catalogue-cover";
import type { MyRatingItem } from "./my-ratings-api";
import { useRatingCommand } from "./use-personal-rating";
import type { RatingCommandError } from "./personal-rating-api";
import { RatingKeypad } from "./rating-keypad";

const failures: Record<RatingCommandError["kind"], string> = {
  authentication: "Tu sesión ha caducado. Puedes iniciar sesión al puntuar desde la ficha del juego.",
  csrf: "No se pudo verificar la solicitud. Recarga la página antes de volver a intentarlo.",
  conflict: "Tu puntuación cambió en otra sesión. Te mostramos la nota actual: revísala antes de volver a intentarlo.",
  validation: "Elige una nota entera del 1 al 10.",
  ineligible: "Este juego ya no admite cambios de nota. Puedes eliminar tu puntuación.",
  "rate-limited": "Espera un momento antes de volver a intentarlo.",
  unavailable: "No se pudo aplicar el cambio. Tu puntuación anterior se conserva.",
  ambiguous: "No sabemos si se aplicó el cambio. Comprobamos tu nota actual antes de permitir otro cambio.",
};

/** A failed command and the collection read it was issued against. */
type Failure = { error: RatingCommandError; readAt: number };

const formatDate = (value: string) => new Intl.DateTimeFormat("es-ES", {
  day: "numeric", month: "short", year: "numeric",
}).format(new Date(value));

/**
 * One row of the personal collection. Its score reading is the single entry point for
 * maintenance: pressing it opens an anchored, non-modal panel with the thermal keypad, the
 * current and pending readings, Save/Cancel and a two-step delete. The panel overlays the rows
 * below instead of growing this one, so the cover, title and dates never move.
 *
 * <p>Picking a value only makes it pending; nothing is sent until Save. Escape, Cancel, an
 * outside press or moving focus away closes the panel without a command. A concurrent or
 * ambiguous outcome blocks further commands until the collection has been read again
 * (`readAt` is the time of the last successful read).
 */
export function MyRatingCard({ item, csrfToken, readAt, onChanged }: {
  item: MyRatingItem; csrfToken: string; readAt: number; onChanged: (message: string) => void;
}) {
  const id = useId();
  const editorId = useId();
  const feedbackId = useId();
  const confirmId = useId();
  const [open, setOpen] = useState(false);
  const [selected, setSelected] = useState(item.personalRating.value);
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const editor = useRef<HTMLDivElement>(null);
  const keypad = useRef<HTMLDivElement>(null);
  const deleteButton = useRef<HTMLButtonElement>(null);
  const keepButton = useRef<HTMLButtonElement>(null);
  const command = useRatingCommand(item.game.gameId);
  const { game, personalRating, ratingSummary } = item;
  const community = ratingSummary && "mean" in ratingSummary ? ratingSummary : null;
  const communityBand = community?.mean == null ? null : thermalBand(community.mean);
  const createdDate = formatDate(personalRating.createdAt);
  const updatedDate = formatDate(personalRating.updatedAt);
  const current = personalRating.value;
  const band = thermalBand(current);
  const pendingBand = thermalBand(selected);
  const changed = selected !== current;
  const busy = command.isPending;
  const mustRead = failure !== null && failure.readAt === readAt
    && (failure.error.kind === "ambiguous" || failure.error.kind === "conflict");
  const path = `/games/${game.gameId}/${game.slug}`;
  const cover = "attribution" in game.primaryCover
    ? { ...game.primaryCover, kind: "provider" as const }
    : { ...game.primaryCover, kind: "fallback" as const };

  function openEditor() {
    if (!mustRead) setFailure(null);
    setSelected(current);
    setConfirmingDelete(false);
    setOpen(true);
  }

  function close(returnFocus: boolean) {
    if (busy) return;
    setOpen(false);
    setConfirmingDelete(false);
    if (returnFocus) trigger.current?.focus();
  }

  function fail(error: RatingCommandError) {
    setConfirmingDelete(false);
    setFailure({ error, readAt });
    // The pressed control may now be gone or disabled: keep focus in the panel with its alert.
    editor.current?.focus();
  }

  // Opening lands on the pressed value, so arrows browse the scale from the current rating.
  useEffect(() => {
    if (open) keypad.current?.querySelector<HTMLButtonElement>('[aria-pressed="true"]')?.focus();
  }, [open]);

  useEffect(() => {
    if (confirmingDelete) keepButton.current?.focus();
  }, [confirmingDelete]);

  useEffect(() => {
    if (!open || busy) return;
    function onKeyDown(event: KeyboardEvent) {
      if (event.key !== "Escape") return;
      setOpen(false);
      setConfirmingDelete(false);
      trigger.current?.focus();
    }
    function onPointerDown(event: PointerEvent) {
      const target = event.target as Node;
      if (!editor.current?.contains(target) && !trigger.current?.contains(target)) {
        setOpen(false);
        setConfirmingDelete(false);
      }
    }
    document.addEventListener("keydown", onKeyDown);
    document.addEventListener("pointerdown", onPointerDown);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      document.removeEventListener("pointerdown", onPointerDown);
    };
  }, [open, busy]);

  function save() {
    if (busy || mustRead || !changed) return;
    setFailure(null);
    setConfirmingDelete(false);
    command.mutate({ type: "save", value: selected, csrfToken, currentRating: personalRating }, {
      onSuccess: () => { setOpen(false); onChanged(`Puntuación de ${game.canonicalTitle} guardada: ${selected}/10.`); },
      onError: fail,
    });
  }

  function remove() {
    if (busy || mustRead) return;
    setFailure(null);
    command.mutate({ type: "delete", csrfToken, currentRating: personalRating }, {
      onSuccess: () => { setOpen(false); onChanged(`Puntuación de ${game.canonicalTitle} eliminada.`); },
      onError: fail,
    });
  }

  // The row is washed by its own cover's light.
  const coverLight = { "--cover-art": `url(${JSON.stringify(game.primaryCover.url)})` } as CSSProperties;
  return <article className="my-rating-card" aria-labelledby={id} aria-busy={busy} style={coverLight}>
    <span className="my-rating-glow" aria-hidden="true" />
    <CatalogueCover cover={cover} to={path} />
    <div className="my-rating-body">
      <h2 className="card-title" id={id}><Link to={path}>{game.canonicalTitle}</Link></h2>
      <GameGenres genres={game.genres} />
      <p className="my-rating-dates">
        {createdDate === updatedDate ? <>Puntuado el <time dateTime={personalRating.createdAt}>{createdDate}</time></>
          : <>Actualizado el <time dateTime={personalRating.updatedAt}>{updatedDate}</time>
            <span> · Puntuado el <time dateTime={personalRating.createdAt}>{createdDate}</time></span></>}
      </p>
    </div>
    <div className="my-rating-comparison">
    <div className="my-rating-score" onBlur={(event) => {
      const next = event.relatedTarget;
      if (open && next instanceof Node && !event.currentTarget.contains(next)) close(false);
    }}>
      {/* The name follows the visible reading, without its decorative separator, then the action. */}
      <button ref={trigger} type="button" className="my-rating-value" data-thermal={band ?? undefined}
        aria-label={`Tu puntuación ${band ? `${thermalLabels[band]} ` : ""}${current}/10. Cambiar o eliminar`}
        aria-haspopup="dialog" aria-expanded={open} aria-controls={open ? editorId : undefined}
        onClick={() => { if (open) close(false); else openEditor(); }}>
        <GameMeter className="my-rating-meter" value={current} />
        <span className="my-rating-label">Tu puntuación {band ? <b>{thermalLabels[band]}</b> : null}</span>
        <strong>{current}/10</strong>
        <span className="my-rating-hint" aria-hidden="true" />
      </button>
      {open ? <div ref={editor} id={editorId} className="my-rating-editor" role="dialog"
        aria-label={`Tu puntuación de ${game.canonicalTitle}`} tabIndex={-1}
        data-thermal={pendingBand ?? undefined}>
        <div className="my-rating-editor-reading">
          {/* The panel's meter swings to the pending value; the persisted one stays named beside it. */}
          <GameMeter className="my-rating-editor-meter" value={selected} />
          <p>
            <span>{changed ? "Nueva nota" : "Nota actual"}</span>
            <strong>{selected}/10</strong>
            {pendingBand ? <b>{thermalLabels[pendingBand]}</b> : null}
          </p>
          {changed ? <p className="my-rating-editor-was" data-thermal={band ?? undefined}>
            <span>Nota actual</span>
            <strong>{current}/10</strong>
            {band ? <b>{thermalLabels[band]}</b> : null}
          </p> : null}
        </div>
        <RatingKeypad ref={keypad} value={selected} onPick={setSelected} disabled={busy}
          describedBy={failure ? feedbackId : undefined} />
        {busy ? <p className="my-rating-pending" role="status">
          {command.variables?.type === "delete" ? "Eliminando puntuación…" : "Guardando cambio…"}
        </p> : null}
        {failure ? <div id={feedbackId} className="game-rating-feedback game-rating-feedback-error" role="alert">
          <p>{failures[failure.error.kind]}</p>
          {failure.error.correlationId ? <p className="game-rating-footnote">Referencia: {failure.error.correlationId}</p> : null}
        </div> : null}
        <div className="my-rating-editor-actions">
          <button className="button" type="button" disabled={busy} onClick={() => close(true)}>Cancelar</button>
          <button className="button button-primary" type="button" disabled={busy || mustRead || !changed}
            onClick={save}>Guardar nota</button>
        </div>
        <div className="my-rating-editor-danger">
          <button ref={deleteButton} className="button rating-remove" type="button" disabled={busy || mustRead}
            aria-expanded={confirmingDelete} onClick={() => setConfirmingDelete(value => !value)}>
            Eliminar puntuación
          </button>
          {confirmingDelete ? <div className="my-rating-confirm" role="group" aria-labelledby={confirmId}>
            <p id={confirmId}>¿Eliminar tu nota de {current}/10? Se borrará de tu colección.</p>
            <button ref={keepButton} className="button" type="button" disabled={busy}
              onClick={() => { setConfirmingDelete(false); deleteButton.current?.focus(); }}>Conservar</button>
            <button className="button button-danger" type="button" disabled={busy || mustRead} onClick={remove}>
              Sí, eliminar
            </button>
          </div> : null}
        </div>
      </div> : null}
    </div>
    <div className="my-rating-community" data-thermal={communityBand ?? undefined} aria-label="Comunidad">
      <GameMeter className="my-rating-meter" value={community?.mean ?? null} />
      <p className="my-rating-label">Comunidad {communityBand ? <b>{thermalLabels[communityBand]}</b> : null}</p>
      {community === null ? <p className="my-rating-community-empty">No disponible</p>
        : community.mean === null ? <p className="my-rating-community-empty">Sin puntuaciones</p>
          : <>
            <strong>{new Intl.NumberFormat("es-ES", { minimumFractionDigits: 1, maximumFractionDigits: 1 }).format(community.mean)}/10</strong>
            <p className="my-rating-community-count">{new Intl.NumberFormat("es-ES").format(community.count)} {community.count === 1 ? "puntuación" : "puntuaciones"}</p>
          </>}
    </div>
    </div>
  </article>;
}
