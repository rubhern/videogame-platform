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
  it("shows the single known year whatever the date precision", () => {
    expect(
      suggestionYear([
        release("p1", "PlayStation 5", { precision: "day", value: "2026-03-18" }),
        release("p2", "Windows PC", { precision: "quarter", value: "2026-Q2" }),
        release("p3", "Xbox Series X|S", { precision: "month", value: "2026-11" }),
      ]),
    ).toBe("2026");
  });

  it("shows an inclusive range across several known years and ignores unknown dates", () => {
    expect(
      suggestionYear([
        release("p1", "PlayStation 5", { precision: "year", value: "2026" }),
        release("p2", "Windows PC", { precision: "unknown", value: null }),
        release("p3", "Xbox Series X|S", { precision: "day", value: "2024-02-01" }),
      ]),
    ).toBe("2024–2026");
  });

  it("shows Por confirmar without any known year", () => {
    expect(suggestionYear([])).toBe("Por confirmar");
    expect(
      suggestionYear([release("p1", "PlayStation 5", { precision: "unknown", value: null })]),
    ).toBe("Por confirmar");
  });
});

describe("suggestion projection", () => {
  it("keeps at most five suggestions and the full result total", () => {
    const items = Array.from({ length: 6 }, (_, index) => game({ gameId: `game-${index}` }));

    const result = toGameSuggestions(page(items, 23));

    expect(result.suggestions).toHaveLength(5);
    expect(result.totalItems).toBe(23);
  });

  it("shows three distinct platforms and folds the rest into the overflow", () => {
    const day = { precision: "day", value: "2026-01-01" } as const;
    const [suggestion] = toGameSuggestions(
      page([
        game({
          releaseContext: [
            release("10000000-0000-4000-8000-000000000003", "Windows PC", day),
            release("10000000-0000-4000-8000-000000000001", "PlayStation 5", day),
            release("10000000-0000-4000-8000-000000000003", "Windows PC", day),
            release("10000000-0000-4000-8000-000000000004", "Xbox Series X|S", day),
            release("platform-switch", "Nintendo Switch", day),
            release("platform-new", "Some Future Console", day),
          ],
        }),
      ]),
    ).suggestions;

    expect(suggestion?.platforms.map(({ name, icon }) => [name, icon])).toEqual([
      ["Windows PC", "windows"],
      ["PlayStation 5", "playstation-5"],
      ["Xbox Series X|S", "xbox-series-x-s"],
    ]);
    expect(suggestion?.hiddenPlatforms.map(({ name, icon }) => [name, icon])).toEqual([
      ["Nintendo Switch", "nintendo-switch"],
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
          releaseContext: [
            release("10000000-0000-4000-8000-000000000001", "PlayStation 5", {
              precision: "year",
              value: "2026",
            }),
          ],
        }),
      ]),
    ).suggestions;

    expect(suggestion?.path).toBe("/games/game-1/eclipse-of-aether");
    expect(suggestion && suggestionAccessibleName(suggestion)).toBe(
      "Eclipse of Aether · también conocido como Aether · PlayStation 5 · 2026",
    );
  });
});
