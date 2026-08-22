import { formatReleaseDate } from "./release-date";
import type { ReleasePage } from "./releases-api";

export type ReleaseListItem = {
  gameId: string;
  slug: string;
  title: string;
  date: string;
  platform: string;
  region: string;
  provenance: string;
  freshness: string;
  review: string | null;
};

export function toReleaseListItems(page: ReleasePage): ReleaseListItem[] {
  return page.items.map((item) => ({
    gameId: item.gameId,
    slug: item.slug,
    title: item.canonicalTitle,
    date: formatReleaseDate(item.release.releaseDate),
    platform: item.release.platform.name,
    region: item.release.region.name,
    provenance: item.release.provenance.sourceName,
    freshness:
      item.release.freshnessStatus === "stale"
        ? "Datos locales desactualizados"
        : "Datos locales actualizados",
    review:
      item.release.reviewStatus === "required" ? "Información pendiente de revisión" : null,
  }));
}
