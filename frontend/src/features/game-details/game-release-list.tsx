import { formatReleaseDate } from "../../shared/catalogue/release-date";
import { releaseStages, releaseStatuses } from "../../shared/catalogue/release-labels";
import { platformIcon, regionIcon } from "../../shared/catalogue/taxonomy-icons";
import { SelectIcon } from "../../shared/ui/select-icon";
import type { GameDetails } from "./game-details-api";
import { groupByContext, type ReleaseGroup } from "./release-groups";

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

/**
 * The attribution line: every source once, in order. Synchronization timing is internal
 * operational evidence and is never shown to players (#267).
 */
function sourceLine(releases: readonly Release[]) {
  const sources = [...new Set(releases.map((release) => release.provenance.sourceName))];
  return `${sources.length === 1 ? "Fuente" : "Fuentes"}: ${sources.join(", ")}`;
}

/**
 * One shared context and the platforms it applies to. The date, status, region, stage and notes
 * are identical for every release in the group, so they are stated once; each platform keeps its
 * own entry, and assistive technology hears the context with the list of platforms it covers.
 */
function ReleaseGroupRow({ group }: { group: ReleaseGroup }) {
  const [release] = group.releases;
  const review = release.reviewStatus === "required";
  const verified = release.verificationLevel === "verified";
  return (
    <li className="game-release-group">
      <dl className="game-release-facts">
        <div className="game-release-when">
          <dt className="sr-only">Lanzamiento</dt>
          <dd className="game-release-date">{formatReleaseDate(release.releaseDate)}</dd>
        </div>
        <div className="game-release-state">
          <dt className="sr-only">Estado</dt>
          <dd>
            <span className={`game-status game-status-${statusTones[release.status]}`}>
              {releaseStatuses[release.status]}
            </span>
          </dd>
        </div>
        <div className="game-release-region">
          <dt className="sr-only">Región</dt>
          <dd>
            <SelectIcon name={regionIcon(release.region.regionId, release.region.name)} />
            <span>{release.region.name}</span>
          </dd>
        </div>
        {/* An unstated stage adds nothing to the date, so only a known one is named. */}
        {release.stage === "unknown" ? null : (
          <div className="game-release-stage">
            <dt className="sr-only">Tipo de lanzamiento</dt>
            <dd>{releaseStages[release.stage]}</dd>
          </div>
        )}
        <div className="game-release-platforms">
          <dt className="sr-only">{group.releases.length === 1 ? "Plataforma" : "Plataformas"}</dt>
          <dd>
            <ul>
              {group.releases.map((member) => (
                <li key={member.releaseId}>
                  {/* Every mark gets the same slot, so square icons and wide wordmarks align the
                      names. */}
                  <span className="game-release-mark">
                    <SelectIcon name={platformIcon(member.platform.platformId, member.platform.name)} />
                  </span>
                  <span>{member.platform.name}</span>
                </li>
              ))}
            </ul>
          </dd>
        </div>
      </dl>
      {review || verified ? (
        <ul className="game-release-flags">
          {review ? <li className="game-warning">Información pendiente de revisión</li> : null}
          {verified ? <li className="game-verified">Información verificada</li> : null}
        </ul>
      ) : null}
    </li>
  );
}

function ReleaseGroups({ releases }: { releases: readonly Release[] }) {
  return (
    <ul className="game-release-list">
      {groupByContext(releases).map((group) => (
        <ReleaseGroupRow group={group} key={group.key} />
      ))}
    </ul>
  );
}

/**
 * Fechas y plataformas: every platform and region with its presented release, in the API's order,
 * so a visitor reads every date without choosing anything first. Releases that share their whole
 * context read as one group, so four platforms launching together state their date once. Further
 * records of a combination stay whole behind one native disclosure, whose count is the number of
 * records it holds, and the source attribution closes the block instead of repeating on every
 * record.
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
        <p className="game-information-empty">No hay lanzamientos comerciales registrados.</p>
      ) : (
        <ReleaseGroups releases={presented} />
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
          <ReleaseGroups releases={additional} />
        </details>
      ) : null}
      {source ? <p className="game-source">{source}</p> : null}
    </section>
  );
}
