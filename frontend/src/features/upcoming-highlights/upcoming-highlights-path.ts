import { PLATFORMS_PATH } from "../platforms/platform-context";

/**
 * Próximos destacados (#388) has no URL state of its own: the horizon and the ranking are fixed
 * product rules, so the platform in the path is the whole shareable context.
 */
const UPCOMING_SEGMENT = "proximos";

/** `/plataformas/<key>/proximos`, where the key is a featured family's or a platform's own ID. */
const UPCOMING_ROUTE = new RegExp(`^${PLATFORMS_PATH}/([^/]+)/${UPCOMING_SEGMENT}$`);

export const PLATFORM_UPCOMING_ROUTE = `${PLATFORMS_PATH.slice(1)}/:platformKey/${UPCOMING_SEGMENT}`;

export function platformUpcomingPath(platformKey: string): string {
  return `${PLATFORMS_PATH}/${encodeURIComponent(platformKey)}/${UPCOMING_SEGMENT}`;
}

/** The platform key a pathname names as upcoming highlights, or null for any other path. */
export function readPlatformUpcomingKey(pathname: string): string | null {
  const match = UPCOMING_ROUTE.exec(pathname);
  if (match?.[1] === undefined) return null;
  try {
    return decodeURIComponent(match[1]);
  } catch {
    return null;
  }
}
