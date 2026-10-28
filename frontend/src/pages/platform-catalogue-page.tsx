import { useEffect } from "react";
import { Link, useLocation, useNavigation, useParams } from "react-router-dom";

import { PlatformCatalogueShell } from "../features/platform-catalogue/platform-catalogue-shell";
import {
  toPlatformCatalogueFailure,
  type PlatformCatalogueState,
} from "../features/platform-catalogue/platform-catalogue-state";
import { readPlatformCatalogueSearch } from "../features/platform-catalogue/platform-catalogue-search";
import { usePlatformGamesQuery } from "../features/platform-catalogue/use-platform-games-query";
import { PlatformPreferencesError } from "../features/platform-preferences/platform-preferences-api";
import { usePlatformList } from "../features/platform-preferences/use-platform-preferences";
import {
  featuredPlatforms,
  otherPlatforms,
  PLATFORMS_PATH,
  resolvePlatformContext,
} from "../features/platforms/platform-context";
import { CatalogueLoading } from "../shared/ui/catalogue-loading";

/**
 * **Explorar catálogo** of one platform (#389). A featured family resolves at once by its key; any
 * other platform is resolved against the public directory, so a stale or invented key never
 * becomes a platform and reads as not on offer instead.
 */
export function PlatformCataloguePage() {
  const { platformKey = "" } = useParams();
  const location = useLocation();
  const navigation = useNavigation();
  // The route prefetches a choice's page before it commits; while it does, the page already
  // reads that choice, so the next one builds on it rather than on the selection it replaces.
  const pending = navigation.location?.pathname === location.pathname ? navigation.location : null;
  const search = readPlatformCatalogueSearch(new URLSearchParams((pending ?? location).search));
  const directory = usePlatformList();
  const featured = featuredPlatforms.find(
    (platform) => platform.key === platformKey || platform.platformIds.includes(platformKey),
  );
  const context =
    featured ??
    (directory.data === undefined ? undefined : resolvePlatformContext(platformKey, directory.data));
  const query = usePlatformGamesQuery(search, context ?? null);

  useEffect(() => {
    const previousTitle = document.title;
    document.title =
      context == null
        ? "Explorar catálogo · Gameómetro"
        : `${context.name} · Explorar catálogo · Gameómetro`;
    return () => {
      document.title = previousTitle;
    };
  }, [context]);

  if (context === undefined) {
    if (directory.isError) {
      const failure =
        directory.error instanceof PlatformPreferencesError ? directory.error : null;
      return (
        <section aria-labelledby="platform-catalogue-title" className="platform-catalogue-page">
          <div className="page-container platform-catalogue">
            <h1 className="page-title" id="platform-catalogue-title">Explorar catálogo</h1>
            <div className="notice notice-danger platform-catalogue-missing" role="alert">
              <p className="notice-kicker">
                {failure?.code === "CATALOGUE_NOT_READY" ? "Datos en preparación" : "Error de carga"}
              </p>
              <h2>No se pudo leer la plataforma</h2>
              <p>A Gameómetro le está costando un poco. Prueba más tarde</p>
              <button
                className="button button-danger"
                onClick={() => void directory.refetch()}
                type="button"
              >
                Reintentar
              </button>
            </div>
          </div>
        </section>
      );
    }
    return (
      <section aria-labelledby="platform-catalogue-title" className="platform-catalogue-page">
        <div className="page-container platform-catalogue">
          <h1 className="sr-only" id="platform-catalogue-title">Explorar catálogo</h1>
          <CatalogueLoading message="Cargando plataforma…" placeholders={12} />
        </div>
      </section>
    );
  }

  if (context === null) {
    return (
      <section aria-labelledby="platform-catalogue-title" className="platform-catalogue-page">
        <div className="page-container platform-catalogue">
          <h1 className="page-title" id="platform-catalogue-title">Explorar catálogo</h1>
          <div className="notice notice-empty platform-catalogue-missing" role="status">
            <p className="notice-kicker">Plataforma no disponible</p>
            <h2>Esa plataforma no tiene juegos en Gameómetro ahora mismo</h2>
            <Link className="button button-primary" to={PLATFORMS_PATH}>
              Ver todas las plataformas
            </Link>
          </div>
        </div>
      </section>
    );
  }

  let state: PlatformCatalogueState;
  if (query.isPending) {
    state = { status: "loading" };
  } else if (query.isError) {
    state = toPlatformCatalogueFailure(query.error);
  } else {
    state = {
      status: "ready",
      model: query.data,
      isRefreshing: query.isFetching,
      isPlaceholderData: query.isPlaceholderData,
    };
  }

  return (
    <PlatformCatalogueShell
      context={context}
      onRetry={() => void query.refetch()}
      otherPlatforms={directory.data === undefined ? null : otherPlatforms(directory.data)}
      search={search}
      state={state}
    />
  );
}
