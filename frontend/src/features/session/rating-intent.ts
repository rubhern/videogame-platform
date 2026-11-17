/**
 * Client for the BFF rating authentication boundary under `/auth`.
 *
 * These are server-owned authentication-navigation routes, not the product API, so they are
 * addressed directly on the same origin rather than through the generated product client. The
 * browser never carries OAuth tokens; it only starts the flow and reads back its own pending,
 * non-persisted selection.
 */

/** A pending, non-persisted rating selection recovered after authentication. */
export type PlatformSelection = { platformId: string; value?: number };

export type PendingRatingIntent = { gameId: string } & (
  | { value: number; platforms?: never }
  | { value?: never; platforms: PlatformSelection[] }
);

const startPath = "/auth/rating-intent/start";

/** Builds the allowlisted server route that starts authentication from the rating boundary. */
export function ratingIntentStartUrl(
  gameId: string,
  slug: string,
  value: number,
): string {
  const params = new URLSearchParams({ gameId, slug, value: String(value) });
  return `${startPath}?${params.toString()}`;
}

/** Stores a platform selection in the BFF session before navigating through OIDC. */
export async function startPlatformRatingIntent(
  gameId: string,
  slug: string,
  platforms: readonly PlatformSelection[],
): Promise<string> {
  const proof = await fetch(`${startPath}?csrf`);
  if (!proof.ok) throw new Error("Could not prepare rating authentication.");

  const body = new URLSearchParams({ gameId, slug });
  for (const { platformId, value } of platforms) {
    body.append("platform", `${platformId}:${value ?? ""}`);
  }
  const response = await fetch(startPath, {
    method: "POST",
    headers: { "X-CSRF-Token": await proof.text() },
    body,
  });
  if (!response.ok) throw new Error("Could not start rating authentication.");
  const result: unknown = await response.json();
  if (typeof result !== "object" || result === null || !("alreadyAuthenticated" in result) ||
      typeof result.alreadyAuthenticated !== "boolean") {
    throw new Error("Invalid rating authentication response.");
  }

  return result.alreadyAuthenticated
    ? `/games/${encodeURIComponent(gameId)}/${encodeURIComponent(slug)}?rating-intent=resumed`
    : "/auth/login/keycloak";
}

function isScore(value: unknown): value is number {
  return typeof value === "number" && Number.isInteger(value) && value >= 1 && value <= 10;
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
  if (response.status === 404) return null;
  if (!response.ok) throw new Error("Could not recover the rating selection.");
  const body: unknown = await response.json();
  if (typeof body !== "object" || body === null || !("gameId" in body) ||
      typeof body.gameId !== "string") return null;

  if (!("platforms" in body) || (Array.isArray(body.platforms) && body.platforms.length === 0)) {
    return "value" in body && isScore(body.value)
      ? { gameId: body.gameId, value: body.value }
      : null;
  }

  if (("value" in body && body.value != null) || !Array.isArray(body.platforms) ||
      body.platforms.length > 50) return null;

  const platforms: PlatformSelection[] = [];
  const ids = new Set<string>();
  for (const platform of body.platforms as unknown[]) {
    if (typeof platform !== "object" || platform === null || !("platformId" in platform) ||
        typeof platform.platformId !== "string" || !platform.platformId.trim() ||
        platform.platformId.length > 100 || ids.has(platform.platformId)) return null;
    const value = "value" in platform ? platform.value : null;
    if (value != null && !isScore(value)) return null;
    ids.add(platform.platformId);
    platforms.push(value == null ? { platformId: platform.platformId } : { platformId: platform.platformId, value });
  }
  return platforms.some((platform) => platform.value !== undefined)
    ? { gameId: body.gameId, platforms }
    : null;
}
