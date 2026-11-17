import { useLocation } from "react-router-dom";

import { FEATURED_PATH } from "../features/releases/featured-search";
import { CALENDAR_PATH } from "../features/releases/release-calendar-search";
import { readReleasesSearch, RELEASES_PATH, releasesSearchPath } from "../features/releases/releases-search";
import { PLATFORMS_PATH } from "../features/platforms/platform-context";

/** The Plataformas index (#287); a selected platform stays on the same path. */
export { PLATFORMS_PATH };

export type ReleaseDestination = { label: string; path: string; to: string };

/**
 * The primary sections, shared by the header's visible navigation and the compact menu so both
 * name, order and mark the same destinations: **Lanzamientos** (Destacados, Explorar,
 * Calendario), TOPómetro and Plataformas.
 */
export function usePrimaryDestinations() {
  const location = useLocation();
  const releases: ReleaseDestination[] = [
    { label: "Destacados", path: FEATURED_PATH, to: FEATURED_PATH },
    {
      label: "Explorar", path: RELEASES_PATH,
      // Explorar from the explorer itself keeps its filters and returns to the first page.
      to: location.pathname === RELEASES_PATH
        ? releasesSearchPath(readReleasesSearch(new URLSearchParams(location.search)), { page: 1 })
        : RELEASES_PATH,
    },
    { label: "Calendario", path: CALENDAR_PATH, to: CALENDAR_PATH },
  ];
  // A section is current on its own page and, for Plataformas, on a platform's destinations too.
  const current = (path: string) => {
    if (location.pathname === path) return "page" as const;
    return path === PLATFORMS_PATH && location.pathname.startsWith(`${path}/`)
      ? ("true" as const)
      : undefined;
  };
  return {
    releases,
    releasesActive: releases.some(destination => destination.path === location.pathname),
    current,
  };
}
