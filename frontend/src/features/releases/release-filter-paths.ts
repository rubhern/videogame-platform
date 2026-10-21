import type { PlatformFilter } from "../platform-preferences/interests";

/** Whether a platform or region restriction applies, whoever chose it (#295). */
export function hasEffectiveFilters(
  search: { readonly regionIds: readonly string[] },
  platformFilter: PlatformFilter,
): boolean {
  return platformFilter.platformIds.length > 0 || search.regionIds.length > 0;
}
