import type { PlatformOption } from "../platform-preferences/interests";
import type { PlatformLogoName } from "../../shared/catalogue/platform-logo-assets";
import { platformIcon } from "../../shared/catalogue/taxonomy-icons";
import type { SelectIconName } from "../../shared/ui/select-icon";

export const PLATFORMS_PATH = "/plataformas";

/** The query parameter that carries a platform context, shareable and kept by history. */
export const PLATFORM_PARAM = "plataforma";

/**
 * One platform as the Plataformas section (#287) presents it: a featured family or a single
 * catalogue platform. `platformIds` are product taxonomy IDs (#178), never provider IDs, and are
 * what a destination filters by; `key` is what a shareable URL names.
 */
export type PlatformContext = {
  key: string;
  /** The catalogue's own name for the platform, the same everywhere in Gameómetro. */
  name: string;
  platformIds: readonly string[];
  logo: SelectIconName;
};

export type FeaturedPlatform = PlatformContext & { logo: PlatformLogoName };

/**
 * The owner's four featured platforms, in the owner's order. Featured versus other is product
 * curation only, never a domain subtype. A family may group several product IDs once the taxonomy
 * holds them; today each resolves to one platform seeded with a stable ID in every environment.
 * Identities are never merged by name.
 */
export const featuredPlatforms: readonly FeaturedPlatform[] = [
  {
    key: "nintendo-switch-2",
    name: "Nintendo Switch 2",
    platformIds: ["10000000-0000-4000-8000-000000000002"],
    logo: "nintendo-switch-2",
  },
  {
    key: "playstation-5",
    name: "PlayStation 5",
    platformIds: ["10000000-0000-4000-8000-000000000001"],
    logo: "playstation-5",
  },
  {
    key: "xbox-series-x-s",
    name: "Xbox Series X|S",
    platformIds: ["10000000-0000-4000-8000-000000000004"],
    logo: "xbox-series-x-s",
  },
  {
    key: "windows-pc",
    name: "Windows PC",
    platformIds: ["10000000-0000-4000-8000-000000000003"],
    logo: "windows",
  },
];

const featuredIds = new Set(featuredPlatforms.flatMap((platform) => platform.platformIds));

/**
 * The other platforms on offer, in the owner's order (owner decision, 2026-10-27). They are acquired
 * from releases with an identity created per environment, so their catalogue name recognizes
 * them, as it does their marks (#178); each listed name may have a few spellings. Two catalogue
 * platforms sharing a name stay two platforms, and a listed platform without games is not shown.
 */
const otherPlatformNames: readonly (readonly string[])[] = [
  ["android"],
  ["ios"],
  ["linux"],
  ["mac", "macos", "mac os"],
  ["playstation 4"],
  ["meta quest 3"],
  ["nintendo switch"],
  ["xbox one"],
  ["playstation vr2"],
];

/** The curated other platforms the directory holds games for, in the owner's order. */
export function otherPlatforms(directory: readonly PlatformOption[]): PlatformContext[] {
  return otherPlatformNames.flatMap((names) =>
    directory
      .filter((platform) =>
        !featuredIds.has(platform.platformId) && names.includes(platform.name.toLocaleLowerCase()))
      .map((platform) => ({
        key: platform.platformId,
        name: platform.name,
        platformIds: [platform.platformId],
        logo: platformIcon(platform.platformId, platform.name),
      })));
}

/** Whether the local catalogue holds games for any platform of a family. */
export function hasCatalogueGames(context: PlatformContext, directory: readonly PlatformOption[]): boolean {
  return directory.some((platform) => context.platformIds.includes(platform.platformId));
}

/**
 * Resolves a shared platform context: a featured family by its key or one of its IDs, otherwise a
 * catalogue platform by its ID. Null when the directory does not hold it, so a stale or invented
 * link never becomes a platform.
 */
export function resolvePlatformContext(
  value: string,
  directory: readonly PlatformOption[],
): PlatformContext | null {
  const featured = featuredPlatforms.find(
    (platform) => platform.key === value || platform.platformIds.includes(value),
  );
  if (featured !== undefined) return featured;
  return otherPlatforms(directory).find((platform) => platform.key === value) ?? null;
}

/** The value a URL names, or null when it is absent or blank. */
export function readPlatformParam(search: URLSearchParams): string | null {
  const value = search.get(PLATFORM_PARAM)?.trim();
  return value === undefined || value === "" ? null : value;
}

/** The Plataformas index, optionally opened on one platform's context. */
export function platformsPath(context?: PlatformContext): string {
  return context === undefined
    ? PLATFORMS_PATH
    : `${PLATFORMS_PATH}?${new URLSearchParams({ [PLATFORM_PARAM]: context.key })}`;
}
