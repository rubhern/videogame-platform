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
        <CatalogueCover cover={item.cover} to={gamePath} />
        <span className="release-date-badge">{item.date}</span>
      </div>
      <div className="card-body">
        <h3 className="card-title">
          <Link tabIndex={-1} to={gamePath}>{item.title}</Link>
        </h3>
        <p className="card-platform">
          {item.platform} · {item.region}
        </p>
        <ul aria-label={`Estado de los datos de ${item.title}`} className="card-statuses">
          <li className="badge">{item.status}</li>
          <li className={item.isStale ? "badge badge-warning" : "badge"}>
            {item.freshness}
          </li>
          {item.review === null ? null : <li className="badge badge-warning">{item.review}</li>}
        </ul>
        <p className="card-source">Fuente: {item.provenance}</p>
        <Link className="card-action" to={gamePath}>
          <span className="sr-only">Ver {item.title}</span>
          <span aria-hidden="true">Ver ficha →</span>
        </Link>
      </div>
    </article>
  );
}
