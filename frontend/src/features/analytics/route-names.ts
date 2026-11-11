/**
 * The bounded vocabulary of pages analytics may report (#507). Only a route's template leaves the
 * browser: never a query string, fragment, search term, internal game ID or account detail. The
 * public game slug (owner decision, #330) and a public platform key are the only path values kept.
 * Administration routes are never reported.
 */
export type RouteName =
  | "home"
  | "featured"
  | "explore"
  | "calendar"
  | "topometro"
  | "platforms"
  | "platform_catalogue"
  | "platform_topometro"
  | "platform_upcoming"
  | "search"
  | "game_detail"
  | "my_ratings"
  | "rating_insights"
  | "profile"
  | "account_deletion"
  | "my_platforms"
  | "welcome"
  | "legal"
  | "not_found";

export type ReportedRoute = { name: RouteName; path: string };

const SLUG = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;

const FIXED: Readonly<Record<string, RouteName>> = {
  "/": "home",
  "/lanzamientos/destacados": "featured",
  "/lanzamientos": "explore",
  "/lanzamientos/calendario": "calendar",
  "/topometro": "topometro",
  "/plataformas": "platforms",
  "/search": "search",
  "/mis-puntuaciones": "my_ratings",
  "/mis-puntuaciones/estadisticas": "rating_insights",
  "/mi-perfil": "profile",
  "/mi-perfil/borrado": "account_deletion",
  "/mis-plataformas": "my_platforms",
  "/bienvenida": "welcome",
  "/privacidad": "legal",
  "/condiciones": "legal",
  "/aviso-legal": "legal",
  "/cookies": "legal",
};

const PLATFORM_PAGES: Readonly<Record<string, RouteName>> = {
  catalogo: "platform_catalogue",
  topometro: "platform_topometro",
  proximos: "platform_upcoming",
};

/** The reported page for a pathname, or null for a route analytics never reports. */
export function reportedRoute(pathname: string): ReportedRoute | null {
  const path = pathname.length > 1 ? pathname.replace(/\/+$/, "") : pathname;
  if (path === "/administracion" || path.startsWith("/administracion/")) return null;

  const fixed = FIXED[path];
  if (fixed !== undefined) return { name: fixed, path };

  const segments = path.split("/").slice(1);
  if (segments[0] === "games" && (segments.length === 2 || segments.length === 3)) {
    const slug = segments[2];
    // The internal game ID never leaves the browser; only the descriptive public slug does.
    return {
      name: "game_detail",
      path: slug !== undefined && slug.length <= 200 && SLUG.test(slug) ? `/games/${slug}` : "/games",
    };
  }
  if (segments[0] === "plataformas" && segments.length === 3) {
    const [, key = "", page = ""] = segments;
    const name = PLATFORM_PAGES[page];
    if (name !== undefined && key.length <= 60 && SLUG.test(key)) {
      return { name, path: `/plataformas/${key}/${page}` };
    }
  }
  return { name: "not_found", path: "/404" };
}
