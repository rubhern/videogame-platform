import { formatCalendarDay, formatReleaseDate } from "../../shared/catalogue/release-date";
import { releaseStages, releaseStatuses } from "../../shared/catalogue/release-labels";
import { platformIcon, regionIcon } from "../../shared/catalogue/taxonomy-icons";
import { SelectIcon } from "../../shared/ui/select-icon";
import type { GameDetails } from "./game-details-api";

type Release = GameDetails["releases"][number];

/** Tone of a release status chip; the chip always states the status in words as well. */
const statusTones: Record<Release["status"], string> = {
  announced: "upcoming",
  scheduled: "upcoming",
  released: "released",
  delayed: "warning",
  cancelled: "danger",
  unknown: "neutral",
};

/**
 * Splits the complete release set into the presented release of each platform and region and the
 * further records of each combination. The API lists platform by platform with each combination's
 * presented release first, so first appearance already is the presentation; nothing is re-sorted,
 * merged or dropped.
 */
function partition(releases: readonly Release[]) {
  const seen = new Set<string>();
  const presented: Release[] = [];
  const additional: Release[] = [];
  for (const release of releases) {
    const key = JSON.stringify([release.platform.platformId, release.region.regionId]);
    if (seen.has(key)) additional.push(release);
    else {
      seen.add(key);
      presented.push(release);
    }
  }
  return { presented, additional };
}

/** The attribution line: every source once, in order, and the most recent synchronization. */
function sourceLine(releases: readonly Release[]) {
  const sources = [...new Set(releases.map((release) => release.provenance.sourceName))];
  const latest = releases.reduce<Release | null>(
    (newest, release) =>
      newest === null || Date.parse(release.lastSyncedAt) > Date.parse(newest.lastSyncedAt)
        ? release
        : newest,
    null,
  );
  return {
    sources: `${sources.length === 1 ? "Fuente" : "Fuentes"}: ${sources.join(", ")}`,
    syncedAt: latest?.lastSyncedAt ?? null,
  };
}

function ReleaseRow({ release }: { release: Release }) {
  const review = release.reviewStatus === "required";
  const stale = release.freshnessStatus === "stale";
  const verified = release.verificationLevel === "verified";
  return (
    <li className="game-release-row">
      {/* The values speak for themselves on screen: a mark, a date, a chip. Assistive technology
          still hears each one named. */}
      <dl className="game-release-facts">
        <div className="game-release-platform">
          <dt className="sr-only">Plataforma</dt>
          <dd>
            {/* Every mark gets the same slot, so square icons and wide wordmarks align the names. */}
            <span className="game-release-mark">
              <SelectIcon name={platformIcon(release.platform.platformId, release.platform.name)} />
            </span>
            <span>{release.platform.name}</span>
          </dd>
        </div>
        <div className="game-release-region">
          <dt className="sr-only">Región</dt>
          <dd>
            <SelectIcon name={regionIcon(release.region.regionId, release.region.name)} />
            <span>{release.region.name}</span>
          </dd>
        </div>
        <div className="game-release-when">
          <dt className="sr-only">Lanzamiento</dt>
          <dd>
            <span className="game-release-date">{formatReleaseDate(release.releaseDate)}</span>
            {/* An unstated stage adds nothing to the date, so only a known one is named. */}
            {release.stage === "unknown" ? null : (
              <span className="game-release-stage">{releaseStages[release.stage]}</span>
            )}
          </dd>
        </div>
        <div className="game-release-state">
          <dt className="sr-only">Estado</dt>
          <dd>
            <span className={`game-status game-status-${statusTones[release.status]}`}>
              {releaseStatuses[release.status]}
            </span>
          </dd>
        </div>
      </dl>
      {review || stale || verified ? (
        <ul className="game-release-flags">
          {review ? <li className="game-warning">Información pendiente de revisión</li> : null}
          {stale ? <li className="game-warning">Datos locales desactualizados</li> : null}
          {verified ? <li className="game-verified">Información verificada</li> : null}
        </ul>
      ) : null}
    </li>
  );
}

/**
 * Fechas y plataformas: every platform and region with its presented release, in the API's order,
 * so a visitor reads every date without choosing anything first. Further records of a combination
 * stay whole behind one native disclosure, and the provenance a visitor needs — the source and the
 * last synchronization — closes the block instead of repeating on every record.
 */
export function GameReleaseList({ releases }: { releases: readonly Release[] }) {
  const { presented, additional } = partition(releases);
  const source = releases.length > 0 ? sourceLine(releases) : null;
  return (
    <section className="game-release-section" aria-labelledby="game-releases-title">
      <h3 className="game-release-title" id="game-releases-title">
        Fechas y plataformas
      </h3>
      {presented.length === 0 ? (
        <p className="game-panel-empty">No hay lanzamientos comerciales registrados.</p>
      ) : (
        <ul className="game-release-list">
          {presented.map((release) => (
            <ReleaseRow key={release.releaseId} release={release} />
          ))}
        </ul>
      )}
      {additional.length > 0 ? (
        <details className="game-release-more">
          <summary>
            <span className="game-release-more-label">
              Otras fechas registradas ({additional.length})
            </span>
            {additional.some((release) => release.reviewStatus === "required") ? (
              <span className="badge badge-warning">Información pendiente de revisión</span>
            ) : null}
          </summary>
          <ul className="game-release-list">
            {additional.map((release) => (
              <ReleaseRow key={release.releaseId} release={release} />
            ))}
          </ul>
        </details>
      ) : null}
      {source ? (
        <p className="game-panel-source">
          {source.sources}
          {source.syncedAt ? (
            <>
              {" · "}Sincronizado el{" "}
              <time dateTime={source.syncedAt}>
                {formatCalendarDay(source.syncedAt.slice(0, 10))}
              </time>
            </>
          ) : null}
        </p>
      ) : null}
    </section>
  );
}
