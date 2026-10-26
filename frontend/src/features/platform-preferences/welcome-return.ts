import { FEATURED_PATH } from "../releases/featured-search";
import { CALENDAR_PATH } from "../releases/release-calendar-search";
import { RELEASES_PATH } from "../releases/releases-search";

/** The first-run choice of preferred platforms (#295). */
export const WELCOME_PATH = "/bienvenida";

const MAX_RETURN_LENGTH = 2048;

/**
 * The discovery destination the welcome returns to. Only Destacados, the explorer and the calendar,
 * with their own query, are ever followed; anything else, encoded tricks and other origins included,
 * returns to Destacados.
 */
export function safeWelcomeReturn(candidate: string | null): string {
  if (candidate === null || candidate.length > MAX_RETURN_LENGTH) return FEATURED_PATH;
  if (!candidate.startsWith("/") || candidate.startsWith("//") || /[\\#%\p{Cc}]/u.test(candidate)) {
    return FEATURED_PATH;
  }
  const queryStart = candidate.indexOf("?");
  const path = queryStart < 0 ? candidate : candidate.slice(0, queryStart);
  return path === "/" || path === FEATURED_PATH || path === RELEASES_PATH || path === CALENDAR_PATH ? candidate : FEATURED_PATH;
}

export function welcomePath(returnTo: string): string {
  const destination = safeWelcomeReturn(returnTo);
  return destination === FEATURED_PATH
    ? WELCOME_PATH
    : `${WELCOME_PATH}?${new URLSearchParams({ returnTo: destination }).toString()}`;
}
