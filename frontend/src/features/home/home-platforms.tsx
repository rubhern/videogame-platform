import { Link } from "react-router-dom";

import { PlatformLogo } from "../../shared/catalogue/platform-logo";
import { platformCataloguePath } from "../platform-catalogue/platform-catalogue-search";
import { usePlatformList } from "../platform-preferences/use-platform-preferences";
import {
  featuredPlatforms,
  hasCatalogueGames,
  PLATFORMS_PATH,
  type FeaturedPlatform,
} from "../platforms/platform-context";
import { platformDestinations } from "../platforms/platform-destinations";
import { HomeSectionHead } from "./home-section";

/** The catalogue is each platform's primary destination, as the header's Plataformas offers it. */
const catalogue = platformDestinations.find((destination) => destination.key === "catalogue");

function HomePlatform({ platform, note }: { platform: FeaturedPlatform; note: string | undefined }) {
  const headingId = `home-platform-${platform.key}`;
  const to = catalogue?.to(platform) ?? platformCataloguePath(platform.key);
  return (
    <article aria-labelledby={headingId} className="home-platform" data-logo={platform.logo}>
      <div aria-hidden="true" className="home-platform-stage">
        <PlatformLogo name={platform.logo} size="large" />
      </div>
      <div className="home-platform-body">
        <h3 className="home-platform-name" id={headingId}>
          {platform.name}
        </h3>
        {/* One way in: the catalogue, whose target covers the card. */}
        <Link aria-label={`Explorar catálogo de ${platform.name}`} className="home-platform-primary" to={to}>
          Explorar catálogo
          <span aria-hidden="true" className="home-platform-arrow">→</span>
        </Link>
        {note === undefined ? null : <p className="home-platform-note">{note}</p>}
      </div>
    </article>
  );
}

/**
 * Plataformas in brief (#382, #287): the owner's four featured platforms by their own identity,
 * each with one way into its catalogue. Their TOPómetro and Próximos destacados, and every other
 * platform, stay one step away on Plataformas. The four never wait for the public directory, which
 * only adds an honest line for a platform without games; the section never varies on the session
 * or Mis plataformas.
 */
export function HomePlatforms() {
  const directory = usePlatformList();
  return (
    <section aria-labelledby="home-platforms-title" className="home-platforms">
      <HomeSectionHead
        action={{ label: "Ver todas las plataformas", to: PLATFORMS_PATH }}
        id="home-platforms-title"
        lead="Su catálogo, su TOPómetro y sus Próximos destacados"
        title="Explora por plataforma"
      />
      <ul aria-label="Plataformas destacadas" className="home-platform-list">
        {featuredPlatforms.map((platform) => (
          <li key={platform.key}>
            <HomePlatform
              note={
                directory.data !== undefined && !hasCatalogueGames(platform, directory.data)
                  ? "Aún sin juegos en Gameómetro"
                  : undefined
              }
              platform={platform}
            />
          </li>
        ))}
      </ul>
    </section>
  );
}
