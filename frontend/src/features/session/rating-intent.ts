/**
 * Client for the BFF rating authentication boundary under `/auth`.
 *
 * These are server-owned authentication-navigation routes, not the product API, so they are
 * addressed directly on the same origin rather than through the generated product client. The
 * browser never carries OAuth tokens; it only starts the flow and reads back its own pending,
 * non-persisted selection.
 */

/** A pending, non-persisted rating selection recovered after authentication. */
export type PendingRatingIntent = { gameId: string; slug: string; value: number };

/** Builds the allowlisted server route that starts authentication from the rating boundary. */
export function ratingIntentStartUrl(
  gameId: string,
  slug: string,
  value: number,
): string {
  const params = new URLSearchParams({ gameId, slug, value: String(value) });
  return `/auth/rating-intent/start?${params.toString()}`;
}

/** Reads and consumes the single recovered selection, or null when none applies. */
export async function getPendingRatingIntent(
  signal?: AbortSignal,
  fetcher: typeof globalThis.fetch = globalThis.fetch,
): Promise<PendingRatingIntent | null> {
  const response = await fetcher("/auth/rating-intent", {
    credentials: "same-origin",
    headers: { Accept: "application/json" },
    ...(signal ? { signal } : {}),
  });
  if (!response.ok) {
    return null;
  }
  const body: unknown = await response.json();
  if (
    typeof body === "object" &&
    body !== null &&
    "gameId" in body &&
    "value" in body
  ) {
    const intent = body as PendingRatingIntent;
    return {
      gameId: intent.gameId,
      slug: intent.slug,
      value: intent.value,
    };
  }
  return null;
}
