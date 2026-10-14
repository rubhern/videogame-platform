import type { CSSProperties } from "react";
import { Link } from "react-router-dom";

import { platformIcon } from "../../shared/catalogue/taxonomy-icons";
import { SelectIcon } from "../../shared/ui/select-icon";
import { FeaturedArtFrame } from "./featured-art";
import { CalendarIcon, SparkIcon } from "./featured-icons";
import type { FeaturedLead } from "./featured-releases-view-model";
import { DiscoveryScores } from "./discovery-scores";
import { ReleaseOverflow } from "./release-overflow";

type FeaturedHeroProps = {
  lead: FeaturedLead;
};

/** Phones show this many platform marks and the exact count of the rest, as the cards do. */
const COMPACT_PLATFORMS = 2;

function longestWord(text: string): number {
  return Math.max(...text.split(/\s+/u).map((word) => word.length));
}

/** The title link spans the frame; source attribution and release overflow remain separate. */
export function FeaturedHero({ lead }: FeaturedHeroProps) {
  const gamePath = `/games/${lead.gameId}/${lead.slug}`;
  const [primaryGroup, ...hiddenGroups] = lead.releaseGroups;
  const { wordmark } = lead;
  const morePlatforms = (primaryGroup?.platforms.length ?? 0) - COMPACT_PLATFORMS;
  const style = {
    "--art": `url(${JSON.stringify(lead.lightUrl)})`,
    "--title-word": String(longestWord(wordmark.main)),
    "--title-length": String(wordmark.main.length),
  } as CSSProperties;

  return (
    <article aria-labelledby="featured-lead-title" className="featured-hero-section">
      <div className="featured-hero" style={style}>
        <div aria-hidden="true" className="featured-hero-light" />
        <div className="featured-hero-visual">
          <FeaturedArtFrame art={lead.art} scale="hero" />
        </div>
        <div className="featured-hero-body">
          <p className="featured-badge">
            <SparkIcon />
            Lanzamiento del mes
          </p>
          <h2 className="featured-hero-title" id="featured-lead-title">
            <Link className="featured-hero-game-link" to={gamePath}>
              <span className="featured-wordmark-main">
                {wordmark.main}
                {wordmark.sub === null ? null : (
                  <span className="featured-wordmark-delimiter">{wordmark.delimiter}</span>
                )}
              </span>
              {wordmark.sub === null ? null : (
                <>
                  {" "}
                  <span className="featured-wordmark-sub">{wordmark.sub}</span>
                </>
              )}
            </Link>
          </h2>
          {primaryGroup === undefined ? null : (
            <ul aria-label="Lanzamiento" className="featured-hero-meta">
              <li className="featured-hero-date">
                <CalendarIcon />
                <span aria-hidden="true">{primaryGroup.shortDate}</span>
                <span className="sr-only">{primaryGroup.date}</span>
              </li>
              <li className="featured-hero-platforms">
                {/* Recognized platforms show their marks, named for assistive technology and as a
                    tooltip; an unrecognized one keeps its name beside the generic mark. Phones keep
                    the first marks and the exact count of the rest; every name stays announced. */}
                <ul aria-label="Plataformas" className="featured-platform-marks">
                  {primaryGroup.platforms.map((name, index) => {
                    const icon = platformIcon(primaryGroup.platformIds[index] ?? "", name);
                    return (
                      <li key={`${name}-${index}`} title={name}>
                        <SelectIcon name={icon} />
                        {icon === "platform" ? (
                          <span className="featured-platform-name">{name}</span>
                        ) : (
                          <span className="sr-only">{name}</span>
                        )}
                      </li>
                    );
                  })}
                </ul>
                {morePlatforms > 0 ? (
                  <span aria-hidden="true" className="featured-platform-more">
                    +{morePlatforms}
                  </span>
                ) : null}
              </li>
              <li className="featured-hero-region">
                <SelectIcon name="worldwide" />
                <span>{primaryGroup.region}</span>
              </li>
            </ul>
          )}
          {lead.genres.length === 0 ? null : (
            <ul aria-label="Géneros" className="featured-genres">
              {lead.genres.map((genre) => (
                <li key={genre.genreId}>{genre.name}</li>
              ))}
            </ul>
          )}
          <DiscoveryScores scores={lead.scores} title={lead.title} layout="hero" />
          {lead.summary === null ? null : (
            <div className="featured-hero-description">
              <p className="featured-hero-summary" lang={lead.summary.language}>
                {lead.summary.text}
              </p>
            </div>
          )}
          {lead.facts.length === 0 ? null : (
            <ul aria-label="Estado del lanzamiento" className="featured-chips">
              {lead.facts.map((fact) => (
                <li className="featured-chip" key={fact}>{fact}</li>
              ))}
            </ul>
          )}
        </div>
      </div>
      {lead.hiddenReleaseCount > 0 ? (
        <div className="featured-hero-overflow">
          <ReleaseOverflow
            groups={hiddenGroups}
            hiddenReleaseCount={lead.hiddenReleaseCount}
            title={lead.title}
          />
        </div>
      ) : null}
    </article>
  );
}
