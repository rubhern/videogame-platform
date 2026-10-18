import { useEffect, useRef } from "react";
import { Link, Outlet, ScrollRestoration, useLocation, useNavigation } from "react-router-dom";

import {
  readDiscoveryView,
  readReleasesSearch,
  releasesSearchPath,
} from "../features/releases/releases-search";
import { BrandMark, BrandWordmark } from "../shared/brand/brand-logo";
import { LegalLinks } from "../shared/legal/legal-links";
import { AccountControl } from "./account-control";
import { CatalogueSearch } from "./catalogue-search";

export function AppShell() {
  const mainContentRef = useRef<HTMLElement>(null);
  const location = useLocation();
  const navigation = useNavigation();
  const discoveryView =
    location.pathname === "/" ? readDiscoveryView(new URLSearchParams(location.search)) : null;
  const pageIdentity = `${location.pathname}|${discoveryView ?? ""}`;
  const previousPage = useRef(pageIdentity);
  const releaseSearch = readReleasesSearch(new URLSearchParams(location.search));

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

          {/* One Lanzamientos navigation holds the three release sections (#151). */}
          <nav aria-label="Lanzamientos" className="primary-nav">
            <Link
              aria-current={discoveryView === "featured" ? "page" : undefined}
              className="nav-link"
              to="/"
            >
              Destacados
            </Link>
            <Link
              aria-current={discoveryView === "recent" ? "page" : undefined}
              className="nav-link"
              to={releasesSearchPath(releaseSearch, { view: "recent", page: 1 })}
            >
              Recientes
            </Link>
            <Link
              aria-current={discoveryView === "upcoming" ? "page" : undefined}
              className="nav-link"
              to={releasesSearchPath(releaseSearch, { view: "upcoming", page: 1 })}
            >
              Próximos
            </Link>
          </nav>

          <CatalogueSearch key={`${location.pathname}?${location.search}`} />

          <AccountControl />
        </div>
      </header>

      {navigation.state === "loading" ? (
        <p className="sr-only" role="status">Cargando página…</p>
      ) : null}
      <main aria-busy={navigation.state === "loading"} id="main-content" ref={mainContentRef} tabIndex={-1}>
        <Outlet />
        <ScrollRestoration />
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
