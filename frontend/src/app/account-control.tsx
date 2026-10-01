import { useEffect, useId, useRef, useState, type FocusEvent } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";

import { useLogout, useSession } from "../features/session/use-session";
import { authenticationStartUrl } from "../features/session/auth-entry";

/** The session-derived header account entry; session and logout remain owned by the BFF hooks. */
export function AccountControl() {
  const session = useSession();
  const logout = useLogout();
  const location = useLocation();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const controlRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const signinRef = useRef<HTMLAnchorElement>(null);
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
          <a className="account-signin" ref={signinRef} href={authenticationStartUrl(returnTo)}>Iniciar sesión</a>
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
          <button
            className="account-option"
            disabled={logout.isPending}
            onClick={() => logout.mutate(csrfToken, {
              onSuccess: () => {
                setOpen(false);
                if (location.pathname === "/mis-puntuaciones") navigate("/");
                else requestAnimationFrame(() => {
                  if (triggerRef.current?.getClientRects().length) triggerRef.current.focus();
                  else signinRef.current?.focus();
                });
              },
            })}
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
