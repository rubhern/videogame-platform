import { useEffect, useId, useRef, useState, type FocusEvent } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";

import { useInterestsSwitch } from "../features/platform-preferences/interests-switch";
import { useInterests } from "../features/platform-preferences/use-platform-preferences";
import { readReleasesSearch, RELEASES_PATH, releasesSearchPath } from "../features/releases/releases-search";
import {
  CALENDAR_PATH,
  calendarSearchPath,
  readCalendarSearch,
} from "../features/releases/release-calendar-search";
import { useLogout, useSession } from "../features/session/use-session";
import { authenticationStartUrl } from "../features/session/auth-entry";

/** The session-derived header account entry; session and logout remain owned by the BFF hooks. */
export function AccountControl() {
  const session = useSession();
  const logout = useLogout();
  const location = useLocation();
  const [open, setOpen] = useState(false);
  const controlRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelId = useId();

  const closeOnBlur = (event: FocusEvent<HTMLDivElement>) => {
    if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false);
  };

  useEffect(() => {
    if (!open) return;

    const closeOnOutsidePress = (event: PointerEvent) => {
      if (event.target instanceof Node && !controlRef.current?.contains(event.target)) {
        setOpen(false);
      }
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setOpen(false);
        triggerRef.current?.focus();
      }
    };

    document.addEventListener("pointerdown", closeOnOutsidePress);
    document.addEventListener("keydown", closeOnEscape);
    return () => {
      document.removeEventListener("pointerdown", closeOnOutsidePress);
      document.removeEventListener("keydown", closeOnEscape);
    };
  }, [open]);

  if (session.data?.authenticated !== true) {
    if (!session.data) return null;
    const returnTo = location.pathname + location.search;
    return (
      <div className="account-control account-entry" data-open={open} ref={controlRef} onBlur={closeOnBlur}>
        <button className="account-trigger account-anonymous-trigger" type="button"
          aria-label="Acceder a tu cuenta" aria-controls={panelId} aria-expanded={open}
          ref={triggerRef} onClick={() => setOpen(current => !current)}>
          <svg aria-hidden="true" fill="none" viewBox="0 0 24 24"><circle cx="12" cy="8" r="3.5" stroke="currentColor" strokeWidth="1.6" /><path d="M5 20v-1a7 7 0 0 1 14 0v1" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" /></svg>
        </button>
        <nav className="account-entry-links" id={panelId} aria-label="Acceso a tu cuenta">
          <a className="account-signin" href={authenticationStartUrl(returnTo)}>Iniciar sesión</a>
          <a className="account-register" href={authenticationStartUrl(returnTo, true)}>Crear cuenta</a>
        </nav>
      </div>
    );
  }

  const csrfToken = session.data.csrfToken;

  return (
    <div
      className="account-control"
      ref={controlRef}
      onBlur={closeOnBlur}
    >
      <button
        aria-label="Mi cuenta"
        aria-controls={open ? panelId : undefined}
        aria-expanded={open}
        className="account-trigger"
        onClick={() => setOpen((current) => !current)}
        ref={triggerRef}
        type="button"
      >
        <span className="account-avatar" aria-hidden="true"><span /></span>
        <span className="account-label">Mi cuenta</span>
        <span className="account-chevron" aria-hidden="true" />
      </button>
      {open ? (
        <div className="account-panel" id={panelId}>
          <Link className="account-option" onClick={() => setOpen(false)} to="/mis-puntuaciones">
            <svg aria-hidden="true" fill="none" viewBox="0 0 20 20">
              <path d="M10 2.5 12.3 7l5 .7-3.6 3.5.8 5-4.5-2.3-4.5 2.3.8-5L2.7 7l5-.7L10 2.5Z" stroke="currentColor" strokeLinejoin="round" strokeWidth="1.5" />
            </svg>
            Mis puntuaciones
          </Link>
          <Link className="account-option" onClick={() => setOpen(false)} to="/mis-puntuaciones/estadisticas">
            <svg aria-hidden="true" fill="none" viewBox="0 0 20 20"><path d="M3 16V10m7 6V4m7 12V7" stroke="currentColor" strokeWidth="2" /></svg>
            Tus estadísticas
          </Link>
          {/* The checkbox applies the preferred platforms to discovery; the link manages them. */}
          <PlatformsMenuRow onNavigate={() => setOpen(false)} />
          {/* Navigation only: the server authorizes every administration read itself. */}
          {session.data.capabilities.includes("catalogue_administration") ? (
            <Link className="account-option" onClick={() => setOpen(false)} to="/administracion/catalogo">
              <svg aria-hidden="true" fill="none" viewBox="0 0 20 20">
                <path d="M3 5h14M3 10h14M3 15h14" stroke="currentColor" strokeLinecap="round" strokeWidth="1.5" />
                <circle cx="7" cy="5" r="1.6" fill="currentColor" />
                <circle cx="13" cy="10" r="1.6" fill="currentColor" />
                <circle cx="9" cy="15" r="1.6" fill="currentColor" />
              </svg>
              Panel de administrador
            </Link>
          ) : null}
          <button
            className="account-option"
            disabled={logout.isPending}
            onClick={() => logout.mutate(csrfToken)}
            type="button"
          >
            <svg aria-hidden="true" fill="none" viewBox="0 0 20 20">
              <path d="M8 3H4.5A1.5 1.5 0 0 0 3 4.5v11A1.5 1.5 0 0 0 4.5 17H8M11 6l4 4-4 4m-7-4h11" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.5" />
            </svg>
            {logout.isPending ? "Cerrando…" : "Cerrar sesión"}
          </button>
          {logout.isError ? <p className="account-logout-error" role="alert">No se pudo cerrar la sesión. Vuelve a intentarlo.</p> : null}
        </div>
      ) : null}
    </div>
  );
}

/**
 * **Mis plataformas** in Mi cuenta (#295): a native checkbox that applies or sets aside the
 * preferred platforms on TOPómetro, Explorar lanzamientos and the release calendar for the whole document, never in the
 * saved choice, beside the link that manages them. It mounts only with the open panel, so the
 * preferences are read when they are needed. Without preferences only the link remains.
 */
function PlatformsMenuRow({ onNavigate }: { onNavigate: () => void }) {
  const location = useLocation();
  const platformsHintId = useId();
  const navigate = useNavigate();
  const interests = useInterests();
  const interestsSwitch = useInterestsSwitch();
  const onExplorer = location.pathname === RELEASES_PATH;
  const onCalendar = location.pathname === CALENDAR_PATH;
  const explorerSearch = readReleasesSearch(new URLSearchParams(location.search));
  const calendarSearch = readCalendarSearch(new URLSearchParams(location.search));
  // A manual platform choice in the explorer or the calendar already sets the preferences aside.
  const manualPlatforms =
    (onExplorer && explorerSearch.platformIds.length > 0) ||
    (onCalendar && calendarSearch.platformIds.length > 0);
  const platformsApplied = interestsSwitch.enabled && !manualPlatforms;

  /**
   * Applies or sets aside the preferred platforms on TOPómetro, the explorer and the calendar, for the
   * whole document and never in the saved choice. On the explorer, applying them replaces a manual
   * platform choice and either change returns to its first page; on the calendar, applying them
   * replaces a manual platform choice too.
   */
  function applyPlatforms(checked: boolean) {
    interestsSwitch.set(checked);
    if (onExplorer) {
      void navigate(
        releasesSearchPath(explorerSearch, { page: 1, ...(checked ? { platformIds: [] } : {}) }),
        { preventScrollReset: true, state: location.state },
      );
    }
    if (onCalendar && checked) {
      void navigate(calendarSearchPath(calendarSearch, { platformIds: [] }), {
        preventScrollReset: true,
        replace: true,
      });
    }
  }

  return (
    <div className="account-option account-option-platforms">
      {interests.status === "ready" ? (
        <input
          aria-describedby={platformsHintId}
          aria-label="Filtrar por mis plataformas"
          checked={platformsApplied}
          className="account-platforms-check"
          onChange={(event) => applyPlatforms(event.currentTarget.checked)}
          type="checkbox"
        />
      ) : (
        <svg aria-hidden="true" fill="none" viewBox="0 0 20 20">
          <path d="M5.5 6.5h9a3 3 0 0 1 2.9 2.2l1 4.1a2 2 0 0 1-3.2 2l-2.1-1.6H6.9l-2.1 1.6a2 2 0 0 1-3.2-2l1-4.1a3 3 0 0 1 2.9-2.2Z" stroke="currentColor" strokeLinejoin="round" strokeWidth="1.5" />
          <path d="M6 8.5v3M4.5 10h3M13.5 9.5h.01M15.5 11h.01" stroke="currentColor" strokeLinecap="round" strokeWidth="1.5" />
        </svg>
      )}
      <Link className="account-platforms-link" onClick={onNavigate} to="/mis-plataformas">
        Mis plataformas
      </Link>
      {interests.status === "ready" ? (
        <span className="sr-only" id={platformsHintId}>
          En TOPómetro, Explorar lanzamientos y el calendario: {interests.platforms.map((platform) => platform.name).join(", ")}
        </span>
      ) : null}
    </div>
  );
}
