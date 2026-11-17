import { Link, useLocation } from "react-router-dom";

import { platformCataloguePath } from "../features/platform-catalogue/platform-catalogue-search";
import { featuredPlatforms, PLATFORMS_PATH } from "../features/platforms/platform-context";
import { SelectIcon } from "../shared/ui/select-icon";

/** The same four direct catalogue entries in the desktop disclosure and the mobile sheet. */
export function PlatformShortcuts({ onNavigate }: { onNavigate?: () => void }) {
  const location = useLocation();
  return (
    <div className="platform-shortcuts">
      <p className="platform-shortcuts-heading">Descubre juegos para tu plataforma</p>
      <ul className="platform-shortcuts-list">
        {featuredPlatforms.map(platform => (
          <li key={platform.key}>
            <Link className="account-option platform-shortcut" to={platformCataloguePath(platform.key)} onClick={onNavigate}>
              <SelectIcon name={platform.logo} />
              <span>{platform.name}</span>
            </Link>
          </li>
        ))}
      </ul>
      <Link className="account-option platform-shortcuts-all" to={PLATFORMS_PATH} onClick={onNavigate} aria-current={location.pathname === PLATFORMS_PATH ? "page" : undefined}>
        Ver todas las plataformas <span aria-hidden="true">→</span>
      </Link>
    </div>
  );
}
