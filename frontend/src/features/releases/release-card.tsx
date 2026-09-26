import type { CSSProperties } from "react";
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
  // The card is lit by its own cover: the same image, blurred beneath it.
  const coverLight = { "--cover-art": `url(${JSON.stringify(item.cover.url)})` } as CSSProperties;

  return (
    <article className="catalogue-card" style={coverLight}>
      <CatalogueCover caption={false} cover={item.cover} to={gamePath} />
      {primaryGroup === undefined ? null : (
        <span aria-hidden="true" className="card-date-badge">
          {primaryGroup.shortDate}
        </span>
      )}
      <div className="card-body">
        <h3 className="card-title">
          <Link to={gamePath}>{item.title}</Link>
        </h3>
        {primaryGroup === undefined ? null : (
          <>
            {/* One compact row for the first relevant release: its date (shown on the cover chip,
                read here in full), then the platforms sharing that date and region, then the
                region. */}
            <p className="card-release">
              <span className="sr-only">{primaryGroup.date}</span>
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
