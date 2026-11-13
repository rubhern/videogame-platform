import type { ReleasesSearch } from "../releases/releases-search";
import { resolvePlatformFilter, type Interests } from "./interests";

/** The explorer's public request: the resolved platforms are explicit, never a session input. */
export function explorerRequestSearch(
  search: ReleasesSearch,
  interestsEnabled: boolean,
  interests: Interests,
): ReleasesSearch {
  const filter = resolvePlatformFilter(search.platformIds, interestsEnabled, interests);
  return { ...search, platformIds: [...filter.platformIds] };
}
