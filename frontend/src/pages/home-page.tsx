import { useEffect } from "react";
import { Navigate, useLocation } from "react-router-dom";

import { HomeCalendarPreview } from "../features/home/home-calendar-preview";
import { legacyHomeRedirect } from "../features/home/home-path";
import { HomePersonal } from "../features/home/home-personal";
import { HomePlatforms } from "../features/home/home-platforms";
import { HomeIntro, PreviewBoundary } from "../features/home/home-section";
import { HomeTopometroPreview } from "../features/home/home-topometro-preview";
import { HomeWeekHighlights } from "../features/home/home-week-highlights";

/**
 * Gameómetro's home (#382): a doorway to what the product already does, each preview reading the
 * same bounded public API as its destination and failing on its own. The week's featured release
 * leads on its own art; the highlights calendar, TOPómetro, Plataformas and the player's corner
 * follow. Former landing links keep their destinations.
 */
export function HomePage() {
  const location = useLocation();
  const legacy = legacyHomeRedirect(new URLSearchParams(location.search));

  useEffect(() => {
    const previousTitle = document.title;
    document.title = "Gameómetro";
    return () => {
      document.title = previousTitle;
    };
  }, []);

  if (legacy !== null) {
    return <Navigate replace state={location.state} to={`${legacy}${location.hash}`} />;
  }

  return (
    <div className="home-page">
      <div className="home-stage">
        <div className="page-container">
          <HomeIntro />
          <PreviewBoundary name="Destacados de la semana">
            <HomeWeekHighlights />
          </PreviewBoundary>
        </div>
      </div>
      <div className="page-container home-sections">
        <div className="home-pair">
          <PreviewBoundary name="El calendario">
            <HomeCalendarPreview />
          </PreviewBoundary>
          <PreviewBoundary name="El TOPómetro">
            <HomeTopometroPreview />
          </PreviewBoundary>
        </div>
        <PreviewBoundary name="Plataformas">
          <HomePlatforms />
        </PreviewBoundary>
        <PreviewBoundary name="Tu espacio">
          <HomePersonal />
        </PreviewBoundary>
      </div>
    </div>
  );
}
