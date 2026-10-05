import { useState } from "react";
import { Link } from "react-router-dom";

import { platformIcon } from "../../shared/catalogue/taxonomy-icons";
import { SelectIcon } from "../../shared/ui/select-icon";
import { FeaturedArtFrame } from "./featured-art";
import type { FeaturedItem } from "./featured-releases-view-model";
import { ReleaseOverflow } from "./release-overflow";

/** A compact row shows up to three platform marks and the exact number of further platforms. */
const VISIBLE_PLATFORMS = 3;

/**
 * One of the other featured releases, designed for wide artwork: the landscape frame carries the
 * first presented release's date, and a screenshot, which never carries a title of its own, gets
 * the game's logo over it; artwork often carries its own and is shown as it is. Below the frame the
 * title is the card's only keyboard stop, then that release's platforms as marks and its region.
 * Every platform stays named for assistive technology, and further releases stay reachable behind
 * the overflow control.
 */
export function FeaturedCard({ item }: { item: FeaturedItem }) {
  const gamePath = `/games/${item.gameId}/${item.slug}`;
  const [primaryGroup, ...hiddenGroups] = item.releaseGroups;
  const [logoFailed, setLogoFailed] = useState(false);
  const visible = primaryGroup?.platforms.slice(0, VISIBLE_PLATFORMS) ?? [];
  const morePlatforms = (primaryGroup?.platforms.length ?? 0) - visible.length;
  const first = item.art[0];
  const logo =
    !logoFailed && first?.kind === "screenshot" && first.presentation === "fill"
      ? item.logo
      : null;

  return (
    <article className="featured-card">
      <div className="featured-card-frame">
        <Link className="featured-card-media" tabIndex={-1} to={gamePath}>
          <FeaturedArtFrame art={item.art} scale="card" />
          {logo === null ? null : (
            <img
              alt=""
              className="featured-card-logo"
              decoding="async"
              loading="lazy"
              onError={() => setLogoFailed(true)}
              src={logo.url}
            />
          )}
        </Link>
        {primaryGroup === undefined ? null : (
          <span aria-hidden="true" className="card-date-badge">
            {primaryGroup.shortDate}
          </span>
        )}
      </div>
      <div className="featured-card-body">
        <h3 className="featured-card-title">
          <Link title={item.title} to={gamePath}>
            {item.title}
          </Link>
        </h3>
        {primaryGroup === undefined ? null : (
          <p className="featured-card-release">
            <span className="sr-only">
              {primaryGroup.date}: {primaryGroup.platforms.join(", ")}.{" "}
            </span>
            <span aria-hidden="true" className="featured-card-marks">
              {visible.map((name, index) => (
                <span key={`${name}-${index}`} title={name}>
                  <SelectIcon name={platformIcon(primaryGroup.platformIds[index] ?? "", name)} />
                </span>
              ))}
              {morePlatforms > 0 ? (
                <span className="featured-card-more">+{morePlatforms}</span>
              ) : null}
            </span>
            <span className="featured-card-region">{primaryGroup.region}</span>
          </p>
        )}
        {item.genres.length === 0 ? null : (
          <ul aria-label="Géneros" className="featured-genres featured-card-genres">
            {item.genres.map((genre) => (
              <li key={genre.genreId}>{genre.name}</li>
            ))}
          </ul>
        )}
        {item.hiddenReleaseCount > 0 ? (
          <ReleaseOverflow
            groups={hiddenGroups}
            hiddenReleaseCount={item.hiddenReleaseCount}
            title={item.title}
          />
        ) : null}
      </div>
    </article>
  );
}
