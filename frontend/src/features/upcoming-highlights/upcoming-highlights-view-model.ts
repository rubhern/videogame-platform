import {
  formatCalendarDay,
  formatDayRange,
  formatReleaseDate,
  formatReleaseDateShort,
  shortMonthName,
} from "../../shared/catalogue/release-date";
import {
  FEATURED_FALLBACK_URL,
  featuredCardArt,
  featuredLeadArt,
  type FeaturedArt,
} from "../releases/featured-releases-view-model";
import type { UpcomingHighlightsResponse } from "./upcoming-highlights-api";

type ResponseItem = UpcomingHighlightsResponse["items"][number];
type ReleaseDate = ResponseItem["platformRelease"]["releaseDate"];

/** When a ranked game arrives on the platform, at the precision the API states and no finer. */
export type UpcomingWhen = {
  precision: ReleaseDate["precision"];
  /** `12 feb 2027`, `feb 2027` or `T1 2027`. */
  short: string;
  /** `12 de febrero de 2027`, `febrero de 2027` or `1.er trimestre de 2027`. */
  full: string;
  /**
   * How far away it is, from the API's evaluation date: days for an exact day (`Faltan 108 días`,
   * `Mañana`), or `Este mes` / `Este trimestre` while the period it names is the current one.
   * Null when a coarser date allows no honest distance.
   */
  countdown: string | null;
};

export type UpcomingHighlight = {
  position: number;
  gameId: string;
  title: string;
  path: string;
  /** The image steps its frame tries, ending at a product-owned fallback. */
  art: FeaturedArt[];
  when: UpcomingWhen;
  platformId: string;
  platformName: string;
  region: string;
  genres: string[];
};

/** One calendar month of the horizon as a share of its width, clipped to the horizon. */
export type HorizonMonth = {
  key: string;
  label: string;
  /** The year, on the first month labelled and on every January. */
  year: string | null;
  /** False for a sliver of a month too narrow to hold its name; its division still shows. */
  labelled: boolean;
  start: number;
  end: number;
};

/**
 * Where a ranked game lands on the horizon: a point for an exact day, a span over the part of its
 * month or quarter inside the horizon. No coarser date is ever drawn as a day.
 */
export type HorizonMark =
  | { position: number; kind: "day"; at: number }
  | { position: number; kind: "span"; start: number; end: number };

export type UpcomingSelection = "ranked" | "popularity-unavailable" | "no-qualifying-releases";

export type UpcomingHighlightsViewModel = {
  evaluatedOn: string;
  window: { from: string; to: string };
  /** `27 oct 2026 – 26 abr 2027`. */
  windowLabel: string;
  /** `Del 27 de octubre de 2026 al 26 de abril de 2027`. */
  windowPhrase: string;
  selection: UpcomingSelection;
  lead: UpcomingHighlight | null;
  others: UpcomingHighlight[];
  /** What lights the page's stage: the lead's own media and the next covers, never a fallback. */
  lights: string[];
  horizon: { months: HorizonMonth[]; marks: HorizonMark[] } | null;
};

const DAY_MS = 86_400_000;

/** About nine days of the horizon: less cannot hold a month's short name at 320px. */
const MIN_LABELLED_SHARE = 0.05;

/** A calendar day the API resolved, as a UTC midnight count; null when malformed. */
function dayNumber(value: string): number | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (match === null) return null;
  const time = Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
  return Number.isNaN(time) ? null : time / DAY_MS;
}

function isoDay(day: number): string {
  return new Date(day * DAY_MS).toISOString().slice(0, 10);
}

/** The first and the last day of a month or quarter value, or null when malformed. */
function periodDays(releaseDate: ReleaseDate): { first: number; last: number } | null {
  const value = "value" in releaseDate ? releaseDate.value : null;
  if (value === null) return null;
  if (releaseDate.precision === "day") {
    const day = dayNumber(value);
    return day === null ? null : { first: day, last: day };
  }
  let year: number;
  let firstMonth: number;
  let months: number;
  if (releaseDate.precision === "month") {
    const match = /^(\d{4})-(\d{2})$/.exec(value);
    if (match === null) return null;
    year = Number(match[1]);
    firstMonth = Number(match[2]) - 1;
    months = 1;
  } else if (releaseDate.precision === "quarter") {
    const match = /^(\d{4})-Q([1-4])$/.exec(value);
    if (match === null) return null;
    year = Number(match[1]);
    firstMonth = (Number(match[2]) - 1) * 3;
    months = 3;
  } else {
    return null;
  }
  return {
    first: Date.UTC(year, firstMonth, 1) / DAY_MS,
    last: Date.UTC(year, firstMonth + months, 0) / DAY_MS,
  };
}

function countdown(releaseDate: ReleaseDate, evaluatedOn: number | null): string | null {
  const period = periodDays(releaseDate);
  if (period === null || evaluatedOn === null) return null;
  if (releaseDate.precision === "day") {
    const days = period.first - evaluatedOn;
    if (days < 1) return null;
    return days === 1 ? "Mañana" : `Faltan ${new Intl.NumberFormat("es-ES").format(days)} días`;
  }
  // A coarser date only says it is near when today already falls inside the period it names.
  if (period.first > evaluatedOn || period.last < evaluatedOn) return null;
  return releaseDate.precision === "month" ? "Este mes" : "Este trimestre";
}

function toHighlight(
  item: ResponseItem,
  evaluatedOn: number | null,
  art: FeaturedArt[],
): UpcomingHighlight {
  const release = item.platformRelease;
  return {
    position: item.position,
    gameId: item.gameId,
    title: item.canonicalTitle,
    path: `/games/${item.gameId}/${item.slug}`,
    art,
    when: {
      precision: release.releaseDate.precision,
      short: formatReleaseDateShort(release.releaseDate),
      full: formatReleaseDate(release.releaseDate),
      countdown: countdown(release.releaseDate, evaluatedOn),
    },
    platformId: release.platform.platformId,
    platformName: release.platform.name,
    region: release.region.name,
    genres: [...item.genres].map((genre) => genre.name),
  };
}

/**
 * The horizon as calendar months laid over its days, and each ranked game's arrival on it. The
 * shares come from the API's own window, so a page left open never draws a day the API did not
 * evaluate.
 */
function toHorizon(
  from: string,
  to: string,
  items: readonly ResponseItem[],
): UpcomingHighlightsViewModel["horizon"] {
  const first = dayNumber(from);
  const last = dayNumber(to);
  if (first === null || last === null || last < first) return null;
  const span = last - first + 1;
  const share = (day: number) => Math.min(Math.max((day - first) / span, 0), 1);

  const months: HorizonMonth[] = [];
  let cursor = first;
  while (cursor <= last) {
    const date = new Date(cursor * DAY_MS);
    const year = date.getUTCFullYear();
    const month = date.getUTCMonth();
    const next = Date.UTC(year, month + 1, 1) / DAY_MS;
    const key = isoDay(cursor).slice(0, 7);
    const start = share(cursor);
    const end = share(Math.min(next, last + 1));
    const labelled = end - start >= MIN_LABELLED_SHARE;
    months.push({
      key,
      label: shortMonthName(key.slice(5)) ?? key,
      year: labelled && (!months.some((shown) => shown.labelled) || month === 0) ? String(year) : null,
      labelled,
      start,
      end,
    });
    cursor = next;
  }

  const marks = items.flatMap((item): HorizonMark[] => {
    const period = periodDays(item.platformRelease.releaseDate);
    if (period === null) return [];
    if (item.platformRelease.releaseDate.precision === "day") {
      return [{ position: item.position, kind: "day", at: share(period.first + 0.5) }];
    }
    return [
      {
        position: item.position,
        kind: "span",
        start: share(Math.max(period.first, first)),
        end: share(Math.min(period.last, last) + 1),
      },
    ];
  });
  return { months, marks };
}

function toSelection(status: UpcomingHighlightsResponse["selection"]["status"]): UpcomingSelection {
  switch (status) {
    case "ranked":
      return "ranked";
    case "popularity_unavailable":
      return "popularity-unavailable";
    case "no_qualifying_releases":
      return "no-qualifying-releases";
  }
}

export function toUpcomingHighlightsViewModel(
  response: UpcomingHighlightsResponse,
): UpcomingHighlightsViewModel {
  const evaluatedOn = dayNumber(response.evaluatedOn);
  const [leadItem, ...otherItems] = response.items;
  const leadArt = leadItem === undefined ? null : featuredLeadArt(leadItem);
  const lead =
    leadItem === undefined || leadArt === null
      ? null
      : toHighlight(leadItem, evaluatedOn, leadArt.art);
  const others = otherItems.map((item) => toHighlight(item, evaluatedOn, featuredCardArt(item)));
  const coverLights = otherItems.flatMap((item) =>
    item.primaryCover.kind === "provider" ? [item.primaryCover.url] : [],
  );
  // The designed fallback never lights a stage; a lead without landscape media may be lit by its
  // own cover, as Destacados's hero is.
  const leadLight =
    leadArt === null || leadArt.light === FEATURED_FALLBACK_URL ? [] : [leadArt.light];

  return {
    evaluatedOn: response.evaluatedOn,
    window: response.window,
    windowLabel: formatDayRange(response.window.from, response.window.to),
    windowPhrase: `Del ${formatCalendarDay(response.window.from)} al ${formatCalendarDay(response.window.to)}`,
    selection: toSelection(response.selection.status),
    lead,
    others,
    lights: [...leadLight, ...coverLights].slice(0, 3),
    horizon: toHorizon(response.window.from, response.window.to, response.items),
  };
}
