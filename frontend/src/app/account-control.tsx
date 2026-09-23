import { useEffect, useId, useRef, useState } from "react";
import { Link } from "react-router-dom";

import { useLogout, useSession } from "../features/session/use-session";

/** The authenticated header entry point; session and logout remain owned by the BFF hooks. */
export function AccountControl() {
  const session = useSession();
  const logout = useLogout();
  const [open, setOpen] = useState(false);
  const controlRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelId = useId();

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

  if (session.data?.authenticated !== true) return null;

  const csrfToken = session.data.csrfToken;

  return (
    <div
      className="account-control"
      ref={controlRef}
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false);
      }}
    >
      <button
        aria-controls={open ? panelId : undefined}
        aria-expanded={open}
        className="account-trigger"
        onClick={() => setOpen((current) => !current)}
        ref={triggerRef}
        type="button"
      >
        <span className="account-avatar" aria-hidden="true"><span /></span>
        <span>Mi cuenta</span>
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
            onClick={() => logout.mutate(csrfToken)}
            type="button"
          >
            <svg aria-hidden="true" fill="none" viewBox="0 0 20 20">
              <path d="M8 3H4.5A1.5 1.5 0 0 0 3 4.5v11A1.5 1.5 0 0 0 4.5 17H8M11 6l4 4-4 4m-7-4h11" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.5" />
            </svg>
            {logout.isPending ? "Cerrando…" : "Cerrar sesión"}
          </button>
        </div>
      ) : null}
    </div>
  );
}
