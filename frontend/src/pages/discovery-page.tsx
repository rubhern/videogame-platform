import { Navigate, useLocation } from "react-router-dom";

import { FEATURED_PATH } from "../features/releases/featured-search";
import { legacyReleasesPath } from "../features/releases/releases-search";

/** Compatibility entry until #382 supplies the home. Never reuse a featured link for a ranking. */
export function DiscoveryPage() {
  const location = useLocation();
  const legacy = legacyReleasesPath(new URLSearchParams(location.search));
  return <Navigate replace to={legacy ?? `${FEATURED_PATH}${location.search}${location.hash}`} state={location.state} />;
}
