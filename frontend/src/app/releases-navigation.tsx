import { useEffect, useId, useRef, useState } from "react";
import { Link, useLocation } from "react-router-dom";
import { PlatformsNavigation } from "./platforms-navigation";

import { TOPOMETRO_PATH } from "../features/rankings/ranking-search";
import { TopometroLogo } from "../shared/brand/brand-logo";
import { usePrimaryDestinations } from "./primary-destinations";

/**
 * The visible primary navigation from tablet width: the **Lanzamientos** disclosure (#385), then
 * TOPómetro (#386), named by its own lockup, and the Plataformas disclosure beside it. Narrower screens
 * carry the same sections in the compact `NavigationMenu` (#358).
 * The disclosure's native links keep their normal Tab and browser behaviour, and leaving it,
 * for TOPómetro or Plataformas included, closes it.
 */
export function ReleasesNavigation() {
  const location = useLocation();
  const { releases, releasesActive, current } = usePrimaryDestinations();
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

  return (
    <nav aria-label="Principal" className="primary-nav">
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
          aria-expanded={open} aria-controls={panelId} aria-current={releasesActive ? "true" : undefined}
          onClick={() => setOpenedAt(current => current === location.key ? null : location.key)}>
          Lanzamientos <span className="releases-chevron" aria-hidden="true" />
        </button>
        <div className="account-panel releases-panel" id={panelId} hidden={!open}>
          {releases.map(destination => (
            <Link className="account-option" key={destination.path} to={destination.to}
              aria-current={current(destination.path)}
              onClick={() => {
                setOpenedAt(null);
                if (destination.path === location.pathname) triggerRef.current?.focus();
              }}>
              {destination.label}
            </Link>
          ))}
        </div>
      </div>
      <Link className="nav-link nav-topometro" to={TOPOMETRO_PATH} aria-current={current(TOPOMETRO_PATH)}>
        <span className="sr-only">TOPómetro</span>
        <TopometroLogo className="nav-topometro-logo" />
      </Link>
      <PlatformsNavigation />
    </nav>
  );
}
