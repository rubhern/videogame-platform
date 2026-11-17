import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useId, useRef, useState, type ChangeEvent, type FormEvent } from "react";

import { searchGames } from "../search/game-search-api";
import {
  AdministrationError,
  deleteEditorialAttentionList,
  EDITORIAL_ATTENTION_KEY,
  getEditorialAttention,
  getEditorialAttentionList,
  loadEditorialAttention,
  previewEditorialAttention,
  type EditorialAttentionAssignment,
  type EditorialAttentionListSummary,
  type EditorialAttentionPreview,
  type EditorialAttentionPreviewEntry,
  type EditorialAttentionWarning,
} from "./administration-api";

/** The longest range one list may cover, as the server enforces it. */
const MAX_RANGE_DAYS = 92;
const SEARCH_RESULTS = 5;

const WARNINGS: Record<EditorialAttentionWarning, string> = {
  unmatched: "Sin coincidencia en el catálogo",
  ambiguous_title: "Varios juegos con ese título",
  no_release_in_range: "Sin lanzamiento en el periodo: no cuenta",
  release_date_differs: "La fecha del fichero no coincide con el catálogo",
  duplicate_game: "Juego repetido: se conserva la puntuación más alta",
};

const day = new Intl.DateTimeFormat("es-ES", { dateStyle: "medium", timeZone: "UTC" });
const dateTime = new Intl.DateTimeFormat("es-ES", { dateStyle: "short", timeStyle: "short" });

function isoDay(date: Date): string {
  return date.toISOString().slice(0, 10);
}

/** The next calendar month: the range an operator usually prepares. */
function nextMonth(now: Date): { from: string; to: string } {
  const from = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + 1, 1));
  const to = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + 2, 0));
  return { from: isoDay(from), to: isoDay(to) };
}

function rangeError(from: string, to: string): string | null {
  if (!from || !to) return "Indica las dos fechas.";
  const days = (Date.parse(to) - Date.parse(from)) / 86_400_000;
  if (Number.isNaN(days) || days < 0) return "La fecha final no puede ser anterior a la inicial.";
  if (days >= MAX_RANGE_DAYS) return "Un periodo abarca como máximo 92 días.";
  return null;
}

function formatDay(value: string): string {
  return day.format(new Date(`${value}T00:00:00Z`));
}

function period(list: { from: string; to: string }): string {
  return `${formatDay(list.from)} – ${formatDay(list.to)}`;
}

/** The operator's choice for one entry: the automatic match, another game, or leaving it out. */
type Choice = { kind: "match" } | { kind: "game"; gameId: string; title: string } | { kind: "exclude" };

function failureMessage(error: AdministrationError): { tone: "warning" | "danger"; text: string } {
  switch (error.code) {
    case "EDITORIAL_ATTENTION_FILE_INVALID":
      return {
        tone: "warning",
        text: `El fichero no es válido${error.pointer ? ` (${error.pointer})` : ""}. Corrígelo o pide al modelo una respuesta nueva; no se ha guardado nada.`,
      };
    case "EDITORIAL_ATTENTION_RANGE_CONFLICT":
      return { tone: "warning", text: "Otra lista cubre parte de ese periodo. Elimínala antes de cargar esta; no se ha guardado nada." };
    case "EDITORIAL_ATTENTION_LIST_NOT_FOUND":
      return { tone: "warning", text: "Esa lista ya no existe. La relación de listas se ha actualizado." };
    case "CSRF_VALIDATION_FAILED":
      return { tone: "warning", text: "La comprobación de seguridad caducó. Recarga la página e inténtalo de nuevo; no se ha guardado nada." };
    case "ACCESS_DENIED":
      return { tone: "warning", text: "Tu cuenta ya no tiene el rol de operador del catálogo." };
    case "AUTHENTICATION_REQUIRED":
      return { tone: "warning", text: "Tu sesión ha caducado. Vuelve a iniciar sesión." };
    default:
      return {
        tone: "danger",
        text: `No se pudo completar la operación.${error.correlationId ? ` Referencia para soporte: ${error.correlationId}.` : ""}`,
      };
  }
}

function Failure({ error }: { error: unknown }) {
  const message = error instanceof AdministrationError ? failureMessage(error)
    : { tone: "danger" as const, text: "No se pudo completar la operación." };
  return <p className={`notice notice-${message.tone}`} role="alert">{message.text}</p>;
}

function PromptSection({ version, template, now }: { version: string; template: string; now: Date }) {
  const initial = nextMonth(now);
  const [from, setFrom] = useState(initial.from);
  const [to, setTo] = useState(initial.to);
  const [copied, setCopied] = useState<"done" | "failed" | null>(null);
  const error = rangeError(from, to);
  const prompt = template.replaceAll("{{FROM}}", from).replaceAll("{{TO}}", to);
  const errorId = useId();

  async function copy() {
    try {
      await navigator.clipboard.writeText(prompt);
      setCopied("done");
    } catch {
      setCopied("failed");
    }
  }

  return <section className="admin-section" aria-labelledby="editorial-prompt-title">
    <h2 id="editorial-prompt-title">Prompt para el modelo</h2>
    <p className="admin-lead">
      Elige el periodo, copia el prompt y pégalo en un modelo con búsqueda web. El modelo entrega un
      fichero JSON descargable, que se carga abajo. Versión del prompt: <span className="admin-code">{version}</span>.
    </p>
    <div className="admin-request-fields">
      <div className="admin-field">
        <label htmlFor="editorial-from">Desde</label>
        <input id="editorial-from" type="date" value={from} aria-invalid={error ? true : undefined}
          aria-describedby={error ? errorId : undefined}
          onChange={(event) => { setFrom(event.target.value); setCopied(null); }} />
      </div>
      <div className="admin-field">
        <label htmlFor="editorial-to">Hasta</label>
        <input id="editorial-to" type="date" value={to} aria-invalid={error ? true : undefined}
          aria-describedby={error ? errorId : undefined}
          onChange={(event) => { setTo(event.target.value); setCopied(null); }} />
      </div>
    </div>
    {error ? <p className="admin-field-error" id={errorId}>{error}</p> : null}
    <div className="admin-field">
      <label htmlFor="editorial-prompt">Prompt</label>
      <textarea id="editorial-prompt" className="admin-prompt" readOnly rows={14} value={prompt} />
    </div>
    <div className="admin-control-actions">
      <button className="button button-primary" type="button" disabled={error !== null}
        onClick={() => { void copy(); }}>Copiar prompt</button>
      <p className="admin-quiet" role="status">
        {copied === "done" ? "Prompt copiado." : copied === "failed" ? "No se pudo copiar: selecciona el texto y cópialo a mano." : ""}
      </p>
    </div>
  </section>;
}

function GamePicker({ rank, onPick, onCancel }: {
  rank: number;
  onPick: (game: { gameId: string; title: string }) => void;
  onCancel: () => void;
}) {
  const [query, setQuery] = useState("");
  const [submitted, setSubmitted] = useState("");
  const results = useQuery({
    queryKey: ["catalogue-search", "editorial-picker", submitted],
    queryFn: ({ signal }) => searchGames({ q: submitted, page: 1, pageSize: SEARCH_RESULTS }, undefined, signal),
    enabled: submitted.length > 0,
    staleTime: 60_000,
  });

  function search(event: FormEvent) {
    event.preventDefault();
    setSubmitted(query.trim());
  }

  return <div className="admin-confirm" role="group" aria-label={`Elegir juego para la entrada ${rank}`}>
    <form className="admin-request-form" onSubmit={search}>
      <div className="admin-field">
        <label htmlFor={`editorial-search-${rank}`}>Buscar en el catálogo</label>
        <input id={`editorial-search-${rank}`} type="search" value={query} autoFocus
          onChange={(event) => setQuery(event.target.value)} />
      </div>
      <div className="admin-confirm-actions">
        <button className="button" type="button" onClick={onCancel}>Cancelar</button>
        <button className="button button-primary" type="submit" disabled={query.trim().length === 0}>Buscar</button>
      </div>
    </form>
    {results.isFetching ? <p className="admin-quiet" role="status">Buscando…</p>
      : results.isError ? <p className="notice notice-danger" role="alert">La búsqueda no está disponible.</p>
        : results.data ? results.data.items.length === 0 ? <p className="admin-quiet" role="status">Sin resultados.</p>
          : <ul className="admin-facts">
            {results.data.items.map((game) => <li key={game.gameId}>
              <button className="button" type="button"
                onClick={() => onPick({ gameId: game.gameId, title: game.canonicalTitle })}>
                {game.canonicalTitle}
              </button>
            </li>)}
          </ul> : null}
  </div>;
}

function PreviewTable({ preview, choices, onChoose }: {
  preview: EditorialAttentionPreview;
  choices: Record<number, Choice>;
  onChoose: (rank: number, choice: Choice) => void;
}) {
  const [picking, setPicking] = useState<number | null>(null);

  function chosen(entry: EditorialAttentionPreviewEntry): string {
    const choice = choices[entry.rank] ?? { kind: "match" };
    if (choice.kind === "exclude") return "Excluida";
    if (choice.kind === "game") return `${choice.title} (elegido a mano)`;
    if (!entry.match) return "Se excluirá";
    return `${entry.match.title} (${entry.match.matchedBy === "igdb_slug" ? "por IGDB" : "por título"})`;
  }

  return <>
    <div className="admin-table-scroll" role="region" aria-label="Entradas del fichero" tabIndex={0}>
      <table className="admin-table">
        <caption>{preview.entries.length} entradas para {period(preview)}</caption>
        <thead>
          <tr>
            <th scope="col">Puesto</th>
            <th scope="col">Título del fichero</th>
            <th scope="col">Puntuación</th>
            <th scope="col">Juego del catálogo</th>
            <th scope="col">Fecha en el periodo</th>
            <th scope="col">Avisos y acciones</th>
          </tr>
        </thead>
        <tbody>
          {preview.entries.map((entry) => {
            const choice = choices[entry.rank] ?? { kind: "match" };
            return <tr key={entry.rank}>
              <td>{entry.rank}</td>
              <td>{entry.title}{entry.releaseDate ? <><br /><span className="admin-quiet">Fichero: {formatDay(entry.releaseDate)}</span></> : null}</td>
              <td>{entry.score}</td>
              <td>{chosen(entry)}</td>
              <td>{choice.kind === "match" && entry.match?.releaseInRange ? formatDay(entry.match.releaseInRange) : "—"}</td>
              <td>
                {choice.kind === "match" && entry.warnings.length > 0 ? <ul className="admin-warning">
                  {entry.warnings.map((warning) => <li key={warning}>{WARNINGS[warning]}</li>)}
                </ul> : null}
                <div className="admin-control-actions">
                  <button className="button" type="button" onClick={() => setPicking(entry.rank)}>Elegir juego</button>
                  {choice.kind === "exclude" || choice.kind === "game"
                    ? <button className="button" type="button" onClick={() => onChoose(entry.rank, { kind: "match" })}>Deshacer</button>
                    : <button className="button" type="button" onClick={() => onChoose(entry.rank, { kind: "exclude" })}>Excluir</button>}
                </div>
                {picking === entry.rank ? <GamePicker rank={entry.rank} onCancel={() => setPicking(null)}
                  onPick={(game) => { onChoose(entry.rank, { kind: "game", ...game }); setPicking(null); }} /> : null}
              </td>
            </tr>;
          })}
        </tbody>
      </table>
    </div>
  </>;
}

function assignments(choices: Record<number, Choice>): EditorialAttentionAssignment[] {
  return Object.entries(choices).flatMap(([rank, choice]): EditorialAttentionAssignment[] =>
    choice.kind === "game" ? [{ rank: Number(rank), gameId: choice.gameId }]
      : choice.kind === "exclude" ? [{ rank: Number(rank) }] : []);
}

function LoadSection({ csrfToken }: { csrfToken: string }) {
  const queryClient = useQueryClient();
  const [content, setContent] = useState("");
  const [choices, setChoices] = useState<Record<number, Choice>>({});
  const feedback = useRef<HTMLDivElement>(null);
  const preview = useMutation<EditorialAttentionPreview, AdministrationError, string>({
    mutationFn: (text) => previewEditorialAttention(text, csrfToken),
    onSuccess: () => setChoices({}),
  });
  const load = useMutation({
    mutationFn: () => loadEditorialAttention(content, assignments(choices), csrfToken),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: EDITORIAL_ATTENTION_KEY });
      feedback.current?.focus();
    },
    onSuccess: () => { preview.reset(); setContent(""); setChoices({}); },
  });

  function readFile(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (!file) return;
    void file.text().then((text) => { setContent(text); preview.reset(); load.reset(); });
  }

  const current = preview.data;
  return <section className="admin-section" aria-labelledby="editorial-load-title">
    <h2 id="editorial-load-title">Cargar lista</h2>
    <p className="admin-lead">
      Elige el fichero JSON que entrega el modelo, o pega su respuesta si no pudo crearlo. La previsualización empareja cada juego con el
      catálogo sin guardar nada; después puedes corregir las entradas y cargar la lista. Cargar el
      mismo periodo otra vez sustituye su lista entera.
    </p>
    <div className="admin-field">
      <label htmlFor="editorial-file">Fichero</label>
      <input id="editorial-file" type="file" accept=".json,.txt,application/json,text/plain" onChange={readFile} />
    </div>
    <div className="admin-field">
      <label htmlFor="editorial-content">Respuesta del modelo</label>
      <textarea id="editorial-content" className="admin-prompt" rows={8} value={content}
        onChange={(event) => { setContent(event.target.value); preview.reset(); load.reset(); }} />
    </div>
    <div className="admin-control-actions">
      <button className="button" type="button" disabled={content.trim().length === 0 || preview.isPending}
        onClick={() => { load.reset(); preview.mutate(content); }}>
        {preview.isPending ? "Previsualizando…" : "Previsualizar"}
      </button>
    </div>
    {preview.isError ? <Failure error={preview.error} /> : null}
    {current ? <>
      <p className="admin-lead">
        Periodo {period(current)} · prompt <span className="admin-code">{current.promptVersion}</span>
        {current.model ? <> · modelo {current.model}</> : null}
      </p>
      {current.conflict ? <p className="notice notice-warning" role="status">
        La lista del {period(current.conflict)} se solapa con este periodo. Elimínala antes de cargar esta.
      </p> : current.replaces ? <p className="notice" role="status">
        Sustituirá la lista cargada para este periodo ({current.replaces.entryCount} entradas).
      </p> : null}
      <PreviewTable preview={current} choices={choices}
        onChoose={(rank, choice) => setChoices((previous) => ({ ...previous, [rank]: choice }))} />
      <div className="admin-control-actions">
        <button className="button button-primary" type="button"
          disabled={current.conflict !== undefined || load.isPending} onClick={() => load.mutate()}>
          {load.isPending ? "Cargando…" : "Cargar lista"}
        </button>
      </div>
    </> : null}
    <div className="admin-request-feedback" ref={feedback} tabIndex={-1}>
      {load.isError ? <Failure error={load.error} />
        : load.isSuccess ? <p className="notice" role="status">
          Lista del {period(load.data.list)} cargada con {load.data.list.entryCount} juegos. Ya ordena Destacados y Explorar.
        </p> : null}
    </div>
  </section>;
}

function ListDetail({ listId }: { listId: string }) {
  const detail = useQuery({
    queryKey: [...EDITORIAL_ATTENTION_KEY, "list", listId],
    queryFn: ({ signal }) => getEditorialAttentionList(listId, signal),
  });
  if (detail.isPending) return <p className="admin-quiet" role="status">Cargando entradas…</p>;
  if (detail.isError) return <Failure error={detail.error} />;
  return <ol className="admin-facts">
    {detail.data.entries.map((entry) => <li key={entry.gameId}>
      <strong>{entry.score}</strong> · {entry.title}
      {entry.fileTitle !== entry.title ? <span className="admin-quiet"> (fichero: {entry.fileTitle})</span> : null}
      {entry.rationale ? <><br /><span className="admin-quiet">{entry.rationale}</span></> : null}
    </li>)}
  </ol>;
}

function LoadedLists({ lists, csrfToken }: { lists: EditorialAttentionListSummary[]; csrfToken: string }) {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState<string | null>(null);
  const [confirming, setConfirming] = useState<string | null>(null);
  const remove = useMutation({
    mutationFn: (listId: string) => deleteEditorialAttentionList(listId, csrfToken),
    onSettled: () => {
      setConfirming(null);
      void queryClient.invalidateQueries({ queryKey: EDITORIAL_ATTENTION_KEY });
    },
  });

  return <section className="admin-section" aria-labelledby="editorial-lists-title">
    <h2 id="editorial-lists-title">Listas cargadas</h2>
    <p className="admin-lead">
      Cada lista puntúa de 1 a 100 los juegos de su periodo y aporta el peso editorial de Ajustes (la mitad por defecto) del interés en Destacados,
      Explorar, el calendario y Próximos destacados. Al eliminarla, ese periodo vuelve a ordenarse solo
      con las señales de IGDB.
    </p>
    {remove.isError ? <Failure error={remove.error} /> : null}
    {lists.length === 0 ? <p className="admin-quiet">Todavía no hay listas cargadas.</p>
      : <ul className="admin-runs">
        {lists.map((list) => <li key={list.listId} className="admin-run">
          <div className="admin-run-head">
            <h3>{period(list)}</h3>
            <p className="admin-run-time">Cargada {dateTime.format(new Date(list.loadedAt))}</p>
          </div>
          <p>{list.entryCount} juegos · prompt <span className="admin-code">{list.promptVersion}</span>{list.model ? <> · {list.model}</> : null}</p>
          <div className="admin-control-actions">
            <button className="button" type="button" aria-expanded={open === list.listId}
              onClick={() => setOpen(open === list.listId ? null : list.listId)}>
              {open === list.listId ? "Ocultar juegos" : "Ver juegos"}
            </button>
            <button className="button button-danger" type="button" onClick={() => setConfirming(list.listId)}>Eliminar</button>
          </div>
          {confirming === list.listId ? <div className="admin-confirm" role="group" aria-label={`Eliminar la lista del ${period(list)}`}>
            <p>¿Eliminar la lista del {period(list)}? Su periodo se ordenará solo con las señales de IGDB.</p>
            <div className="admin-confirm-actions">
              <button className="button" type="button" autoFocus onClick={() => setConfirming(null)}>Conservar lista</button>
              <button className="button button-danger" type="button" disabled={remove.isPending}
                onClick={() => remove.mutate(list.listId)}>Sí, eliminar</button>
            </div>
          </div> : null}
          {open === list.listId ? <ListDetail listId={list.listId} /> : null}
        </li>)}
      </ul>}
  </section>;
}

/**
 * Interés editorial: the prompt an operator gives a language model, the preview and load of its
 * answer, and the loaded lists. The server validates, matches and stores; this tab presents.
 */
export function EditorialAttention({ csrfToken, now }: { csrfToken: string; now: Date }) {
  const overview = useQuery({
    queryKey: EDITORIAL_ATTENTION_KEY,
    queryFn: ({ signal }) => getEditorialAttention(signal),
    staleTime: 0,
  });
  if (overview.isPending) return <p className="result-count" role="status">Cargando el interés editorial…</p>;
  if (overview.isError) return <div className="notice notice-danger" role="alert">
    <p>No se pudo leer el interés editorial.</p>
    <button className="button button-danger" type="button" onClick={() => { void overview.refetch(); }}>Reintentar</button>
  </div>;
  return <>
    <PromptSection version={overview.data.prompt.version} template={overview.data.prompt.template} now={now} />
    <LoadSection csrfToken={csrfToken} />
    <LoadedLists lists={overview.data.lists} csrfToken={csrfToken} />
  </>;
}
