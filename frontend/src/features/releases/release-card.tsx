import { Link } from "react-router-dom";

import { NeedleIcon } from "../../shared/brand/needle-icon";
import { GameGenres } from "../../shared/catalogue/game-genres";
import { PlatformMarks } from "../../shared/catalogue/platform-marks";
import { platformIcon } from "../../shared/catalogue/taxonomy-icons";
import { CatalogueCover } from "../../shared/ui/catalogue-cover";
import { DiscoveryScores } from "../../shared/score/discovery-scores";
import { ReleaseOverflow } from "./release-overflow";
import type { ReleaseListItem } from "./releases-view-model";

type ReleaseCardProps = {
  item: ReleaseListItem;
  /** Reports the calendar day of the card's release while it is pointed at or focused. */
  onPoint?: (day: string | null) => void;
};

/** A card draws this many platform marks; every further platform stays named and counted. */
const VISIBLE_PLATFORMS = 3;

/**
 * One release in a window: the cover leads, then the first relevant release's date under the fire
 * needle, the title as the card's only keyboard stop, the score readings, and one release line
 * with that date's platforms as marks and its region. The full date stays in that line for
 * assistive technology; further releases stay behind the overflow control.
 */
export function ReleaseCard({ item, onPoint }: ReleaseCardProps) {
  const gamePath = `/games/${item.gameId}/${item.slug}`;
  const [primaryGroup, ...hiddenGroups] = item.releaseGroups;
  const day = primaryGroup?.day ?? null;
  const point = onPoint === undefined || day === null ? undefined : () => onPoint(day);
  const leave = onPoint === undefined || day === null ? undefined : () => onPoint(null);

  return (
    <article
      className="catalogue-card release-card"
      onBlur={leave}
      onFocus={point}
      onPointerEnter={point}
      onPointerLeave={leave}
    >
      <CatalogueCover caption={false} cover={item.cover} to={gamePath} />
      {primaryGroup === undefined ? null : (
        <p aria-hidden="true" className="card-date">
          <NeedleIcon className="card-date-needle" />
          {primaryGroup.shortDate}
        </p>
      )}
      <h3 className="card-title">
        <Link to={gamePath}>{item.title}</Link>
      </h3>
      <DiscoveryScores scores={item.scores} title={item.title} />
      {primaryGroup === undefined ? null : (
        <div className="card-release">
          <span className="sr-only">{primaryGroup.date}</span>
          <PlatformMarks
            label={`Plataformas de ${item.title}`}
            marks={primaryGroup.platforms.map((name, index) => {
              const id = primaryGroup.platformIds[index] ?? "";
              return { key: `${id}-${index}`, name, icon: platformIcon(id, name) };
            })}
            visible={VISIBLE_PLATFORMS}
          />
          <span className="card-region">{primaryGroup.region}</span>
        </div>
      )}
      <GameGenres genres={item.genres} />
      {primaryGroup?.review ? (
        <ul aria-label={`Estado de los datos de ${item.title}`} className="card-statuses">
          <li className="badge badge-warning">Información pendiente de revisión</li>
        </ul>
      ) : null}
      {item.hiddenReleaseCount > 0 ? (
        <ReleaseOverflow
          title={item.title}
          hiddenReleaseCount={item.hiddenReleaseCount}
          groups={hiddenGroups}
        />
      ) : null}
    </article>
  );
}
