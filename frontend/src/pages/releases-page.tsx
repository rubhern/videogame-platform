import { Navigate, useLocation, useSearchParams } from "react-router-dom";

import { readDayFrame } from "../features/releases/day-bar";

import { toFailureState } from "../features/releases/releases-failure";
import { readReleasesSearch, releasesSearchPath } from "../features/releases/releases-search";
import {
  ReleasesShell,
  type ReleasesShellState,
} from "../features/releases/releases-shell";
import { useReleasesQuery } from "../features/releases/use-releases-query";
import { resolvePlatformFilter } from "../features/platform-preferences/interests";
import { useInterests } from "../features/platform-preferences/use-platform-preferences";
import { useInterestsSwitch } from "../features/platform-preferences/interests-switch";
import { explorerRequestSearch } from "../features/platform-preferences/discovery-requests";
import { wishlistSessionScope } from "../features/wishlist/wishlist-api";
import { WishlistActions } from "../features/wishlist/wishlist-controls";
import { useSession } from "../features/session/use-session";
import { useDocumentTitle } from "../shared/browser/use-document-title";

export function ReleasesPage() {
  useDocumentTitle("Lanzamientos · Gameómetro");
  const [searchParams] = useSearchParams();
  const location = useLocation();
  const search = readReleasesSearch(searchParams);
  // Preferred platforms resolve in the browser into the explicit filter the public read carries,
  // and the read waits for them, so a first view never shows every platform and then narrows.
  const interests = useInterests();
  const { enabled } = useInterestsSwitch();
  const platformFilter = resolvePlatformFilter(search.platformIds, enabled, interests);
  const session = useSession({ refetchOnMount: false });
  const privateRead = session.data?.authenticated === true && !session.isError;
  const query = useReleasesQuery(
    explorerRequestSearch(search, enabled, interests),
    interests.status !== "pending" && (!search.wishedOnly || privateRead),
    wishlistSessionScope(session.data),
  );

  if (search.wishedOnly && (session.data?.authenticated === false ||
      (query.isError && query.error.status === 401))) {
    return <Navigate replace to={releasesSearchPath(search, { wishedOnly: false, page: 1 })} />;
  }

  let state: ReleasesShellState;
  if (search.wishedOnly && session.isError) {
    state = { status: "error", message: "No se pudo comprobar tu sesión.", correlationId: null };
  } else if (query.isPending) {
    state = { status: "loading" };
  } else if (query.isError) {
    state = toFailureState(query.error);
  } else {
    state = {
      status: "ready",
      model: query.data,
      isRefreshing: query.isFetching,
      isPlaceholderData: query.isPlaceholderData,
    };
  }

  const gameIds = state.status === "ready" && !state.isPlaceholderData
    ? state.model.items.map((item) => item.gameId) : [];
  return (
    <WishlistActions batches={[gameIds]}>
      <ReleasesShell
        canFilterWishlist={privateRead}
        interests={interests}
        keptFrame={readDayFrame(location.state)}
        platformFilter={platformFilter}
        onRetry={() => {
          if (search.wishedOnly && session.isError) void session.refetch();
          else void query.refetch();
        }}
        search={search}
        state={state}
      />
    </WishlistActions>
  );
}
