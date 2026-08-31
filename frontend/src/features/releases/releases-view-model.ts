import { formatCalendarDay, formatReleaseDate } from "./release-date";
import type { ReleasePage } from "./releases-api";
import type { ReleaseView } from "./releases-search";

type ReleaseItem = ReleasePage["items"][number];
type Cover = ReleaseItem["primaryCover"];
type ReleaseStatus = ReleaseItem["release"]["status"];

export type ReleaseCover =
  | {
      kind: "provider";
      url: string;
      alternativeText: string;
      attribution: { label: string; sourceUrl: string };
    }
  | { kind: "fallback"; url: string; alternativeText: string };

export type ReleaseListItem = {
  releaseId: string;
  gameId: string;
  slug: string;
  title: string;
  date: string;
  platform: string;
  region: string;
  status: string;
  provenance: string;
  isStale: boolean;
  freshness: string;
  review: string | null;
  cover: ReleaseCover;
};

export type ReleaseFilterOption = { id: string; name: string };

export type ReleasesViewModel = {
  view: ReleaseView;
  title: string;
  windowDescription: string;
  evaluatedOnDescription: string;
  platforms: ReleaseFilterOption[];
  regions: ReleaseFilterOption[];
  activePlatformId: string | null;
  activeRegionId: string | null;
  items: ReleaseListItem[];
  staleItemCount: number;
  page: { number: number; size: number; totalItems: number; totalPages: number };
};

const statusLabels: Record<ReleaseStatus, string> = {
  announced: "Anunciado",
  scheduled: "Programado",
  released: "Publicado",
  delayed: "Retrasado",
  cancelled: "Cancelado",
  unknown: "Estado sin confirmar",
};

const viewTitles: Record<ReleaseView, string> = {
  recent: "Lanzamientos recientes",
  upcoming: "Próximos lanzamientos",
};

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

export function toReleaseListItems(page: ReleasePage): ReleaseListItem[] {
  return page.items.map((item) => ({
    releaseId: item.release.releaseId,
    gameId: item.gameId,
    slug: item.slug,
    title: item.canonicalTitle,
    date: formatReleaseDate(item.release.releaseDate),
    platform: item.release.platform.name,
    region: item.release.region.name,
    status: statusLabels[item.release.status],
    provenance: item.release.provenance.sourceName,
    isStale: item.release.freshnessStatus === "stale",
    freshness:
      item.release.freshnessStatus === "stale"
        ? "Datos locales desactualizados"
        : "Datos locales actualizados",
    review:
      item.release.reviewStatus === "required" ? "Información pendiente de revisión" : null,
    cover: toCover(item.primaryCover),
  }));
}

export function toReleasesViewModel(page: ReleasePage): ReleasesViewModel {
  const items = toReleaseListItems(page);

  return {
    view: page.view,
    title: viewTitles[page.view],
    windowDescription: `Del ${formatCalendarDay(page.window.from)} al ${formatCalendarDay(
      page.window.to,
    )}`,
    evaluatedOnDescription: `Ventana evaluada el ${formatCalendarDay(page.evaluatedOn)}`,
    platforms: page.availableFilters.platforms.map((platform) => ({
      id: platform.platformId,
      name: platform.name,
    })),
    regions: page.availableFilters.regions.map((region) => ({
      id: region.regionId,
      name: region.name,
    })),
    activePlatformId: page.activeFilters.platformId,
    activeRegionId: page.activeFilters.regionId,
    items,
    staleItemCount: items.filter((item) => item.isStale).length,
    page: page.page,
  };
}
