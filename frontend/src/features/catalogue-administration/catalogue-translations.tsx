import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import {
  AdministrationError,
  controlTranslationExecution,
  getTranslationCoverage,
  getTranslationExecution,
  TRANSLATION_COVERAGE_KEY,
  TRANSLATION_EXECUTION_KEY,
  type TranslationCommand,
  type TranslationCoverage,
  type TranslationExecution,
} from "./administration-api";

const KINDS = { summary: "Resúmenes", genre: "Géneros", game_mode: "Modos de juego" } as const;
const STATES: Record<TranslationExecution["state"], string> = {
  not_started: "Sin ejecuciones",
  running: "En curso",
  stopping: "Detención solicitada",
  stopped: "Detenida",
  interrupted: "Interrumpida",
  completed: "Completada",
  attention: "Requiere atención",
};
const REASONS: Record<NonNullable<TranslationExecution["reason"]>, string> = {
  batch_failed: "Un lote completo quedó sin traducir. Comprueba el servicio antes de reanudar. Podrás reintentar los fallos al terminar el recorrido.",
  remaining_failures: "El recorrido terminó con intentos fallidos o en curso. Revisa la cobertura antes de reintentar.",
  worker_failed: "El proceso se interrumpió. Puedes reanudar desde el último lote guardado.",
};
const COMMANDS: Record<TranslationCommand["action"], { label: string; question: string; detail: string }> = {
  start: {
    label: "Ejecutar traducciones pendientes",
    question: "¿Ejecutar las traducciones pendientes?",
    detail: "Se recorrerá el catálogo por lotes. Puedes cerrar esta página y volver para consultar el progreso.",
  },
  stop: {
    label: "Detener traducciones",
    question: "¿Detener la ejecución?",
    detail: "Se guardará el lote en curso antes de detenerse.",
  },
  resume: {
    label: "Reanudar traducciones",
    question: "¿Reanudar la ejecución?",
    detail: "Se conservarán las traducciones guardadas. Se retomará el recorrido o se revisarán los pendientes tras un fallo.",
  },
};
const count = new Intl.NumberFormat("es-ES");
const percentage = new Intl.NumberFormat("es-ES", { style: "percent", maximumFractionDigits: 1 });
const dateTime = new Intl.DateTimeFormat("es-ES", { dateStyle: "short", timeStyle: "medium" });

function CoverageBreakdown({ coverage }: { coverage: TranslationCoverage }) {
  return <div className="translation-coverage-grid">
    {(Object.keys(KINDS) as (keyof typeof KINDS)[]).map((kind) => {
      const rows = coverage.rows.filter((row) => row.kind === kind);
      const sum = (matches: (row: typeof rows[number]) => boolean) =>
        rows.reduce((total, row) => total + (matches(row) ? row.items : 0), 0);
      const total = sum(() => true);
      const notApplicable = sum((row) => row.status === "not_applicable");
      const eligible = total - notApplicable;
      const current = sum((row) => row.status === "current");
      const curated = sum((row) => row.origin === "curated");
      const figures: [string, number][] = [
        ["Elegibles", eligible],
        ["Traducción vigente", current],
        ["Pendientes", eligible - current],
        ["Listos para reutilizar", sum((row) => row.status === "reusable")],
        ["Traducción anterior conservada", sum((row) => row.lastValidRetained)],
        ["Fallidos o reintentables", sum((row) => row.status === "retryable")],
        ["En traducción", sum((row) => row.status === "busy")],
        ["No aplicables", notApplicable],
      ];
      return <section key={kind} className="translation-coverage-kind" aria-labelledby={`translation-kind-${kind}`}>
        <h3 id={`translation-kind-${kind}`}>{KINDS[kind]}</h3>
        {total === 0 ? <p>Sin contenido registrado.</p> : <dl className="admin-glossary">
          {figures.map(([label, items]) => <div key={label}>
            <dt>{label}</dt>
            <dd>{count.format(items)} · {percentage.format(items / total)}</dd>
          </div>)}
          {eligible > 0 ? <div><dt>Cobertura de los elegibles</dt><dd>{percentage.format(current / eligible)}</dd></div> : null}
          {curated > 0 ? <div><dt>Etiquetas curadas</dt><dd>{count.format(curated)}</dd></div> : null}
        </dl>}
      </section>;
    })}
  </div>;
}

function active(state: TranslationExecution["state"] | undefined) {
  return state === "running" || state === "stopping";
}

function commandFor(state: TranslationExecution["state"]): TranslationCommand["action"] | null {
  if (state === "running") return "stop";
  if (state === "stopped" || state === "interrupted" || state === "attention") return "resume";
  if (state === "not_started" || state === "completed") return "start";
  return null;
}

function ExecutionProgress({ execution }: { execution: TranslationExecution }) {
  const { processed = 0, estimatedTotal, succeeded = 0, skipped = 0, failed = 0 } = execution;
  const progress = execution.state === "completed" || execution.reason === "remaining_failures" ? 100
    : estimatedTotal ? Math.min(99, Math.floor(processed / estimatedTotal * 100)) : undefined;
  return <>
    <label className="admin-history-progress">
      {progress === undefined ? "Progreso del recorrido" : `Progreso estimado del recorrido · ${progress}%`}
      {progress !== undefined || active(execution.state) ? <progress max={100} value={progress} /> : null}
    </label>
    <dl className="admin-glossary">
      <div><dt>Procesados</dt><dd>{count.format(processed)}{estimatedTotal !== undefined ? ` de ${count.format(estimatedTotal)} estimados` : ""}</dd></div>
      <div><dt>Traducidos o reutilizados</dt><dd>{count.format(succeeded)}</dd></div>
      <div><dt>Omitidos</dt><dd>{count.format(skipped)}</dd></div>
      <div><dt>Fallidos o reservados</dt><dd>{count.format(failed)}</dd></div>
      {execution.currentKind ? <div><dt>Último lote</dt><dd>{KINDS[execution.currentKind]}</dd></div> : null}
      {execution.startedAt ? <div><dt>Inicio</dt><dd>{dateTime.format(new Date(execution.startedAt))}</dd></div> : null}
      {execution.lastActivityAt ? <div><dt>Última actividad</dt><dd>{dateTime.format(new Date(execution.lastActivityAt))}</dd></div> : null}
    </dl>
  </>;
}

export function CatalogueTranslations({ csrfToken }: { csrfToken: string }) {
  const client = useQueryClient();
  const [confirming, setConfirming] = useState<TranslationCommand | null>(null);
  const control = useRef<HTMLButtonElement>(null);
  const cancel = useRef<HTMLButtonElement>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const coverage = useQuery<TranslationCoverage, AdministrationError>({
    queryKey: TRANSLATION_COVERAGE_KEY,
    queryFn: ({ signal }) => getTranslationCoverage(signal),
    staleTime: 60_000,
    retry: false,
  });
  const status = useQuery<TranslationExecution, AdministrationError>({
    queryKey: TRANSLATION_EXECUTION_KEY,
    queryFn: ({ signal }) => getTranslationExecution(signal),
    refetchInterval: (query) => active(query.state.data?.state) ? 5_000 : 30_000,
    retry: false,
  });
  const execution = status.data;
  const action = execution ? commandFor(execution.state) : null;
  const confirmation = confirming?.action === action && confirming.executionId === execution?.executionId ? confirming : null;
  useEffect(() => { if (confirmation) cancel.current?.focus(); }, [confirmation]);
  const previousState = useRef<TranslationExecution["state"] | undefined>(undefined);
  useEffect(() => {
    const state = execution?.state;
    if (active(previousState.current) && !active(state)) {
      void client.invalidateQueries({ queryKey: TRANSLATION_COVERAGE_KEY });
    }
    previousState.current = state;
  }, [client, execution?.state]);

  const mutation = useMutation({
    mutationFn: (command: TranslationCommand) => controlTranslationExecution(command, csrfToken),
    onMutate: async () => { await client.cancelQueries({ queryKey: TRANSLATION_EXECUTION_KEY }); },
    onSuccess: (result) => { client.setQueryData(TRANSLATION_EXECUTION_KEY, result); },
    onSettled: () => { void client.invalidateQueries({ queryKey: TRANSLATION_EXECUTION_KEY }); },
  });

  function closeConfirmation() {
    setConfirming(null);
    control.current?.focus();
  }

  return <>
    <section className="admin-section" aria-labelledby="translation-coverage-title">
      <div className="admin-section-head">
        <h2 id="translation-coverage-title">Cobertura de traducción</h2>
        <button className="button" type="button" disabled={coverage.isFetching} onClick={() => { void coverage.refetch(); }}>
          {coverage.isFetching ? "Actualizando…" : "Actualizar cobertura"}
        </button>
      </div>
      {coverage.isPending ? <p role="status">Cargando cobertura…</p> : null}
      {coverage.isError ? <p className="notice notice-danger" role="alert">No se pudo actualizar la cobertura de traducción.</p> : null}
      {coverage.data ? <>
        <p className="admin-lead">Lectura del {dateTime.format(new Date(coverage.data.observedAt))}. Los porcentajes de cada estado se calculan sobre el total de su grupo. Los pendientes incluyen las traducciones anteriores conservadas, los reintentos y el trabajo en curso.</p>
        {coverage.data.rows.every((row) => row.status === "not_applicable")
          ? <p>No hay contenido elegible para traducir.</p> : null}
        <CoverageBreakdown coverage={coverage.data} />
      </> : null}
    </section>
    <section className="admin-section" aria-labelledby="translation-execution-title">
      <div className="admin-section-head">
        <h2 id="translation-execution-title" ref={heading} tabIndex={-1}>Ejecución de traducciones</h2>
        <button className="button" type="button" disabled={status.isFetching} onClick={() => { void status.refetch(); }}>
          Actualizar estado
        </button>
      </div>
      {status.isPending ? <p role="status">Cargando ejecución…</p> : null}
      {status.isError ? <p className="notice notice-danger" role="alert">El estado de ejecución no está disponible.</p> : null}
      {mutation.isError ? <p className="notice notice-danger" role="alert">
        {mutation.error instanceof AdministrationError && mutation.error.code === "TRANSLATION_EXECUTION_CONFLICT"
          ? "La ejecución cambió. Comprueba su estado antes de volver a actuar."
          : "No se pudo confirmar la operación. Comprueba el estado antes de repetirla."}
      </p> : null}
      {execution ? <>
        <p role="status" className="admin-lead"><strong>{STATES[execution.state]}</strong></p>
        {execution.reason ? <p className="notice notice-warning">{REASONS[execution.reason]}</p> : null}
        {execution.executionId ? <ExecutionProgress execution={execution} /> : <p>El catálogo todavía no tiene una ejecución registrada.</p>}
        {action ? <button ref={control} type="button" className={action === "stop" ? "button" : "button button-primary"}
          disabled={mutation.isPending || status.isError} aria-expanded={confirmation !== null}
          onClick={() => {
            mutation.reset();
            setConfirming({ action, ...(execution.executionId ? { executionId: execution.executionId } : {}) });
          }}>
          {execution.reason === "remaining_failures" ? "Reintentar traducciones" : COMMANDS[action].label}
        </button> : null}
      </> : null}
      {confirmation ? <div className="admin-confirm" role="group" aria-labelledby="translation-confirm-title"
        onKeyDown={(event) => { if (event.key === "Escape") closeConfirmation(); }}>
        <h3 id="translation-confirm-title">{COMMANDS[confirmation.action].question}</h3>
        <p>{COMMANDS[confirmation.action].detail}</p>
        <div className="admin-confirm-actions">
          <button ref={cancel} className="button" type="button" onClick={closeConfirmation}>Cancelar</button>
          <button className="button button-primary" type="button" disabled={status.isError}
            onClick={() => {
              setConfirming(null);
              heading.current?.focus();
              mutation.mutate(confirmation);
            }}>Confirmar</button>
        </div>
      </div> : null}
    </section>
  </>;
}
