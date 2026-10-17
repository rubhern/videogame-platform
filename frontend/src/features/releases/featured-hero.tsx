import { useLayoutEffect, useRef, type CSSProperties } from "react";
import { Link } from "react-router-dom";

import { platformIcon } from "../../shared/catalogue/taxonomy-icons";
import { DiscoveryScores } from "../../shared/score/discovery-scores";
import { SelectIcon } from "../../shared/ui/select-icon";
import { FeaturedArtFrame } from "./featured-art";
import { NeedleIcon } from "../../shared/brand/needle-icon";
import { CalendarIcon } from "./featured-icons";
import type { FeaturedLead } from "./featured-releases-view-model";
import { MonthDial, type DialReadout } from "./month-dial";
import { DIAL_HEIGHT, DIAL_WIDTH } from "./month-dial-geometry";
import type { FeaturedGranularity } from "./featured-search";
import { describePeriodScale, type PeriodTimeline } from "./period-timeline";
import { ReleaseOverflow } from "./release-overflow";

type FeaturedHeroProps = {
  lead: FeaturedLead;
  granularity: FeaturedGranularity;
  /** The represented period; null only when the response's dates are malformed. */
  timeline: PeriodTimeline | null;
  /** `octubre de 2026` or `la semana del 12 al 18 de octubre de 2026`. */
  periodPhrase: string;
};

/** Phones show this many platform marks and the exact count of the rest, as the cards do. */
const COMPACT_PLATFORMS = 2;

/**
 * The compact date as the seal's readout: `29 oct 2026` reads as the day over its month, a coarser
 * period as its lead over the year, and a date without numerals (`Por confirmar`) quietly.
 */
function toReadout(shortDate: string): DialReadout {
  const [lead = shortDate, ...rest] = shortDate.split(" ");
  return { lead, rest: rest.length === 0 ? null : rest.join(" "), quiet: !/\d/u.test(shortDate) };
}

function longestWord(text: string): number {
  return Math.max(...text.split(/\s+/u).map((word) => word.length));
}

/**
 * The period's featured release. Its own art is the stage, and Gameómetro's temporal G frames its
 * copy: the week's or the month's days around the bowl, the elapsed ones lit, the release's day
 * under the fire needle. The G keeps its own proportions; the copy keeps to its reading zone. The title link
 * spans the hero; score attribution and release overflow stay separate.
 */
export function FeaturedHero({ lead, granularity, timeline, periodPhrase }: FeaturedHeroProps) {
  const zoneRef = useRef<HTMLDivElement>(null);
  const bodyRef = useRef<HTMLDivElement>(null);

  useLayoutEffect(() => {
    const zone = zoneRef.current;
    const body = bodyRef.current;
    if (zone === null || body === null || typeof ResizeObserver === "undefined") return;
    const desktop = window.matchMedia("(min-width: 620px)");
    const fit = () => {
      body.style.setProperty("--hero-copy-fit", "1");
      if (!desktop.matches || zone.clientHeight === 0) return;
      const available = zone.getBoundingClientRect().height;
      // Find the largest fitting size: changing zoom can also change line wrapping. Keep
      // measurement bounded and leave the whole title, metadata and readings visible.
      if (body.getBoundingClientRect().height <= available) return;
      let low = 0;
      let high = 1;
      for (let pass = 0; pass < 8; pass += 1) {
        const scale = (low + high) / 2;
        body.style.setProperty("--hero-copy-fit", String(scale));
        const height = Math.max(body.getBoundingClientRect().height, body.scrollHeight * scale);
        if (height <= available - 1) low = scale;
        else high = scale;
      }
      body.style.setProperty("--hero-copy-fit", String(low));
    };
    fit();
    const observer = new ResizeObserver(fit);
    observer.observe(zone);
    observer.observe(body);
    desktop.addEventListener("change", fit);
    return () => {
      observer.disconnect();
      desktop.removeEventListener("change", fit);
    };
  }, [lead]);

  const gamePath = `/games/${lead.gameId}/${lead.slug}`;
  const [primaryGroup, ...hiddenGroups] = lead.releaseGroups;
  const { wordmark } = lead;
  const morePlatforms = (primaryGroup?.platforms.length ?? 0) - COMPACT_PLATFORMS;
  const markLabel = primaryGroup?.shortDate ?? "Por confirmar";
  const style = {
    "--art": `url(${JSON.stringify(lead.lightUrl)})`,
    "--title-word": String(longestWord(wordmark.main)),
    "--title-length": String(wordmark.main.length),
  } as CSSProperties;

  return (
    <article aria-labelledby="featured-lead-title" className="featured-hero-section" style={style}>
      <div className="featured-hero">
        {/* The game's own light washes the stage; its art runs up behind the header on wide
            screens and sits above the copy on narrow ones. */}
        <div aria-hidden="true" className="featured-hero-light" />
        <div className="featured-hero-visual">
          <FeaturedArtFrame art={lead.art} scale="hero" />
        </div>
        <div className="featured-dial">
          <p className="featured-badge">
            <NeedleIcon className="featured-icon featured-needle-icon" />
            {granularity === "week" ? "Lanzamiento de la semana" : "Lanzamiento del mes"}
          </p>
          {timeline === null ? null : (
            <MonthDial
              className="featured-dial-g"
              detail="full"
              height={DIAL_HEIGHT}
              mark={lead.mark}
              markLabel={markLabel}
              timeline={timeline}
              width={DIAL_WIDTH}
            />
          )}
          {timeline === null ? null : (
            <MonthDial
              className="featured-dial-emblem"
              detail="emblem"
              height={DIAL_HEIGHT}
              mark={lead.mark}
              markLabel={markLabel}
              readout={toReadout(markLabel)}
              timeline={timeline}
              width={DIAL_WIDTH}
            />
          )}
          <div className="featured-hero-zone" ref={zoneRef}>
            <div className="featured-hero-body" ref={bodyRef}>
              <h2
                className="featured-hero-title"
                id="featured-lead-title"
              >
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
              {/* Release and genres form one run of facts: a column on the G, one wrapping line on
                  phones. The wrapper adds no semantics. */}
              <div className="featured-hero-facts">
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
                {lead.genres.length === 0 ? null : (
                  <ul aria-label="Géneros" className="featured-genres">
                    {lead.genres.map((genre) => (
                      <li key={genre.genreId}>{genre.name}</li>
                    ))}
                  </ul>
                )}
              </div>
              {timeline === null ? null : (
                <p className="sr-only">{describePeriodScale(timeline, lead.mark, periodPhrase)}</p>
              )}
              <DiscoveryScores scores={lead.scores} title={lead.title} layout="hero" />
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
