import { platformCataloguePath } from "../platform-catalogue/platform-catalogue-search";
import { DEFAULT_RANKING_SOURCE, rankingPath } from "../rankings/ranking-search";
import { platformUpcomingPath } from "../upcoming-highlights/upcoming-highlights-path";
import type { PlatformContext } from "./platform-context";

export type PlatformDestinationKey = "upcoming" | "topometro" | "catalogue";

/**
 * One of the three destinations every platform offers. `to` returns the destination's path for a
 * platform context, or null while it is not available: the index then shows it as `Próximamente`
 * and never links it. Each feature activates its destination here once it genuinely supports the
 * platform, without changing the index.
 */
export type PlatformDestination = {
  key: PlatformDestinationKey;
  label: string;
  to: (context: PlatformContext) => string | null;
};

export const platformDestinations: readonly PlatformDestination[] = [
  { key: "catalogue", label: "Explorar catálogo", to: (context) => platformCataloguePath(context.key) },
  { key: "topometro", label: "TOPómetro", to: (context) => rankingPath(DEFAULT_RANKING_SOURCE, context.key) },
  { key: "upcoming", label: "Próximos destacados", to: (context) => platformUpcomingPath(context.key) },
];
