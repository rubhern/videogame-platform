import { Link, useLocation } from "react-router-dom";

import { SelectIcon } from "../../shared/ui/select-icon";
import { PlatformSwitcher } from "../platform-catalogue/platform-switcher";
import type { PlatformContext } from "./platform-context";
import { platformDestinations, type PlatformDestinationKey } from "./platform-destinations";

type PlatformNavigationProps = {
  context: PlatformContext;
  active: PlatformDestinationKey;
  others: readonly PlatformContext[] | null;
  pathForPlatform: (context: PlatformContext) => string;
};

/** Shared platform identity and real links; the current route owns selection and history. */
export function PlatformNavigation({ context, active, others, pathForPlatform }: PlatformNavigationProps) {
  const location = useLocation();
  return (
    <div className="platform-navigation">
      <div className="platform-navigation-context">
        <span className="platform-navigation-identity">
          <SelectIcon name={context.logo} />
          <span>{context.name}</span>
        </span>
        <PlatformSwitcher current={context} others={others} pathFor={pathForPlatform} />
      </div>
      <nav aria-label={`Navegación de ${context.name}`} className="platform-navigation-links">
        {platformDestinations.map(destination => {
          const to = destination.to(context);
          return to === null ? null : (
            <Link key={destination.key} to={active === destination.key ? `${location.pathname}${location.search}` : to} aria-current={active === destination.key ? "page" : undefined}>
              {destination.key === "catalogue" ? "Catálogo" : destination.label}
            </Link>
          );
        })}
      </nav>
    </div>
  );
}
