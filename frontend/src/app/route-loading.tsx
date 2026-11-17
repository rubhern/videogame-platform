import { useLocation } from "react-router-dom";

import { GameDetailsLoading } from "../features/game-details/game-details-loading";
import { HomeLoading } from "../features/home/home-loading";
import { HOME_PATH, legacyHomeRedirect } from "../features/home/home-path";
import { FeaturedReleasesShell } from "../features/releases/featured-releases-shell";
import { FEATURED_PATH, readFeaturedSearch } from "../features/releases/featured-search";
import { readReleasesSearch, RELEASES_PATH } from "../features/releases/releases-search";
import { ReleasesShell } from "../features/releases/releases-shell";
import { readGameSearchParams } from "../features/search/game-search-params";
import { GameSearchShell } from "../features/search/game-search-shell";
import { BackToOrigin } from "../pages/game-details-page";

/** Preserve each public page's loading surface without mounting its query or command effects. */
export function RouteLoading() {
  const location = useLocation();
  const params = new URLSearchParams(location.search);
  const state = { status: "loading" } as const;
  const onRetry = () => undefined; // Loading surfaces do not expose a retry action.

  // The home keeps its own surface; a former featured link still waits as Destacados.
  if (location.pathname === HOME_PATH && legacyHomeRedirect(params) === null) {
    return <HomeLoading />;
  }
  if (location.pathname === HOME_PATH || location.pathname === FEATURED_PATH) {
    return <FeaturedReleasesShell search={readFeaturedSearch(params)} state={state} onRetry={onRetry} />;
  }
  if (location.pathname === RELEASES_PATH) {
    return <ReleasesShell search={readReleasesSearch(params)} state={state} onRetry={onRetry} />;
  }
  if (location.pathname === "/search") {
    return <GameSearchShell params={readGameSearchParams(params)} state={state} onRetry={onRetry} />;
  }
  if (location.pathname.startsWith("/games/")) {
    return <section className="game-details-section"><div className="page-container">
      <h1 className="sr-only">Detalle del juego</h1>
      <GameDetailsLoading message="Cargando el juego…" />
      <div className="game-details-back"><BackToOrigin /></div>
    </div></section>;
  }
  return <p className="page-container" role="status">Cargando página…</p>;
}
