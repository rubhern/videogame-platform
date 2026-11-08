import { useEffect, useId, useRef, useState, type CSSProperties, type MouseEvent } from "react";
import { Link, useLocation } from "react-router-dom";
import { PlatformShortcuts } from "./platform-shortcuts";

import { TOPOMETRO_PATH } from "../features/rankings/ranking-search";
import { useSession } from "../features/session/use-session";
import { TopometroLogo } from "../shared/brand/brand-logo";
import { AccountActions, AccountEntryLinks } from "./account-control";
import { usePrimaryDestinations } from "./primary-destinations";

/** Orders the sheet's staggered entrance; the CSS reads it only when motion is allowed. */
const step = (index: number) => ({ "--menu-step": index }) as CSSProperties;

/**
 * The compact header's navigation (#358): one **Menú** button opening a sheet under the header
 * row with the primary sections — Lanzamientos with its three destinations, TOPómetro and
 * Plataformas — above a separate account dock. It is a disclosure: focus stays on the button,
 * the sheet follows it in the tab order, and Escape, leaving it, pressing outside, following a
 * link or a new page closes it.
 */
export function NavigationMenu() {
  const location = useLocation();
  const session = useSession();
  const { releases, current } = usePrimaryDestinations();
  const [openedOn, setOpenedOn] = useState<string | null>(null);
  // Another page dismisses the sheet; a query change on the same page (an account toggle) does not.
  if (openedOn !== null && openedOn !== location.pathname) setOpenedOn(null);
  const open = openedOn === location.pathname;
  const controlRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelId = useId();
  const releasesId = useId();
  const accountId = useId();
  const close = () => setOpenedOn(null);

  useEffect(() => {
    if (!open) return;
    const outsidePress = (event: PointerEvent) => {
      if (event.target instanceof Node && !controlRef.current?.contains(event.target)) setOpenedOn(null);
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      setOpenedOn(null);
      triggerRef.current?.focus();
    };
    document.addEventListener("pointerdown", outsidePress);
    document.addEventListener("keydown", escape);
    return () => {
      document.removeEventListener("pointerdown", outsidePress);
      document.removeEventListener("keydown", escape);
    };
  }, [open]);

  /** Any link closes the sheet; one to the page already shown returns focus to the button. */
  function followLink(event: MouseEvent<HTMLDivElement>) {
    const link = event.target instanceof Element ? event.target.closest("a") : null;
    if (link === null) return;
    close();
    if (new URL(link.href, window.location.href).pathname === location.pathname) triggerRef.current?.focus();
  }

  const sessionState = session.data;
  const authenticated = sessionState?.authenticated === true
    ? { capabilities: sessionState.capabilities, csrfToken: sessionState.csrfToken }
    : null;

  return (
    <div className="navigation-menu" data-open={open} ref={controlRef}
      onBlur={event => {
        if (!event.currentTarget.contains(event.relatedTarget)) close();
      }}>
      <button className="navigation-menu-trigger" type="button" ref={triggerRef} aria-label="Menú"
        aria-expanded={open} aria-controls={open ? panelId : undefined}
        onClick={() => setOpenedOn(current => current === null ? location.pathname : null)}>
        <span className="navigation-menu-glyph" aria-hidden="true"><span /><span /></span>
        {authenticated ? <span className="navigation-menu-session" aria-hidden="true" /> : null}
      </button>
      {open ? (
        <>
          {/* Dims the page and takes the tap that dismisses the sheet, so nothing beneath acts. */}
          <div className="navigation-menu-scrim" aria-hidden="true"
            onMouseDown={event => event.preventDefault()} onClick={close} />
          <div className="navigation-menu-panel" id={panelId} onClick={followLink}>
            <div className="navigation-menu-sheet page-container">
              <nav aria-label="Principal">
                <ul className="navigation-menu-sections">
                  <li className="navigation-menu-section" style={step(0)}>
                    <span className="navigation-menu-heading" id={releasesId}>Lanzamientos</span>
                    <ul className="navigation-menu-releases" aria-labelledby={releasesId}>
                      {releases.map(destination => (
                        <li key={destination.path}>
                          <Link className="navigation-menu-release" to={destination.to}
                            aria-current={current(destination.path)}>
                            {destination.label}
                          </Link>
                        </li>
                      ))}
                    </ul>
                  </li>
                  <li className="navigation-menu-section" style={step(1)}>
                    <Link className="navigation-menu-link" to={TOPOMETRO_PATH}
                      aria-current={current(TOPOMETRO_PATH)}>
                      <span className="sr-only">TOPómetro</span>
                      <TopometroLogo className="navigation-menu-topometro" />
                      <span className="navigation-menu-arrow" aria-hidden="true" />
                    </Link>
                  </li>
                  <li className="navigation-menu-section" style={step(2)}>
                    <span className="navigation-menu-heading">Plataformas</span>
                    <PlatformShortcuts />
                  </li>
                </ul>
              </nav>
              {sessionState ? (
                <div className="navigation-menu-account" role="group" aria-labelledby={accountId} style={step(3)}>
                  <p className="navigation-menu-account-title" id={accountId}>
                    {authenticated ? <span className="account-avatar" aria-hidden="true"><span /></span> : null}
                    {authenticated ? "Mi cuenta" : "Tu cuenta"}
                  </p>
                  {authenticated ? (
                    <div className="navigation-menu-account-actions">
                      <AccountActions capabilities={authenticated.capabilities}
                        csrfToken={authenticated.csrfToken} onNavigate={close} />
                    </div>
                  ) : (
                    <AccountEntryLinks />
                  )}
                </div>
              ) : null}
            </div>
          </div>
        </>
      ) : null}
    </div>
  );
}
