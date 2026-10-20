import { Navigate, useSearchParams } from "react-router-dom";

import { legacyReleasesPath } from "../features/releases/releases-search";
import { FeaturedReleasesPage } from "./featured-releases-page";

/**
 * The landing route is TOPómetro (#151, #341). A link from before #285 that named a recent or
 * upcoming list lands on the release explorer instead, keeping the filters it carried.
 */
export function DiscoveryPage() {
  const [searchParams] = useSearchParams();
  const legacy = legacyReleasesPath(searchParams);
  return legacy === null ? <FeaturedReleasesPage /> : <Navigate replace to={legacy} />;
}
