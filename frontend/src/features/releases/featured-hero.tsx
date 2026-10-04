import type { CSSProperties } from "react";
import { Link } from "react-router-dom";

import { platformIcon } from "../../shared/catalogue/taxonomy-icons";
import { SelectIcon } from "../../shared/ui/select-icon";
import { FeaturedArtFrame } from "./featured-art";
import { CalendarIcon, SparkIcon } from "./featured-icons";
import type { FeaturedLead } from "./featured-releases-view-model";
import { ReleaseOverflow } from "./release-overflow";

type FeaturedHeroProps = {
  lead: FeaturedLead;
};

/** Phones show this many platform marks and the exact count of the rest, as the cards do. */
const COMPACT_PLATFORMS = 2;

function longestWord(text: string): number {
  return Math.max(...text.split(/\s+/u).map((word) => word.length));
}

/**
 * The month's featured release, cinematic. Key art keeps its subject clear of the logo space at
 * its left, so the copy takes that side and the landscape image spans the rest of the hero,
 * fading in behind the copy; the whole card is lit by the same image, blurred. Gameómetro sets
 * the canonical title as its own wordmark, never a provider logo: the name in the wide display
 * cut, cast in moonlit metal that takes a trace of the art's colour, and any subtitle in the lit
 * serif. Its size follows the name's longest word and length, so every title keeps one wordmark
 * scale without breaking a word. The copy states only contract data: the presented release's
 * date, platforms and region, its lifecycle and stage, and further releases behind the overflow
 * control, then the one action, Ver ficha; the page's own link opens the release lists. It
 * names the selection's rule, attention, and never a quality, rating or award.
 */
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
    <article aria-labelledby="featured-lead-title" className="featured-hero" style={style}>
      <div aria-hidden="true" className="featured-hero-light" />
      <Link className="featured-hero-visual" tabIndex={-1} to={gamePath}>
        <FeaturedArtFrame art={lead.art} scale="hero" />
      </Link>
      <div className="featured-hero-body">
        <p className="featured-badge">
          <SparkIcon />
          Lanzamiento del mes
        </p>
        <h2 className="featured-hero-title" id="featured-lead-title">
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
        <div className="featured-hero-facts">
          {lead.facts.length === 0 ? null : (
            <ul aria-label="Estado del lanzamiento" className="featured-chips">
              {lead.facts.map((fact) => (
                <li className="featured-chip" key={fact}>
                  {fact}
                </li>
              ))}
            </ul>
          )}
          {lead.hiddenReleaseCount > 0 ? (
            <ReleaseOverflow
              groups={hiddenGroups}
              hiddenReleaseCount={lead.hiddenReleaseCount}
              title={lead.title}
            />
          ) : null}
        </div>
        <div className="featured-hero-actions">
          <Link className="button button-primary featured-cta view-action" to={gamePath}>
            Ver ficha <span className="sr-only">de {lead.title}</span>
            <span aria-hidden="true">→</span>
          </Link>
        </div>
      </div>
    </article>
  );
}
