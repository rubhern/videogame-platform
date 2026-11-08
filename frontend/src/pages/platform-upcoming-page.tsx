import { useEffect } from "react";
import { Link, useParams } from "react-router-dom";

import { PlatformPreferencesError } from "../features/platform-preferences/platform-preferences-api";
import { usePlatformList } from "../features/platform-preferences/use-platform-preferences";
import {
  featuredPlatforms,
  otherPlatforms,
  PLATFORMS_PATH,
  resolvePlatformContext,
  type PlatformContext,
} from "../features/platforms/platform-context";
import { UpcomingHighlightsApiError } from "../features/upcoming-highlights/upcoming-highlights-api";
import {
  UpcomingHighlightsShell,
  type UpcomingHighlightsState,
} from "../features/upcoming-highlights/upcoming-highlights-shell";
import { useUpcomingHighlightsQuery } from "../features/upcoming-highlights/use-upcoming-highlights-query";

function Unavailable() {
  return (
    <section aria-labelledby="upcoming-title" className="hero-page upcoming-page">
      <div className="page-container upcoming">
        <h1 className="page-title" id="upcoming-title">Próximos destacados</h1>
        <div className="notice notice-empty upcoming-notice" role="status">
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

function PlatformUpcoming({
  context,
  others,
}: {
  context: PlatformContext;
  others: readonly PlatformContext[] | null;
}) {
  const query = useUpcomingHighlightsQuery(context);
  let state: UpcomingHighlightsState;
  if (query.isPending) {
    state = { status: "loading" };
  } else if (query.isError) {
    state =
      query.error instanceof UpcomingHighlightsApiError && query.error.code === "CATALOGUE_NOT_READY"
        ? { status: "catalogue-not-ready" }
        : {
            status: "error",
            correlationId:
              query.error instanceof UpcomingHighlightsApiError ? query.error.correlationId : null,
          };
  } else {
    state = { status: "ready", model: query.data };
  }
  return (
    <UpcomingHighlightsShell
      context={context}
      onRetry={() => void query.refetch()}
      others={others}
      state={state}
    />
  );
}

/**
 * A platform's **Próximos destacados** (#388). A featured family resolves at once by its key, so
 * its ranking never waits for the directory; any other platform is resolved against the public
 * directory, so a stale or invented key never becomes a platform. `Mis plataformas` never applies.
 */
export function PlatformUpcomingPage() {
  const { platformKey = "" } = useParams();
  const directory = usePlatformList();
  const featured = featuredPlatforms.find(
    (platform) => platform.key === platformKey || platform.platformIds.includes(platformKey),
  );
  const context =
    featured ??
    (directory.data === undefined ? undefined : resolvePlatformContext(platformKey, directory.data));

  useEffect(() => {
    const previousTitle = document.title;
    document.title =
      context == null
        ? "Próximos destacados · Gameómetro"
        : `Próximos destacados de ${context.name} · Gameómetro`;
    return () => {
      document.title = previousTitle;
    };
  }, [context]);

  if (context === null) return <Unavailable />;

  if (context === undefined) {
    const failure = directory.error instanceof PlatformPreferencesError ? directory.error : null;
    return (
      <section aria-labelledby="upcoming-title" className="hero-page upcoming-page">
        <div className="page-container upcoming">
          <h1 className={directory.isError ? "page-title" : "sr-only"} id="upcoming-title">
            Próximos destacados
          </h1>
          {directory.isError ? (
            <div className="notice notice-danger upcoming-notice" role="alert">
              <p className="notice-kicker">
                {failure?.code === "CATALOGUE_NOT_READY" ? "Datos en preparación" : "Error de carga"}
              </p>
              <h2>No se pudo leer la plataforma</h2>
              <p>A Gameómetro le está costando un poco. Prueba más tarde</p>
              <button className="button button-danger" onClick={() => void directory.refetch()} type="button">
                Reintentar
              </button>
            </div>
          ) : (
            <p className="sr-only" role="status">Cargando plataforma…</p>
          )}
        </div>
      </section>
    );
  }

  return (
    <PlatformUpcoming
      // Another platform is another ranking, with its own reads and pointer state.
      context={context}
      key={context.key}
      others={directory.data === undefined ? null : otherPlatforms(directory.data)}
    />
  );
}
