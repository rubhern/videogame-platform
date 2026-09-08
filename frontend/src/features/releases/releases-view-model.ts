import { formatCalendarDay, formatReleaseDate } from "../../shared/catalogue/release-date";
import { regionLabel } from "../../shared/catalogue/region-label";
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
  | {
      kind: "local-preview";
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

export function toReleaseListItems(page: ReleasePage): ReleaseListItem[] {
  return page.items.map((item) => ({
    releaseId: item.release.releaseId,
    gameId: item.gameId,
    slug: item.slug,
    title: item.canonicalTitle,
    date: formatReleaseDate(item.release.releaseDate),
    platform: item.release.platform.name,
    region: regionLabel(item.release.region.name),
    status: statusLabels[item.release.status],
    provenance: item.release.provenance.sourceName,
    isStale: item.release.freshnessStatus === "stale",
    freshness:
      item.release.freshnessStatus === "stale"
        ? "Datos locales desactualizados"
        : "Datos locales actualizados",
    review:
      item.release.reviewStatus === "required" ? "Información pendiente de revisión" : null,
    // Owner-provided previews are an opt-in Vite development overlay. The API cover
    // remains authoritative in tests and production builds.
    cover: localCoverPreview(item.slug, item.canonicalTitle) ?? toCover(item.primaryCover),
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
      name: regionLabel(region.name),
    })),
    activePlatformId: page.activeFilters.platformId,
    activeRegionId: page.activeFilters.regionId,
    items,
    staleItemCount: items.filter((item) => item.isStale).length,
    page: page.page,
  };
}
