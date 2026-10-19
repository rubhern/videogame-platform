import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useEffect, useId, useRef, useState } from "react";

import { authenticationStartUrl } from "../session/auth-entry";
import { SESSION_QUERY_KEY } from "../session/use-session";
import {
  abandonRun, AdministrationError, requestRunStop, SYNCHRONIZATION_STATUS_KEY, type CurrentRun,
  type SynchronizationStatus,
} from "./administration-api";
import { formatInterval, runReading, STOPPED } from "./administration-format";

type Action = "stop" | "abandon";

/** The run a command named, kept so its outcome is still told once the run leaves the panel. */
interface Command {
  action: Action;
  run: Pick<CurrentRun, "runId" | "from" | "to">;
}

/** Codes after which the status the page shows is no longer the server's: read it again. */
const REFRESHING = new Set(["SYNCHRONIZATION_RUN_FINISHED", "SYNCHRONIZATION_RUN_NOT_FOUND", "SYNCHRONIZATION_RUN_NOT_STALE"]);

function rejectionTone(error: AdministrationError): string {
  switch (error.code) {
    case "SYNCHRONIZATION_RUN_FINISHED":
    case "SYNCHRONIZATION_RUN_NOT_FOUND":
    case "SYNCHRONIZATION_RUN_NOT_STALE":
    case "CSRF_VALIDATION_FAILED":
    case "ACCESS_DENIED":
      return "notice-warning";
    case "AUTHENTICATION_REQUIRED":
      return "notice-empty";
    default:
      return "notice-danger";
  }
}

function RejectionNotice({ error }: { error: AdministrationError }) {
  switch (error.code) {
    case "SYNCHRONIZATION_RUN_FINISHED":
      return <>
        <p className="notice-kicker">Sin cambios</p>
        <h3>La ejecución ya había terminado</h3>
        <p>No se ha cambiado nada. Su resultado aparece en Ejecuciones recientes.</p>
      </>;
    case "SYNCHRONIZATION_RUN_NOT_FOUND":
      return <>
        <p className="notice-kicker">Sin cambios</p>
        <h3>La ejecución ya no está en el historial</h3>
        <p>No se ha cambiado nada. El estado se ha actualizado con la ejecución actual, si la hay.</p>
      </>;
    case "SYNCHRONIZATION_RUN_NOT_STALE":
      return <>
        <p className="notice-kicker">No se ha abandonado</p>
        <h3>La ejecución sigue dando señales</h3>
        <p>Su proceso ha dado señales dentro del plazo de abandono, así que sigue siendo suyo. Si quieres
          terminarla, solicita su detención.</p>
      </>;
    case "CSRF_VALIDATION_FAILED":
      return <>
        <p className="notice-kicker">Sin cambios</p>
        <h3>No se pudo verificar la orden</h3>
        <p>Hemos renovado la verificación de tu sesión. Vuelve a intentarlo.</p>
      </>;
    case "AUTHENTICATION_REQUIRED":
      return <>
        <p className="notice-kicker">Sesión</p>
        <h3>Tu sesión ha terminado</h3>
        <p>Inicia sesión de nuevo para controlar la sincronización.</p>
        <a className="button button-primary" href={authenticationStartUrl("/administracion/catalogo")}>Iniciar sesión</a>
      </>;
    case "ACCESS_DENIED":
      return <>
        <p className="notice-kicker">Acceso restringido</p>
        <h3>Tu cuenta no puede controlar sincronizaciones</h3>
        <p>Esta acción requiere el rol de operador del catálogo.</p>
      </>;
    default:
      return <>
        <p className="notice-kicker">Error</p>
        <h3>No se pudo enviar la orden</h3>
        <p>Consulta el estado actualizado antes de repetirla: puede no haberse aplicado.</p>
        {error.correlationId ? <p className="notice-reference">Referencia para soporte: {error.correlationId}</p> : null}
      </>;
  }
}

/** Where the commanded run is now, read from the ordinary status rather than the command's answer. */
function OutcomeNotice({ command, status }: { command: Command; status: SynchronizationStatus }) {
  const interval = formatInterval(command.run.from, command.run.to);
  const finished = status.recentRuns.find((run) => run.runId === command.run.runId);
  if (command.action === "abandon") {
    return <div className="notice notice-warning">
      <p className="notice-kicker">Ejecución abandonada</p>
      <h3>Abandonada y cerrada</h3>
      <p>{interval}. Su proceso, si reaparece, ya no puede publicar juegos, registrar cobertura ni terminarla. Lo
        ya publicado se conserva y no se ha reintentado nada: ya puedes solicitar otra sincronización.</p>
    </div>;
  }
  if (status.currentRun?.runId === command.run.runId) {
    return <div className="notice notice-info">
      <p className="notice-kicker">Detención solicitada</p>
      <h3>Se detendrá en su próximo punto seguro</h3>
      <p>{interval}. Sigue en curso hasta terminar el juego o la página que procesa. El estado se consulta de nuevo
        cada pocos segundos; puedes salir de la página.</p>
    </div>;
  }
  if (finished) {
    const reading = runReading(finished);
    const stopped = finished.outcomeCode === STOPPED;
    return <div className={`notice ${stopped || reading.tone === "partial" ? "notice-warning"
      : reading.tone === "complete" ? "notice-info" : "notice-danger"}`}>
      <p className="notice-kicker">{stopped ? "Detención completada" : "Terminó antes de detenerse"}</p>
      <h3>{reading.label}</h3>
      <p>{interval}. {reading.explanation}</p>
    </div>;
  }
  return <div className="notice notice-info">
    <p className="notice-kicker">Detención solicitada</p>
    <h3>La ejecución ya no aparece en el historial</h3>
    <p>{interval}. El historial conservado es limitado; consulta la cobertura de esas fechas.</p>
  </div>;
}

/**
 * Stop and abandonment of the run in flight, each confirmed once more and sent for that run's
 * identifier. A stop is a request the run honours at its next safe point; abandonment is offered
 * only while the status reports the run stalled, and the server decides it again.
 */
export function RunControls({ csrfToken, status }: { csrfToken: string; status: SynchronizationStatus }) {
  const queryClient = useQueryClient();
  const id = useId();
  const run = status.currentRun;
  const [confirming, setConfirming] = useState<Command | null>(null);
  const [sent, setSent] = useState<Command | null>(null);
  const triggers = useRef<Partial<Record<Action, HTMLButtonElement | null>>>({});
  const keep = useRef<HTMLButtonElement>(null);
  const feedback = useRef<HTMLDivElement>(null);
  const [focusFeedback, setFocusFeedback] = useState(0);
  const command = useMutation<unknown, AdministrationError, Command>({
    mutationFn: ({ action, run: target }) => action === "stop"
      ? requestRunStop(target.runId, csrfToken) : abandonRun(target.runId, csrfToken),
    onMutate: (target) => { setSent(target); setConfirming(null); },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: SYNCHRONIZATION_STATUS_KEY });
      setFocusFeedback((count) => count + 1);
    },
    onError: (error) => {
      if (error.code !== null && REFRESHING.has(error.code)) {
        void queryClient.invalidateQueries({ queryKey: SYNCHRONIZATION_STATUS_KEY });
      }
      if (error.code === "CSRF_VALIDATION_FAILED" || error.code === "AUTHENTICATION_REQUIRED") {
        void queryClient.invalidateQueries({ queryKey: SESSION_QUERY_KEY });
      }
      setFocusFeedback((count) => count + 1);
    },
  });

  // A confirmation belongs to the run it was opened for; a replaced run closes it.
  const open = confirming && run?.runId === confirming.run.runId ? confirming.action : null;

  // Moving focus is a synchronization with the DOM: into a confirmation as it opens, and to the
  // outcome once a command settles.
  useEffect(() => {
    if (open) keep.current?.focus();
  }, [open]);
  useEffect(() => {
    if (focusFeedback > 0) feedback.current?.focus();
  }, [focusFeedback]);

  function cancel(action: Action) {
    setConfirming(null);
    triggers.current[action]?.focus();
  }

  const busy = command.isPending;
  const canStop = run !== undefined && !run.stopRequestedAt;
  const canAbandon = run?.stalled === true;
  const interval = run ? formatInterval(run.from, run.to) : "";

  return <div className="admin-control">
    {run && (canStop || canAbandon) ? <div className="admin-control-actions">
      {canStop ? <button ref={(element) => { triggers.current.stop = element; }} className="button" type="button"
        disabled={busy} aria-expanded={open === "stop"} aria-controls={`${id}-confirm`}
        onClick={() => { setConfirming(open === "stop" ? null : { action: "stop", run }); }}>
        Detener sincronización
      </button> : null}
      {canAbandon ? <button ref={(element) => { triggers.current.abandon = element; }} className="button button-danger"
        type="button" disabled={busy} aria-expanded={open === "abandon"} aria-controls={`${id}-confirm`}
        onClick={() => { setConfirming(open === "abandon" ? null : { action: "abandon", run }); }}>
        Abandonar ejecución
      </button> : null}
    </div> : null}
    {run && open ? <div className="admin-confirm" id={`${id}-confirm`} role="group" aria-labelledby={`${id}-confirm-title`}
      aria-describedby={`${id}-confirm-detail`}
      onKeyDown={(event) => { if (event.key === "Escape") cancel(open); }}>
      {open === "stop" ? <>
        <h3 id={`${id}-confirm-title`}>¿Detener la sincronización de {interval}?</h3>
        <p id={`${id}-confirm-detail`}>
          Terminará en su próximo punto seguro, al acabar el juego o la página que procesa, no al instante. Lo ya
          publicado se conserva, el intervalo no quedará registrado como cubierto y nada se reintenta. La
          sincronización programada sigue su calendario.
        </p>
        <div className="admin-confirm-actions">
          <button ref={keep} className="button" type="button" onClick={() => cancel("stop")}>Seguir sincronizando</button>
          <button className="button button-danger" type="button" disabled={busy}
            onClick={() => command.mutate({ action: "stop", run })}>Sí, detener</button>
        </div>
      </> : <>
        <h3 id={`${id}-confirm-title`}>¿Abandonar la ejecución de {interval}?</h3>
        <p id={`${id}-confirm-detail`} className="admin-warning">
          Úsalo solo si su proceso ya no existe, por ejemplo tras reiniciar la aplicación: lleva más del plazo de
          abandono sin dar señales. Se cerrará como abandonada y su proceso, si reaparece, no podrá publicar más
          juegos, registrar cobertura ni terminarla. Lo ya publicado se conserva y no se deshace. Si su proceso da
          señales antes, el servidor no la abandonará.
        </p>
        <div className="admin-confirm-actions">
          <button ref={keep} className="button" type="button" onClick={() => cancel("abandon")}>Conservar ejecución</button>
          <button className="button button-danger" type="button" disabled={busy}
            onClick={() => command.mutate({ action: "abandon", run })}>Sí, abandonar</button>
        </div>
      </>}
    </div> : null}
    {busy ? <p className="admin-quiet" role="status">
      {sent?.action === "abandon" ? "Abandonando la ejecución…" : "Solicitando la detención…"}
    </p> : null}
    <div ref={feedback} tabIndex={-1} role="status" className="admin-request-feedback">
      {busy ? null : command.isError ? <div className={`notice ${rejectionTone(command.error)}`}>
        <RejectionNotice error={command.error} />
      </div> : command.isSuccess && sent ? <OutcomeNotice command={sent} status={status} /> : null}
    </div>
  </div>;
}
