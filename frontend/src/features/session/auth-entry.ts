/** Presentation guard; the BFF independently validates every destination before storing it. */
export function safeReturnTo(candidate: string | null): string {
  if (!candidate || candidate.length > 2048 || !candidate.startsWith("/")) return "/";
  const queryStart = candidate.indexOf("?");
  const path = queryStart < 0 ? candidate : candidate.slice(0, queryStart);
  const query = queryStart < 0 ? "" : candidate.slice(queryStart + 1);
  const keys = path === "/" ? ["view", "weeks", "platformIds", "regionIds", "page", "pageSize"]
    : path === "/search" ? ["q", "page", "pageSize"]
      : path === "/mis-puntuaciones" ? []
        : /^\/games\/[a-z0-9-]{1,100}(?:\/[a-z0-9-]{1,200})?$/.test(path ?? "") ? ["platformId", "regionId"]
          : null;
  if (!keys || candidate.includes("#") || candidate.includes("\\")) return "/";
  for (const [key, value] of new URLSearchParams(query)) {
    if (!keys.includes(key) || /[\p{Cc}\\%]/u.test(value)) return "/";
  }
  return candidate;
}

/** Full document navigation to the BFF; React never owns an authentication screen. */
export function authenticationStartUrl(returnTo: string, register = false): string {
  const params = new URLSearchParams({ returnTo: safeReturnTo(returnTo) });
  if (register) params.set("intent", "register");
  return `/auth/start?${params}`;
}
