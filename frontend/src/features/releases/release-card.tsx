import { Link } from "react-router-dom";

import { CatalogueCover } from "../../shared/ui/catalogue-cover";
import { ReleaseOverflow } from "./release-overflow";
import type { ReleaseListItem } from "./releases-view-model";

type ReleaseCardProps = {
  item: ReleaseListItem;
};

export function ReleaseCard({ item }: ReleaseCardProps) {
  const gamePath = `/games/${item.gameId}/${item.slug}`;
  const [primaryGroup, ...hiddenGroups] = item.releaseGroups;

  return (
    <article className="catalogue-card">
      <CatalogueCover caption={false} cover={item.cover} to={gamePath} />
      <div className="card-body">
        <h3 className="card-title">
          <Link to={gamePath}>{item.title}</Link>
        </h3>
        {primaryGroup === undefined ? null : (
          <>
            {/* One compact row for the first relevant release: its date, then the platforms
                sharing that date and region, then the region. */}
            <p className="card-release">
              <span className="search-release-date">{primaryGroup.date}</span>
              <span className="card-platform">
                {primaryGroup.platforms.join(" · ")} · {primaryGroup.region}
              </span>
            </p>
            {primaryGroup.review ? (
              <ul aria-label={`Estado de los datos de ${item.title}`} className="card-statuses">
                <li className="badge badge-warning">Información pendiente de revisión</li>
              </ul>
            ) : null}
          </>
        )}
        {item.hiddenReleaseCount > 0 ? (
          <ReleaseOverflow
            title={item.title}
            hiddenReleaseCount={item.hiddenReleaseCount}
            groups={hiddenGroups}
          />
        ) : null}
      </div>
    </article>
  );
}
