import { useQuery } from "@tanstack/react-query";
import { useRef, useState, type KeyboardEvent } from "react";
import { Link, useSearchParams } from "react-router-dom";

import {
  ADMINISTRATION_KEY, AdministrationError, getSynchronizationStatus, type SynchronizationStatus,
} from "../features/catalogue-administration/administration-api";
import { CoverageExplorer, MAX_YEAR, MIN_YEAR } from "../features/catalogue-administration/coverage-explorer";
import { CurrentRunPanel, RecentRuns } from "../features/catalogue-administration/synchronization-runs";
import { authenticationStartUrl } from "../features/session/auth-entry";
import { useSession } from "../features/session/use-session";
import { HeroTitle } from "../shared/ui/hero-title";

type PanelTab = "ejecuciones" | "cobertura";
const TABS: { id: PanelTab; label: string }[] = [
  { id: "ejecuciones", label: "Ejecuciones" },
  { id: "cobertura", label: "Cobertura" },
];

/** While a run is in flight the status is read again on this cadence; no push channel exists. */
const RUNNING_REFRESH_MS = 15_000;

function selectedYear(value: string | null, fallback: number): number {
  const year = value !== null && /^\d{4}$/.test(value) ? Number(value) : fallback;
  return year >= MIN_YEAR && year <= MAX_YEAR ? year : fallback;
}

function selectedMonth(value: string | null): number | null {
  if (value === null || !/^\d{1,2}$/.test(value)) return null;
  const month = Number(value);
  return month >= 1 && month <= 12 ? month : null;
}

function SignInRequired() {
  return <div className="notice notice-empty" role="status">
    <p className="notice-kicker">Sesión</p>
    <h2>Necesitas una sesión activa</h2>
    <p>Inicia sesión con una cuenta de operador del catálogo para ver la sincronización.</p>
    <a className="button button-primary" href={authenticationStartUrl("/administracion/catalogo")}>Iniciar sesión</a>
  </div>;
}

function AccessDenied() {
  return <div className="notice notice-warning" role="status">
    <p className="notice-kicker">Acceso restringido</p>
    <h2>Tu cuenta no puede administrar el catálogo</h2>
    <p>
      Esta vista solo está disponible para cuentas con el rol de operador del catálogo. Si acabas de
      recibirlo, cierra la sesión y vuelve a entrar.
    </p>
    <Link className="button button-primary" to="/">Volver al inicio</Link>
  </div>;
}

/**
 * Catalogue acquisition evidence for the operator: the run in flight, bounded recent runs and
 * historical coverage. The server authorizes every read; this page only presents its answers.
 */
/**
 * Two tabs over one page: the selection lives in the URL, so a reload, a shared link or a return
 * from sign-in reopens the same view. Arrow keys, Home and End move between tabs.
 */
function PanelTabs({ selected, onSelect }: { selected: PanelTab; onSelect: (tab: PanelTab) => void }) {
  const tabs = useRef<(HTMLButtonElement | null)[]>([]);
  function move(event: KeyboardEvent<HTMLDivElement>) {
    const current = TABS.findIndex((tab) => tab.id === selected);
    const next = event.key === "ArrowRight" ? (current + 1) % TABS.length
      : event.key === "ArrowLeft" ? (current - 1 + TABS.length) % TABS.length
        : event.key === "Home" ? 0 : event.key === "End" ? TABS.length - 1 : null;
    if (next === null) return;
    event.preventDefault();
    const tab = TABS[next];
    if (tab) {
      onSelect(tab.id);
      tabs.current[next]?.focus();
    }
  }
  return <div className="admin-tabs" role="tablist" aria-label="Secciones del panel" onKeyDown={move}>
    {TABS.map((tab, index) => <button key={tab.id} ref={(element) => { tabs.current[index] = element; }}
      className="admin-tab" id={`admin-tab-${tab.id}`} role="tab" type="button"
      aria-selected={tab.id === selected} aria-controls={`admin-panel-${tab.id}`}
      tabIndex={tab.id === selected ? 0 : -1} onClick={() => onSelect(tab.id)}>
      {tab.label}
    </button>)}
  </div>;
}

export function CatalogueAdministrationPage() {
  const session = useSession();
  const [params, setParams] = useSearchParams();
  const tab: PanelTab = params.get("tab") === "cobertura" ? "cobertura" : "ejecuciones";
  function selectTab(next: PanelTab) {
    if (next === tab) return;
    setParams((current) => {
      const updated = new URLSearchParams(current);
      updated.set("tab", next);
      return updated;
    });
  }
  // Freshness is judged against one instant per visit, so every row reads against the same now.
  const [now] = useState(() => new Date());
  const year = selectedYear(params.get("year"), now.getUTCFullYear());
  const month = selectedMonth(params.get("month"));
  const authenticated = session.data?.authenticated === true;
  const status = useQuery<SynchronizationStatus, AdministrationError>({
    queryKey: [...ADMINISTRATION_KEY, "status"],
    queryFn: ({ signal }) => getSynchronizationStatus(signal),
    enabled: authenticated,
    staleTime: 0,
    refetchInterval: (query) => (query.state.data?.currentRun ? RUNNING_REFRESH_MS : false),
  });
  const code = status.error?.code;

  return <section className="hero-page admin-page" aria-labelledby="admin-title">
    <div className="page-container">
      <header className="page-head">
        <p className="eyebrow">Panel de administrador</p>
        <HeroTitle id="admin-title" title="Sincronización y cobertura" />
      </header>
      {session.isPending ? <p className="result-count" role="status">Comprobando sesión…</p>
        : session.isError ? <div className="notice notice-danger" role="alert">
          <p className="notice-kicker">Sesión</p>
          <p>No se pudo comprobar tu sesión.</p>
          <button className="button button-danger" type="button" onClick={() => { void session.refetch(); }}>Reintentar</button>
        </div>
          : !authenticated || code === "AUTHENTICATION_REQUIRED" ? <SignInRequired />
            : code === "ACCESS_DENIED" ? <AccessDenied />
              : status.isError ? <div className="notice notice-danger" role="alert">
                <p className="notice-kicker">Error de carga</p>
                <h2>No se pudo leer el estado de la sincronización</h2>
                <p>Los datos del catálogo no han cambiado. Inténtalo de nuevo en unos momentos.</p>
                <button className="button button-danger" type="button" onClick={() => { void status.refetch(); }}>Reintentar</button>
                {status.error.correlationId ? <p className="notice-reference">Referencia para soporte: {status.error.correlationId}</p> : null}
              </div>
                : status.isPending ? <p className="result-count" role="status">Cargando el estado de la sincronización…</p>
                  : <>
                    <PanelTabs selected={tab} onSelect={selectTab} />
                    <div className="admin-panel" id={`admin-panel-${tab}`} role="tabpanel"
                      aria-labelledby={`admin-tab-${tab}`}>
                    {tab === "ejecuciones" ? <>
                    <section className="admin-section" aria-labelledby="admin-current-title">
                      <div className="admin-section-head">
                        <h2 id="admin-current-title">Ejecución actual</h2>
                        <button className="button" type="button" disabled={status.isFetching}
                          onClick={() => { void status.refetch(); }}>
                          {status.isFetching ? "Actualizando…" : "Actualizar estado"}
                        </button>
                      </div>
                      <CurrentRunPanel run={status.data.currentRun} />
                    </section>
                    <section className="admin-section" aria-labelledby="admin-runs-title">
                      <h2 id="admin-runs-title">Ejecuciones recientes</h2>
                      <p className="admin-lead">
                        Historial operativo limitado a las últimas ejecuciones conservadas. Una ejecución
                        fallida, interrumpida o abandonada nunca cambia la cobertura ya registrada.
                      </p>
                      <RecentRuns status={status.data} />
                    </section>
                    </> : <section className="admin-section" aria-labelledby="admin-coverage-title">
                      <h2 id="admin-coverage-title">Cobertura histórica</h2>
                      <dl className="admin-glossary">
                        <div>
                          <dt>Cubierto</dt>
                          <dd>
                            Una sincronización leyó por completo esas fechas de lanzamiento en el momento indicado.
                            No garantiza que el proveedor no cambie esos datos después.
                          </dd>
                        </div>
                        <div>
                          <dt>Nunca cubierto</dt>
                          <dd>
                            Ninguna sincronización completa ha recorrido esas fechas desde que se registra la
                            cobertura. Las ejecuciones anteriores a ese registro no cuentan, aunque el catálogo ya
                            tenga juegos de esas fechas.
                          </dd>
                        </div>
                        <div>
                          <dt>Frescura</dt>
                          <dd>Antigüedad del escaneo completo más antiguo. Es una lectura de esta vista, no una caducidad.</dd>
                        </div>
                      </dl>
                      <CoverageExplorer year={year} month={month} now={now} />
                    </section>}
                    </div>
                  </>}
    </div>
  </section>;
}
