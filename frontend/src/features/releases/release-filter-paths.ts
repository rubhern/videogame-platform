import type { PlatformFilter } from "../platform-preferences/interests";
import type { ReleasesSearch } from "./releases-search";

/** Whether a platform or region restriction applies, whoever chose it (#295). */
export function hasEffectiveFilters(search: ReleasesSearch, platformFilter: PlatformFilter): boolean {
  return platformFilter.platformIds.length > 0 || search.regionIds.length > 0;
}
