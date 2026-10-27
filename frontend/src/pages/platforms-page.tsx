import { useEffect } from "react";
import { useSearchParams } from "react-router-dom";

import { PlatformPreferencesError } from "../features/platform-preferences/platform-preferences-api";
import { usePlatformList } from "../features/platform-preferences/use-platform-preferences";
import { OtherPlatforms, type PlatformDirectoryState } from "../features/platforms/other-platforms";
import { PlatformCard } from "../features/platforms/platform-card";
import {
  featuredPlatforms,
  hasCatalogueGames,
  otherPlatforms,
  PLATFORM_PARAM,
  readPlatformParam,
  resolvePlatformContext,
  type PlatformContext,
} from "../features/platforms/platform-context";
import { platformDestinations } from "../features/platforms/platform-destinations";
import { HeroTitle } from "../shared/ui/hero-title";

/**
 * **Plataformas** (#287): the owner's four featured platforms on their own stages, then the
 * owner's other platforms that the catalogue holds games for. Each platform offers the same three
 * destinations directly. The featured cards never wait for the directory read, which only adds
 * honest coverage and the other platforms.
 */
export function PlatformsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const directory = usePlatformList();
  const requested = readPlatformParam(searchParams);

  useEffect(() => {
    const previousTitle = document.title;
    document.title = "Plataformas · Gameómetro";
    return () => { document.title = previousTitle; };
  }, []);

  let state: PlatformDirectoryState;
  if (directory.data !== undefined) {
    state = { status: "ready", platforms: otherPlatforms(directory.data) };
  } else if (directory.isError) {
    const failure = directory.error instanceof PlatformPreferencesError ? directory.error : null;
    state = failure?.code === "CATALOGUE_NOT_READY"
      ? { status: "catalogue-not-ready" }
      : { status: "error", correlationId: failure?.correlationId ?? null };
  } else {
    state = { status: "loading" };
  }

  // A featured platform named in the URL is already on the page; only the others open in place.
  const resolved = requested === null || directory.data === undefined
    ? undefined
    : resolvePlatformContext(requested, directory.data);
  const selection = resolved === undefined || featuredPlatforms.some((platform) => platform === resolved)
    ? undefined
    : resolved;

  return (
    <section aria-labelledby="platforms-title" className="hero-page platforms-page">
      <div className="page-container platforms-section">
        <header className="page-head platforms-head">
          <HeroTitle id="platforms-title" title="Plataformas" />
        </header>

        <ul aria-label="Plataformas destacadas" className="platform-showcase">
          {featuredPlatforms.map((platform) => (
            <li className="platform-showcase-item" key={platform.key}>
              <PlatformCard
                context={platform}
                destinations={platformDestinations}
                note={
                  directory.data !== undefined && !hasCatalogueGames(platform, directory.data)
                    ? "Aún sin juegos en Gameómetro"
                    : undefined
                }
              />
            </li>
          ))}
        </ul>

        <OtherPlatforms
          destinations={platformDestinations}
          onRetry={() => void directory.refetch()}
          onToggle={(platform: PlatformContext) => {
            // Opening one closes any other; opening the open one closes it. Each is a history entry.
            setSearchParams(
              platform.key === selection?.key ? {} : { [PLATFORM_PARAM]: platform.key },
              { preventScrollReset: true },
            );
          }}
          selection={selection}
          state={state}
        />
      </div>
    </section>
  );
}
