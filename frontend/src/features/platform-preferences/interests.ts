import type { components } from "../../shared/api/generated/schema";

export type PlatformOption = components["schemas"]["Platform"];

/**
 * The signed-in visitor's preferred platforms as release discovery reads them.
 *
 * `pending` holds discovery back until the session and the preferences are known, so a first read
 * never shows every platform and then narrows; `unavailable` is an anonymous visitor or a
 * preference read that failed, and `none` a signed-in visitor without preferred platforms. Neither
 * of those two offers an interests control that would have no effect.
 */
export type Interests =
  | { status: "pending" }
  | { status: "unavailable" }
  | { status: "none" }
  | { status: "ready"; platforms: readonly PlatformOption[] };

/**
 * How the platform dimension of a discovery screen is decided.
 *
 * - `on`: **Mis plataformas** in **Mi cuenta** applies the preferred platforms (the default).
 * - `off`: the player turned it off; every platform shows and the preferences are kept.
 * - `manual`: platforms chosen by hand in the explorer's **Plataforma**; preferences are off.
 * - `inactive`: no preferences apply (anonymous, none saved, or not yet known).
 */
export type InterestMode = "on" | "off" | "manual" | "inactive";

export type PlatformFilter = {
  mode: InterestMode;
  /** The explicit platforms the public request carries; empty means every platform. */
  platformIds: readonly string[];
};

/**
 * Resolves the effective platform filter from the URL's manual choice, the document-wide switch
 * and the player's preferences. The URL never carries preferences: a shared link means "the
 * default view", which each signed-in reader resolves with their own platforms and an anonymous
 * reader with every platform.
 */
export function resolvePlatformFilter(
  urlPlatformIds: readonly string[],
  interestsEnabled: boolean,
  interests: Interests,
): PlatformFilter {
  if (interests.status !== "ready") {
    return { mode: "inactive", platformIds: urlPlatformIds };
  }
  if (urlPlatformIds.length > 0) {
    return { mode: "manual", platformIds: urlPlatformIds };
  }
  if (!interestsEnabled) {
    return { mode: "off", platformIds: [] };
  }
  return { mode: "on", platformIds: interests.platforms.map((platform) => platform.platformId) };
}

export function interestPlatformIds(interests: Interests): readonly string[] {
  return interests.status === "ready" ? interests.platforms.map((platform) => platform.platformId) : [];
}

/**
 * A manual selection's outcome: the URL's next platform set and whether preferences then apply,
 * or `null` to leave the document-wide switch as it is.
 */
export type PlatformSelectionChange = { platformIds: string[]; interestsEnabled: boolean | null };

/**
 * Toggles one platform starting from what **Plataforma** visibly shows, so no hidden selection
 * intersects the player's choice. Any manual choice turns preferences off; a selection equal to the
 * preferred platforms is the preferences themselves again, and emptying a selection shows every
 * platform rather than silently returning to the preferences.
 */
export function togglePlatformSelection(
  filter: PlatformFilter,
  platformId: string,
  interests: Interests,
): PlatformSelectionChange {
  const current = filter.platformIds;
  const next = current.includes(platformId)
    ? current.filter((existing) => existing !== platformId)
    : [...current, platformId];
  const preferred = interestPlatformIds(interests);
  if (preferred.length === 0) {
    return { platformIds: next, interestsEnabled: null };
  }
  if (sameSet(next, preferred)) {
    return { platformIds: [], interestsEnabled: true };
  }
  return { platformIds: next, interestsEnabled: false };
}

/** Removing every filter shows every platform, including the preferred ones. */
export function clearedPlatforms(interests: Interests): PlatformSelectionChange {
  return { platformIds: [], interestsEnabled: interests.status === "ready" ? false : null };
}

function sameSet(left: readonly string[], right: readonly string[]): boolean {
  return left.length === right.length && left.every((value) => right.includes(value));
}
