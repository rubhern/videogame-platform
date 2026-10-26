import { useEffect, useId, useRef, useState } from "react";
import { Link, useLocation } from "react-router-dom";

import { TOPOMETRO_PATH } from "../features/rankings/ranking-search";
import { TopometroLogo } from "../shared/brand/brand-logo";
import { FEATURED_PATH } from "../features/releases/featured-search";
import { CALENDAR_PATH } from "../features/releases/release-calendar-search";
import { readReleasesSearch, RELEASES_PATH, releasesSearchPath } from "../features/releases/releases-search";

/**
 * The primary navigation: the **Lanzamientos** disclosure (#385) and TOPómetro (#386) beside it,
 * named by its own lockup.
 * The disclosure's native links keep their normal Tab and browser behaviour, and leaving it,
 * TOPómetro included, closes it.
 */
export function ReleasesNavigation() {
  const location = useLocation();
  const [openedAt, setOpenedAt] = useState<string | null>(null);
  // A new history entry dismisses the panel while preserving the trigger's DOM and focus.
  if (openedAt !== null && openedAt !== location.key) setOpenedAt(null);
  const open = openedAt === location.key;
  const controlRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelId = useId();
  const destinations = [
    { label: "Destacados", path: FEATURED_PATH, to: FEATURED_PATH },
    {
      label: "Explorar", path: RELEASES_PATH,
      to: location.pathname === RELEASES_PATH
        ? releasesSearchPath(readReleasesSearch(new URLSearchParams(location.search)), { page: 1 })
        : RELEASES_PATH,
    },
    { label: "Calendario", path: CALENDAR_PATH, to: CALENDAR_PATH },
  ];
  const active = destinations.some(destination => destination.path === location.pathname);

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
          aria-expanded={open} aria-controls={panelId} aria-current={active ? "true" : undefined}
          onClick={() => setOpenedAt(current => current === location.key ? null : location.key)}>
          Lanzamientos <span className="releases-chevron" aria-hidden="true" />
        </button>
        <div className="account-panel releases-panel" id={panelId} hidden={!open}>
          {destinations.map(destination => (
            <Link className="account-option" key={destination.path} to={destination.to}
              aria-current={location.pathname === destination.path ? "page" : undefined}
              onClick={() => {
                setOpenedAt(null);
                if (destination.path === location.pathname) triggerRef.current?.focus();
              }}>
              {destination.label}
            </Link>
          ))}
        </div>
      </div>
      <Link className="nav-link nav-topometro" to={TOPOMETRO_PATH}
        aria-current={location.pathname === TOPOMETRO_PATH ? "page" : undefined}>
        <span className="sr-only">TOPómetro</span>
        <TopometroLogo className="nav-topometro-logo" />
      </Link>
    </nav>
  );
}
