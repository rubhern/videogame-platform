import { describe, expect, it } from "vitest";

import {
  featuredPlatforms,
  hasCatalogueGames,
  otherPlatforms,
  platformsPath,
  readPlatformParam,
  resolvePlatformContext,
  type PlatformContext,
} from "./platform-context";

const directory = [
  { platformId: "android-id", name: "Android" },
  { platformId: "mac-id", name: "Mac" },
  { platformId: "quest-3-id", name: "Meta Quest 3" },
  { platformId: "quest-2-id", name: "Meta Quest 2" },
  { platformId: "10000000-0000-4000-8000-000000000002", name: "Nintendo Switch 2" },
  { platformId: "ps4-id", name: "PlayStation 4" },
  { platformId: "10000000-0000-4000-8000-000000000003", name: "Windows PC" },
  { platformId: "playdate-id", name: "Playdate" },
  { platformId: "steamvr-id", name: "SteamVR" },
];

describe("platform context", () => {
  it("features the owner's four platforms, in order, by their catalogue names and product IDs", () => {
    expect(featuredPlatforms.map((platform) => platform.name)).toEqual([
      "Nintendo Switch 2", "PlayStation 5", "Xbox Series X|S", "Windows PC",
    ]);
    expect(featuredPlatforms.flatMap((platform) => platform.platformIds)).toEqual([
      "10000000-0000-4000-8000-000000000002",
      "10000000-0000-4000-8000-000000000001",
      "10000000-0000-4000-8000-000000000004",
      "10000000-0000-4000-8000-000000000003",
    ]);
  });

  it("offers only the owner's other platforms that have games, in the owner's order, with their own marks", () => {
    expect(otherPlatforms(directory)).toEqual([
      { key: "android-id", name: "Android", platformIds: ["android-id"], logo: "android" },
      { key: "mac-id", name: "Mac", platformIds: ["mac-id"], logo: "macos" },
      { key: "ps4-id", name: "PlayStation 4", platformIds: ["ps4-id"], logo: "playstation-4" },
      { key: "quest-3-id", name: "Meta Quest 3", platformIds: ["quest-3-id"], logo: "meta-quest" },
    ]);
  });

  it("never merges identities by name: a namesake with another ID stays another platform", () => {
    const namesakes = [
      { platformId: "acquired-ps5", name: "PlayStation 5" },
      { platformId: "ps4-a", name: "PlayStation 4" },
      { platformId: "ps4-b", name: "PlayStation 4" },
    ];
    const ps5 = featuredPlatforms.find((platform) => platform.key === "playstation-5") as PlatformContext;
    expect(otherPlatforms(namesakes).map((platform) => platform.key)).toEqual(["ps4-a", "ps4-b"]);
    expect(hasCatalogueGames(ps5, namesakes)).toBe(false);
  });

  it("knows which featured families have games", () => {
    expect(featuredPlatforms.map((platform) => hasCatalogueGames(platform, directory)))
      .toEqual([true, false, false, true]);
  });

  it("resolves a shared context by family key, family ID or offered platform ID, and nothing else", () => {
    expect(resolvePlatformContext("windows-pc", directory)?.name).toBe("Windows PC");
    expect(resolvePlatformContext("10000000-0000-4000-8000-000000000003", directory)?.key).toBe("windows-pc");
    expect(resolvePlatformContext("ps4-id", directory)?.name).toBe("PlayStation 4");
    expect(resolvePlatformContext("PlayStation 4", directory)).toBeNull();
    expect(resolvePlatformContext("steamvr-id", directory)).toBeNull();
    expect(resolvePlatformContext("unknown", directory)).toBeNull();
  });

  it("names the context in a shareable URL", () => {
    const ps4 = resolvePlatformContext("ps4-id", directory) ?? undefined;
    expect(platformsPath()).toBe("/plataformas");
    expect(platformsPath(ps4)).toBe("/plataformas?plataforma=ps4-id");
    expect(readPlatformParam(new URLSearchParams("plataforma=ps4-id"))).toBe("ps4-id");
    expect(readPlatformParam(new URLSearchParams("plataforma=%20"))).toBeNull();
    expect(readPlatformParam(new URLSearchParams())).toBeNull();
  });
});
