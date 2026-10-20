import type { Location } from "react-router-dom";

/**
 * Where a game page was opened from, so its back action can name and restore that screen (#285).
 *
 * The origin travels in the history entry's React Router state, never in the URL: a shared game
 * link stays clean and a personal search never leaks into it. A reload or browser history keeps
 * the entry's state; a direct link has none and falls back to TOPómetro. State is browser input,
 * so it is validated on every read and only ever names one of the product's own list routes.
 */
export type ReturnOrigin = "featured" | "releases" | "search" | "my-ratings";

/**
 * The state a link into a game page carries. `dayFrame` keeps Explorar lanzamientos' day bar as it
 * was; it is presentation context, revalidated by the explorer when it returns.
 */
export type GameLinkState = { returnTo: { path: string; entry: string; dayFrame?: DayFrame } };

/** The state a return navigation carries: the history entry whose reading position it restores. */
export type ReturnNavigationState = { restoreEntry: string; dayFrame?: DayFrame };

type DayFrame = { from: string; to: string };

export type ReturnContext = {
  origin: ReturnOrigin;
  /** The origin's path and query, ready for a link. */
  path: string;
  label: string;
  state: ReturnNavigationState;
};

const ORIGINS: Record<string, ReturnOrigin> = {
  "/": "featured",
  "/lanzamientos": "releases",
  "/search": "search",
  "/mis-puntuaciones": "my-ratings",
};

const LABELS: Record<ReturnOrigin, string> = {
  featured: "Volver al TOPómetro",
  releases: "Volver a lanzamientos",
  search: "Volver a resultados",
  "my-ratings": "Volver a mis puntuaciones",
};

/** Without a known origin the game page returns to the landing route. */
export const DEFAULT_RETURN: Pick<ReturnContext, "path" | "label"> = {
  path: "/",
  label: LABELS.featured,
};

const MAX_PATH_LENGTH = 2048;
const ENTRY_KEY = /^[A-Za-z0-9_-]{1,64}$/;
const BASE = "https://gameometro.invalid";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

/** Only the shape of a frame of two calendar-day strings; the explorer validates the days. */
function readFrame(state: unknown): DayFrame | undefined {
  if (!isRecord(state) || !isRecord(state.dayFrame)) return undefined;
  const { from, to } = state.dayFrame;
  const day = /^\d{4}-\d{2}-\d{2}$/;
  return typeof from === "string" && typeof to === "string" && day.test(from) && day.test(to)
    ? { from, to }
    : undefined;
}

function readEntry(value: unknown): string | null {
  return typeof value === "string" && ENTRY_KEY.test(value) ? value : null;
}

/**
 * The history entry a location stands for when saving or restoring its reading position: a
 * return navigation stands for the entry it returns to, any other location for itself.
 */
export function historyEntry(location: Pick<Location, "key" | "state">): string {
  const state: unknown = location.state;
  return (isRecord(state) ? readEntry(state.restoreEntry) : null) ?? location.key;
}

/** Only a same-origin path to one of the list routes is a return target; anything else is none. */
function originOf(path: string): { origin: ReturnOrigin; path: string } | null {
  if (path.length > MAX_PATH_LENGTH || !path.startsWith("/") || path.startsWith("//")) return null;
  if (path.includes("\\")) return null;
  let url: URL;
  try {
    url = new URL(path, BASE);
  } catch {
    return null;
  }
  const origin = url.origin === BASE ? ORIGINS[url.pathname] : undefined;
  return origin === undefined ? null : { origin, path: `${url.pathname}${url.search}` };
}

/** The state a link into a game page carries from `location`, or none from any other screen. */
export function gameLinkState(
  location: Pick<Location, "pathname" | "search" | "key" | "state">,
): GameLinkState | undefined {
  const origin = originOf(`${location.pathname}${location.search}`);
  if (origin === null) return undefined;
  const dayFrame = readFrame(location.state);
  return {
    returnTo: {
      path: origin.path,
      entry: historyEntry(location),
      ...(dayFrame === undefined ? {} : { dayFrame }),
    },
  };
}

/** The validated origin of a game page's history entry, or null for a direct link or bad state. */
export function readReturnContext(state: unknown): ReturnContext | null {
  if (!isRecord(state) || !isRecord(state.returnTo)) return null;
  const { path, entry } = state.returnTo;
  const restoreEntry = readEntry(entry);
  const origin = typeof path === "string" ? originOf(path) : null;
  if (origin === null || restoreEntry === null) return null;
  const dayFrame = readFrame(state.returnTo);
  return {
    origin: origin.origin,
    path: origin.path,
    label: LABELS[origin.origin],
    state: { restoreEntry, ...(dayFrame === undefined ? {} : { dayFrame }) },
  };
}
