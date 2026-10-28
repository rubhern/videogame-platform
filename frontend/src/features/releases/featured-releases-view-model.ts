import {
  formatCalendarDay,
  formatDayRange,
  formatReleaseDateShort,
} from "../../shared/catalogue/release-date";
import { toWordmark, type TitleWordmark } from "../../shared/catalogue/title-wordmark";
import { releaseStatuses } from "../../shared/catalogue/release-labels";
import type { FeaturedReleasesResponse } from "./featured-releases-api";
import type { FeaturedGranularity } from "./featured-search";
import {
  toPeriodTimeline,
  toReleaseMark,
  type PeriodTimeline,
  type ReleaseMark,
} from "./period-timeline";
import { toReleaseListItem, type ReleaseListItem } from "./releases-view-model";

type FeaturedResponseItem = FeaturedReleasesResponse["items"][number];
type FeaturedRelease = FeaturedResponseItem["releases"][number];

/** The product-owned landscape image every featured frame can always fall back to. */
export const FEATURED_FALLBACK_URL = "/assets/featured/fallback.svg";

/**
 * One image a featured frame can show. A `fill` image is cropped to the frame; a `contain` image
 * is shown whole over its own blurred light and is never stretched or cropped.
 */
export type FeaturedArt = {
  kind: "artwork" | "screenshot" | "cover" | "fallback";
  presentation: "fill" | "contain";
  /** At the scale of the period's featured release. */
  url: string;
  /** At the scale of the other featured releases. */
  compactUrl: string;
  alt: string;
};

/** A transparent provider logo used only by a secondary screenshot-card overlay. */
export type FeaturedTitleLogo = { url: string; alt: string };

/**
 * A ranked game: its release card plus the image steps its frames try in order. For a card the
 * first is the contract's featured image; if a provider image fails to load, the provider cover
 * shown whole is tried next, and the product-owned fallback always ends the list.
 */
export type FeaturedItem = ReleaseListItem & {
  art: FeaturedArt[];
  logo: FeaturedTitleLogo | null;
  /** Where the first presented release falls on the represented period's temporal scale. */
  mark: ReleaseMark;
};

/**
 * Why the selection holds what it holds. A stale ranking is still the last valid local ranking,
 * so it stays usable and only says how current its attention evidence is.
 */
export type FeaturedSelectionState =
  | { status: "ranked"; stale: boolean; observedOn: string }
  | { status: "popularity-unavailable" }
  | { status: "no-qualifying-releases" };

/** The canonical title set as Gameómetro's own wordmark (`shared/catalogue/title-wordmark`). */
export type FeaturedWordmark = TitleWordmark;

/**
 * The period's featured release: a featured item plus what its hero sets in words and light. Its
 * `art` never presents a cover first: landscape media, then the designed fallback, and the
 * provider cover only as the last resort when nothing else loads.
 */
export type FeaturedLead = FeaturedItem & {
  /** Only exceptional lifecycle, date uncertainty and review notices. */
  facts: string[];
  wordmark: FeaturedWordmark;
  /** What the hero's light and title sheen sample: its landscape image, else its cover. */
  lightUrl: string;
};

/** An adjacent period the API offers: its contract value and how a sentence names it. */
export type FeaturedPeriodStep = { period: string; phrase: string };

export type FeaturedReleasesViewModel = {
  granularity: FeaturedGranularity;
  /** The represented period as the contract names it: `2026-W42` or `2026-10`. */
  period: string;
  /** `12–18 oct 2026` or `Octubre 2026`, as the period selector shows it. */
  periodLabel: string;
  /** `la semana del 12 al 18 de octubre de 2026` or `octubre de 2026`, as a sentence says it. */
  periodPhrase: string;
  /** Whether the API's evaluated date falls inside the represented period. */
  isCurrent: boolean;
  /** The represented period's first and last day, which Explorar lanzamientos opens on (#285). */
  window: { from: string; to: string };
  /** The adjacent periods inside the API's navigation bounds; null at a bound. */
  previous: FeaturedPeriodStep | null;
  next: FeaturedPeriodStep | null;
  /**
   * The represented period as the temporal G reads it, from the window and evaluated date of this
   * same response, so the progress always agrees with the selection shown; null only for a
   * malformed response.
   */
  timeline: PeriodTimeline | null;
  selection: FeaturedSelectionState;
  lead: FeaturedLead | null;
  others: FeaturedItem[];
};

const monthNames = [
  "enero",
  "febrero",
  "marzo",
  "abril",
  "mayo",
  "junio",
  "julio",
  "agosto",
  "septiembre",
  "octubre",
  "noviembre",
  "diciembre",
] as const;

function monthParts(month: string): { name: string; year: string } {
  const [year = "", number = ""] = month.split("-");
  return { name: monthNames[Number(number) - 1] ?? "", year: String(Number(year)) };
}

/** `Octubre 2026`. */
export function formatMonthLabel(month: string): string {
  const { name, year } = monthParts(month);
  return `${name.charAt(0).toUpperCase()}${name.slice(1)} ${year}`;
}

/** `octubre de 2026`. */
export function formatMonthPhrase(month: string): string {
  const { name, year } = monthParts(month);
  return `${name} de ${year}`;
}

/**
 * A week's days as a sentence names them, as compactly as the shared parts allow: `del 12 al 18 de
 * octubre de 2026`, `del 28 de septiembre al 4 de octubre de 2026` or `del 28 de diciembre de 2026
 * al 3 de enero de 2027`.
 */
export function formatWeekRange(from: string, to: string): string {
  const [startDay = "", startMonth = "", startYear = ""] = formatCalendarDay(from).split(" de ");
  const end = formatCalendarDay(to);
  const [, endMonth = "", endYear = ""] = end.split(" de ");
  const first =
    startYear !== endYear
      ? `${startDay} de ${startMonth} de ${startYear}`
      : startMonth !== endMonth
        ? `${startDay} de ${startMonth}`
        : startDay;
  return `del ${first} al ${end}`;
}

/** `la semana del 12 al 18 de octubre de 2026`. */
export function formatWeekPhrase(from: string, to: string): string {
  return `la semana ${formatWeekRange(from, to)}`;
}

const DAY_MS = 86_400_000;

/** A contract day shifted by whole days; the API's window is the only source of the dates. */
function shiftDay(value: string, days: number): string {
  const [year = 0, month = 1, day = 1] = value.split("-").map(Number);
  const date = new Date(0);
  date.setUTCFullYear(year, month - 1, day);
  const shifted = new Date(date.getTime() + days * DAY_MS);
  return [
    String(shifted.getUTCFullYear()).padStart(4, "0"),
    String(shifted.getUTCMonth() + 1).padStart(2, "0"),
    String(shifted.getUTCDate()).padStart(2, "0"),
  ].join("-");
}

/**
 * The Monday-to-Sunday days of an ISO 8601 week `YYYY-Www`: the week holding the year's 4 January
 * is its first. Only the URL-requested label uses it, until the response's own window arrives.
 */
export function isoWeekWindow(week: string): { from: string; to: string } | null {
  const match = /^(\d{4})-W(\d{2})$/.exec(week);
  if (match === null) return null;
  const year = Number(match[1]);
  const number = Number(match[2]);
  const fourth = new Date(0);
  fourth.setUTCFullYear(year, 0, 4);
  const firstMonday = fourth.getTime() - ((fourth.getUTCDay() + 6) % 7) * DAY_MS;
  const from = firstMonday + (number - 1) * 7 * DAY_MS;
  const iso = (time: number) => {
    const date = new Date(time);
    return [
      String(date.getUTCFullYear()).padStart(4, "0"),
      String(date.getUTCMonth() + 1).padStart(2, "0"),
      String(date.getUTCDate()).padStart(2, "0"),
    ].join("-");
  };
  // Week 53 exists only when the year's last ISO week reaches it; 28 December is always in it.
  const lastWeek = isoWeeksInYear(year);
  return number < 1 || number > lastWeek ? null : { from: iso(from), to: iso(from + 6 * DAY_MS) };
}

function isoWeeksInYear(year: number): number {
  const date = new Date(0);
  date.setUTCFullYear(year, 11, 28);
  const thursday = date.getTime() + (3 - ((date.getUTCDay() + 6) % 7)) * DAY_MS;
  const january = new Date(0);
  january.setUTCFullYear(new Date(thursday).getUTCFullYear(), 0, 1);
  return Math.floor((thursday - january.getTime()) / (7 * DAY_MS)) + 1;
}

/** The selector's label for a URL-requested period before its response arrives. */
export function formatRequestedPeriodLabel(
  granularity: FeaturedGranularity,
  period: string,
): string | null {
  if (granularity === "month") return formatMonthLabel(period);
  const window = isoWeekWindow(period);
  return window === null ? null : formatDayRange(window.from, window.to);
}

/** The live status's phrase for a URL-requested period before its response arrives. */
export function formatRequestedPeriodPhrase(
  granularity: FeaturedGranularity,
  period: string,
): string | null {
  if (granularity === "month") return formatMonthPhrase(period);
  const window = isoWeekWindow(period);
  return window === null ? null : formatWeekPhrase(window.from, window.to);
}

/**
 * How a sentence names an adjacent period. A neighbouring week is the represented window moved by
 * seven days, so its dates come from the API's own window, never from a browser-side week rule.
 */
function stepPhrase(
  granularity: FeaturedGranularity,
  period: string,
  window: { from: string; to: string },
  offset: -1 | 1,
): string {
  if (granularity === "month") return formatMonthPhrase(period);
  return formatWeekPhrase(shiftDay(window.from, offset * 7), shiftDay(window.to, offset * 7));
}

function toSelection(selection: FeaturedReleasesResponse["selection"]): FeaturedSelectionState {
  if (selection.status === "ranked") {
    const observed = selection.popularityObservedAt ?? "";
    return {
      status: "ranked",
      stale: selection.popularityFreshness === "stale",
      observedOn: formatReleaseDateShort({ precision: "day", value: observed.slice(0, 10) }),
    };
  }
  return selection.status === "popularity_unavailable"
    ? { status: "popularity-unavailable" }
    : { status: "no-qualifying-releases" };
}

function facts(release: FeaturedRelease | undefined): string[] {
  if (release === undefined) {
    return [];
  }
  const notices: string[] = [];
  if (release.status === "delayed" || release.status === "cancelled") {
    notices.push(releaseStatuses[release.status]);
  }
  if (release.releaseDate.precision === "unknown") notices.push("Fecha por confirmar");
  if (release.reviewStatus === "required") notices.push("Fecha pendiente de revisión");
  return notices;
}

function fallbackArt(title: string): FeaturedArt {
  return {
    kind: "fallback",
    presentation: "fill",
    url: FEATURED_FALLBACK_URL,
    compactUrl: FEATURED_FALLBACK_URL,
    alt: `Imagen destacada no disponible de ${title}`,
  };
}

/** What an art step list is built from: any ranked item carrying the featured-image contract. */
export type FeaturedArtSource = Pick<
  FeaturedResponseItem,
  "featuredImage" | "primaryCover" | "canonicalTitle"
>;

/**
 * A card's image steps: the contract's featured image, then the provider cover shown whole if a
 * provider image fails to load, and the product-owned fallback always last.
 */
export function featuredCardArt(item: FeaturedArtSource): FeaturedArt[] {
  const image = item.featuredImage;
  const first: FeaturedArt = {
    kind: image.kind,
    presentation: image.presentation,
    url: image.url,
    compactUrl: image.compactUrl,
    alt: image.alternativeText,
  };
  if (image.kind === "fallback") {
    return [first];
  }
  const cover = item.primaryCover;
  const coverStep: FeaturedArt[] =
    image.kind !== "cover" && cover.kind === "provider"
      ? [
          {
            kind: "cover",
            presentation: "contain",
            url: cover.url,
            compactUrl: cover.url,
            alt: cover.alternativeText,
          },
        ]
      : [];
  return [first, ...coverStep, fallbackArt(item.canonicalTitle)];
}

function toFeaturedItem(
  item: FeaturedResponseItem,
  timeline: PeriodTimeline | null,
): FeaturedItem {
  const first = item.releases[0];
  return {
    ...toReleaseListItem(item),
    art: featuredCardArt(item),
    logo:
      item.logo === undefined ? null : { url: item.logo.url, alt: item.logo.alternativeText },
    // The first presented release is the card's visible release row, so its date is the one the
    // scale marks.
    mark:
      first === undefined || timeline === null
        ? { kind: "unknown" }
        : toReleaseMark(first.releaseDate, timeline),
  };
}

export { toWordmark };

/**
 * The hero sets the title itself and never presents a cover as its image: its landscape media
 * first, then the designed fallback, which the cover may light, and the cover shown whole only
 * when not even that can load.
 */
/**
 * A lead's image steps: its landscape media, then the designed fallback, and the provider cover
 * shown whole only when not even that can load. `light` is what may light the page's stage.
 */
export function featuredLeadArt(item: FeaturedArtSource): { art: FeaturedArt[]; light: string } {
  const image = item.featuredImage;
  const landscape: FeaturedArt[] =
    image.kind === "artwork" || image.kind === "screenshot"
      ? [
          {
            kind: image.kind,
            presentation: image.presentation,
            url: image.url,
            compactUrl: image.compactUrl,
            alt: image.alternativeText,
          },
        ]
      : [];
  const cover = item.primaryCover;
  const lastResort: FeaturedArt[] =
    cover.kind === "provider"
      ? [
          {
            kind: "cover",
            presentation: "contain",
            url: cover.url,
            compactUrl: cover.url,
            alt: cover.alternativeText,
          },
        ]
      : [];
  return {
    art: [...landscape, fallbackArt(item.canonicalTitle), ...lastResort],
    light: landscape[0]?.compactUrl ?? lastResort[0]?.url ?? FEATURED_FALLBACK_URL,
  };
}

function toFeaturedLead(
  item: FeaturedResponseItem,
  timeline: PeriodTimeline | null,
): FeaturedLead {
  const { art, light } = featuredLeadArt(item);
  return {
    ...toFeaturedItem(item, timeline),
    art,
    facts: facts(item.releases[0]),
    wordmark: toWordmark(item.canonicalTitle),
    lightUrl: light,
  };
}

export function toFeaturedReleasesViewModel(
  response: FeaturedReleasesResponse,
): FeaturedReleasesViewModel {
  const [lead, ...others] = response.items;
  const { granularity, window, evaluatedOn } = response;
  const period = (granularity === "week" ? response.week : response.month) ?? "";
  const timeline = toPeriodTimeline(granularity, window, evaluatedOn);
  const step = (target: string | undefined, offset: -1 | 1): FeaturedPeriodStep | null =>
    target === undefined
      ? null
      : { period: target, phrase: stepPhrase(granularity, target, window, offset) };
  return {
    granularity,
    period,
    periodLabel:
      granularity === "week" ? formatDayRange(window.from, window.to) : formatMonthLabel(period),
    periodPhrase:
      granularity === "week"
        ? formatWeekPhrase(window.from, window.to)
        : formatMonthPhrase(period),
    isCurrent: window.from <= evaluatedOn && evaluatedOn <= window.to,
    window: { from: window.from, to: window.to },
    previous: step(response.navigation.previous, -1),
    next: step(response.navigation.next, 1),
    timeline,
    selection: toSelection(response.selection),
    lead: lead === undefined ? null : toFeaturedLead(lead, timeline),
    others: others.map((other) => toFeaturedItem(other, timeline)),
  };
}
