import { formatReleaseDateShort } from "../../shared/catalogue/release-date";
import { releaseStages, releaseStatuses } from "../../shared/catalogue/release-labels";
import type { FeaturedReleasesResponse } from "./featured-releases-api";
import { shiftMonth } from "./featured-search";
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
  /** At the scale of the month's featured release. */
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
};

/**
 * Why the selection holds what it holds. A stale ranking is still the last valid local ranking,
 * so it stays usable and only says how current its attention evidence is.
 */
export type FeaturedSelectionState =
  | { status: "ranked"; stale: boolean; observedOn: string }
  | { status: "popularity-unavailable" }
  | { status: "no-qualifying-releases" };

/**
 * The canonical title set as a product-owned wordmark: the name, then the subtitle an explicit
 * delimiter introduces. The lockup drops the delimiter visually; assistive technology and the
 * heading's text still read the canonical title unchanged.
 */
export type FeaturedWordmark = {
  main: string;
  /** `:` or a spaced dash, as the canonical title writes it; empty without a subtitle. */
  delimiter: string;
  sub: string | null;
};

/**
 * The month's featured release: a featured item plus what its hero sets in words and light. Its
 * `art` never presents a cover first: landscape media, then the designed fallback, and the
 * provider cover only as the last resort when nothing else loads.
 */
export type FeaturedLead = FeaturedItem & {
  /** Lifecycle and, when known, stage of the presented release, in the product vocabulary. */
  facts: string[];
  wordmark: FeaturedWordmark;
  /** What the hero's light and title sheen sample: its landscape image, else its cover. */
  lightUrl: string;
};

export type FeaturedReleasesViewModel = {
  month: string;
  /** `Octubre 2026`, as the month selector shows it. */
  monthLabel: string;
  /** `octubre de 2026`, as a sentence says it. */
  monthPhrase: string;
  /** The evaluated month in `Europe/Madrid`, which the landing route represents. */
  currentMonth: string;
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
  return release.stage === "unknown"
    ? [releaseStatuses[release.status]]
    : [releaseStatuses[release.status], releaseStages[release.stage]];
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

function art(item: FeaturedResponseItem): FeaturedArt[] {
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

function toFeaturedItem(item: FeaturedResponseItem): FeaturedItem {
  return {
    ...toReleaseListItem(item),
    art: art(item),
    logo:
      item.logo === undefined ? null : { url: item.logo.url, alt: item.logo.alternativeText },
  };
}

/** A subtitle follows the first colon or spaced dash, as in `Onimusha: Way of the Sword`. */
const SUBTITLE = /^(.+?)(:| [-–—]) (.+)$/u;

export function toWordmark(title: string): FeaturedWordmark {
  const match = SUBTITLE.exec(title);
  const [, main = "", delimiter = "", sub = ""] = match ?? [];
  return main.trim() === "" || sub.trim() === ""
    ? { main: title, delimiter: "", sub: null }
    : { main, delimiter, sub };
}

/**
 * The hero sets the title itself and never presents a cover as its image: its landscape media
 * first, then the designed fallback, which the cover may light, and the cover shown whole only
 * when not even that can load.
 */
function toFeaturedLead(item: FeaturedResponseItem): FeaturedLead {
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
    ...toFeaturedItem(item),
    art: [...landscape, fallbackArt(item.canonicalTitle), ...lastResort],
    facts: facts(item.releases[0]),
    wordmark: toWordmark(item.canonicalTitle),
    lightUrl: landscape[0]?.compactUrl ?? lastResort[0]?.url ?? FEATURED_FALLBACK_URL,
  };
}

export function toFeaturedReleasesViewModel(
  response: FeaturedReleasesResponse,
): FeaturedReleasesViewModel {
  const [lead, ...others] = response.items;
  return {
    month: response.month,
    monthLabel: formatMonthLabel(response.month),
    monthPhrase: formatMonthPhrase(response.month),
    currentMonth: response.evaluatedOn.slice(0, 7),
    selection: toSelection(response.selection),
    lead: lead === undefined ? null : toFeaturedLead(lead),
    others: others.map(toFeaturedItem),
  };
}

/**
 * The adjacent months within the calendar year of the evaluated month, the only year featured
 * discovery presents: January has no previous month and December no next one. The current
 * month is the landing route itself.
 */
export function adjacentMonths(
  month: string,
  currentMonth: string,
): { previous: string | null; next: string | null } {
  const year = `${currentMonth.slice(0, 4)}-`;
  const withinYear = (target: string | null) => (target?.startsWith(year) ? target : null);
  return { previous: withinYear(shiftMonth(month, -1)), next: withinYear(shiftMonth(month, 1)) };
}
