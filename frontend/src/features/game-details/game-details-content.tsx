import { useSearchParams } from "react-router-dom";

import {
  formatCalendarDay,
  formatReleaseDate,
} from "../../shared/catalogue/release-date";
import { regionLabel } from "../../shared/catalogue/region-label";
import { CatalogueCover } from "../../shared/ui/catalogue-cover";
import type { GameDetails } from "./game-details-api";
import { GameRatingEntry } from "./game-rating-entry";

const eligibilityReasons: Record<
  GameDetails["ratingEligibility"]["reason"],
  string
> = {
  ELIGIBLE_RELEASE_FOUND:
    "Este juego cumple las condiciones de lanzamiento para recibir puntuaciones.",
  NO_COMMERCIAL_RELEASE: "Todavía no hay un lanzamiento comercial registrado.",
  RELEASE_NOT_OCCURRED:
    "El lanzamiento todavía no cumple la fecha necesaria para puntuar.",
  RELEASE_CANCELLED: "Los lanzamientos registrados están cancelados.",
  RELEASE_DATE_UNCERTAIN:
    "La fecha de lanzamiento es incierta y necesita verificación.",
  RELEASE_REVIEW_REQUIRED:
    "La información del lanzamiento está pendiente de revisión.",
};

const releaseStatuses: Record<
  GameDetails["releases"][number]["status"],
  string
> = {
  announced: "Anunciado",
  scheduled: "Programado",
  released: "Publicado",
  delayed: "Retrasado",
  cancelled: "Cancelado",
  unknown: "Estado sin confirmar",
};

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
      <div>
        <dt>Lanzamiento</dt>
        <dd className="game-release-date">
          {formatReleaseDate(release.releaseDate)}
        </dd>
      </div>
      <div>
        <dt>Estado</dt>
        <dd>{releaseStatuses[release.status]}</dd>
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

export function GameDetailsContent({ game }: { game: GameDetails }) {
  const [search, setSearch] = useSearchParams();
  // The API supplies one complete, bounded game. Selection changes only its visible context.
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
  const releases = platformReleases.filter(
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
      <header className="game-detail-heading">
        <p className="eyebrow eyebrow-dot">Ficha del catálogo</p>
        <h1 className="game-detail-title">{game.canonicalTitle}</h1>
        {game.aliases.length > 0 ? (
          <p className="game-detail-aliases">
            También conocido como {game.aliases.join(" · ")}
          </p>
        ) : null}
      </header>

      <div className="game-artwork">
        <CatalogueCover cover={coverForPresentation(game)} />
      </div>

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
                  <span>{p.name}</span>
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
                  <span>{regionLabel(r.name)}</span>
                </label>
              ))}
            </div>
          </fieldset>
        </div>
      ) : null}

      <div className="game-information-panel">
        <section
          className="game-release-context"
          aria-labelledby="game-context-title"
        >
          <h2 id="game-context-title" className="game-panel-title">
            Contexto de lanzamiento
          </h2>
          <p className="game-context-announcement" role="status">
            {platform && region
              ? `${platform.name} · ${regionLabel(region.name)} · ${releases.length} ${releases.length === 1 ? "lanzamiento" : "lanzamientos"}`
              : "No hay lanzamientos comerciales registrados."}
          </p>
          {releases.map((release, index) => (
            <div key={release.releaseId} className="game-release-record">
              {releases.length > 1 ? <h3>Registro {index + 1}</h3> : null}
              <Evidence release={release} />
            </div>
          ))}
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

      <section
        className="game-community-score"
        aria-labelledby="statistics-title"
      >
        <span className="game-score-symbol" aria-hidden="true">
          ☆
        </span>
        <div>
          <h2 id="statistics-title" className="game-score-label">
            Puntuaciones de la comunidad
          </h2>
          {available ? (
            available.count === 0 ? (
              <>
                <p className="game-score-empty">Sin nota todavía</p>
                <p className="game-score-description">
                  Todavía no hay puntuaciones para este juego.
                </p>
              </>
            ) : (
              <>
                <p
                  className="game-score-value"
                  aria-label={`Nota media: ${mean} de 10`}
                >
                  {mean}
                  <span> / 10</span>
                </p>
                <p className="game-score-description">
                  {available.count.toLocaleString("es-ES")} puntuaciones
                </p>
              </>
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
      </section>

      <div className="game-rating-context">
        <section
          aria-labelledby="eligibility-title"
          className="game-eligibility"
        >
          <p className="game-context-kicker">Elegibilidad del juego</p>
          <h2 className="game-panel-title" id="eligibility-title">
            {game.ratingEligibility.eligible
              ? "Disponible para puntuar"
              : "Todavía no disponible para puntuar"}
          </h2>
          <p>{eligibilityReasons[game.ratingEligibility.reason]}</p>
          <p className="game-rating-scope">
            La elegibilidad y la nota son globales al juego.
          </p>
          <p className="game-evaluated-on">
            Evaluado el {formatCalendarDay(game.ratingEligibility.evaluatedOn)}{" "}
            · Europe/Madrid
          </p>
        </section>
        <section
          aria-labelledby="personal-title"
          className="game-personal-rating"
        >
          <p className="game-context-kicker">Contexto personal</p>
          <h2 className="game-panel-title" id="personal-title">
            Tu puntuación
          </h2>
          {game.ratingEligibility.eligible ? (
            <GameRatingEntry game={game} />
          ) : (
            <p>La puntuación todavía no está disponible para este juego.</p>
          )}
        </section>
      </div>
    </div>
  );
}
