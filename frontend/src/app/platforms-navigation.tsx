import { useEffect, useId, useRef, useState } from "react";
import { useLocation } from "react-router-dom";

import { PLATFORMS_PATH, usePrimaryDestinations } from "./primary-destinations";
import { PlatformShortcuts } from "./platform-shortcuts";

export function PlatformsNavigation() {
  const location = useLocation();
  const { current } = usePrimaryDestinations();
  const [openedAt, setOpenedAt] = useState<string | null>(null);
  if (openedAt !== null && openedAt !== location.key) setOpenedAt(null);
  const open = openedAt === location.key;
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const panelId = useId();

  useEffect(() => {
    if (!open) return;
    const outside = (event: PointerEvent) => {
      if (event.target instanceof Node && !root.current?.contains(event.target)) setOpenedAt(null);
    };
    document.addEventListener("pointerdown", outside);
    return () => document.removeEventListener("pointerdown", outside);
  }, [open]);

  return (
    <div className="releases-navigation" ref={root}
      onBlur={event => {
        if (!event.currentTarget.contains(event.relatedTarget)) setOpenedAt(null);
      }}
      onKeyDown={event => {
        if (event.key === "Escape" && open) {
          event.preventDefault();
          event.stopPropagation();
          setOpenedAt(null);
          trigger.current?.focus();
        }
      }}>
      <button className="nav-link releases-trigger" type="button" ref={trigger}
        aria-expanded={open} aria-controls={panelId} aria-current={current(PLATFORMS_PATH)}
        onClick={() => setOpenedAt(value => value === location.key ? null : location.key)}>
        Plataformas <span className="releases-chevron" aria-hidden="true" />
      </button>
      <div className="account-panel releases-panel platforms-panel" id={panelId} hidden={!open}>
        <PlatformShortcuts onNavigate={() => {
          setOpenedAt(null);
          trigger.current?.focus();
        }} />
      </div>
    </div>
  );
}
