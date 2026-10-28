import { Link } from "react-router-dom";

import { isPlatformLogoName, platformLogoAssets } from "../../shared/catalogue/platform-logo-assets";
import { SelectIcon, type SelectIconName } from "../../shared/ui/select-icon";
import { platformCataloguePath } from "../platform-catalogue/platform-catalogue-search";
import { PlatformDestinations } from "./platform-card";
import { PLATFORMS_PATH, type PlatformContext } from "./platform-context";
import type { PlatformDestination } from "./platform-destinations";

export type PlatformDirectoryState =
  | { status: "loading" }
  | { status: "catalogue-not-ready" }
  | { status: "error"; correlationId: string | null }
  | { status: "ready"; platforms: readonly PlatformContext[] };

type OtherPlatformsProps = {
  state: PlatformDirectoryState;
  /** The platform the URL opens, resolved against the directory; null when it names none of them. */
  selection: PlatformContext | null | undefined;
  destinations: readonly PlatformDestination[];
  onToggle: (platform: PlatformContext) => void;
  onRetry: () => void;
};

const PLACEHOLDERS = 6;

/** A platform whose artwork is its own name (SteamVR) shows no mark beside that same name. */
function drawsMark(logo: SelectIconName): boolean {
  return !isPlatformLogoName(logo) || platformLogoAssets[logo].file !== null;
}

/**
 * **Otras plataformas**: the owner's other platforms that Gameómetro has games for, by their own
 * name and mark. Each name links to the catalogue; a separate disclosure opens its three
 * destinations. The open platform lives in the URL, so it is shareable and kept by history.
 */
export function OtherPlatforms({ state, selection, destinations, onToggle, onRetry }: OtherPlatformsProps) {
  const ready = state.status === "ready" ? state : null;
  const openKey = selection?.key;

  return (
    <section aria-labelledby="other-platforms-title" className="other-platforms">
      <h2 className="other-platforms-title" id="other-platforms-title">Otras plataformas</h2>

      {state.status === "loading" ? (
        <>
          <p className="sr-only" role="status">Cargando plataformas…</p>
          <ul aria-hidden="true" className="other-platforms-list">
            {Array.from({ length: PLACEHOLDERS }, (_, index) => (
              <li className="other-platform-placeholder" key={index}>
                <span className="skeleton" />
              </li>
            ))}
          </ul>
        </>
      ) : null}

      {state.status === "catalogue-not-ready" ? (
        <div className="notice notice-info" role="alert">
          <p className="notice-kicker">Datos en preparación</p>
          <h3>Las plataformas todavía no están disponibles</h3>
          <p>Aún no tenemos datos válidos de juegos, pero tranquilo que llegarán</p>
          <button className="button button-primary" onClick={onRetry} type="button">
            Reintentar
          </button>
        </div>
      ) : null}

      {state.status === "error" ? (
        <div className="notice notice-danger" role="alert">
          <p className="notice-kicker">Error de carga</p>
          <h3>No se pudieron cargar las plataformas</h3>
          <p>A Gameómetro le está costando un poco. Prueba más tarde</p>
          <button className="button button-danger" onClick={onRetry} type="button">
            Reintentar
          </button>
          {state.correlationId === null ? null : (
            <p className="notice-reference">Referencia para soporte: {state.correlationId}</p>
          )}
        </div>
      ) : null}

      {selection === null && ready !== null ? (
        <div className="notice notice-empty" role="status">
          <p className="notice-kicker">Plataforma no encontrada</p>
          <p>Esa plataforma no tiene juegos en Gameómetro ahora mismo.</p>
          <Link className="text-link" preventScrollReset to={PLATFORMS_PATH}>
            Ver todas las plataformas
          </Link>
        </div>
      ) : null}

      {ready !== null && ready.platforms.length === 0 ? (
        <div className="notice notice-empty" role="status">
          <p className="notice-kicker">Sin otras plataformas</p>
          <p>De momento, Gameómetro no tiene juegos de otras plataformas.</p>
        </div>
      ) : null}

      {ready !== null && ready.platforms.length > 0 ? (
        <ul className="other-platforms-list">
          {ready.platforms.map((platform) => {
            const open = platform.key === openKey;
            const destinationsId = `other-platform-${platform.key}-destinations`;
            return (
              <li className="other-platform" data-open={open || undefined} key={platform.key}>
                <Link className="other-platform-toggle" to={platformCataloguePath(platform.key)}>
                  <span className="other-platform-mark" data-logo={platform.logo}>
                    {drawsMark(platform.logo) ? <SelectIcon name={platform.logo} /> : null}
                  </span>
                  <span className="other-platform-name">{platform.name}</span>
                </Link>
                <button aria-controls={open ? destinationsId : undefined} aria-expanded={open}
                  aria-label={`Más destinos de ${platform.name}`} className="other-platform-more"
                  onClick={() => onToggle(platform)} type="button">
                  <span aria-hidden="true" className="other-platform-chevron" />
                </button>
                {open ? (
                  <PlatformDestinations context={platform} destinations={destinations} id={destinationsId} />
                ) : null}
              </li>
            );
          })}
        </ul>
      ) : null}
    </section>
  );
}
