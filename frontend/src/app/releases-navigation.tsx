import { Link } from "react-router-dom";

import { TOPOMETRO_PATH } from "../features/rankings/ranking-search";
import { TopometroLogo } from "../shared/brand/brand-logo";
import { NavigationDisclosure } from "./navigation-disclosure";
import { PlatformShortcuts } from "./platform-shortcuts";
import { PLATFORMS_PATH, usePrimaryDestinations } from "./primary-destinations";

/**
 * The visible primary navigation from tablet width: three disclosures in the same interaction and
 * surface language. **Lanzamientos** (#385) holds Destacados, Explorar and Calendario; **Lo mejor**
 * holds TOPómetro (#386), named by its own lockup, and is where the next rankings will go (owner
 * decision, 2026-11-26); **Plataformas** holds the four catalogue shortcuts and the index.
 * Narrower screens carry the same sections in the compact `NavigationMenu` (#358).
 */
export function ReleasesNavigation() {
  const { releases, releasesActive, bestActive, current } = usePrimaryDestinations();

  return (
    <nav aria-label="Principal" className="primary-nav">
      <NavigationDisclosure label="Lanzamientos" current={releasesActive ? "true" : undefined}>
        {releases.map(destination => (
          <Link className="account-option" key={destination.path} to={destination.to}
            aria-current={current(destination.path)}>
            {destination.label}
          </Link>
        ))}
      </NavigationDisclosure>
      <NavigationDisclosure label="Lo mejor" current={bestActive ? "true" : undefined}>
        <Link className="account-option nav-topometro" to={TOPOMETRO_PATH} aria-current={current(TOPOMETRO_PATH)}>
          <span className="sr-only">TOPómetro</span>
          <TopometroLogo className="nav-topometro-logo" />
        </Link>
      </NavigationDisclosure>
      <NavigationDisclosure label="Plataformas" current={current(PLATFORMS_PATH)} panelClassName="platforms-panel">
        <PlatformShortcuts />
      </NavigationDisclosure>
    </nav>
  );
}
