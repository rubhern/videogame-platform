import type { CurrentRun, FinishedRun, SynchronizationStatus } from "./administration-api";
import { formatCount, formatDuration, formatInstant, formatInterval, runReading } from "./administration-format";

/** The run holding synchronization ownership, or the calm statement that none does. */
export function CurrentRunPanel({ run }: { run: CurrentRun | undefined }) {
  if (!run) {
    return <p className="admin-quiet">No hay ninguna sincronización en curso.</p>;
  }
  return <div className="admin-current" data-stalled={run.stalled}>
    <p className="admin-run-result">
      <span className="admin-mark" data-tone={run.stalled ? "failed" : "running"} aria-hidden="true" />
      {run.stalled ? "En curso, sin señales recientes" : "En curso"}
    </p>
    <dl className="admin-facts">
      <div><dt>Intervalo</dt><dd>{formatInterval(run.from, run.to)}</dd></div>
      <div><dt>Inicio</dt><dd>{formatInstant(run.startedAt)}</dd></div>
      <div><dt>Última señal</dt><dd>{formatInstant(run.lastHeartbeatAt)}</dd></div>
    </dl>
    {run.stalled ? <p className="admin-warning">
      El proceso no ha dado señales dentro del plazo de abandono: la próxima sincronización que
      empiece la registrará como abandonada.
    </p> : null}
  </div>;
}

function Counters({ run }: { run: FinishedRun }) {
  const counters = run.counters;
  if (!counters) return <p className="admin-quiet">Sin contadores: la ejecución no llegó a informarlos.</p>;
  return <dl className="admin-facts admin-counters">
    <div>
      <dt>Juegos</dt>
      <dd>
        {formatCount(counters.createdGames)} nuevos · {formatCount(counters.updatedGames)} actualizados ·{" "}
        {formatCount(counters.unchangedGames)} sin cambios · {formatCount(counters.deferredGames)} aplazados ·{" "}
        <strong>{formatCount(counters.failedGames)} fallidos</strong>
      </dd>
    </div>
    <div>
      <dt>Lanzamientos</dt>
      <dd>
        {formatCount(counters.createdReleases)} nuevos · {formatCount(counters.updatedReleases)} actualizados ·{" "}
        {formatCount(counters.unchangedReleases)} sin cambios · {formatCount(counters.deletedReleases)} eliminados
      </dd>
    </div>
    <div>
      <dt>Peticiones al proveedor</dt>
      <dd>{formatCount(counters.providerRequests)} ({formatCount(counters.providerRetries)} reintentos)</dd>
    </div>
  </dl>;
}

/** Finished runs newest first: operational history, never a change to durable coverage. */
export function RecentRuns({ status }: { status: SynchronizationStatus }) {
  if (status.recentRuns.length === 0) {
    return <div className="notice notice-empty" role="status">
      <p className="notice-kicker">Sin historial</p>
      <h3>Todavía no hay ejecuciones terminadas</h3>
      <p>Cuando termine una sincronización aparecerá aquí con su resultado y sus contadores.</p>
    </div>;
  }
  return <ol className="admin-runs">
    {status.recentRuns.map((run) => {
      const reading = runReading(run);
      return <li key={run.runId}>
        <article className="admin-run" aria-labelledby={`run-${run.runId}`}>
          <header className="admin-run-head">
            <h3 className="admin-run-result" id={`run-${run.runId}`}>
              <span className="admin-mark" data-tone={reading.tone} aria-hidden="true" />
              {reading.label}
              <span className="sr-only">, {formatInterval(run.from, run.to)}</span>
            </h3>
            <p className="admin-run-time">
              {formatInstant(run.startedAt)} · {formatDuration(run.startedAt, run.completedAt)}
            </p>
          </header>
          <dl className="admin-facts">
            <div><dt>Intervalo</dt><dd>{formatInterval(run.from, run.to)}</dd></div>
            <div><dt>Código</dt><dd className="admin-code">{run.outcomeCode}</dd></div>
          </dl>
          <p className="admin-run-explanation">{reading.explanation}</p>
          <Counters run={run} />
        </article>
      </li>;
    })}
  </ol>;
}
