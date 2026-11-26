import { useEffect, useId, useRef, useState, type MouseEvent, type ReactNode } from "react";
import { useLocation } from "react-router-dom";

type NavigationDisclosureProps = {
  /** The section's visible name, which is also the trigger's accessible name. */
  label: string;
  /** Marks the section current while one of its destinations is shown. */
  current?: "page" | "true" | undefined;
  /** Extra classes for the panel, beside the shared dropdown surface. */
  panelClassName?: string;
  /** The section's destinations: native links. */
  children: ReactNode;
};

/**
 * One section of the visible primary navigation from tablet width (#385, #358): a native button
 * that shows its destinations in a dropdown panel. Links keep their normal Tab and browser
 * behaviour. Escape closes the panel and returns focus to the button; leaving it, pressing
 * outside, following a link or a new history entry closes it, and a link to the page already
 * shown returns focus to the button.
 */
export function NavigationDisclosure({ label, current, panelClassName, children }: NavigationDisclosureProps) {
  const location = useLocation();
  const [openedAt, setOpenedAt] = useState<string | null>(null);
  // A new history entry dismisses the panel while preserving the trigger's DOM and focus.
  if (openedAt !== null && openedAt !== location.key) setOpenedAt(null);
  const open = openedAt === location.key;
  const controlRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelId = useId();

  useEffect(() => {
    if (!open) return;
    const outsidePress = (event: PointerEvent) => {
      if (event.target instanceof Node && !controlRef.current?.contains(event.target)) setOpenedAt(null);
    };
    document.addEventListener("pointerdown", outsidePress);
    return () => document.removeEventListener("pointerdown", outsidePress);
  }, [open]);

  function followLink(event: MouseEvent<HTMLDivElement>) {
    const link = event.target instanceof Element ? event.target.closest("a") : null;
    if (link === null) return;
    setOpenedAt(null);
    if (new URL(link.href, window.location.href).pathname === location.pathname) triggerRef.current?.focus();
  }

  return (
    <div className="releases-navigation" ref={controlRef}
      onBlur={event => {
        if (!event.currentTarget.contains(event.relatedTarget)) setOpenedAt(null);
      }}
      onKeyDown={event => {
        if (event.key === "Escape" && open) {
          event.preventDefault();
          event.stopPropagation();
          setOpenedAt(null);
          triggerRef.current?.focus();
        }
      }}>
      <button className="nav-link releases-trigger" type="button" ref={triggerRef}
        aria-expanded={open} aria-controls={panelId} aria-current={current}
        onClick={() => setOpenedAt(value => value === location.key ? null : location.key)}>
        {label} <span className="releases-chevron" aria-hidden="true" />
      </button>
      <div className={panelClassName ? `account-panel releases-panel ${panelClassName}` : "account-panel releases-panel"}
        id={panelId} hidden={!open} onClick={followLink}>
        {children}
      </div>
    </div>
  );
}
