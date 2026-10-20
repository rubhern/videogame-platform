import { RELEASES_PATH } from "../releases/releases-search";

/** The first-run choice of preferred platforms (#295). */
export const WELCOME_PATH = "/bienvenida";

const MAX_RETURN_LENGTH = 2048;

/**
 * The discovery destination the welcome returns to. Only TOPómetro and the release explorer, with
 * their own query, are ever followed; anything else, encoded tricks and other origins included,
 * returns to TOPómetro.
 */
export function safeWelcomeReturn(candidate: string | null): string {
  if (candidate === null || candidate.length > MAX_RETURN_LENGTH) return "/";
  if (!candidate.startsWith("/") || candidate.startsWith("//") || /[\\#%\p{Cc}]/u.test(candidate)) {
    return "/";
  }
  const queryStart = candidate.indexOf("?");
  const path = queryStart < 0 ? candidate : candidate.slice(0, queryStart);
  return path === "/" || path === RELEASES_PATH ? candidate : "/";
}

export function welcomePath(returnTo: string): string {
  const destination = safeWelcomeReturn(returnTo);
  return destination === "/"
    ? WELCOME_PATH
    : `${WELCOME_PATH}?${new URLSearchParams({ returnTo: destination }).toString()}`;
}
