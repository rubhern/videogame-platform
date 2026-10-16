import { useRef, type CSSProperties } from "react";
import { Link } from "react-router-dom";

import { useElementSize } from "../../shared/browser/use-element-size";
import { platformIcon } from "../../shared/catalogue/taxonomy-icons";
import { DiscoveryScores } from "../../shared/score/discovery-scores";
import { SelectIcon } from "../../shared/ui/select-icon";
import { FeaturedArtFrame } from "./featured-art";
import { CalendarIcon, NeedleIcon } from "./featured-icons";
import type { FeaturedLead } from "./featured-releases-view-model";
import { MonthDial } from "./month-dial";
import { encloseDial, type DialShape } from "./month-dial-geometry";
import { describeMonthScale, type MonthTimeline } from "./month-timeline";
import { ReleaseOverflow } from "./release-overflow";

type FeaturedHeroProps = {
  lead: FeaturedLead;
  /** The represented month; null only when the response's dates are malformed. */
  timeline: MonthTimeline | null;
  /** `octubre de 2026`. */
  monthPhrase: string;
};

/** Phones show this many platform marks and the exact count of the rest, as the cards do. */
const COMPACT_PLATFORMS = 2;

/** The phone's G: a small emblem beside the art's lower edge, never the whole screen. */
const EMBLEM: { width: number; height: number; shape: DialShape } = {
  width: 150,
  height: 132,
  shape: { cx: 75, cy: 68, a: 44, b: 40, exponent: 3.2, band: 12, bar: 12 },
};

function longestWord(text: string): number {
  return Math.max(...text.split(/\s+/u).map((word) => word.length));
}

/**
 * The month's featured release. Its own art is the stage, and Gameómetro's temporal G holds its
 * copy: the month's days around the bowl, the elapsed ones lit, the release's day under the fire
 * needle. The title link spans the hero; score attribution and release overflow stay separate.
 */
export function FeaturedHero({ lead, timeline, monthPhrase }: FeaturedHeroProps) {
  const gamePath = `/games/${lead.gameId}/${lead.slug}`;
  const [primaryGroup, ...hiddenGroups] = lead.releaseGroups;
  const { wordmark } = lead;
  const morePlatforms = (primaryGroup?.platforms.length ?? 0) - COMPACT_PLATFORMS;
  const copyRef = useRef<HTMLDivElement>(null);
  const copySize = useElementSize(copyRef);
  const dial =
    timeline === null || copySize === null ? null : encloseDial(copySize.width, copySize.height);
  const markLabel = primaryGroup?.shortDate ?? "Por confirmar";
  const style = {
    "--art": `url(${JSON.stringify(lead.lightUrl)})`,
    "--title-word": String(longestWord(wordmark.main)),
    "--title-length": String(wordmark.main.length),
  } as CSSProperties;
  const dialStyle =
    dial === null
      ? undefined
      : ({
          "--dial-pad-x": `${dial.padX}px`,
          "--dial-pad-y": `${dial.padY}px`,
          "--dial-w": `${dial.width}px`,
          "--dial-h": `${dial.height}px`,
        } as CSSProperties);

  return (
    <article aria-labelledby="featured-lead-title" className="featured-hero-section" style={style}>
      <div className="featured-hero">
        {/* The game's own light washes the stage; its art runs up behind the header on wide
            screens and sits above the copy on narrow ones. */}
        <div aria-hidden="true" className="featured-hero-light" />
        <div className="featured-hero-visual">
          <FeaturedArtFrame art={lead.art} scale="hero" />
        </div>
        <div className="featured-dial" style={dialStyle}>
          {dial === null || timeline === null ? null : (
            <MonthDial
              className="featured-dial-g"
              detail="full"
              height={dial.height}
              mark={lead.mark}
              markLabel={markLabel}
              shape={dial.shape}
              timeline={timeline}
              width={dial.width}
            />
          )}
          {timeline === null ? null : (
            <MonthDial
              className="featured-dial-emblem"
              detail="emblem"
              height={EMBLEM.height}
              mark={lead.mark}
              markLabel={markLabel}
              shape={EMBLEM.shape}
              timeline={timeline}
              width={EMBLEM.width}
            />
          )}
          <div className="featured-hero-body" ref={copyRef}>
            <p className="featured-badge">
              <NeedleIcon />
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
                  {/* Recognized platforms show their marks, named for assistive technology and
                      as a tooltip; an unrecognized one keeps its name beside the generic mark.
                      Phones keep the first marks and the exact count of the rest; every name
                      stays announced. */}
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
            {timeline === null ? null : (
              <p className="sr-only">{describeMonthScale(timeline, lead.mark, monthPhrase)}</p>
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
                  <li className="featured-chip" key={fact}>
                    {fact}
                  </li>
                ))}
              </ul>
            )}
          </div>
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
