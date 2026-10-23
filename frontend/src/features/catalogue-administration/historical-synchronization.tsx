import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRef, useState } from "react";
import {
  AdministrationError, controlHistoricalSynchronization, getHistoricalStatus, HISTORICAL_STATUS_KEY,
  startHistoricalSynchronization, SYNCHRONIZATION_STATUS_KEY,
  type HistoricalStatus, type HistoricalTarget, type SynchronizationStatus,
} from "./administration-api";
import { useSession } from "../session/use-session";

const STATES: Record<HistoricalStatus["state"], string> = {
  not_started: "Sin iniciar", running: "En curso", waiting: "Esperando otra sincronización",
  stopping: "Deteniéndose", stopped: "Detenida", interrupted: "Interrumpida",
  completed: "Completada", attention: "Requiere atención",
};
const ACTIVE: HistoricalStatus["state"][] = ["running", "waiting", "stopping"];
const number = (value: number | undefined) => value === undefined ? "No disponible" : value.toLocaleString("es-ES");
const instant = (value: string | undefined) => value ? new Date(value).toLocaleString("es-ES") : "No disponible";

const REASONS: Record<string, string> = {
  HISTORICAL_WORKER_INTERRUPTED: "El proceso se interrumpió. El último mes pendiente necesita una reanudación explícita.",
  HISTORICAL_INTERRUPTED: "La aplicación se detuvo antes de completar el histórico.",
  HISTORICAL_STOPPED: "La detención conserva los meses completados y los juegos ya guardados.",
  HISTORICAL_STOP_REQUESTED: "La detención está registrada; espera al siguiente punto seguro.",
  HISTORICAL_EXECUTION_FAILED: "Falló la orquestación. Revisa la ejecución vinculada antes de reanudar.",
  HISTORICAL_DISPATCH_FAILED: "No se pudo poner en marcha la ejecución. Revisa el estado antes de reanudar.",
  SYNCHRONIZATION_INTERRUPTED: "No terminó el recorrido del mes. Los meses anteriores se conservan.",
  SYNCHRONIZATION_FAILED: "La adquisición falló y necesita revisión antes de repetir el mes.",
  SYNCHRONIZATION_DISABLED: "Este entorno no tiene acceso configurado al proveedor.",
  SYNCHRONIZATION_OWNERSHIP_LOST: "La ejecución perdió su propiedad. Revisa su cierre antes de reanudar.",
  SYNCHRONIZATION_ALREADY_RUNNING: "Otra sincronización ocupa el turno. El histórico espera.",
  SCHEDULED_SYNCHRONIZATION_PENDING: "Una sincronización programada tiene el siguiente turno.",
  SYNCHRONIZATION_STOPPED: "La ejecución se detuvo en un punto seguro; el mes parcial se repetirá al reanudar.",
};

type Command = { action: "start"; target: HistoricalTarget } | { action: "stop" | "resume"; id: string };
function commandError(error: AdministrationError) {
  switch (error.code) {
    case "HISTORICAL_SYNCHRONIZATION_CONFLICT": return "Otra ejecución sigue activa o el estado ha cambiado. Consulta el estado antes de repetir. Conservamos el histórico pendiente.";
    case "HISTORICAL_SYNCHRONIZATION_NOT_FOUND": return "Este histórico ya no existe. Consulta el estado actual.";
    case "HISTORICAL_SYNCHRONIZATION_RANGE_INVALID": return "Revisa las fechas: desde 1950 hasta 2099, con el inicio anterior o igual al final.";
    case "CSRF_VALIDATION_FAILED": return "La prueba de seguridad ha caducado. Se está renovando; revisa el estado antes de repetir.";
    case "AUTHENTICATION_REQUIRED": return "Tu sesión ha terminado. Vuelve a iniciar sesión para continuar.";
    case "ACCESS_DENIED": return "Tu cuenta ya no tiene permiso para administrar el catálogo.";
    default: return "No se pudo confirmar la operación. Consulta el estado antes de repetirla.";
  }
}

/** Progress is always a persisted server snapshot. Local state holds only form and confirmation. */
export function HistoricalSynchronization({ csrfToken, ordinary }: { csrfToken: string; ordinary: SynchronizationStatus }) {
  const client = useQueryClient();
  const session = useSession();
  const [from, setFrom] = useState("2017-03-01");
  const [to, setTo] = useState("2026-09-30");
  const [coveredMonths, setCoveredMonths] = useState<HistoricalTarget["coveredMonths"]>("skip");
  const [confirmation, setConfirmation] = useState<Command | null>(null);
  const safeButton = useRef<HTMLButtonElement>(null);
  const trigger = useRef<HTMLElement | null>(null);
  const outcome = useRef<HTMLDivElement>(null);
  const query = useQuery<HistoricalStatus, AdministrationError>({
    queryKey: HISTORICAL_STATUS_KEY, queryFn: ({ signal }) => getHistoricalStatus(signal),
    staleTime: 0, refetchInterval: (q) => q.state.data && ACTIVE.includes(q.state.data.state) ? 5_000 : 15_000,
  });
  const mutation = useMutation<HistoricalStatus, AdministrationError, Command>({
    mutationFn: (command) => command.action === "start" ? startHistoricalSynchronization(command.target, csrfToken)
      : controlHistoricalSynchronization(command.action, command.id, csrfToken),
    onSuccess: (data) => { client.setQueryData(HISTORICAL_STATUS_KEY, data); },
    onError: (error) => { if (error.code === "CSRF_VALIDATION_FAILED") void session.refetch(); },
    onSettled: () => {
      setConfirmation(null);
      void client.invalidateQueries({ queryKey: HISTORICAL_STATUS_KEY });
      void client.invalidateQueries({ queryKey: SYNCHRONIZATION_STATUS_KEY });
      requestAnimationFrame(() => outcome.current?.focus());
    },
  });
  function confirm(command: Command, element: HTMLElement) {
    trigger.current = element; setConfirmation(command);
    requestAnimationFrame(() => safeButton.current?.focus());
  }
  function cancel() { setConfirmation(null); trigger.current?.focus(); }
  const data = query.data;
  const unfinished = data && data.state !== "not_started" && data.state !== "completed";
  const busy = mutation.isPending || query.isFetching || query.isError;
  const otherRun = ordinary.currentRun && ordinary.currentRun.runId !== data?.currentRunId;
  const invalidRange = !from || !to || from > to || from < "1950-01-01" || to > "2099-12-31";
  return <section className="admin-section" aria-labelledby="admin-history-title">
    <h2 id="admin-history-title">Sincronización histórica</h2>
    <p className="admin-lead">Recorre el intervalo por meses, uno a uno. Los meses completados quedan guardados.
      Detener o cerrar el navegador conserva el progreso; tras un reinicio solo continúa si lo reanudas.</p>
    {otherRun ? <p className="notice notice-warning" role="status">Hay una sincronización periódica o manual en curso.
      El histórico espera su turno; las programadas tienen prioridad entre meses.</p> : null}
    {query.isPending ? <p role="status">Cargando el histórico…</p> : null}
    {query.isError ? <div className="notice notice-danger" role="alert"><p>{commandError(query.error)}</p>
      {query.error.correlationId ? <p>Referencia: {query.error.correlationId}</p> : null}
      <button className="button" onClick={() => { void query.refetch(); }}>Reintentar carga</button></div> : null}
    {data ? <>
      <p className="admin-lead" role="status">Estado: <strong>{STATES[data.state]}</strong></p>
      {data.backfillId ? <>
        <dl className="admin-glossary">
          <div><dt>Intervalo solicitado</dt><dd>{data.from} — {data.to}</dd></div>
          <div><dt>Mes actual o pendiente</dt><dd>{data.currentFrom ? `${data.currentFrom} — ${data.currentTo}` : "Ninguno"}</dd></div>
          <div><dt>Meses completados</dt><dd>{number(data.completedMonths)} de {number(data.totalMonths)}; {number(data.skippedMonths)} omitidos por cobertura</dd></div>
          <div><dt>Meses pendientes</dt><dd>{number(data.remainingMonths)}</dd></div>
          <div><dt>Juegos procesados en meses completados</dt><dd>{number(data.processedGames)}</dd></div>
          <div><dt>Lanzamientos procesados en meses completados</dt><dd>{number(data.processedReleases)}</dd></div>
          <div><dt>Fallos conocidos de calidad de datos</dt><dd>{number(data.dataQualityFailedGames)}</dd></div>
          <div><dt>Inicio</dt><dd>{instant(data.startedAt)}</dd></div>
          <div><dt>Última actividad</dt><dd>{instant(data.lastActivityAt)}</dd></div>
          <div><dt>Tiempo transcurrido desde el inicio</dt><dd>{number(data.elapsedSeconds)} s</dd></div>
        </dl>
        <label className="admin-history-progress">Progreso por meses: {data.progressPercent}%
          <progress max={100} value={data.progressPercent ?? 0} aria-label="Progreso por meses" />
        </label>
        <p className="admin-lead">Los contadores suman trabajo por mes, no juegos distintos. Los omitidos no suman juegos.
          Un mes completado puede tener fallos de calidad: consulta Cobertura para ver los periodos escaneados.</p>
        {data.currentRunId ? <dl className="admin-glossary">
          <div><dt>Juegos del mes actual</dt><dd>{number(data.currentProcessedGames)}</dd></div>
          <div><dt>Lanzamientos del mes actual</dt><dd>{number(data.currentProcessedReleases)}</dd></div>
          <div><dt>Solicitudes al proveedor</dt><dd>{number(data.currentProviderRequests)}</dd></div>
          <div><dt>Fallos operativos del mes actual</dt><dd>{data.currentFailedGames === undefined || data.currentDataQualityFailedGames === undefined
            ? "No disponible" : number(Math.max(0, data.currentFailedGames - data.currentDataQualityFailedGames))}</dd></div>
          <div><dt>Fallos del mes actual</dt><dd>{number(data.currentFailedGames)}; {number(data.currentDataQualityFailedGames)} de calidad conocida</dd></div>
        </dl> : null}
        {data.state === "attention" || data.state === "interrupted" ? <div className="notice notice-warning" role="status">
          <p>La progresión está pausada. Revisa la causa antes de reanudar. Si la ejecución vinculada sigue activa,
            detenla y espera a su cierre; si su proceso ya no existe, usa el abandono de ejecuciones estancadas.</p>
        </div> : null}
        {data.lastReason ? <p className="admin-lead">{REASONS[data.lastReason] ?? "Consulta el resultado de la ejecución vinculada antes de continuar."} <span>Último motivo: <code>{data.lastReason}</code></span></p> : null}
        <div className="admin-history-actions">
          {ACTIVE.includes(data.state) && data.state !== "stopping" ? <button className="button" disabled={busy}
            onClick={(event) => confirm({ action: "stop", id: data.backfillId ?? "" }, event.currentTarget)}>Detener histórico</button> : null}
          {["stopped", "interrupted", "attention"].includes(data.state) ? <button className="button button-primary" disabled={busy}
            onClick={(event) => confirm({ action: "resume", id: data.backfillId ?? "" }, event.currentTarget)}>Reanudar histórico</button> : null}
          <button className="button" disabled={query.isFetching} onClick={() => { void query.refetch(); }}>Actualizar histórico</button>
        </div>
      </> : null}
      {!unfinished ? <form className="admin-history-form" onSubmit={(event) => {
        event.preventDefault(); if (!invalidRange) confirm({ action: "start", target: { from, to, coveredMonths } },
          event.currentTarget.querySelector("button") ?? event.currentTarget);
      }}>
        <label>Desde<input type="date" required min="1950-01-01" max="2099-12-31" value={from} onChange={(event) => setFrom(event.target.value)} /></label>
        <label>Hasta<input type="date" required min={from || "1950-01-01"} max="2099-12-31" value={to} onChange={(event) => setTo(event.target.value)} /></label>
        <label>Meses ya cubiertos<select value={coveredMonths} onChange={(event) => setCoveredMonths(event.target.value === "skip" ? "skip" : "rerun")}>
          <option value="skip">Omitir los completamente cubiertos</option><option value="rerun">Volver a procesarlos</option>
        </select></label>
        <p>Ambas fechas incluidas. Primer y último mes se ajustan a las fechas elegidas. Todas las plataformas compatibles.</p>
        <button className="button button-primary" disabled={busy || invalidRange}>Iniciar histórico</button>
      </form> : null}
    </> : null}
    {confirmation ? <div className="notice notice-warning" role="group" aria-label="Confirmar operación histórica"
      onKeyDown={(event) => { if (event.key === "Escape" && !mutation.isPending) cancel(); }}>
      <p>{confirmation.action === "start" ? `¿Iniciar el histórico entre ${confirmation.target.from} y ${confirmation.target.to}?`
        : confirmation.action === "stop" ? "¿Detener el histórico en su próximo punto seguro?" : "¿Reanudar desde el último mes pendiente?"}</p>
      <p>Puede realizar muchas solicitudes al proveedor. Los meses completados y los juegos guardados se conservan.
        Un mes parcial se repite al reanudar. Las sincronizaciones periódicas mantienen sus propios controles.</p>
      <button className="button" type="button" ref={safeButton} disabled={mutation.isPending} onClick={cancel}>Cancelar</button>
      <button className="button button-primary" type="button" disabled={mutation.isPending}
        onClick={() => mutation.mutate(confirmation)}>Confirmar {confirmation.action === "start" ? "inicio" : confirmation.action === "stop" ? "detención" : "reanudación"}</button>
    </div> : null}
    <div ref={outcome} tabIndex={-1} role={mutation.isError ? "alert" : "status"}>
      {mutation.isPending ? "Registrando operación…" : mutation.isError ? <div className="notice notice-danger">
        <p>{commandError(mutation.error)}</p>{mutation.error.correlationId ? <p>Referencia: {mutation.error.correlationId}</p> : null}
      </div> : mutation.isSuccess ? <p>Solicitud registrada. El estado persistido indica cuándo termina.</p> : null}
    </div>
  </section>;
}
