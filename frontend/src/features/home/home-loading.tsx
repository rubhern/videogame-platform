import { HomeIntro } from "./home-section";
import { PodiumLoading } from "./home-topometro-preview";
import { HomeReleaseLoading } from "./home-release-discovery";

/**
 * The home's own surface while its first reads are under way: the opening and placeholders shaped
 * like the previews they replace, with one live status. It mounts no query and no effect.
 */
export function HomeLoading() {
  return (
    <div className="home-page">
      <div className="home-stage">
        <div className="page-container">
          <HomeIntro />
          <p className="sr-only" role="status">
            Cargando Gameómetro…
          </p>
          <HomeReleaseLoading />
        </div>
      </div>
      <div aria-hidden="true" className="page-container home-sections">
        <div className="home-topometro">
          <span className="skeleton home-section-skeleton" />
          <div className="home-podium-grid"><PodiumLoading /><PodiumLoading /></div>
        </div>
      </div>
    </div>
  );
}
