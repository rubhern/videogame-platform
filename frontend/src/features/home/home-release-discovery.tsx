import { useState } from "react";
import { Link, Navigate, useSearchParams } from "react-router-dom";

import { useQueries } from "@tanstack/react-query";

import { NeedleIcon } from "../../shared/brand/needle-icon";
import { PlatformMarks, type PlatformMark } from "../../shared/catalogue/platform-marks";
import { platformIcon } from "../../shared/catalogue/taxonomy-icons";
import { formatDayRange } from "../../shared/catalogue/release-date";
import { useGameLinkState } from "../../shared/navigation/use-game-link-state";
import { DiscoveryScores } from "../../shared/score/discovery-scores";
import { resolvePlatformFilter } from "../platform-preferences/interests";
import { useInterestsSwitch } from "../platform-preferences/interests-switch";
import { PlatformSwitcher } from "../platform-catalogue/platform-switcher";
import { useInterests, usePlatformList } from "../platform-preferences/use-platform-preferences";
import { otherPlatforms, resolvePlatformContext } from "../platforms/platform-context";
import { FeaturedArtFrame } from "../releases/featured-art";
import { CalendarIcon } from "../releases/featured-icons";
import { CalendarExpectationLegend } from "../releases/release-calendar-grid";
import { FeaturedPeriodSelector } from "../releases/featured-period-selector";
import { FEATURED_FALLBACK_URL, type FeaturedArt, type FeaturedItem } from "../releases/featured-releases-view-model";
import { featuredSearchPath, type FeaturedSearch } from "../releases/featured-search";
import { countLabel, densityLevel, EXPECTATION_LABELS, toReleaseCalendarModel, type ReleaseCalendarModel } from "../releases/release-calendar-model";
import { releaseCalendarQueryOptions } from "../releases/use-release-calendar-query";
import { calendarSearchPath, defaultCalendarSearch, toCalendarQuery } from "../releases/release-calendar-search";
import type { PeriodTimeline } from "../releases/period-timeline";
import type { ReleaseContextGroup, ReleaseListItem } from "../releases/releases-view-model";
import { defaultReleasesSearch, RELEASES_PATH, releasesRangePath } from "../releases/releases-search";
import { useFeaturedReleasesQuery } from "../releases/use-featured-releases-query";
import { useReleasesQuery } from "../releases/use-releases-query";
import { homeSearchPath, isoWeekForDay, readHomeSearch } from "./home-path";
import { HomeNotice } from "./home-section";

const PREVIEW_SIZE = 5;
// Use a full week's scale for every group, including weeks cut by a month boundary.
const WEEKLY_INTEREST_MULTIPLIER = 7;

function timelineInterest(slot: { from: string; to: string }, calendars: readonly ReleaseCalendarModel[], weekly: boolean) {
  if (!calendars.some(({ window }) => window.from <= slot.from && window.to >= slot.to)) {
    return null;
  }
  const days = calendars.flatMap((calendar) => calendar.days.filter(
    (day) => day.date >= slot.from && day.date <= slot.to,
  ));
  const count = days.reduce((sum, day) => sum + day.gameCount, 0);
  if (count === 0) return null;
  const thresholds = calendars[0]?.interestThresholds;
  const density = densityLevel(
    days.reduce((sum, day) => sum + day.totalHype, 0),
    weekly ? thresholds?.map((threshold) => threshold * WEEKLY_INTEREST_MULTIPLIER) : thresholds,
  );
  return { count, density };
}

type HomeGame = ReleaseListItem & Partial<Pick<FeaturedItem, "art" | "logo" | "stage">>;

function artFor(game: HomeGame): FeaturedArt[] {
  if (game.art !== undefined) return game.art;
  const fallback: FeaturedArt = {
    kind: "fallback",
    presentation: "fill",
    url: FEATURED_FALLBACK_URL,
    compactUrl: FEATURED_FALLBACK_URL,
    alt: "Imagen destacada no disponible",
  };
  if (game.cover.kind === "fallback") return [fallback];
  return [{
    kind: "cover",
    presentation: "contain",
    url: game.cover.url,
    compactUrl: game.cover.url,
    alt: game.cover.alternativeText,
  }, fallback];
}

function platformMarks(group: ReleaseContextGroup): PlatformMark[] {
  return group.platforms.map((name, index) => {
    const id = group.platformIds[index] ?? "";
    return { key: id === "" ? `${name}-${index}` : id, name, icon: platformIcon(id, name) };
  });
}

function HomeHighlightCard({ item }: { item: HomeGame }) {
  const art = artFor(item);
  const linkState = useGameLinkState();
  const gamePath = `/games/${item.gameId}/${item.slug}`;
  const [group] = item.releaseGroups;
  const [logoFailed, setLogoFailed] = useState(false);
  const first = art[0];
  // A screenshot never carries a title of its own, so the game's logo stands on it, as on Destacados.
  const logo =
    !logoFailed && first?.kind === "screenshot" && first.presentation === "fill" ? item.logo ?? null : null;
  return (
    <article className="home-more-card">
      <Link aria-hidden="true" className="home-more-media" state={linkState} tabIndex={-1} to={gamePath}>
        <FeaturedArtFrame art={art} scale="card" />
        {logo === null ? null : (
          <img
            alt=""
            className="home-more-logo"
            decoding="async"
            loading="lazy"
            onError={() => setLogoFailed(true)}
            src={logo.url}
          />
        )}
      </Link>
      <div className="home-more-body">
        {group === undefined ? null : (
          <p className="home-more-date">
            <NeedleIcon className="home-more-needle" />
            <span aria-hidden="true">{group.shortDate}</span>
            <span className="sr-only">{group.date}</span>
            {item.stage === null ? null : (
              <span className="featured-release-stage">{item.stage}</span>
            )}
          </p>
        )}
        <h3 className="home-more-title">
          <Link state={linkState} title={item.title} to={gamePath}>
            {item.title}
          </Link>
        </h3>
        {group === undefined ? null : (
          <PlatformMarks label={`Plataformas de ${item.title}`} marks={platformMarks(group)} visible={3} />
        )}
      </div>
    </article>
  );
}


function HomeLead({ game }: { game: HomeGame }) {
  const linkState = useGameLinkState();
  const [release] = game.releaseGroups;
  return (
    <article aria-labelledby="home-protagonist" className="home-feature">
      <div className="home-feature-art" aria-hidden="true">
        <FeaturedArtFrame art={artFor(game)} scale="hero" />
      </div>
      <div className="home-feature-copy">
        {release && <p className="home-more-date"><NeedleIcon className="home-more-needle" />{release.date}</p>}
        <h2 id="home-protagonist">
          <Link className="home-feature-game-link" state={linkState} to={"/games/" + game.gameId + "/" + game.slug}>
            {game.title}
          </Link>
        </h2>
        {release && (
          <div className="home-feature-facts">
            <PlatformMarks label="Plataformas" marks={platformMarks(release)} visible={3} />
            <span>{release.region}</span>
            {game.stage && <span className="featured-release-stage">{game.stage}</span>}
          </div>
        )}
        <DiscoveryScores scores={game.scores} title={game.title} layout="hero" />
      </div>
    </article>
  );
}

export function HomeReleaseLoading() {
  return <div aria-hidden="true" className="skeleton home-feature home-feature-pending" />;
}

function timelineSlots(timeline: PeriodTimeline) {
  const slots: (typeof timeline.divisions)[] = [];
  for (const day of timeline.divisions) {
    const previous = slots.at(-1);
    if (timeline.granularity === "week" || day.weekday === "lunes" || previous === undefined) {
      slots.push([day]);
    } else {
      previous.push(day);
    }
  }
  const today = timeline.today === null ? undefined : timeline.divisions[timeline.today - 1]?.date;
  return slots.flatMap((days) => {
    const first = days[0];
    const last = days.at(-1);
    if (first === undefined || last === undefined) return [];
    return [{
      from: first.date,
      to: last.date,
      label: timeline.granularity === "week" ? first.weekday.slice(0, 3) : "Semana",
      number: first === last ? first.day : first.day + "–" + last.day,
      current: today !== undefined && first.date <= today && today <= last.date,
    }];
  });
}

export function HomeReleaseDiscovery() {
  const [params, setSearchParams] = useSearchParams();
  const { featured, platformKey, selected } = readHomeSearch(params);
  const directory = usePlatformList();
  const platform = platformKey === undefined ? null : resolvePlatformContext(platformKey, directory.data ?? []);
  const platformPending = platformKey !== undefined && platform === null && directory.isPending;
  const interests = useInterests();
  const interestsSwitch = useInterestsSwitch();
  const filter = resolvePlatformFilter(platform?.platformIds ?? [], interestsSwitch.enabled, interests);
  const query = useFeaturedReleasesQuery(featured, filter.platformIds, interests.status !== "pending" && !platformPending);
  const model = query.isPlaceholderData ? undefined : query.data;
  // Keep the calendar steady during a platform change, without showing the previous platform's games.
  const frame = model ?? (query.data?.granularity === featured.granularity &&
    (featured.period === null ? query.data.isCurrent : query.data.period === featured.period) ? query.data : undefined);
  // A weekly frame can touch two calendar months.
  const calendarMonths = model === undefined ? [] : [...new Set([
    model.window.from.slice(0, 7), model.window.to.slice(0, 7),
  ])];
  const calendars = useQueries({
    queries: calendarMonths.map((month) => ({
      ...releaseCalendarQueryOptions({
        ...toCalendarQuery({ ...defaultCalendarSearch, month }, filter.platformIds),
        includePast: true,
      }),
      select: toReleaseCalendarModel,
      refetchOnMount: false as const,
    })),
  }).flatMap((calendar) => calendar.data === undefined ? [] : [calendar.data]);
  const slots = frame?.timeline ? timelineSlots(frame.timeline) : [];
  const slice = slots.find((slot) => slot.from === selected);
  const sliceQuery = useReleasesQuery({
    ...defaultReleasesSearch,
    from: slice?.from ?? null,
    to: slice?.to ?? null,
    platformIds: [...filter.platformIds],
    sort: "hype",
    pageSize: PREVIEW_SIZE,
  }, slice !== undefined && interests.status !== "pending" && !platformPending);
  const activeQuery = slice === undefined ? query : sliceQuery;
  const isLoading = activeQuery.isPending || activeQuery.isPlaceholderData;
  const activeSlice = slice === undefined || sliceQuery.isPlaceholderData ? undefined : sliceQuery.data;
  const items = slice === undefined
    ? model?.lead ? [model.lead, ...model.others].slice(0, PREVIEW_SIZE) : []
    : activeSlice?.items ?? [];
  const [lead, ...others] = items;
  const featuredItems = model?.lead ? [model.lead, ...model.others] : [];
  const featuredById = new Map(featuredItems.map((game) => [game.gameId, game]));
  const withFeaturedArt = (game: HomeGame): HomeGame => ({ ...featuredById.get(game.gameId), ...game });
  const range = slice ?? frame?.window;
  const explore = range === undefined ? RELEASES_PATH : releasesRangePath(range.from, range.to, platform?.platformIds);
  const calendar = calendarSearchPath(defaultCalendarSearch, {
    month: range !== undefined && frame !== undefined && range.from >= frame.evaluatedOn
      ? range.from.slice(0, 7)
      : null,
    day: slice?.from === slice?.to && slice !== undefined && frame !== undefined && slice.from >= frame.evaluatedOn
      ? slice.from
      : null,
    platformIds: [...(platform?.platformIds ?? [])],
  });
  const pathFor = (next: FeaturedSearch) => {
    const anchor = slice?.from ?? (featured.period === null ? undefined : frame?.window.from);
    if (anchor === undefined || next.granularity === featured.granularity) {
      return homeSearchPath(next, null, platform?.key);
    }
    const period = next.granularity === "month" ? anchor.slice(0, 7) : isoWeekForDay(anchor);
    return homeSearchPath({ ...next, period }, null, platform?.key);
  };
  const label = slice ? formatDayRange(slice.from, slice.to) : model?.periodLabel;
  const count = activeSlice?.page.totalItems ?? items.length;
  const unit = slice ? (count === 1 ? "lanzamiento" : "lanzamientos") : (count === 1 ? "destacado" : "destacados");
  const summary = label === undefined ? "" : [label, count + " " + unit].join(" · ");

  function updateSelection(date: string | null, platformKey: string | undefined) {
    const path = homeSearchPath(featured, date, platformKey);
    setSearchParams(path.split("?")[1] ?? "", { preventScrollReset: true });
  }

  if (platformKey !== undefined && platform === null && directory.isSuccess) {
    return <Navigate replace to={homeSearchPath(featured, selected, undefined)} />;
  }
  if (featured.period !== null && query.isError && query.error.code === "FILTER_INVALID") {
    return <Navigate replace to={pathFor({ ...featured, period: null })} />;
  }

  return (
    <section aria-label="Lanzamientos" className="home-discovery">
      <div className="home-discovery-controls">
        <FeaturedPeriodSelector label="Periodo de lanzamientos" model={frame ?? null} pathFor={pathFor} search={featured} />
        <PlatformSwitcher
          current={platform}
          others={directory.data === undefined ? null : otherPlatforms(directory.data)}
          pathFor={(option) => homeSearchPath(featured, selected, option.key)}
          filters={{
            current: platform === null ? (filter.mode === "on" ? "preferred" : "all") : null,
            preferredAvailable: interests.status === "ready",
            onChange: (value) => {
              interestsSwitch.set(value === "preferred");
              if (platformKey !== undefined) updateSelection(selected, undefined);
            },
          }}
        />
        <Link
          className="button home-featured-entry"
          to={featuredSearchPath({ ...featured, ...(platform === null ? {} : { platform: platform.key }) })}
        >
          <NeedleIcon className="home-featured-entry-needle" />
          Abrir Destacados <span aria-hidden="true" className="home-chevron" />
        </Link>
      </div>
      {slots.length > 0 && (
        <ul className="home-timeline" data-granularity={featured.granularity} aria-label={featured.granularity === "week" ? "Días de la semana" : "Semanas del mes"}>
          {slots.map((slot) => {
            const interest = timelineInterest(slot, calendars, featured.granularity === "month");
            return (
              <li key={slot.from}>
                <button
                  aria-label={[slot.label, formatDayRange(slot.from, slot.to), slot.current ? (featured.granularity === "week" ? "hoy" : "semana actual") : "", interest === null ? "" : countLabel(interest.count) + ", " + EXPECTATION_LABELS[interest.density]].filter(Boolean).join(", ")}
                  aria-pressed={slice?.from === slot.from}
                  className="home-timeline-slot"
                  title={interest === null ? undefined : EXPECTATION_LABELS[interest.density]}
                  data-current={slot.current || undefined}
                  data-density={interest?.density}
                  onClick={() => updateSelection(slice?.from === slot.from ? null : slot.from, platform?.key)}
                  type="button"
                >
                  <span>{slot.current ? (featured.granularity === "week" ? "Hoy" : "Actual") : slot.label}</span>
                  <strong>{slot.number}</strong>
                  {interest !== null && <span aria-hidden="true" className="home-timeline-interest">
                    <span className="calendar-day-dot" />{interest.count}
                  </span>}
                </button>
              </li>
            );
          })}
        </ul>
      )}
      <div className="home-discovery-readout">
        <p role="status">{isLoading ? "Cargando lanzamientos…" : activeQuery.isError ? "" : summary}</p>
        {calendars.length > 0 && (
          <>
            <div className="home-expectation-inline">
              <CalendarExpectationLegend weekly={featured.granularity === "month"} />
            </div>
            <details className="home-expectation-disclosure">
              <summary className="button">Expectación</summary>
              <div className="home-expectation-panel">
                <CalendarExpectationLegend weekly={featured.granularity === "month"} />
              </div>
            </details>
          </>
        )}
        {slice && <Link className="view-action" preventScrollReset to={pathFor(featured)}>Ver todo el periodo</Link>}
      </div>
      {isLoading ? <HomeReleaseLoading /> : activeQuery.isError ? (
        <HomeNotice
          heading="h2"
          actions={<button className="button" onClick={() => void activeQuery.refetch()} type="button">Reintentar</button>}
          correlationId={activeQuery.error.correlationId}
          kicker={activeQuery.error.code === "CATALOGUE_NOT_READY" ? "Datos en preparación" : "Error de carga"}
          title={activeQuery.error.code === "CATALOGUE_NOT_READY" ? "Los juegos todavía no están disponibles" : "Los lanzamientos se han tomado un respiro"}
          tone={activeQuery.error.code === "CATALOGUE_NOT_READY" ? "info" : "danger"}
        />
      ) : lead === undefined ? (
        <HomeNotice
          heading="h2"
          kicker="Lanzamientos"
          title={slice ? "Sin lanzamientos en estas fechas" : "Sin destacados para este periodo"}
          tone="empty"
          actions={filter.mode === "on" ? (
            <button className="button" onClick={() => interestsSwitch.set(false)} type="button">Ver todas las plataformas</button>
          ) : undefined}
        >
          <p>{!slice && model?.selection.status === "popularity-unavailable" ? "Aún faltan datos de interés para este periodo." : "Elige otra fecha o plataforma."}</p>
        </HomeNotice>
      ) : (
        <>
          <HomeLead game={withFeaturedArt(lead)} key={lead.gameId} />
          {others.length > 0 && (
            <ul aria-label={slice === undefined ? "Más destacados" : "Más lanzamientos"} className="home-more-list">
              {others.map((game) => <li className="min-w-0" key={game.gameId}><HomeHighlightCard item={withFeaturedArt(game)} /></li>)}
            </ul>
          )}
        </>
      )}
      <div className="home-discovery-links">
        <Link className="button home-discovery-link" to={explore}>Ver todos los lanzamientos <span aria-hidden="true" className="home-chevron" /></Link>
        <Link className="button home-discovery-link" to={calendar}><CalendarIcon />Ver calendario <span aria-hidden="true" className="home-chevron" /></Link>
      </div>
    </section>
  );
}
