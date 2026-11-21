import type { components, operations } from "../../shared/api/generated/schema";
import type { SessionState } from "../session/session-api";
import { productApiClient } from "../../shared/api/product-api-client";

export const WISHLIST_KEY = ["me", "wishlist"] as const;
export const WISHLIST_STATUS_KEY = [...WISHLIST_KEY, "status"] as const;
export const WISHLIST_PAGE_SIZE = 10;

const sessionScopes = new WeakMap<SessionState, number>();
let nextScope = 0;

/** Give each authenticated session cache its own in-memory key without including its CSRF proof. */
export function wishlistSessionScope(session: SessionState | undefined): number | undefined {
  if (session?.authenticated !== true) return undefined;
  const known = sessionScopes.get(session);
  if (known !== undefined) return known;
  const scope = ++nextScope;
  sessionScopes.set(session, scope);
  return scope;
}

type WishlistQuery = NonNullable<operations["listMyWishlist"]["parameters"]["query"]>;
type Problem = components["schemas"]["Problem"];

export class WishlistError extends Error {
  constructor(readonly status: number, readonly correlationId: string | null) {
    super("The wishlist request failed.");
  }
}

function requestFailure(response: Response, error: Problem | undefined) {
  return new WishlistError(
    response.status, error?.correlationId ?? response.headers.get("X-Correlation-ID"),
  );
}

export async function listWishlist(query: WishlistQuery, signal: AbortSignal) {
  const { data, error, response } = await productApiClient.GET("/me/wishlist", {
    params: { query }, cache: "no-store", signal,
  });
  if (data !== undefined) return data;
  throw requestFailure(response, error);
}

export async function readWishlistStatus(gameIds: string[], signal: AbortSignal) {
  const { data, error, response } = await productApiClient.GET("/me/wishlist/status", {
    params: { query: { gameIds } }, cache: "no-store", signal,
  });
  if (data !== undefined) return data.gameIds;
  throw requestFailure(response, error);
}

export type WishlistCommand = { gameId: string; csrfToken: string; saved: boolean };

export async function changeWish({ gameId, csrfToken, saved }: WishlistCommand) {
  const params = { path: { gameId }, header: { "X-CSRF-Token": csrfToken } };
  const result = saved
    ? await productApiClient.PUT("/me/wishlist/games/{gameId}", { params, cache: "no-store" })
    : await productApiClient.DELETE("/me/wishlist/games/{gameId}", { params, cache: "no-store" });
  if (result.response.status === 204) return;
  throw requestFailure(result.response, result.error);
}


export type WishlistMove = components["schemas"]["WishlistMove"];

export async function moveWish({
  gameId, csrfToken, move,
}: { gameId: string; csrfToken: string; move: WishlistMove }) {
  const result = await productApiClient.PUT("/me/wishlist/games/{gameId}/position", {
    params: { path: { gameId }, header: { "X-CSRF-Token": csrfToken } },
    body: move,
    cache: "no-store",
  });
  if (result.response.status !== 204) throw requestFailure(result.response, result.error);
}
