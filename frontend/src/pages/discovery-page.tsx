import { useSearchParams } from "react-router-dom";

import { readDiscoveryView } from "../features/releases/releases-search";
import { FeaturedReleasesPage } from "./featured-releases-page";
import { ReleasesPage } from "./releases-page";

/** The landing route: Destacados by default, or the release list its `view` names (#151). */
export function DiscoveryPage() {
  const [searchParams] = useSearchParams();
  return readDiscoveryView(searchParams) === "featured" ? (
    <FeaturedReleasesPage />
  ) : (
    <ReleasesPage />
  );
}
