import { useEffect, useRef } from "react";
import { Link, Outlet, useLocation, useNavigation } from "react-router-dom";

import {
  readReleasesSearch,
  RELEASES_PATH,
  releasesSearchPath,
} from "../features/releases/releases-search";
import { CALENDAR_PATH } from "../features/releases/release-calendar-search";
import { BrandMark, BrandWordmark } from "../shared/brand/brand-logo";
import { LegalLinks } from "../shared/legal/legal-links";
import { AccountControl } from "./account-control";
import { CatalogueSearch } from "./catalogue-search";

export function AppShell() {
  const mainContentRef = useRef<HTMLElement>(null);
  const location = useLocation();
  const navigation = useNavigation();
  const onReleases = location.pathname === RELEASES_PATH;
  // The calendar is a view of Lanzamientos (#250), so the entry stays current there too.
  const inReleases = onReleases || location.pathname === CALENDAR_PATH;
  const pageIdentity = location.pathname;
  const previousPage = useRef(pageIdentity);
  // On the explorer itself the entry returns to its first page and keeps the rest of the choice.
  const releasesPath = onReleases
    ? releasesSearchPath(readReleasesSearch(new URLSearchParams(location.search)), { page: 1 })
    : RELEASES_PATH;

  useEffect(() => {
    if (previousPage.current === pageIdentity) {
      return;
    }

    previousPage.current = pageIdentity;
    mainContentRef.current?.focus({ preventScroll: true });
  }, [pageIdentity]);

  return (
    <div className="app-frame">
      {/* Light unsharp mask, applied only to the large game-detail cover. The provider CDN
          has no cover size big enough for that frame, so the artwork is always upscaled
          there; this restores edge definition without touching the source. The kernel sums
          to 1, so brightness is preserved. */}
      <svg aria-hidden="true" className="filter-defs" focusable="false">
        <filter id="cover-unsharp" x="0" y="0" width="100%" height="100%">
          <feConvolveMatrix
            order="3"
            preserveAlpha="true"
            kernelMatrix="0 -0.22 0 -0.22 1.88 -0.22 0 -0.22 0"
          />
        </filter>
      </svg>
      <a className="skip-link button button-primary" href="#main-content">
        Saltar al contenido
      </a>
      <header className="site-header">
        <div className="header-layout page-container">
          <Link className="brand" to="/" aria-label="Gameómetro · Inicio">
            <BrandMark className="brand-mark" />
            <BrandWordmark className="brand-wordmark" />
          </Link>

          {/* Two release entries (#341): Lanzamientos opens the release explorer (#285) and
              TOPómetro is the landing selection. */}
          <nav aria-label="Principal" className="primary-nav">
            <Link
              aria-current={inReleases ? "page" : undefined}
              className="nav-link"
              to={releasesPath}
            >
              Lanzamientos
            </Link>
            <Link
              aria-current={location.pathname === "/" ? "page" : undefined}
              className="nav-link"
              to="/"
            >
              TOPómetro
            </Link>
          </nav>

          <CatalogueSearch key={`${location.pathname}?${location.search}`} />

          <AccountControl />
        </div>
      </header>

      <p className="sr-only" aria-live="polite">
        {navigation.state === "loading" ? "Cargando página…" : ""}
      </p>
      <main aria-busy={navigation.state === "loading"} id="main-content" ref={mainContentRef} tabIndex={-1}>
        <Outlet />
      </main>

      <footer className="site-footer">
        <div className="page-container">
          <span className="site-footer-brand">
            <BrandMark className="site-footer-mark" />
            Gameómetro
          </span>
          <LegalLinks />
        </div>
      </footer>
    </div>
  );
}
