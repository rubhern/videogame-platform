import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useEffect, useId, useRef, useState, type FormEvent } from "react";

import { authenticationStartUrl } from "../session/auth-entry";
import { SESSION_QUERY_KEY } from "../session/use-session";
import {
  AdministrationError, requestSynchronization, SYNCHRONIZATION_STATUS_KEY, type AcceptedRun, type SynchronizationStatus,
} from "./administration-api";
import { formatInterval, runReading } from "./administration-format";
import {
  EARLIEST, LATEST, MAX_INTERVAL_DAYS, readRequestedRun, validateWindow, type Field, type FieldErrors,
  type RequestedRun, type RequestedRunReading,
} from "./synchronization-request-state";

function serverFieldError(error: AdministrationError | null): FieldErrors {
  if (error?.code !== "SYNCHRONIZATION_RANGE_INVALID") return {};
  const message = `Usa fechas entre 1950 y 2099, con la final no anterior a la inicial y como máximo ${MAX_INTERVAL_DAYS} días.`;
  return error.pointer === "/from" ? { from: message } : { to: message };
}

function RequestedRunNotice({ requested, reading }: { requested: RequestedRun; reading: RequestedRunReading }) {
  const interval = formatInterval(requested.run.from, requested.run.to);
  switch (reading.phase) {
    case "accepted":
    case "running":
      return <>
        <p className="notice-kicker">Solicitud aceptada</p>
        <h3>{reading.phase === "running" ? "Sincronización en curso" : "Sincronización aceptada"}</h3>
        <p>{interval}. El estado se consulta de nuevo cada pocos segundos mientras dura; puedes salir de la página.</p>
        {reading.phase === "running" && reading.stalled ? <p className="admin-warning">
          El proceso no ha dado señales dentro del plazo de abandono.
        </p> : null}
      </>;
    case "finished": {
      const result = runReading(reading.run);
      return <>
        <p className="notice-kicker">Solicitud terminada</p>
        <h3>{result.label}</h3>
        <p>{interval}. {result.explanation}</p>
        <p className="admin-quiet">Sus contadores aparecen en Ejecuciones recientes.</p>
      </>;
    }
    case "missing":
      return <>
        <p className="notice-kicker">Solicitud aceptada</p>
        <h3>La ejecución ya no aparece en el historial</h3>
        <p>{interval}. El historial conservado es limitado; consulta la cobertura de esas fechas.</p>
      </>;
  }
}

function tone(reading: RequestedRunReading): string {
  if (reading.phase !== "finished") return "notice-info";
  const result = runReading(reading.run).tone;
  return result === "complete" ? "notice-info" : result === "partial" ? "notice-warning" : "notice-danger";
}

/** A rejection that started nothing is a warning; only an unexplained failure is a danger. */
function rejectionTone(error: AdministrationError): string {
  switch (error.code) {
    case "SYNCHRONIZATION_ALREADY_RUNNING":
    case "SYNCHRONIZATION_DISABLED":
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
    case "SYNCHRONIZATION_ALREADY_RUNNING":
      return <>
        <p className="notice-kicker">No se ha iniciado</p>
        <h3>Ya hay una sincronización en curso</h3>
        <p>Solo se ejecuta una a la vez. Espera a que termine la actual y vuelve a solicitarla.</p>
      </>;
    case "SYNCHRONIZATION_DISABLED":
      return <>
        <p className="notice-kicker">No se ha iniciado</p>
        <h3>La sincronización no está disponible</h3>
        <p>Este entorno no tiene acceso al proveedor del catálogo.</p>
      </>;
    case "CSRF_VALIDATION_FAILED":
      return <>
        <p className="notice-kicker">No se ha iniciado</p>
        <h3>No se pudo verificar la solicitud</h3>
        <p>Hemos renovado la verificación de tu sesión. Vuelve a solicitarla.</p>
      </>;
    case "AUTHENTICATION_REQUIRED":
      return <>
        <p className="notice-kicker">Sesión</p>
        <h3>Tu sesión ha terminado</h3>
        <p>Inicia sesión de nuevo para solicitar la sincronización.</p>
        <a className="button button-primary" href={authenticationStartUrl("/administracion/catalogo")}>Iniciar sesión</a>
      </>;
    case "ACCESS_DENIED":
      return <>
        <p className="notice-kicker">Acceso restringido</p>
        <h3>Tu cuenta no puede solicitar sincronizaciones</h3>
        <p>Esta acción requiere el rol de operador del catálogo.</p>
      </>;
    default:
      return <>
        <p className="notice-kicker">Error</p>
        <h3>No se pudo solicitar la sincronización</h3>
        <p>No se ha iniciado nada y los datos del catálogo no han cambiado. Inténtalo de nuevo en unos momentos.</p>
        {error.correlationId ? <p className="notice-reference">Referencia para soporte: {error.correlationId}</p> : null}
      </>;
  }
}

/**
 * The operator's bounded synchronization request: two labelled dates, the contract's limits in
 * words, and the outcome of the request followed through the ordinary status. The request answers
 * once the run owns the synchronization; it never waits for the provider.
 */
export function SynchronizationRequest({ csrfToken, status, statusReadAt, requested, onAccepted }: {
  csrfToken: string;
  status: SynchronizationStatus;
  statusReadAt: number;
  requested: RequestedRun | null;
  onAccepted: (requested: RequestedRun) => void;
}) {
  const queryClient = useQueryClient();
  const id = useId();
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [errors, setErrors] = useState<FieldErrors>({});
  const feedback = useRef<HTMLDivElement>(null);
  const [focusFeedback, setFocusFeedback] = useState(0);
  const command = useMutation<AcceptedRun, AdministrationError, { from: string; to: string }>({
    mutationFn: (window) => requestSynchronization(window, csrfToken),
    onSuccess: (run) => {
      onAccepted({ run, acceptedAt: Date.now() });
      void queryClient.invalidateQueries({ queryKey: SYNCHRONIZATION_STATUS_KEY });
      setFocusFeedback((count) => count + 1);
    },
    onError: (error) => {
      if (error.code === "SYNCHRONIZATION_ALREADY_RUNNING") {
        void queryClient.invalidateQueries({ queryKey: SYNCHRONIZATION_STATUS_KEY });
      }
      if (error.code === "CSRF_VALIDATION_FAILED" || error.code === "AUTHENTICATION_REQUIRED") {
        void queryClient.invalidateQueries({ queryKey: SESSION_QUERY_KEY });
      }
      setErrors(serverFieldError(error));
      if (error.code === "SYNCHRONIZATION_RANGE_INVALID") {
        document.getElementById(error.pointer === "/from" ? `${id}-from` : `${id}-to`)?.focus();
      } else {
        setFocusFeedback((count) => count + 1);
      }
    },
  });

  // Moving focus to the outcome is a synchronization with the DOM after the request settles.
  useEffect(() => {
    if (focusFeedback > 0) feedback.current?.focus();
  }, [focusFeedback]);

  const running = status.currentRun !== undefined;
  const reading = requested ? readRequestedRun(requested, status, statusReadAt) : null;
  // An invalid interval is answered beside its field; every other rejection gets a notice.
  const rejection = command.isError && command.error.code !== "SYNCHRONIZATION_RANGE_INVALID" ? command.error : null;

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const found = validateWindow(from, to);
    setErrors(found);
    if (found.from || found.to) {
      document.getElementById(found.from ? `${id}-from` : `${id}-to`)?.focus();
      return;
    }
    command.mutate({ from, to });
  }

  function field(name: Field, label: string, value: string, onChange: (value: string) => void) {
    const error = errors[name];
    return <div className="admin-field">
      <label htmlFor={`${id}-${name}`}>{label}</label>
      <input id={`${id}-${name}`} name={name} type="date" required min={EARLIEST} max={LATEST} value={value}
        aria-invalid={error ? true : undefined} aria-describedby={error ? `${id}-${name}-error` : undefined}
        onChange={(event) => { onChange(event.target.value); }} />
      {error ? <p className="admin-field-error" id={`${id}-${name}-error`}>{error}</p> : null}
    </div>;
  }

  return <div className="admin-request">
    <form className="admin-request-form" noValidate onSubmit={submit} aria-describedby={`${id}-limits`}>
      <div className="admin-request-fields">
        {field("from", "Desde", from, setFrom)}
        {field("to", "Hasta", to, setTo)}
        <button className="button button-primary" type="submit" disabled={command.isPending || running}>
          {command.isPending ? "Solicitando…" : "Solicitar sincronización"}
        </button>
      </div>
      <p className="admin-quiet" id={`${id}-limits`}>
        Ambas fechas se incluyen. Como máximo {MAX_INTERVAL_DAYS} días, entre 1950 y 2099; solo se ejecuta una
        sincronización a la vez, la programada incluida.
      </p>
      {running ? <p className="admin-quiet">Hay una sincronización en curso: podrás solicitar otra cuando termine.</p> : null}
    </form>
    <div ref={feedback} tabIndex={-1} role="status" className="admin-request-feedback">
      {rejection ? <div className={`notice ${rejectionTone(rejection)}`}>
        <RejectionNotice error={rejection} />
      </div>
        : requested && reading ? <div className={`notice ${tone(reading)}`}>
          <RequestedRunNotice requested={requested} reading={reading} />
        </div> : null}
    </div>
  </div>;
}
