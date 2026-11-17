import { Link } from "react-router-dom";

import { TopometroLogo } from "../../shared/brand/brand-logo";
import { PlatformLogo } from "../../shared/catalogue/platform-logo";
import { platformCataloguePath } from "../platform-catalogue/platform-catalogue-search";
import type { FeaturedPlatform, PlatformContext } from "./platform-context";
import type { PlatformDestination } from "./platform-destinations";

function DestinationLabel({ destination }: { destination: PlatformDestination }) {
  // TOPómetro is named by its lockup, as the header names it.
  return destination.key === "topometro" ? (
    <span className="platform-destination-label">
      <span className="sr-only">TOPómetro</span>
      <TopometroLogo className="platform-destination-lockup" />
    </span>
  ) : (
    <span className="platform-destination-label">{destination.label}</span>
  );
}

type PlatformDestinationsProps = {
  context: PlatformContext;
  destinations: readonly PlatformDestination[];
  id?: string;
};

/**
 * A platform's three destinations as direct rows. One not yet available reads `Próximamente` and
 * is no link, so the index never offers a dead end.
 */
export function PlatformDestinations({ context, destinations, id }: PlatformDestinationsProps) {
  return (
    <ul aria-label={`Destinos de ${context.name}`} className="platform-destinations" id={id}>
      {destinations.map((destination) => {
        const to = destination.to(context);
        return (
          <li className="platform-destination" data-destination={destination.key} key={destination.key}>
            {to === null ? (
              <span className="platform-destination-row platform-destination-pending">
                <DestinationLabel destination={destination} />
                <span className="platform-destination-state">Próximamente</span>
              </span>
            ) : (
              <Link className="platform-destination-row platform-destination-link" to={to}>
                <DestinationLabel destination={destination} />
                <span aria-hidden="true" className="platform-destination-arrow" />
              </Link>
            )}
          </li>
        );
      })}
    </ul>
  );
}

type PlatformCardProps = {
  context: FeaturedPlatform;
  destinations: readonly PlatformDestination[];
  /** An honest coverage line, for a platform without games yet. */
  note?: string | undefined;
};

/** A featured platform on its own stage: the large logo leads, its name heads the destinations. */
export function PlatformCard({ context, destinations, note }: PlatformCardProps) {
  const headingId = `platform-${context.key}-title`;
  return (
    <article aria-labelledby={headingId} className="platform-card" data-logo={context.logo}>
      <Link className="platform-card-stage" to={platformCataloguePath(context.key)} aria-label={`Explorar catálogo de ${context.name}`}>
        <PlatformLogo name={context.logo} size="large" />
      </Link>
      <div className="platform-card-body">
        <h2 className="platform-card-name" id={headingId}><Link to={platformCataloguePath(context.key)}>{context.name}</Link></h2>
        <PlatformDestinations context={context} destinations={destinations} />
        {/* After the destinations, so the rows of neighbouring cards stay aligned. */}
        {note === undefined ? null : <p className="platform-card-note">{note}</p>}
      </div>
    </article>
  );
}
