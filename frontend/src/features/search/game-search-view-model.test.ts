import { describe, expect, it } from "vitest";

import type { components } from "../../shared/api/generated/schema";
import {
  toGameSearchViewModel,
  type GameSearchResult,
  type GameSearchViewModel,
} from "./game-search-view-model";

type GameSearchPage = components["schemas"]["GameSearchPage"];
type GameSummary = GameSearchPage["items"][number];

const providerCover = {
  kind: "provider",
  url: "https://images.igdb.com/igdb/image/upload/t_cover_big/co1.webp",
  alternativeText: "Carátula de Resident Evil Requiem",
  attribution: { label: "IGDB", sourceUrl: "https://www.igdb.com/games/resident-evil-requiem" },
} as const;

const releaseContext = [
  {
    platform: { platformId: "platform-ps5", name: "PlayStation 5" },
    region: { regionId: "region-europe", name: "Europe" },
    releaseDate: { precision: "day", value: "2026-02-27" },
    status: "released",
    freshnessStatus: "stale",
  },
] as const satisfies GameSummary["releaseContext"];

function summary(overrides: Partial<GameSummary> = {}): GameSummary {
  return {
    gameId: "game-resident-evil-requiem",
    slug: "resident-evil-requiem",
    canonicalTitle: "Resident Evil Requiem",
    matchedAlias: "Biohazard Requiem",
    primaryCover: providerCover,
    releaseContext: [...releaseContext],
    ...overrides,
  };
}

function page(items: GameSummary[]): GameSearchPage {
  return {
    items,
    page: { number: 1, size: 6, totalItems: items.length, totalPages: items.length === 0 ? 0 : 1 },
  };
}

function onlyResult(model: GameSearchViewModel): GameSearchResult {
  const [result, ...rest] = model.results;
  if (result === undefined || rest.length > 0) {
    throw new Error("The view model must hold exactly one result for this assertion.");
  }
  return result;
}

describe("catalogue search view model", () => {
  it("presents the match context, cover and bounded release context", () => {
    const result = onlyResult(toGameSearchViewModel(page([summary()])));

    expect(result.title).toEqual("Resident Evil Requiem");
    expect(result.matchedAlias).toEqual("Biohazard Requiem");
    expect(result.cover).toEqual({
      kind: "provider",
      url: providerCover.url,
      alternativeText: providerCover.alternativeText,
      attribution: { label: "IGDB", sourceUrl: providerCover.attribution.sourceUrl },
    });
    expect(result.releaseContext).toEqual([
      {
        key: "platform-ps5-region-europe-0",
        platform: "PlayStation 5",
        region: "Europe",
        date: "27 de febrero de 2026",
        status: "Publicado",
        isStale: true,
      },
    ]);
    expect(result.hasStaleContext).toBe(true);
  });

  it("reports no match context when only the canonical title matched", () => {
    const withoutAlias: GameSummary = {
      gameId: "game-pragmata",
      slug: "pragmata",
      canonicalTitle: "Pragmata",
      primaryCover: providerCover,
      releaseContext: [],
    };

    expect(onlyResult(toGameSearchViewModel(page([withoutAlias]))).matchedAlias).toBeNull();
  });

  it("treats the product-owned cover as the fallback variant", () => {
    const result = onlyResult(
      toGameSearchViewModel(
        page([
          summary({
            primaryCover: {
              kind: "fallback",
              url: "/assets/covers/fallback.svg",
              alternativeText: "Carátula oficial no disponible",
              attribution: null,
            },
          }),
        ]),
      ),
    );

    expect(result.cover).toEqual({
      kind: "fallback",
      url: "/assets/covers/fallback.svg",
      alternativeText: "Carátula oficial no disponible",
    });
  });

  it("keeps a game without release context usable instead of inventing one", () => {
    const result = onlyResult(toGameSearchViewModel(page([summary({ releaseContext: [] })])));

    expect(result.releaseContext).toEqual([]);
    expect(result.hasStaleContext).toBe(false);
  });

  it("gives every bounded release-context row a stable identity", () => {
    const result = onlyResult(
      toGameSearchViewModel(
        page([summary({ releaseContext: [...releaseContext, ...releaseContext] })]),
      ),
    );

    expect(result.releaseContext.map((context) => context.key)).toEqual([
      "platform-ps5-region-europe-0",
      "platform-ps5-region-europe-1",
    ]);
  });

  it("carries an empty result page through unchanged", () => {
    const model = toGameSearchViewModel(page([]));

    expect(model.results).toEqual([]);
    expect(model.page.totalItems).toEqual(0);
  });
});
