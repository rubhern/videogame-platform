import { useId, type CSSProperties } from "react";
import { Link } from "react-router-dom";

import { TOPOMETRO_PATH } from "../features/rankings/ranking-search";
import { TopometroLogo } from "../shared/brand/brand-logo";
import { HeaderSheet } from "./header-sheet";
import { PlatformShortcuts } from "./platform-shortcuts";
import { usePrimaryDestinations } from "./primary-destinations";

/** Orders the sheet's staggered entrance; the CSS reads it only when motion is allowed. */
const step = (index: number) => ({ "--menu-step": index }) as CSSProperties;

/**
 * The compact header's navigation (#358): one **Menú** button, after the account's own, opening
 * the primary sections only. Each section is a small heading over uniform rows: **Lanzamientos**
 * with its three destinations, **Lo mejor** with TOPómetro, and **Plataformas** with its four
 * catalogue shortcuts and the index.
 */
export function NavigationMenu() {
  const { releases, current } = usePrimaryDestinations();
  const releasesId = useId();
  const bestId = useId();
  const platformsId = useId();

  return (
    <HeaderSheet className="navigation-menu" label="Menú"
      trigger={<span className="navigation-menu-glyph" aria-hidden="true"><span /><span /><span /></span>}>
      <nav aria-label="Principal">
        <div className="header-sheet-group" style={step(0)}>
          <h2 className="header-sheet-label" id={releasesId}>Lanzamientos</h2>
          <ul className="header-sheet-rows" aria-labelledby={releasesId}>
            {releases.map(destination => (
              <li key={destination.path}>
                <Link className="header-sheet-row" to={destination.to} aria-current={current(destination.path)}>
                  {destination.label}
                </Link>
              </li>
            ))}
          </ul>
        </div>
        <div className="header-sheet-group" style={step(1)}>
          <h2 className="header-sheet-label" id={bestId}>Lo mejor</h2>
          <ul className="header-sheet-rows" aria-labelledby={bestId}>
            <li>
              <Link className="header-sheet-row navigation-menu-topometro" to={TOPOMETRO_PATH}
                aria-current={current(TOPOMETRO_PATH)}>
                <span className="sr-only">TOPómetro</span>
                <TopometroLogo className="navigation-menu-topometro-logo" />
              </Link>
            </li>
          </ul>
        </div>
        <div className="header-sheet-group" style={step(2)}>
          <h2 className="header-sheet-label" id={platformsId}>Plataformas</h2>
          <PlatformShortcuts />
        </div>
      </nav>
    </HeaderSheet>
  );
}
