import { describe, expect, it } from "vitest";

import type { components } from "../../shared/api/generated/schema";
import {
  isSuggestionTerm,
  suggestionAccessibleName,
  suggestionYear,
  toGameSuggestions,
} from "./game-suggestions";

type GameSummary = components["schemas"]["GameSummary"];
type ReleaseSummary = components["schemas"]["ReleaseSummary"];
type ReleaseDate = components["schemas"]["ReleaseDate"];

function release(platformId: string, name: string, releaseDate: ReleaseDate): ReleaseSummary {
  return {
    platform: { platformId, name },
    region: { regionId: "region-europe", name: "Europe" },
    releaseDate,
    status: "released",
    freshnessStatus: "fresh",
  };
}

type Platform = components["schemas"]["Platform"];
type CompactReleaseSummary = components["schemas"]["CompactReleaseSummary"];

const ps5 = { platformId: "10000000-0000-4000-8000-000000000001", name: "PlayStation 5" };
const pc = { platformId: "10000000-0000-4000-8000-000000000003", name: "Windows PC" };
const xbox = { platformId: "10000000-0000-4000-8000-000000000004", name: "Xbox Series X|S" };
const day = { precision: "day", value: "2026-01-01" } as const;

function summary(
  platforms: Platform[],
  totalPlatforms: number,
  years: Pick<CompactReleaseSummary, "earliestKnownYear" | "latestKnownYear"> = {},
): CompactReleaseSummary {
  return { platforms, totalPlatforms, ...years };
}

function game(overrides: Partial<GameSummary> = {}): GameSummary {
  return {
    gameId: "game-1",
    slug: "eclipse-of-aether",
    canonicalTitle: "Eclipse of Aether",
    primaryCover: {
      kind: "fallback",
      url: "/assets/covers/fallback.svg",
      alternativeText: "Carátula oficial no disponible",
      attribution: null,
    },
    releaseContext: [],
    releaseSummary: { platforms: [], totalPlatforms: 0 },
    ...overrides,
  };
}

function page(items: GameSummary[], totalItems = items.length) {
  return { items, page: { number: 1, size: 5, totalItems, totalPages: 1 } };
}

describe("typeahead eligibility", () => {
  it("counts Unicode code points, not UTF-16 units", () => {
    expect(isSuggestionTerm("a")).toBe(false);
    expect(isSuggestionTerm("🎮")).toBe(false);
    expect(isSuggestionTerm("🎮🎮")).toBe(true);
    expect(isSuggestionTerm("ae")).toBe(true);
  });

  it("never requests a term the search contract would reject", () => {
    expect(isSuggestionTerm("a".repeat(100))).toBe(true);
    expect(isSuggestionTerm("a".repeat(101))).toBe(false);
  });
});

describe("suggestion year", () => {
  it("shows the single known year", () => {
    expect(suggestionYear(summary([], 0, { earliestKnownYear: 2026, latestKnownYear: 2026 }))).toBe("2026");
  });

  it("shows an inclusive range across several known years", () => {
    expect(suggestionYear(summary([], 0, { earliestKnownYear: 2024, latestKnownYear: 2026 }))).toBe(
      "2024–2026",
    );
  });

  it("shows Por confirmar without any known year", () => {
    expect(suggestionYear(summary([], 0))).toBe("Por confirmar");
  });

  it("never falls back to the bounded release context for the year", () => {
    const [suggestion] = toGameSuggestions(
      page([
        game({
          releaseContext: [release(ps5.platformId, ps5.name, { precision: "day", value: "2025-05-01" })],
          releaseSummary: summary([ps5], 1),
        }),
      ]),
    ).suggestions;

    expect(suggestion?.year).toBe("Por confirmar");
  });
});

describe("suggestion projection", () => {
  it("keeps at most five suggestions and the full result total", () => {
    const items = Array.from({ length: 6 }, (_, index) => game({ gameId: `game-${index}` }));

    const result = toGameSuggestions(page(items, 23));

    expect(result.suggestions).toHaveLength(5);
    expect(result.totalItems).toBe(23);
  });

  it("shows the summary's first three platforms and the exact +N from the distinct total", () => {
    const [suggestion] = toGameSuggestions(
      page([
        game({
          // The bounded sample is filled by duplicate releases on one platform and must be ignored.
          releaseContext: [
            release(pc.platformId, pc.name, day),
            release(pc.platformId, pc.name, day),
            release(pc.platformId, pc.name, day),
          ],
          releaseSummary: summary([ps5, pc, xbox], 7),
        }),
      ]),
    ).suggestions;

    expect(suggestion?.platforms.map(({ name, icon }) => [name, icon])).toEqual([
      ["PlayStation 5", "playstation-5"],
      ["Windows PC", "windows"],
      ["Xbox Series X|S", "xbox-series-x-s"],
    ]);
    expect(suggestion?.hiddenPlatformCount).toBe(4);
    expect(suggestion && suggestionAccessibleName(suggestion)).toBe(
      "Eclipse of Aether · PlayStation 5, Windows PC, Xbox Series X|S · 4 plataformas más · Por confirmar",
    );
  });

  it("shows one icon per distinct platform when several releases share it", () => {
    const [suggestion] = toGameSuggestions(
      page([
        game({
          releaseContext: [
            release(ps5.platformId, ps5.name, day),
            release(ps5.platformId, ps5.name, { precision: "year", value: "2027" }),
          ],
          releaseSummary: summary([ps5], 1, { earliestKnownYear: 2026, latestKnownYear: 2027 }),
        }),
      ]),
    ).suggestions;

    expect(suggestion?.platforms.map(({ id }) => id)).toEqual([ps5.platformId]);
    expect(suggestion?.hiddenPlatformCount).toBe(0);
    expect(suggestion?.year).toBe("2026–2027");
  });

  it("keeps the accessible icon fallback for a platform without a known mark", () => {
    const future = { platformId: "platform-new", name: "Some Future Console" };
    const [suggestion] = toGameSuggestions(page([game({ releaseSummary: summary([future], 1) })])).suggestions;

    expect(suggestion?.platforms.map(({ name, icon }) => [name, icon])).toEqual([
      ["Some Future Console", "platform"],
    ]);
  });

  it("shows the matched alias only when it differs from the canonical title", () => {
    const [aliased, redundant] = toGameSuggestions(
      page([
        game({ gameId: "a", canonicalTitle: "Resident Evil 4", matchedAlias: "Biohazard 4" }),
        game({ gameId: "b", canonicalTitle: "Resident Evil 4", matchedAlias: "resident evil 4" }),
      ]),
    ).suggestions;

    expect(aliased?.alias).toBe("Biohazard 4");
    expect(redundant?.alias).toBeNull();
  });

  it("links to the game page and names the option without relying on icons", () => {
    const [suggestion] = toGameSuggestions(
      page([
        game({
          matchedAlias: "Aether",
          releaseSummary: summary([ps5], 1, { earliestKnownYear: 2026, latestKnownYear: 2026 }),
        }),
      ]),
    ).suggestions;

    expect(suggestion?.path).toBe("/games/game-1/eclipse-of-aether");
    expect(suggestion && suggestionAccessibleName(suggestion)).toBe(
      "Eclipse of Aether · también conocido como Aether · PlayStation 5 · 2026",
    );
  });
});
