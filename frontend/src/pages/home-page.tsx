import { useEffect } from "react";
import { Navigate, useLocation } from "react-router-dom";

import { legacyHomeRedirect } from "../features/home/home-path";
import { HomePersonal } from "../features/home/home-personal";
import { HomePlatforms } from "../features/home/home-platforms";
import { HomeIntro, PreviewBoundary } from "../features/home/home-section";
import { HomeTopometroPreview } from "../features/home/home-topometro-preview";
import { HomeReleaseDiscovery } from "../features/home/home-release-discovery";

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
          <PreviewBoundary name="Lanzamientos">
            <HomeReleaseDiscovery />
          </PreviewBoundary>
        </div>
      </div>
      <div className="page-container home-sections">
        <PreviewBoundary name="El TOPómetro">
          <HomeTopometroPreview />
        </PreviewBoundary>
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
