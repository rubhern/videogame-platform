import { CalendarLoading } from "./home-calendar-preview";
import { HomeIntro } from "./home-section";
import { PodiumLoading } from "./home-topometro-preview";
import { HomeWeekLoading } from "./home-week-highlights";

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
          <HomeWeekLoading />
        </div>
      </div>
      <div aria-hidden="true" className="page-container home-sections">
        <div className="home-pair">
          <div className="home-calendar">
            <span className="skeleton home-section-skeleton" />
            <CalendarLoading />
          </div>
          <div className="home-topometro">
            <span className="skeleton home-section-skeleton" />
            <PodiumLoading />
          </div>
        </div>
      </div>
    </div>
  );
}
