import { formatCalendarDay, formatCompactCalendarDay, formatReleaseDate } from "../../shared/catalogue/release-date";
import { regionLabel } from "../../shared/catalogue/region-label";
import type { ReleasePage } from "./releases-api";
import type { ReleaseView } from "./releases-search";

type ReleaseItem = ReleasePage["items"][number];
type Release = ReleaseItem["releases"][number];
type Cover = ReleaseItem["primaryCover"];

export type ReleaseCover =
  | {
      kind: "provider";
      url: string;
      alternativeText: string;
      attribution: { label: string; sourceUrl: string };
    }
  | {
      kind: "local-preview";
      url: string;
      alternativeText: string;
      attribution: { label: string; sourceUrl: string };
    }
  | { kind: "fallback"; url: string; alternativeText: string };

/**
 * Releases that share the same date/precision and region collapse into one display group so a
 * card shows platforms together (`PS5 · PC · Xbox · Mundial`) instead of repeating rows. A group
 * keeps every meaningful difference the popover must preserve: date, region and platforms.
 */
export type ReleaseContextGroup = {
  key: string;
  date: string;
  region: string;
  platforms: string[];
  isStale: boolean;
  review: boolean;
  releaseCount: number;
};

export type ReleaseListItem = {
  gameId: string;
  slug: string;
  title: string;
  // Groups ordered by the UC-001 release order; the first group is the visible row and the
  // rest move behind the "+ N lanzamientos más" control without growing the card.
  releaseGroups: ReleaseContextGroup[];
  // Number of hidden releases (not hidden groups), so the control reports releases the visitor
  // cannot see yet.
  hiddenReleaseCount: number;
  isStale: boolean;
  cover: ReleaseCover;
};

export type ReleaseFilterOption = { id: string; name: string };

export type ReleasesViewModel = {
  view: ReleaseView;
  title: string;
  windowDescription: string;
  compactWindowDescription: string;
  platforms: ReleaseFilterOption[];
  regions: ReleaseFilterOption[];
  activePlatformIds: string[];
  activeRegionIds: string[];
  items: ReleaseListItem[];
  staleItemCount: number;
  page: { number: number; size: number; totalItems: number; totalPages: number };
};

const viewTitles: Record<ReleaseView, string> = {
  recent: "Lanzamientos recientes",
  upcoming: "Próximos lanzamientos",
};

function readLocalCoverPreviews(): Record<string, string> {
  if (!import.meta.env.DEV || import.meta.env.MODE === "test") {
    return {};
  }

  try {
    const value: unknown = JSON.parse(import.meta.env.VITE_LOCAL_COVER_PREVIEWS ?? "{}");
    if (value === null || typeof value !== "object" || Array.isArray(value)) {
      return {};
    }

    return Object.fromEntries(
      Object.entries(value).filter(
        (entry): entry is [string, string] =>
          typeof entry[1] === "string" && /^[a-z0-9][a-z0-9.-]*\.png$/i.test(entry[1]),
      ),
    );
  } catch {
    return {};
  }
}

const localCoverPreviews = readLocalCoverPreviews();

function localCoverPreview(slug: string, title: string): ReleaseCover | null {
  const filename = localCoverPreviews[slug];
  if (filename === undefined) {
    return null;
  }

  const url = `/local-preview/covers/${filename}`;
  return {
    kind: "local-preview",
    url,
    alternativeText: `Vista previa de la carátula de ${title}`,
    attribution: { label: "Vista previa local", sourceUrl: url },
  };
}

export function releaseViewTitle(view: ReleaseView): string {
  return viewTitles[view];
}

/**
 * The reviewed contract distinguishes both cover variants by their attribution: a
 * provider cover always carries one and the product-owned fallback never does. The
 * generated transport type narrows the null-valued branch away, so the variant is
 * detected the same way the release-date union is.
 */
function toCover(cover: Cover): ReleaseCover {
  const attribution = "attribution" in cover ? cover.attribution : null;

  if (attribution === null) {
    return {
      kind: "fallback",
      url: cover.url,
      alternativeText: cover.alternativeText,
    };
  }

  return {
    kind: "provider",
    url: cover.url,
    alternativeText: cover.alternativeText,
    attribution: { label: attribution.label, sourceUrl: attribution.sourceUrl },
  };
}

/**
 * Collapses the ordered releases into date/region groups, preserving the UC-001 order: the first
 * release stays first, and platforms sharing that release's date and region join its group.
 */
function toReleaseContextGroups(releases: readonly Release[]): ReleaseContextGroup[] {
  const groups: ReleaseContextGroup[] = [];
  const byKey = new Map<string, ReleaseContextGroup>();
  for (const release of releases) {
    // The formatted date encodes precision and value, so it is a stable grouping key together
    // with the region without reaching into the closed date union.
    const date = formatReleaseDate(release.releaseDate);
    const key = `${date}|${release.region.regionId}`;
    let group = byKey.get(key);
    if (group === undefined) {
      group = {
        key,
        date,
        region: regionLabel(release.region.name),
        platforms: [],
        isStale: false,
        review: false,
        releaseCount: 0,
      };
      byKey.set(key, group);
      groups.push(group);
    }
    group.platforms.push(release.platform.name);
    group.releaseCount += 1;
    group.isStale = group.isStale || release.freshnessStatus === "stale";
    group.review = group.review || release.reviewStatus === "required";
  }
  return groups;
}

export function toReleaseListItems(page: ReleasePage): ReleaseListItem[] {
  return page.items.map((item) => {
    const releaseGroups = toReleaseContextGroups(item.releases);
    const hiddenReleaseCount = releaseGroups
      .slice(1)
      .reduce((total, group) => total + group.releaseCount, 0);
    return {
      gameId: item.gameId,
      slug: item.slug,
      title: item.canonicalTitle,
      releaseGroups,
      hiddenReleaseCount,
      isStale: releaseGroups.some((group) => group.isStale),
      // Owner-provided previews are an opt-in Vite development overlay. The API cover
      // remains authoritative in tests and production builds.
      cover: localCoverPreview(item.slug, item.canonicalTitle) ?? toCover(item.primaryCover),
    };
  });
}

export function toReleasesViewModel(page: ReleasePage): ReleasesViewModel {
  const items = toReleaseListItems(page);

  return {
    view: page.view,
    title: viewTitles[page.view],
    windowDescription: `Del ${formatCalendarDay(page.window.from)} al ${formatCalendarDay(
      page.window.to,
    )}`,
    compactWindowDescription: `Del ${formatCompactCalendarDay(page.window.from)} al ${formatCompactCalendarDay(page.window.to)}`,
    platforms: page.availableFilters.platforms.map((platform) => ({
      id: platform.platformId,
      name: platform.name,
    })),
    regions: page.availableFilters.regions.map((region) => ({
      id: region.regionId,
      name: regionLabel(region.name),
    })),
    activePlatformIds: page.activeFilters.platformIds,
    activeRegionIds: page.activeFilters.regionIds,
    items,
    staleItemCount: items.filter((item) => item.isStale).length,
    page: page.page,
  };
}
