import { useEffect, useId, useLayoutEffect, useRef, useState, type MouseEvent, type ReactNode } from "react";
import { useLocation } from "react-router-dom";

type HeaderSheetProps = {
  /** Scopes the disclosure's own styles beside the shared `header-sheet` ones. */
  className: string;
  /** The trigger's accessible name. */
  label: string;
  /** The trigger's visible content; it reads its state from `aria-expanded`. */
  trigger: ReactNode;
  /** Extra classes for the trigger, beside `header-sheet-trigger`. */
  triggerClassName?: string;
  /** The sheet's content, mounted only while it is open. */
  children: ReactNode;
};

/**
 * One of the compact header's disclosures (#358): a native button that opens a night sheet under
 * the header row, over a scrim that dims the page and takes the tap that closes it. The account
 * and **Menú** each own one, so only one shows at a time: pressing the other trigger is an outside
 * press. Focus stays on the button, the sheet follows it in the tab order, and Escape, leaving
 * it, pressing outside, following a link or showing another page closes it.
 */
export function HeaderSheet({ className, label, trigger, triggerClassName, children }: HeaderSheetProps) {
  const location = useLocation();
  const [openedOn, setOpenedOn] = useState<string | null>(null);
  // Another page dismisses the sheet; a query change on the same page (a filter toggle) does not.
  if (openedOn !== null && openedOn !== location.pathname) setOpenedOn(null);
  const open = openedOn === location.pathname;
  const controlRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const panelId = useId();
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

  useLayoutEffect(() => {
    const panel = panelRef.current;
    if (!open || panel === null) return;

    const fitPanel = () => {
      // Leave a reachable strip of scrim even when the consent notice shifts the header down.
      const availableHeight = window.innerHeight - Math.max(0, panel.getBoundingClientRect().top) - 48;
      panel.style.setProperty("--header-sheet-available-height", `${Math.max(0, availableHeight)}px`);
    };

    fitPanel();
    const observer = typeof ResizeObserver === "undefined" ? null : new ResizeObserver(fitPanel);
    observer?.observe(document.body);
    window.addEventListener("resize", fitPanel);
    window.addEventListener("scroll", fitPanel, { passive: true });
    return () => {
      observer?.disconnect();
      window.removeEventListener("resize", fitPanel);
      window.removeEventListener("scroll", fitPanel);
    };
  }, [open]);

  /** Any link closes the sheet; one to the page already shown returns focus to the button. */
  function followLink(event: MouseEvent<HTMLDivElement>) {
    const link = event.target instanceof Element ? event.target.closest("a") : null;
    if (link === null) return;
    close();
    if (new URL(link.href, window.location.href).pathname === location.pathname) triggerRef.current?.focus();
  }

  return (
    <div className={`header-sheet ${className}`} data-open={open} ref={controlRef}
      onBlur={event => {
        if (!event.currentTarget.contains(event.relatedTarget)) close();
      }}>
      <button className={triggerClassName ? `header-sheet-trigger ${triggerClassName}` : "header-sheet-trigger"}
        type="button" ref={triggerRef} aria-label={label}
        aria-expanded={open} aria-controls={open ? panelId : undefined}
        onClick={() => setOpenedOn(current => current === null ? location.pathname : null)}>
        {trigger}
      </button>
      {open ? (
        <>
          {/* Dims the page and takes the tap that dismisses the sheet, so nothing beneath acts. */}
          <div className="header-sheet-scrim" aria-hidden="true"
            onMouseDown={event => event.preventDefault()} onClick={close} />
          <div className="header-sheet-panel" id={panelId} onClick={followLink} ref={panelRef}>
            <div className="header-sheet-body page-container">{children}</div>
          </div>
        </>
      ) : null}
    </div>
  );
}
