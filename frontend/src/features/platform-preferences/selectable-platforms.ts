import { platformIcon } from "../../shared/catalogue/taxonomy-icons";
import type { PlatformOption } from "./interests";

/** Preserve catalogue ordering and offer only platforms with an owner-provided mark. */
export function selectablePlatforms(platforms: readonly PlatformOption[]): PlatformOption[] {
  return platforms.filter((platform) => platformIcon(platform.platformId, platform.name) !== "platform");
}
