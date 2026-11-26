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
import {
  LibraryIcon, PlatformsIcon, ProfileIcon, RatingsIcon, StatisticsIcon,
} from "../features/session/account-icons";
import { useLogout, useSession } from "../features/session/use-session";
import { authenticationStartUrl } from "../features/session/auth-entry";
import type { SessionCapability } from "../features/session/session-api";
import { HeaderSheet } from "./header-sheet";

/**
 * The session-derived header account entry from tablet width; below it the compact header's
 * {@link AccountSheet} carries the same entry. Session and logout remain owned by the BFF hooks.
 */
export function AccountControl() {
  const session = useSession();
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
    return (
      <div className="account-control account-entry">
        <AccountEntryLinks />
      </div>
    );
  }

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
          <AccountActions
            capabilities={session.data.capabilities}
            csrfToken={session.data.csrfToken}
            onNavigate={() => setOpen(false)}
          />
        </div>
      ) : null}
    </div>
  );
}

/**
 * The compact header's account (#358): its own disclosure beside **Menú**, so navigation and the
 * account never share a sheet. With a session the trigger is the avatar, named `Mi cuenta`, and
 * the sheet holds the account actions; without one it reads `Entrar` (an icon on the narrowest
 * phones) and the sheet offers `Iniciar sesión` and `Crear cuenta`.
 */
export function AccountSheet() {
  const session = useSession();
  const titleId = useId();
  if (!session.data) return null;

  if (session.data.authenticated !== true) {
    return (
      <HeaderSheet className="account-sheet" label="Entrar" triggerClassName="account-sheet-entry"
        trigger={<>
          <span className="account-sheet-entry-text" aria-hidden="true">Entrar</span>
          <ProfileIcon />
        </>}>
        <div className="header-sheet-group" role="group" aria-labelledby={titleId}>
          <h2 className="header-sheet-label" id={titleId}>Tu cuenta</h2>
          <p className="account-sheet-lead">Guarda tus deseados, puntúa juegos y elige tus plataformas.</p>
          <AccountEntryLinks />
        </div>
      </HeaderSheet>
    );
  }

  return (
    <HeaderSheet className="account-sheet" label="Mi cuenta" triggerClassName="account-sheet-avatar"
      trigger={<span className="account-avatar" aria-hidden="true"><span /></span>}>
      <div className="account-sheet-actions" role="group" aria-labelledby={titleId}>
        <h2 className="sr-only" id={titleId}>Mi cuenta</h2>
        <AccountActions capabilities={session.data.capabilities} csrfToken={session.data.csrfToken} />
      </div>
    </HeaderSheet>
  );
}

/** The anonymous entry: BFF/OIDC navigation that returns to the current page. */
export function AccountEntryLinks() {
  const location = useLocation();
  const returnTo = location.pathname + location.search;
  return (
    <nav className="account-entry-links" aria-label="Acceso a tu cuenta">
      <a className="account-signin" href={authenticationStartUrl(returnTo)}>Iniciar sesión</a>
      <a className="account-register" href={authenticationStartUrl(returnTo, true)}>Crear cuenta</a>
    </nav>
  );
}

type AccountActionsProps = {
  capabilities: readonly SessionCapability[];
  csrfToken: string;
  /**
   * Dismisses the surface that holds the actions when one of them navigates; a header sheet
   * closes itself on any link.
   */
  onNavigate?: () => void;
};

/**
 * The authenticated account actions, shared by the header's Mi cuenta panel and the compact
 * account sheet. They mount only with the surface that shows them. The groups (profile, the
 * player's own lists, settings and logout) are only separated on the compact sheet.
 */
export function AccountActions({ capabilities, csrfToken, onNavigate }: AccountActionsProps) {
  const logout = useLogout();
  return (
    <>
      <div className="account-group">
        <Link className="account-option" onClick={onNavigate} to="/mi-perfil">
          <ProfileIcon />
          Mi perfil
        </Link>
      </div>
      <div className="account-group">
        <Link className="account-option" onClick={onNavigate} to="/mis-deseados">
          <span aria-hidden="true" className="wishlist-mark" />
          Mis deseados
        </Link>
        <Link className="account-option" onClick={onNavigate} to="/mis-juegos">
          <LibraryIcon />
          Mis juegos
        </Link>
        <Link className="account-option" onClick={onNavigate} to="/mis-puntuaciones">
          <RatingsIcon />
          Mis puntuaciones
        </Link>
        <Link className="account-option" onClick={onNavigate} to="/mis-puntuaciones/estadisticas">
          <StatisticsIcon />
          Tus estadísticas
        </Link>
      </div>
      <div className="account-group">
        {/* The checkbox applies the preferred platforms to discovery; the link manages them. */}
        <PlatformsMenuRow onNavigate={onNavigate} />
        {/* Navigation only: the server authorizes every administration read itself. */}
        {capabilities.includes("catalogue_administration") ? (
          <Link className="account-option" onClick={onNavigate} to="/administracion/catalogo">
            <svg aria-hidden="true" fill="none" viewBox="0 0 20 20">
              <path d="M3 5h14M3 10h14M3 15h14" stroke="currentColor" strokeLinecap="round" strokeWidth="1.5" />
              <circle cx="7" cy="5" r="1.6" fill="currentColor" />
              <circle cx="13" cy="10" r="1.6" fill="currentColor" />
              <circle cx="9" cy="15" r="1.6" fill="currentColor" />
            </svg>
            Panel de administrador
          </Link>
        ) : null}
      </div>
      <div className="account-group">
        <button
          className="account-option account-logout"
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
    </>
  );
}

/**
 * **Mis plataformas** in Mi cuenta (#295): a native checkbox that applies or sets aside the
 * preferred platforms on Destacados, Explorar lanzamientos and the release calendar for the whole document, never in the
 * saved choice, beside the link that manages them. It mounts only with the open panel, so the
 * preferences are read when they are needed. Without preferences only the link remains.
 */
function PlatformsMenuRow({ onNavigate }: { onNavigate?: (() => void) | undefined }) {
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
   * Applies or sets aside the preferred platforms on Destacados, the explorer and the calendar, for the
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
        <PlatformsIcon />
      )}
      <Link className="account-platforms-link" onClick={onNavigate} to="/mis-plataformas">
        Mis plataformas
      </Link>
      {interests.status === "ready" ? (
        <span className="sr-only" id={platformsHintId}>
          En Destacados, Explorar lanzamientos y el calendario: {interests.platforms.map((platform) => platform.name).join(", ")}
        </span>
      ) : null}
    </div>
  );
}
