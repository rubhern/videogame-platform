import type { CSSProperties, PointerEvent } from "react";
import { useSearchParams } from "react-router-dom";

import {
  formatCalendarDay,
  formatReleaseDate,
} from "../../shared/catalogue/release-date";
import { releaseStages, releaseStatuses } from "../../shared/catalogue/release-labels";
import { platformIcon, regionIcon } from "../../shared/catalogue/taxonomy-icons";
import { GameMeter } from "../../shared/score/game-meter";
import { thermalBand, thermalLabels } from "../../shared/score/thermal-band";
import { CatalogueCover } from "../../shared/ui/catalogue-cover";
import { CinematicStage } from "../../shared/ui/cinematic-stage";
import { SelectIcon } from "../../shared/ui/select-icon";
import { GameRatingPanel } from "../ratings/game-rating-panel";
import type { GameDetails } from "./game-details-api";

/** Tone of a release status chip; the chip always states the status in words as well. */
const statusTones: Record<GameDetails["releases"][number]["status"], string> = {
  announced: "upcoming",
  scheduled: "upcoming",
  released: "released",
  delayed: "warning",
  cancelled: "danger",
  unknown: "neutral",
};

/** Long canonical titles step down a size so the hero keeps its shape. */
const LONG_TITLE = 40;

function coverForPresentation(game: GameDetails) {
  const cover = game.primaryCover;
  if ("attribution" in cover && cover.attribution) {
    return {
      ...cover,
      kind: "provider" as const,
      attribution: cover.attribution,
    };
  }
  return {
    kind: "fallback" as const,
    url: cover.url,
    alternativeText: cover.alternativeText,
  };
}

function Evidence({ release }: { release: GameDetails["releases"][number] }) {
  return (
    <dl className="game-release-metadata">
      <div className="game-release-lead">
        <dt>Lanzamiento</dt>
        <dd className="game-release-date">
          {formatReleaseDate(release.releaseDate)}
          <span className="game-release-stage">{releaseStages[release.stage]}</span>
        </dd>
      </div>
      <div className="game-release-lead">
        <dt>Estado</dt>
        <dd className="game-release-status">
          <span className={`game-status game-status-${statusTones[release.status]}`}>
            {releaseStatuses[release.status]}
          </span>
        </dd>
      </div>
      <div>
        <dt>Verificación</dt>
        <dd>
          {release.verificationLevel === "verified"
            ? "Información verificada"
            : "Información del proveedor sin verificación adicional"}
        </dd>
      </div>
      <div>
        <dt>Revisión</dt>
        <dd
          className={
            release.reviewStatus === "required" ? "game-warning" : undefined
          }
        >
          {release.reviewStatus === "required"
            ? "Información pendiente de revisión"
            : "Sin revisión pendiente"}
        </dd>
      </div>
      <div>
        <dt>Actualidad</dt>
        <dd
          className={
            release.freshnessStatus === "stale" ? "game-warning" : undefined
          }
        >
          {release.freshnessStatus === "stale"
            ? "Datos locales desactualizados"
            : "Datos locales actualizados"}
        </dd>
      </div>
      <div>
        <dt>Fuente</dt>
        <dd>{release.provenance.sourceName}</dd>
      </div>
      <div>
        <dt>Sincronización</dt>
        <dd>
          <time dateTime={release.lastSyncedAt}>
            {formatCalendarDay(release.lastSyncedAt.slice(0, 10))}
          </time>
        </dd>
      </div>
      {release.lastVerifiedAt ? (
        <div>
          <dt>Última verificación</dt>
          <dd>
            <time dateTime={release.lastVerifiedAt}>
              {formatCalendarDay(release.lastVerifiedAt.slice(0, 10))}
            </time>
          </dd>
        </div>
      ) : null}
      {release.providerUpdatedAt ? (
        <div>
          <dt>Actualización de la fuente</dt>
          <dd>
            <time dateTime={release.providerUpdatedAt}>
              {formatCalendarDay(release.providerUpdatedAt.slice(0, 10))}
            </time>
          </dd>
        </div>
      ) : null}
    </dl>
  );
}

// The cover leans toward a mouse pointer and catches its light. Only style properties change, so
// nothing re-renders; touch, pen and reduced motion keep the cover still (see game-details.css).
function tiltCover(event: PointerEvent<HTMLDivElement>) {
  if (event.pointerType !== "mouse") return;
  const box = event.currentTarget.getBoundingClientRect();
  const x = (event.clientX - box.left) / box.width - 0.5;
  const y = (event.clientY - box.top) / box.height - 0.5;
  const { style } = event.currentTarget;
  style.setProperty("--tilt-x", `${(-y * 9).toFixed(2)}deg`);
  style.setProperty("--tilt-y", `${(x * 11).toFixed(2)}deg`);
  style.setProperty("--glare-x", `${((x + 0.5) * 100).toFixed(1)}%`);
  style.setProperty("--glare-y", `${((y + 0.5) * 100).toFixed(1)}%`);
}

function releaseCover(event: PointerEvent<HTMLDivElement>) {
  const { style } = event.currentTarget;
  for (const property of ["--tilt-x", "--tilt-y", "--glare-x", "--glare-y"]) {
    style.removeProperty(property);
  }
}

function contextAnnouncement(platform: string, region: string, additional: number) {
  const records =
    additional === 0
      ? ""
      : ` · ${additional} ${additional === 1 ? "fecha adicional" : "fechas adicionales"}`;
  return `${platform} · ${region}${records}`;
}

export function GameDetailsContent({ game }: { game: GameDetails }) {
  const [search, setSearch] = useSearchParams();
  // The API supplies one complete, bounded game. Selection changes only its visible context.
  // Releases arrive platform by platform with each platform's and each platform-and-region's
  // presented release first, so first appearance already selects the default region and record.
  const platforms = [
    ...new Map(
      game.releases.map((r) => [r.platform.platformId, r.platform]),
    ).values(),
  ];
  const platform =
    platforms.find((p) => p.platformId === search.get("platformId")) ??
    platforms[0];
  const platformReleases = game.releases.filter(
    (r) => r.platform.platformId === platform?.platformId,
  );
  const regions = [
    ...new Map(
      platformReleases.map((r) => [r.region.regionId, r.region]),
    ).values(),
  ];
  const region =
    regions.find((r) => r.regionId === search.get("regionId")) ?? regions[0];
  // Later records of the same platform and region stay available, unmerged, behind a disclosure.
  const [presented, ...additional] = platformReleases.filter(
    (r) => r.region.regionId === region?.regionId,
  );
  const statistics = game.ratingStatistics;
  const available =
    statistics.status === "available" && "count" in statistics
      ? statistics
      : null;
  const mean = available?.mean?.toLocaleString("es-ES", {
    minimumFractionDigits: 1,
    maximumFractionDigits: 1,
  });
  const scored = available !== null && available.count > 0;
  const communityMean = scored ? (available.mean ?? null) : null;
  // The temperature interprets the mean the page shows: the contract already rounds it.
  const band = communityMean === null ? null : thermalBand(communityMean);

  function selectPlatform(id: string) {
    const next = new URLSearchParams(search);
    next.set("platformId", id);
    const nextReleases = game.releases.filter(
      (r) => r.platform.platformId === id,
    );
    const nextRegion =
      nextReleases.find((r) => r.region.regionId === region?.regionId) ??
      nextReleases[0];
    if (nextRegion) next.set("regionId", nextRegion.region.regionId);
    else next.delete("regionId");
    setSearch(next, { preventScrollReset: true });
  }

  return (
    <div className="game-detail-layout">
      {/* The stage is lit by the cover this page already shows: no extra request, no new asset,
          and nothing readable depends on it. */}
      <CinematicStage coverUrl={game.primaryCover.url} variant="cover" />

      <header className="game-detail-heading">
        <p className="eyebrow eyebrow-dot">Ficha del catálogo</p>
        <h1
          className={`game-detail-title${game.canonicalTitle.length > LONG_TITLE ? " game-detail-title-long" : ""}`}
        >
          {game.canonicalTitle}
        </h1>
        {game.aliases.length > 0 ? (
          <p className="game-detail-aliases">
            También conocido como <span>{game.aliases.join(" · ")}</span>
          </p>
        ) : null}
      </header>

      <div
        className="game-artwork"
        onPointerLeave={releaseCover}
        onPointerMove={tiltCover}
        style={{ "--cover-art": `url(${JSON.stringify(game.primaryCover.url)})` } as CSSProperties}
      >
        <CatalogueCover cover={coverForPresentation(game)} />
      </div>

      <div className="game-scores">
        <section
          className="game-community-score"
          aria-labelledby="statistics-title"
          data-thermal={band ?? undefined}
        >
          <GameMeter className="game-score-meter" value={communityMean} />
          <h2 id="statistics-title" className="game-score-label">
            Puntuaciones de la comunidad
          </h2>
          <div className="game-score-body">
            {scored ? (
              <div className="game-score-reading">
                <p className="game-score-value" aria-label={`Nota media: ${mean} de 10`}>
                  {mean}
                  <span> / 10</span>
                </p>
                {band === null ? null : (
                  <p className="thermal-tag">
                    <span className="sr-only">Temperatura: </span>
                    {thermalLabels[band]}
                  </p>
                )}
              </div>
            ) : null}
            <div className="game-score-detail">
              {available ? (
                available.count === 0 ? (
                  <>
                    <p className="game-score-empty">Sin nota todavía</p>
                    <p className="game-score-description">
                      Todavía no hay puntuaciones para este juego.
                    </p>
                  </>
                ) : (
                  <p className="game-score-description">
                    {available.count.toLocaleString("es-ES")}{" "}
                    {available.count === 1 ? "puntuación" : "puntuaciones"}
                  </p>
                )
              ) : (
                <div role="status">
                  <p className="game-score-empty">Nota no disponible</p>
                  <p className="game-score-description">
                    Las estadísticas no están disponibles temporalmente. Puedes
                    seguir consultando el juego.
                  </p>
                </div>
              )}
            </div>
          </div>
        </section>
        <GameRatingPanel game={game} />
      </div>

      <div className="game-detail-main">
        <section
          className="game-release-context"
          aria-labelledby="game-context-title"
        >
          <div className="game-context-header">
            <h2 id="game-context-title" className="game-panel-title">
              Contexto de lanzamiento
            </h2>
            <p className="game-context-announcement" role="status">
              {platform && region
                ? contextAnnouncement(
                    platform.name,
                    region.name,
                    additional.length,
                  )
                : "No hay lanzamientos comerciales registrados."}
            </p>
          </div>
          {/* The selectors lead the release evidence they change; the ratings above belong to
              the whole game and never follow this selection. */}
          {platform && region ? (
            <div className="game-context-selectors">
              <fieldset>
                <legend>Plataforma</legend>
                <div className="game-context-options">
                  {platforms.map((p) => (
                    <label className="game-context-option" key={p.platformId}>
                      <input
                        type="radio"
                        name="game-platform"
                        value={p.platformId}
                        checked={platform.platformId === p.platformId}
                        onChange={() => selectPlatform(p.platformId)}
                      />
                      <span>
                        <SelectIcon name={platformIcon(p.platformId, p.name)} />
                        {p.name}
                      </span>
                    </label>
                  ))}
                </div>
              </fieldset>
              <fieldset>
                <legend>Región</legend>
                <div className="game-context-options">
                  {regions.map((r) => (
                    <label className="game-context-option" key={r.regionId}>
                      <input
                        type="radio"
                        name="game-region"
                        value={r.regionId}
                        checked={region.regionId === r.regionId}
                        onChange={() => {
                          const next = new URLSearchParams(search);
                          next.set("platformId", platform.platformId);
                          next.set("regionId", r.regionId);
                          setSearch(next, { preventScrollReset: true });
                        }}
                      />
                      <span>
                        <SelectIcon name={regionIcon(r.regionId, r.name)} />
                        {r.name}
                      </span>
                    </label>
                  ))}
                </div>
              </fieldset>
            </div>
          ) : null}
          {presented ? (
            <div className="game-release-records">
              <div className="game-release-record">
                <Evidence release={presented} />
              </div>
            </div>
          ) : null}
          {additional.length > 0 ? (
            <details className="game-release-more">
              <summary>
                <span className="game-release-more-label">
                  Otras fechas registradas ({additional.length})
                </span>
                {additional.some((r) => r.reviewStatus === "required") ? (
                  <span className="badge badge-warning">
                    Información pendiente de revisión
                  </span>
                ) : null}
              </summary>
              <div className="game-release-records">
                {additional.map((release) => (
                  <div key={release.releaseId} className="game-release-record">
                    <Evidence release={release} />
                  </div>
                ))}
              </div>
            </details>
          ) : null}
        </section>
        <section
          aria-labelledby="summary-title"
          className="game-compact-summary"
        >
          <h2 className="game-panel-title" id="summary-title">
            Resumen
          </h2>
          <p className="game-summary-text" lang={game.summary.language}>
            {game.summary.text}
          </p>
          <p className="game-summary-source">
            {"provenance" in game.summary
              ? `Fuente del resumen: ${game.summary.provenance.sourceName}`
              : "Resumen editorial del catálogo"}
          </p>
        </section>
      </div>
    </div>
  );
}
