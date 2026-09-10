import { useEffect, useRef } from "react";
import { Link, Outlet, useLocation } from "react-router-dom";

import { readReleasesSearch, releasesSearchPath } from "../features/releases/releases-search";
import { AccountControl } from "./account-control";
import { CatalogueSearch } from "./catalogue-search";

export function AppShell() {
  const mainContentRef = useRef<HTMLElement>(null);
  const location = useLocation();
  const previousPathname = useRef(location.pathname);
  const releaseSearch = readReleasesSearch(new URLSearchParams(location.search));

  useEffect(() => {
    if (previousPathname.current === location.pathname) {
      return;
    }

    previousPathname.current = location.pathname;
    mainContentRef.current?.focus();
  }, [location.pathname]);

  const onReleases = location.pathname === "/";

  return (
    <div className="app-frame">
      <a className="skip-link button button-primary" href="#main-content">
        Saltar al contenido
      </a>
      <header className="site-header">
        <div className="header-layout page-container">
          <Link className="brand" to="/" aria-label="VideoGame Platform · Inicio">
            <span className="brand-mark" aria-hidden="true">
              <span />
            </span>
            <span className="brand-name">
              <span>VideoGame</span>
              <span>Platform</span>
            </span>
          </Link>

          <nav aria-label="Secciones principales" className="primary-nav">
            <Link
              aria-current={onReleases && releaseSearch.view === "recent" ? "page" : undefined}
              className="nav-link"
              to={releasesSearchPath(releaseSearch, { view: "recent", page: 1 })}
            >
              Recientes
            </Link>
            <Link
              aria-current={onReleases && releaseSearch.view === "upcoming" ? "page" : undefined}
              className="nav-link"
              to={releasesSearchPath(releaseSearch, { view: "upcoming", page: 1 })}
            >
              Próximos
            </Link>
            <Link
              aria-current={location.pathname === "/search" ? "page" : undefined}
              className="nav-link"
              to="/search"
            >
              Buscar
            </Link>
          </nav>

          <CatalogueSearch key={`${location.pathname}?${location.search}`} />

          <span className="account-divider" aria-hidden="true" />
          <AccountControl />
        </div>
        <div className="catalogue-masthead">
          <div className="page-container">
            <span>Catálogo de lanzamientos · MVP privado</span>
            <span>Datos locales · sin consultas al proveedor</span>
          </div>
        </div>
      </header>

      <main id="main-content" ref={mainContentRef} tabIndex={-1}>
        <Outlet />
      </main>

      <footer className="site-footer">
        <div className="page-container">
          <span>VideoGame Platform · MVP</span>
          <span>Catálogo local de lanzamientos</span>
        </div>
      </footer>
    </div>
  );
}
