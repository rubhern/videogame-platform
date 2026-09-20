import { Link } from "react-router-dom";

import { CatalogueCover } from "../../shared/ui/catalogue-cover";
import type { ReleaseListItem } from "./releases-view-model";

type ReleaseCardProps = {
  item: ReleaseListItem;
};

export function ReleaseCard({ item }: ReleaseCardProps) {
  const gamePath = `/games/${item.gameId}/${item.slug}`;

  return (
    <article className="catalogue-card">
      <div className="release-cover-wrap">
        <CatalogueCover caption={false} cover={item.cover} to={gamePath} />
        <span className="release-date-badge">{item.date}</span>
      </div>
      <div className="card-body">
        <h3 className="card-title">
          <Link to={gamePath}>{item.title}</Link>
        </h3>
        <p className="card-platform">
          {item.platform} · {item.region}
        </p>
        {item.review === null ? null : (
          <ul aria-label={`Estado de los datos de ${item.title}`} className="card-statuses">
            <li className="badge badge-warning">{item.review}</li>
          </ul>
        )}
      </div>
    </article>
  );
}
