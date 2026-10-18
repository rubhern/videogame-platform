import { useQuery } from "@tanstack/react-query";
import { useEffect, useRef } from "react";
import { Link } from "react-router-dom";

import {
  ADMINISTRATION_KEY, AdministrationError, getSynchronizationCoverage, type SynchronizationCoverage,
} from "./administration-api";
import {
  coverageLabels, coveredShare, formatDay, formatInstant, formatInterval, formatMonth, freshnessLabel,
} from "./administration-format";
import {
  answers, decadeOf, lastDayOfMonth, monthSummaries, periodsWithin, RECENT_SCAN_DAYS, type CoverageSummary,
  yearSummaries,
} from "./coverage-calendar";

export const MIN_YEAR = 1950;
export const MAX_YEAR = 2099;

function useCoverage(from: string, to: string, enabled = true) {
  return useQuery<SynchronizationCoverage, AdministrationError>({
    queryKey: [...ADMINISTRATION_KEY, "coverage", from, to],
    queryFn: ({ signal }) => getSynchronizationCoverage({ from, to }, signal),
    enabled, staleTime: 0,
  });
}

function CoverageCell({ summary }: { summary: CoverageSummary }) {
  return <span className="admin-coverage">
    <span className="admin-mark" data-coverage={summary.state} aria-hidden="true" />
    {coverageLabels[summary.state]}
    {summary.incomplete && summary.state !== "unknown" ? <span className="admin-quiet">(resumen incompleto)</span> : null}
  </span>;
}

function SummaryCells({ summary, now }: { summary: CoverageSummary; now: Date }) {
  return <>
    <td><CoverageCell summary={summary} /></td>
    <td className="admin-number">{coveredShare(summary)}</td>
    <td>{summary.oldestScan ? formatInstant(summary.oldestScan) : "—"}</td>
    <td>
      <span className="admin-freshness" data-recent={summary.recent ?? undefined}>{freshnessLabel(summary, now)}</span>
      {summary.failedGames ? <span className="admin-quiet admin-block">Con juegos fallidos</span> : null}
    </td>
  </>;
}

function SummaryHead({ first }: { first: string }) {
  return <thead>
    <tr>
      <th scope="col">{first}</th>
      <th scope="col">Cobertura</th>
      <th scope="col">Días cubiertos</th>
      <th scope="col">Escaneo completo más antiguo</th>
      <th scope="col">Frescura</th>
    </tr>
  </thead>;
}

function Failure({ error, onRetry }: { error: AdministrationError | null; onRetry: () => void }) {
  return <div className="notice notice-danger" role="alert">
    <p className="notice-kicker">Error de carga</p>
    <p>No se pudo leer la cobertura de este periodo.</p>
    <button className="button button-danger" type="button" onClick={onRetry}>Reintentar</button>
    {error?.correlationId ? <p className="notice-reference">Referencia para soporte: {error.correlationId}</p> : null}
  </div>;
}

/**
 * Bounded historical coverage, read a decade at a time: years, then the selected year's months,
 * then a month's own periods. Every level derives from the same provider-independent periods.
 */
export function CoverageExplorer({ year, month, now }: { year: number; month: number | null; now: Date }) {
  const decade = decadeOf(year);
  const decadeFrom = `${decade}-01-01`;
  const decadeTo = `${decade + 9}-12-31`;
  const yearFrom = `${year}-01-01`;
  const yearTo = `${year}-12-31`;
  const decadeRead = useCoverage(decadeFrom, decadeTo);
  // A year has at most 366 stored intervals, below the server's bound, so one read answers it.
  const yearAnswered = decadeRead.data ? answers(decadeRead.data, yearFrom, yearTo) : false;
  const yearRead = useCoverage(yearFrom, yearTo, decadeRead.isSuccess && !yearAnswered);
  const yearData = yearAnswered ? decadeRead.data : yearRead.data;

  const yearHeading = useRef<HTMLHeadingElement>(null);
  const monthHeading = useRef<HTMLHeadingElement>(null);
  const previous = useRef({ year, month });
  // Drilling down or moving along the calendar moves focus to the level that changed.
  useEffect(() => {
    const before = previous.current;
    previous.current = { year, month };
    if (month !== null && month !== before.month) monthHeading.current?.focus();
    else if (year !== before.year) yearHeading.current?.focus();
  }, [year, month]);

  const monthFrom = month === null ? null : `${year}-${String(month).padStart(2, "0")}-01`;
  const monthTo = month === null ? null : lastDayOfMonth(year, month);

  return <div className="admin-coverage-explorer">
    <nav className="admin-decades" aria-label="Décadas">
      {decade - 10 >= decadeOf(MIN_YEAR) ? <Link className="button view-action view-action-back" to={`?tab=cobertura&year=${decade - 10}`}>
        <span aria-hidden="true">←</span> {decade - 10}–{decade - 1}
      </Link> : null}
      <p className="admin-decade" aria-live="polite">Década {decade}–{decade + 9}</p>
      {decade + 10 <= MAX_YEAR ? <Link className="button view-action" to={`?tab=cobertura&year=${decade + 10}`}>
        {decade + 10}–{decade + 19} <span aria-hidden="true">→</span>
      </Link> : null}
    </nav>

    {decadeRead.isPending ? <p className="admin-quiet" role="status">Cargando la cobertura de la década…</p>
      : decadeRead.isError ? <Failure error={decadeRead.error} onRetry={() => { void decadeRead.refetch(); }} />
        : <div className="admin-table-scroll" role="region" aria-label="Cobertura por año" tabIndex={0}>
          <table className="admin-table">
            <caption>Cobertura por año, {decade}–{decade + 9}</caption>
            <SummaryHead first="Año" />
            <tbody>
              {yearSummaries(decadeRead.data, decade, now).map((summary, index) => {
                const rowYear = decade + index;
                return <tr key={rowYear} data-selected={rowYear === year || undefined}>
                  <th scope="row">
                    <Link to={`?tab=cobertura&year=${rowYear}`} aria-current={rowYear === year ? "true" : undefined}>{rowYear}</Link>
                  </th>
                  <SummaryCells summary={summary} now={now} />
                </tr>;
              })}
            </tbody>
          </table>
        </div>}
    {decadeRead.data?.continuesFrom ? <p className="admin-quiet">
      La década tiene más intervalos de los que caben en una respuesta: el resumen se detiene el{" "}
      {formatDay(decadeRead.data.continuesFrom)}. Abre un año para verlo completo.
    </p> : null}

    <section className="admin-level" aria-labelledby="admin-year-title">
      <h3 id="admin-year-title" ref={yearHeading} tabIndex={-1}>Meses de {year}</h3>
      {yearData ? <div className="admin-table-scroll" role="region" aria-label={`Cobertura por mes de ${year}`} tabIndex={0}>
        <table className="admin-table">
          <caption>Cobertura por mes de {year}</caption>
          <SummaryHead first="Mes" />
          <tbody>
            {monthSummaries(yearData, year, now).map((summary, index) => {
              const rowMonth = index + 1;
              const selected = rowMonth === month;
              return <tr key={rowMonth} data-selected={selected || undefined}>
                <th scope="row">
                  <Link to={`?tab=cobertura&year=${year}&month=${rowMonth}`} aria-current={selected ? "true" : undefined}>
                    {formatMonth(year, rowMonth)}
                  </Link>
                </th>
                <SummaryCells summary={summary} now={now} />
              </tr>;
            })}
          </tbody>
        </table>
      </div>
        : yearRead.isError ? <Failure error={yearRead.error} onRetry={() => { void yearRead.refetch(); }} />
          : decadeRead.isError ? null : <p className="admin-quiet" role="status">Cargando los meses de {year}…</p>}
    </section>

    {month !== null && monthFrom && monthTo ? <section className="admin-level" aria-labelledby="admin-month-title">
      <h3 id="admin-month-title" ref={monthHeading} tabIndex={-1}>Periodos de {formatMonth(year, month).toLowerCase()} de {year}</h3>
      {yearData ? <ol className="admin-periods">
        {periodsWithin(yearData.periods, monthFrom, monthTo).map((period) => <li key={period.from}>
          <span className="admin-mark" data-coverage={period.status === "covered" ? "complete" : "never"} aria-hidden="true" />
          <strong>{formatInterval(period.from, period.to)}</strong>
          {" · "}{period.status === "covered" ? "Cubierto" : "Nunca cubierto"}
          {period.lastScan ? <span className="admin-quiet admin-block">
            Escaneo completo terminado el {formatInstant(period.lastScan.scanCompletedAt)}
            {period.lastScan.failedGames > 0
              ? ` · su ejecución tuvo ${period.lastScan.failedGames} juegos fallidos`
              : " · sin juegos fallidos"}
          </span> : null}
        </li>)}
      </ol> : null}
      <p><Link className="text-link" to={`?tab=cobertura&year=${year}`}>Cerrar el detalle del mes</Link></p>
    </section> : null}
    <p className="admin-footnote">
      «Reciente» significa que el escaneo completo más antiguo del periodo tiene {RECENT_SCAN_DAYS} días o menos.
    </p>
  </div>;
}
