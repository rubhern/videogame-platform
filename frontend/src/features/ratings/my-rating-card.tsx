import { useQuery } from "@tanstack/react-query";
import { useEffect, useId, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { GameGenres } from "../../shared/catalogue/game-genres";
import { PlatformMarks } from "../../shared/catalogue/platform-marks";
import { platformIcon } from "../../shared/catalogue/taxonomy-icons";
import { DiscoveryScores } from "../../shared/score/discovery-scores";
import { toDiscoveryScores } from "../../shared/score/discovery-scores-model";
import { GameMeter } from "../../shared/score/game-meter";
import { thermalBand, thermalLabels } from "../../shared/score/thermal-band";
import { CatalogueCover } from "../../shared/ui/catalogue-cover";
import type { MyRatingItem } from "./my-ratings-api";
import { useRatingCommand } from "./use-personal-rating";
import type { RatingCommandError } from "./personal-rating-api";
import { contributionSource, formatPersonalScore, scoredPlatforms } from "./personal-score";
import { gameDetailsQueryOptions } from "../game-details/game-details-api";
import { PlatformRatingDialog } from "./platform-rating-dialog";
import { RatingKeypad } from "./rating-keypad";
import { useGameLinkState } from "../../shared/navigation/use-game-link-state";

const failures: Record<RatingCommandError["kind"], string> = {
  authentication: "Tu sesión ha caducado. Puedes iniciar sesión al puntuar desde la ficha del juego.",
  csrf: "No se pudo verificar la solicitud. Recarga la página antes de volver a intentarlo.",
  conflict: "Tu puntuación cambió en otra sesión. Te mostramos la puntuación actual: revísala antes de volver a intentarlo.",
  validation: "Elige una puntuación entera del 1 al 10.",
  platform: "Una de tus plataformas ya no figura en este juego. Revísalas en la ficha del juego.",
  ineligible: "Este juego ya no admite cambios de puntuación. Puedes eliminar tu puntuación.",
  "rate-limited": "Espera un momento antes de volver a intentarlo.",
  unavailable: "No se pudo aplicar el cambio. Tu puntuación anterior se conserva.",
  ambiguous: "No sabemos si se aplicó el cambio. Comprobamos tu puntuación actual antes de permitir otro cambio.",
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
 *
 * <p>Post-MVP (#359) the reading is the rating's one contribution, with the platforms it was
 * played on as quiet marks beneath. A rating scored by platform opens the same panel with its
 * breakdown instead of the keypad, so a general score saved here never replaces platform scores,
 * and a general one keeps its platforms. **Puntuar por plataforma** opens the game page's own
 * platform sheet; it reads the game's public details only then, for their release platforms.
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
  const [editingPlatforms, setEditingPlatforms] = useState(false);
  // The game's release platforms, read once the sheet is asked for; the public read is cached.
  const details = useQuery({ ...gameDetailsQueryOptions(item.game.gameId), enabled: editingPlatforms });
  const { game, personalRating, ratingSummary, externalScore } = item;
  // The same readings as discovery and search cards: only valid sources, never an invented one.
  const scores = toDiscoveryScores(ratingSummary === undefined ? undefined
    : { community: ratingSummary, ...(externalScore === undefined ? {} : { external: externalScore }) });
  const createdDate = formatDate(personalRating.createdAt);
  const updatedDate = formatDate(personalRating.updatedAt);
  const current = personalRating.contribution;
  const byPlatform = personalRating.mode === "platforms";
  const source = contributionSource(personalRating);
  const band = thermalBand(current);
  const pendingBand = selected === null ? null : thermalBand(selected);
  const changed = !byPlatform && selected !== personalRating.value;
  const reading = byPlatform || selected === null ? current : selected;
  const readingBand = byPlatform ? band : pendingBand;
  const busy = command.isPending;
  const mustRead = failure !== null && failure.readAt === readAt
    && (failure.error.kind === "ambiguous" || failure.error.kind === "conflict");
  const path = `/games/${game.gameId}/${game.slug}`;
  const linkState = useGameLinkState();
  const cover = "attribution" in game.primaryCover
    ? { ...game.primaryCover, kind: "provider" as const }
    : { ...game.primaryCover, kind: "fallback" as const };

  function openEditor() {
    if (!mustRead) setFailure(null);
    setSelected(personalRating.value);
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
    // A rating scored by platform has no keypad here: the panel itself takes focus.
    if (open) (keypad.current?.querySelector<HTMLButtonElement>('[aria-pressed="true"]') ?? editor.current)?.focus();
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
    if (busy || mustRead || !changed || selected === null) return;
    setFailure(null);
    setConfirmingDelete(false);
    // Only the general score: the platforms it was played on stay as they are.
    command.mutate({ type: "save", write: { value: selected }, csrfToken, currentRating: personalRating }, {
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

  function editPlatforms() {
    if (busy || mustRead) return;
    setFailure(null);
    setConfirmingDelete(false);
    setOpen(false);
    setEditingPlatforms(true);
  }

  function platformsClosed(saved: typeof personalRating | null) {
    setEditingPlatforms(false);
    trigger.current?.focus();
    if (saved !== null) {
      onChanged(`Puntuación de ${game.canonicalTitle} guardada: ${formatPersonalScore(saved.contribution)}/10.`);
    }
  }

  // The row's edge takes the temperature of the visitor's own rating, so the ledger reads at a glance.
  return <article className="my-rating-card" aria-labelledby={id} aria-busy={busy} data-thermal={band ?? undefined}>
    <CatalogueCover cover={cover} linkState={linkState} to={path} />
    <div className="my-rating-body">
      <h2 className="card-title" id={id}><Link state={linkState} to={path}>{game.canonicalTitle}</Link></h2>
      <GameGenres genres={game.genres} />
      <DiscoveryScores scores={scores} title={game.canonicalTitle} />
    </div>
    <div className="my-rating-score" onBlur={(event) => {
      const next = event.relatedTarget;
      if (open && next instanceof Node && !event.currentTarget.contains(next)) close(false);
    }}>
      {/* The name follows the visible reading, without its decorative separator, then the action. */}
      <button ref={trigger} type="button" className="my-rating-value" data-thermal={band ?? undefined}
        aria-label={`Tu puntuación ${band ? `${thermalLabels[band]} ` : ""}${formatPersonalScore(current)}/10${source ? `, ${source.toLocaleLowerCase("es-ES")}` : ""}. Cambiar o eliminar`}
        aria-haspopup="dialog" aria-expanded={open} aria-controls={open ? editorId : undefined}
        onClick={() => { if (open) close(false); else openEditor(); }}>
        <GameMeter className="my-rating-meter" value={current} />
        <span className="my-rating-label">Tu puntuación</span>
        <span className="my-rating-reading">
          <strong>{formatPersonalScore(current)}/10</strong>{band ? <b>{thermalLabels[band]}</b> : null}
        </span>
        <span className="my-rating-hint" aria-hidden="true" />
      </button>
      {/* Where it was played, as quiet marks: never a breakdown on the line itself. */}
      {personalRating.platforms.length > 0 ? <div className="my-rating-platforms">
        <PlatformMarks label={`Plataformas en las que has jugado a ${game.canonicalTitle}`} visible={3}
          marks={personalRating.platforms.map((entry) => ({ key: entry.platform.platformId, name: entry.platform.name,
            icon: platformIcon(entry.platform.platformId, entry.platform.name) }))} />
        {source ? <span>{source}</span> : null}
      </div> : null}
      {editingPlatforms && details.isPending ? <p className="my-rating-pending" role="status">Cargando plataformas…</p> : null}
      {editingPlatforms && details.isError ? <div className="game-rating-feedback game-rating-feedback-error" role="alert">
        <p>No se pudieron cargar las plataformas del juego. Inténtalo de nuevo más tarde.</p>
        <button className="button" type="button" onClick={() => setEditingPlatforms(false)}>Cerrar</button>
      </div> : null}
      {editingPlatforms && details.data ? <PlatformRatingDialog csrfToken={csrfToken} game={details.data}
        onClose={platformsClosed} rating={personalRating} /> : null}
      {open ? <div ref={editor} id={editorId} className="my-rating-editor" role="dialog"
        aria-label={`Tu puntuación de ${game.canonicalTitle}`} tabIndex={-1}
        data-thermal={readingBand ?? undefined}>
        <div className="my-rating-editor-reading">
          {/* The panel's meter swings to the pending value; the persisted one stays named beside it. */}
          <GameMeter className="my-rating-editor-meter" value={reading} />
          <p>
            <span>{changed ? "Nueva puntuación" : "Puntuación actual"}</span>
            <strong>{formatPersonalScore(reading)}/10</strong>
            {readingBand ? <b>{thermalLabels[readingBand]}</b> : null}
          </p>
          {changed ? <p className="my-rating-editor-was" data-thermal={band ?? undefined}>
            <span>Puntuación actual</span>
            <strong>{formatPersonalScore(current)}/10</strong>
            {band ? <b>{thermalLabels[band]}</b> : null}
          </p> : null}
        </div>
        {byPlatform ? <div className="my-rating-editor-platforms">
          <p>{source}.</p>
          <ul aria-label="Tus notas por plataforma">
            {scoredPlatforms(personalRating).map((entry) => <li key={entry.platform.platformId}>
              <span>{entry.platform.name}</span>
              <strong data-thermal={thermalBand(entry.value) ?? undefined}>{entry.value}</strong>
            </li>)}
          </ul>
        </div> : <RatingKeypad ref={keypad} value={selected} onPick={setSelected} disabled={busy}
          describedBy={failure ? feedbackId : undefined} />}
        <button className="button my-rating-editor-platforms-edit" type="button" disabled={busy || mustRead}
          onClick={editPlatforms}>
          {personalRating.platforms.length > 0 ? "Editar plataformas" : "Puntuar por plataforma"}
        </button>
        {busy ? <p className="my-rating-pending" role="status">
          {command.variables?.type === "delete" ? "Eliminando puntuación…" : "Guardando cambio…"}
        </p> : null}
        {failure ? <div id={feedbackId} className="game-rating-feedback game-rating-feedback-error" role="alert">
          <p>{failures[failure.error.kind]}</p>
          {failure.error.correlationId ? <p className="game-rating-footnote">Referencia: {failure.error.correlationId}</p> : null}
        </div> : null}
        {byPlatform ? null : <div className="my-rating-editor-actions">
          <button className="button" type="button" disabled={busy} onClick={() => close(true)}>Cancelar</button>
          <button className="button button-primary" type="button" disabled={busy || mustRead || !changed}
            onClick={save}>Guardar puntuación</button>
        </div>}
        <div className="my-rating-editor-danger">
          <button ref={deleteButton} className="button rating-remove" type="button" disabled={busy || mustRead}
            aria-expanded={confirmingDelete} onClick={() => setConfirmingDelete(value => !value)}>
            Eliminar puntuación
          </button>
          {confirmingDelete ? <div className="my-rating-confirm" role="group" aria-labelledby={confirmId}>
            <p id={confirmId}>¿Seguro que quieres eliminar esta puntuación?</p>
            <button ref={keepButton} className="button" type="button" disabled={busy}
              onClick={() => { setConfirmingDelete(false); deleteButton.current?.focus(); }}>Conservar</button>
            <button className="button button-danger" type="button" disabled={busy || mustRead} onClick={remove}>
              Sí, eliminar
            </button>
          </div> : null}
        </div>
      </div> : null}
    </div>
    {/* The latest date leads; each date stays whole and a wrapped one never starts with its separator. */}
    <p className="my-rating-dates"><span>
      {createdDate === updatedDate ? <span>Puntuado el <time dateTime={personalRating.createdAt}>{createdDate}</time></span>
        : <><span>Actualizado el <time dateTime={personalRating.updatedAt}>{updatedDate}</time></span>
          <span><span className="my-rating-dates-separator"> · </span>Puntuado el <time dateTime={personalRating.createdAt}>{createdDate}</time></span></>}
    </span></p>
  </article>;
}
