import { useEffect } from "react";
import { useParams, useSearchParams } from "react-router-dom";

import { PlatformPreferencesError } from "../features/platform-preferences/platform-preferences-api";
import { usePlatformList } from "../features/platform-preferences/use-platform-preferences";
import {
  featuredPlatforms,
  otherPlatforms,
  resolvePlatformContext,
} from "../features/platforms/platform-context";
import { readRankingSource } from "../features/rankings/ranking-search";
import { PlatformRankingUnavailable, TopometroRanking } from "../features/rankings/topometro-ranking";

/**
 * A platform's **TOPómetro** (#387). A featured family resolves at once by its key, so its ranking
 * never waits for the directory; any other platform is resolved against the public directory, so
 * a stale or invented key never becomes a platform. `Mis plataformas` never applies.
 */
export function PlatformTopometroPage() {
  const { platformKey = "" } = useParams();
  const [searchParams] = useSearchParams();
  const source = readRankingSource(searchParams);
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
      context == null ? "TOPómetro · Gameómetro" : `TOPómetro de ${context.name} · Gameómetro`;
    return () => {
      document.title = previousTitle;
    };
  }, [context]);

  if (context === null) return <PlatformRankingUnavailable />;

  if (context === undefined) {
    const failure = directory.error instanceof PlatformPreferencesError ? directory.error : null;
    return (
      <section aria-labelledby="topometro-title" className="hero-page topometro-page" data-scope="platform">
        <div className="page-container topometro-section">
          <h1 className={directory.isError ? "page-title" : "sr-only"} id="topometro-title">TOPómetro</h1>
          {directory.isError ? (
            <div className="notice notice-danger ranking-notice" role="alert">
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
    <TopometroRanking
      // Another platform is another sequence: its own podium, focus and loaded pages.
      key={context.key}
      platform={{ context, others: directory.data === undefined ? null : otherPlatforms(directory.data) }}
      source={source}
    />
  );
}
