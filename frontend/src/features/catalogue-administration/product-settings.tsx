import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState, type FormEvent } from "react";

import {
  AdministrationError,
  getProductSettings,
  PRODUCT_SETTINGS_KEY,
  updateProductSettings,
  type ProductSettingKey,
  type ProductSettingsCoverage,
  type ProductSettingsOverview,
  type ProductSettingValue,
} from "./administration-api";
import { changesOf, draftError, withDraft, type Drafts } from "./product-settings-drafts";

type Group = ProductSettingValue["group"];

const GROUPS: { id: Group; title: string; lead: string }[] = [
  {
    id: "featured",
    title: "Destacados",
    lead: "Cuántas semanas y meses se pueden recorrer en Destacados, hacia atrás y hacia delante. Los meses hacia delante también limitan el calendario de lanzamientos.",
  },
  {
    id: "upcoming_highlights",
    title: "Próximos destacados",
    lead: "El horizonte en meses naturales de Próximos destacados de cada plataforma, contado desde hoy en Madrid.",
  },
  {
    id: "attention",
    title: "Fórmula de interés",
    lead: "Pesos del interés que ordena Destacados, Explorar por interés, el calendario y Próximos destacados. Las tres señales de IGDB pesan entre sí; el peso editorial es la parte del interés que aporta la lista editorial.",
  },
  {
    id: "rankings",
    title: "TOPómetros",
    lead: "Cuántas puntuaciones necesita un juego para entrar en cada TOPómetro.",
  },
  {
    id: "calendar",
    title: "Calendario",
    lead: "Qué juegos cuentan en un día, cuántos se muestran al elegirlo y a partir de qué suma de Hypes se colorea cada nivel de interés. Los umbrales deben crecer de azul a rojo.",
  },
  {
    id: "synchronization",
    title: "Sincronización programada",
    lead: "Los días que pide cada sincronización programada. Se aplican en la siguiente ejecución programada; la hora sigue en la configuración del despliegue y nada se sincroniza al guardar.",
  },
];

const SETTINGS: Record<ProductSettingKey, { label: string; unit: string }> = {
  "featured.weeks-back": { label: "Semanas hacia atrás", unit: "semanas" },
  "featured.weeks-forward": { label: "Semanas hacia delante", unit: "semanas" },
  "featured.months-back": { label: "Meses hacia atrás", unit: "meses" },
  "featured.months-forward": { label: "Meses hacia delante", unit: "meses" },
  "upcoming-highlights.horizon-months": { label: "Horizonte", unit: "meses" },
  "attention.hypes-weight": { label: "Peso de Hypes", unit: "" },
  "attention.want-to-play-weight": { label: "Peso de Want to Play", unit: "" },
  "attention.visits-weight": { label: "Peso de Visits", unit: "" },
  "attention.editorial-weight": { label: "Peso editorial", unit: "de 0 a 1" },
  "attention.inherited-share": { label: "Parte heredada por ports y remakes", unit: "de 0 a 1" },
  "attention.re-release-share": { label: "Parte que conserva un relanzamiento", unit: "de 0 a 1" },
  "rankings.igdb-minimum-ratings": { label: "Mínimo de puntuaciones IGDB", unit: "puntuaciones" },
  "rankings.community-minimum-ratings": { label: "Mínimo de puntuaciones Gameómetro", unit: "puntuaciones" },
  "calendar.minimum-hypes": { label: "Hypes mínimos para contar un juego", unit: "Hypes" },
  "calendar.day-preview-size": { label: "Juegos mostrados al elegir un día", unit: "juegos" },
  "calendar.low-interest-hypes": { label: "Interés bajo (azul) desde", unit: "Hypes al día" },
  "calendar.moderate-interest-hypes": { label: "Interés moderado (amarillo) desde", unit: "Hypes al día" },
  "calendar.high-interest-hypes": { label: "Interés alto (naranja) desde", unit: "Hypes al día" },
  "calendar.very-high-interest-hypes": { label: "Interés muy alto (rojo) desde", unit: "Hypes al día" },
  "synchronization.near-term.past-days": { label: "Diaria: días hacia atrás", unit: "días" },
  "synchronization.near-term.future-days": { label: "Diaria: días hacia delante", unit: "días" },
  "synchronization.upcoming.past-days": { label: "Semanal: días hacia atrás", unit: "días" },
  "synchronization.upcoming.future-days": { label: "Semanal: días hacia delante", unit: "días" },
};

const ORIGIN: Record<ProductSettingValue["origin"], string> = {
  default: "Valor por defecto",
  deployment: "Valor del despliegue",
  operator: "Cambiado por un operador",
  invalid_override: "Valor guardado no válido: se usa el valor por defecto",
};

const number = new Intl.NumberFormat("es-ES", { maximumFractionDigits: 4 });
const day = new Intl.DateTimeFormat("es-ES", { dateStyle: "medium", timeZone: "UTC" });
const dateTime = new Intl.DateTimeFormat("es-ES", { dateStyle: "short", timeStyle: "short" });
const monthName = new Intl.DateTimeFormat("es-ES", { month: "long", year: "numeric", timeZone: "UTC" });

function label(key: string): string {
  return key in SETTINGS ? SETTINGS[key as ProductSettingKey].label : key;
}

function failureMessage(error: unknown): { tone: "warning" | "danger"; text: string } {
  if (!(error instanceof AdministrationError)) return { tone: "danger", text: "No se pudo guardar." };
  switch (error.code) {
    case "PRODUCT_SETTINGS_REVISION_CONFLICT":
      return { tone: "warning", text: "Otro cambio se guardó antes. Los valores se han vuelto a leer: revísalos y guarda de nuevo; no se ha cambiado nada." };
    case "PRODUCT_SETTING_INVALID":
      return error.pointer === "/changes"
        ? { tone: "warning", text: "La combinación no es válida: alguna señal de IGDB debe pesar y los umbrales del calendario deben crecer de azul a rojo. No se ha cambiado nada." }
        : { tone: "warning", text: `Hay un valor fuera de su rango${error.pointer ? ` (${error.pointer})` : ""}. No se ha cambiado nada.` };
    case "CSRF_VALIDATION_FAILED":
      return { tone: "warning", text: "La comprobación de seguridad caducó. Recarga la página e inténtalo de nuevo; no se ha cambiado nada." };
    case "ACCESS_DENIED":
      return { tone: "warning", text: "Tu cuenta ya no tiene el rol de operador del catálogo." };
    case "AUTHENTICATION_REQUIRED":
      return { tone: "warning", text: "Tu sesión ha caducado. Vuelve a iniciar sesión." };
    default:
      return {
        tone: "danger",
        text: `No se pudo guardar.${error.correlationId ? ` Referencia para soporte: ${error.correlationId}.` : ""}`,
      };
  }
}

function formatMonth(month: string): string {
  return monthName.format(new Date(`${month}-01T00:00:00Z`));
}

/** Where presentation reaches beyond what scheduled acquisition is configured to cover. */
function CoverageNotice({ coverage }: { coverage: ProductSettingsCoverage }) {
  const beyond: string[] = [];
  if (coverage.featuredBeyondAcquisition) {
    beyond.push(`Destacados y el calendario llegan hasta ${formatMonth(coverage.featuredLastMonth)}`);
  }
  if (coverage.upcomingBeyondAcquisition) {
    beyond.push(`Próximos destacados llega hasta el ${day.format(new Date(`${coverage.upcomingHorizonEnd}T00:00:00Z`))}`);
  }
  const reach = `La sincronización programada llega hasta el ${day.format(new Date(`${coverage.acquisitionEnd}T00:00:00Z`))} (${coverage.acquisitionForwardDays} días hacia delante).`;
  return <div className="admin-settings-coverage">
    {!coverage.schedulingEnabled ? <p className="notice notice-warning" role="status">
      Este despliegue no ejecuta las sincronizaciones programadas: nada amplía el catálogo automáticamente.
    </p> : null}
    {beyond.length > 0 ? <div className="notice notice-warning" role="status">
      <p className="notice-kicker">Más allá de la sincronización</p>
      <p>{beyond.join(". ")}. {reach}</p>
      <p>
        Mostrar esos periodos no trae sus datos: aparecerán incompletos o vacíos. Amplía los días hacia
        delante de la sincronización semanal o solicita una sincronización de ese intervalo en Ejecuciones.
      </p>
    </div> : <p className="admin-quiet">{reach} Las ventanas de presentación quedan dentro.</p>}
  </div>;
}

function SettingField({ setting, draft, onDraft }: {
  setting: ProductSettingValue;
  draft: string | null | undefined;
  onDraft: (draft: string | null | undefined) => void;
}) {
  const id = `setting-${setting.key.replaceAll(".", "-")}`;
  const restoring = draft === null;
  const shown = draft === undefined || draft === null ? String(restoring ? setting.defaultValue : setting.value) : draft;
  const error = draft === undefined || draft === null ? null : draftError(setting, draft);
  const meta = SETTINGS[setting.key];
  const changed = setting.origin === "operator" || setting.origin === "invalid_override";
  return <div className="admin-field admin-setting">
    <label htmlFor={id}>{meta.label}</label>
    <div className="admin-setting-control">
      <input id={id} type="text" inputMode={setting.kind === "integer" ? "numeric" : "decimal"}
        value={shown} disabled={restoring} aria-invalid={error !== null}
        aria-describedby={`${id}-hint${error ? ` ${id}-error` : ""}`}
        onChange={(event) => onDraft(event.target.value)} />
      {meta.unit ? <span className="admin-quiet">{meta.unit}</span> : null}
      {restoring ? <button className="button" type="button" onClick={() => onDraft(undefined)}>Deshacer</button>
        : changed || draft !== undefined ? <button className="button" type="button"
          onClick={() => onDraft(changed ? null : undefined)}>
          {changed ? "Restaurar por defecto" : "Deshacer"}
        </button> : null}
    </div>
    <p className="admin-quiet" id={`${id}-hint`}>
      {restoring ? "Se restaurará el valor por defecto. " : `${ORIGIN[setting.origin]}. `}
      Por defecto {number.format(setting.defaultValue)} · entre {number.format(setting.minimum)} y {number.format(setting.maximum)}
      {setting.changedAt && !restoring ? ` · cambiado el ${dateTime.format(new Date(setting.changedAt))}` : ""}
    </p>
    {error ? <p className="admin-field-error" id={`${id}-error`}>{error}</p> : null}
  </div>;
}

function SettingsForm({ overview, csrfToken }: { overview: ProductSettingsOverview; csrfToken: string }) {
  const queryClient = useQueryClient();
  const feedback = useRef<HTMLDivElement>(null);
  const [drafts, setDrafts] = useState<Drafts>({});
  const [focusFeedback, setFocusFeedback] = useState(0);
  const changes = changesOf(overview.settings, drafts);
  const invalid = overview.settings.some((setting) => {
    const draft = drafts[setting.key];
    return typeof draft === "string" && draftError(setting, draft) !== null;
  });
  const save = useMutation({
    mutationFn: () => updateProductSettings(overview.revision, changes, csrfToken),
    onSuccess: (updated) => {
      queryClient.setQueryData(PRODUCT_SETTINGS_KEY, updated);
      setDrafts({});
      // Public reads follow the settings: refetch them so this browser never shows the old result.
      void queryClient.invalidateQueries({ predicate: (query) => query.queryKey[0] !== "me" && query.queryKey[0] !== "session" });
    },
    onError: (error) => {
      if (error instanceof AdministrationError && error.code === "PRODUCT_SETTINGS_REVISION_CONFLICT") {
        setDrafts({});
        void queryClient.invalidateQueries({ queryKey: PRODUCT_SETTINGS_KEY });
      }
    },
    onSettled: () => { setFocusFeedback((count) => count + 1); },
  });
  // Moving focus is a synchronization with the DOM: to the outcome once it has rendered.
  useEffect(() => {
    if (focusFeedback > 0) feedback.current?.focus();
  }, [focusFeedback]);

  function submit(event: FormEvent) {
    event.preventDefault();
    if (changes.length > 0 && !invalid) save.mutate();
  }

  function setDraft(key: ProductSettingKey, draft: string | null | undefined) {
    save.reset();
    setDrafts((previous) => withDraft(previous, key, draft));
  }

  const message = save.isError ? failureMessage(save.error) : null;
  return <form onSubmit={submit} noValidate>
    {GROUPS.map((group) => <section key={group.id} className="admin-section" aria-labelledby={`settings-${group.id}`}>
      <h2 id={`settings-${group.id}`}>{group.title}</h2>
      <p className="admin-lead">{group.lead}</p>
      {group.id === "synchronization" ? <CoverageNotice coverage={overview.coverage} /> : null}
      <div className="admin-settings-grid">
        {overview.settings.filter((setting) => setting.group === group.id).map((setting) =>
          <SettingField key={setting.key} setting={setting} draft={drafts[setting.key]}
            onDraft={(draft) => setDraft(setting.key, draft)} />)}
      </div>
    </section>)}
    <div className="admin-settings-actions">
      <p className="admin-quiet" role="status">
        {changes.length === 0 ? "Sin cambios pendientes." : changes.length === 1 ? "1 cambio pendiente." : `${changes.length} cambios pendientes.`}
      </p>
      <div className="admin-control-actions">
        <button className="button" type="button" disabled={Object.keys(drafts).length === 0 || save.isPending}
          onClick={() => { setDrafts({}); save.reset(); }}>Descartar</button>
        <button className="button button-primary" type="submit" disabled={changes.length === 0 || invalid || save.isPending}>
          {save.isPending ? "Guardando…" : "Guardar cambios"}
        </button>
      </div>
    </div>
    <div className="admin-request-feedback" ref={feedback} tabIndex={-1}>
      {message ? <p className={`notice notice-${message.tone}`} role="alert">{message.text}</p>
        : save.isSuccess ? <p className="notice" role="status">
          Cambios guardados. Las páginas públicas los usan desde su próxima lectura.
        </p> : null}
    </div>
  </form>;
}

function RecentChanges({ overview }: { overview: ProductSettingsOverview }) {
  return <section className="admin-section" aria-labelledby="settings-history">
    <h2 id="settings-history">Cambios recientes</h2>
    {overview.recentChanges.length === 0 ? <p className="admin-quiet">Todavía no se ha cambiado ningún ajuste.</p>
      : <ul className="admin-runs">
        {overview.recentChanges.map((change) => <li key={`${change.revision}-${change.key}`} className="admin-run">
          <div className="admin-run-head">
            <h3>{label(change.key)}</h3>
            <p className="admin-run-time">{dateTime.format(new Date(change.changedAt))}</p>
          </div>
          <p>
            {number.format(change.previousValue)} → {number.format(change.newValue)}
            {change.restoredDefault ? " (valor por defecto restaurado)" : ""}
          </p>
        </li>)}
      </ul>}
  </section>;
}

/**
 * Ajustes: the operator-editable product settings (UC-024). The server validates, stores and
 * audits every change; this tab presents them, prepares changes and states how far presentation
 * reaches beyond the configured acquisition.
 */
export function ProductSettings({ csrfToken }: { csrfToken: string }) {
  const overview = useQuery({
    queryKey: PRODUCT_SETTINGS_KEY,
    queryFn: ({ signal }) => getProductSettings(signal),
    staleTime: 0,
  });
  if (overview.isPending) return <p className="result-count" role="status">Cargando los ajustes…</p>;
  if (overview.isError) return <div className="notice notice-danger" role="alert">
    <p>No se pudieron leer los ajustes.</p>
    <button className="button button-danger" type="button" onClick={() => { void overview.refetch(); }}>Reintentar</button>
  </div>;
  return <>
    <SettingsForm overview={overview.data} csrfToken={csrfToken} />
    <RecentChanges overview={overview.data} />
  </>;
}
