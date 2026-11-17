import { describe, expect, it } from "vitest";

import { rankingPath, readPlatformRankingKey, readRankingSource } from "./ranking-search";

describe("TOPómetro URL state", () => {
  it("names a source globally or on a platform, the default source without a parameter", () => {
    expect(rankingPath("igdb")).toBe("/topometro");
    expect(rankingPath("gameometro")).toBe("/topometro?source=gameometro");
    expect(rankingPath("igdb", "playstation-5")).toBe("/plataformas/playstation-5/topometro");
    expect(rankingPath("gameometro", "ps4-id")).toBe("/plataformas/ps4-id/topometro?source=gameometro");
  });

  it("reads the platform a path names and nothing else", () => {
    expect(readPlatformRankingKey("/plataformas/windows-pc/topometro")).toBe("windows-pc");
    expect(readPlatformRankingKey("/plataformas/windows-pc/catalogo")).toBeNull();
    expect(readPlatformRankingKey("/topometro")).toBeNull();
    expect(readPlatformRankingKey("/plataformas/%E0%A4%A/topometro")).toBeNull();
  });

  it("reads anything but the community as the default source", () => {
    expect(readRankingSource(new URLSearchParams("source=gameometro"))).toBe("gameometro");
    expect(readRankingSource(new URLSearchParams("source=critics"))).toBe("igdb");
  });
});
