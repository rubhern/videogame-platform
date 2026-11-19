import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { gameDetailsFixture } from "../../test/game-details-fixture";
import type { GameDetails } from "./game-details-api";
import { gameStructuredData } from "./game-structured-data";
import { GameStructuredData } from "./game-structured-data-script";

const ORIGIN = "https://gameometro.com";

function richGame(): GameDetails {
  const game = gameDetailsFixture();
  const [release] = game.releases;
  if (release === undefined) throw new Error("The fixture has a release.");
  return {
    ...game,
    summary: {
      kind: "sourced",
      text: "Una nueva pesadilla.",
      language: "es",
      provenance: { sourceKind: "external_provider", sourceName: "IGDB", sourceEntityType: "game_summary" },
    },
    primaryCover: {
      kind: "provider",
      url: "https://images.igdb.com/igdb/image/upload/t_cover_big/co1.webp",
      alternativeText: "Portada de Resident Evil Requiem",
      attribution: { label: "IGDB", sourceUrl: "https://www.igdb.com/games/resident-evil-requiem" },
    },
    genres: [{ genreId: "shooter", name: "Disparos" }],
    developers: [{ companyId: "capcom", name: "Capcom" }],
    publishers: [{ companyId: "capcom", name: "Capcom" }],
    releases: [
      release,
      { ...release, releaseId: "release-two", region: { regionId: "japan", name: "Japón" } },
      { ...release, releaseId: "release-three", platform: { platformId: "pc", name: "PC (Windows)" } },
      { ...release, releaseId: "cancelled", status: "cancelled", platform: { platformId: "wii-u", name: "Wii U" } },
    ],
    ratingStatistics: { ...game.ratingStatistics, mean: 8.4, count: 12 },
    // A third-party reading the page shows separately; it never becomes the site's rating.
    externalScore: {
      source: "igdb",
      kind: "player",
      value: 9.1,
      scale: 10,
      count: 400,
      attribution: { label: "IGDB", sourceUrl: "https://www.igdb.com/games/resident-evil-requiem" },
      observedAt: "2026-11-01T00:00:00Z",
    },
  };
}

describe("gameStructuredData", () => {
  it("describes the game with what its page shows and the community reading alone", () => {
    expect(gameStructuredData(richGame(), ORIGIN)).toEqual({
      "@context": "https://schema.org",
      "@type": "VideoGame",
      name: "Resident Evil Requiem",
      url: `${ORIGIN}/games/30000000-0000-4000-8000-000000000005/resident-evil-requiem`,
      description: "Una nueva pesadilla.",
      image: "https://images.igdb.com/igdb/image/upload/t_cover_big/co1.webp",
      genre: ["Disparos"],
      gamePlatform: ["PlayStation 5", "PC (Windows)"],
      author: [{ "@type": "Organization", name: "Capcom" }],
      publisher: [{ "@type": "Organization", name: "Capcom" }],
      aggregateRating: {
        "@type": "AggregateRating",
        ratingValue: 8.4,
        ratingCount: 12,
        bestRating: 10,
        worstRating: 1,
      },
    });
  });

  it("omits every field the catalogue does not truthfully hold", () => {
    // The fixture: an editorial notice, a fallback cover, no credits and no community votes.
    expect(gameStructuredData(gameDetailsFixture(), ORIGIN)).toEqual({
      "@context": "https://schema.org",
      "@type": "VideoGame",
      name: "Resident Evil Requiem",
      url: `${ORIGIN}/games/30000000-0000-4000-8000-000000000005/resident-evil-requiem`,
      gamePlatform: ["PlayStation 5"],
    });
  });

  it("never states a rating when the community reading is unavailable", () => {
    const game = {
      ...gameDetailsFixture(),
      ratingStatistics: { status: "unavailable" as const, reasonCode: "RATING_STATISTICS_READ_FAILED" as const },
    };
    expect(gameStructuredData(game, ORIGIN)).not.toHaveProperty("aggregateRating");
  });

  it("renders inert JSON that no title can break out of", () => {
    const game = { ...gameDetailsFixture(), canonicalTitle: "</script><img src=x onerror=alert(1)>" };
    const { container } = render(<GameStructuredData game={game} />);
    const script = container.querySelector('script[type="application/ld+json"]');
    expect(script?.textContent).not.toContain("</script>");
    expect(JSON.parse(script?.textContent ?? "{}")).toMatchObject({ name: game.canonicalTitle });
    expect(container.querySelector("img")).toBeNull();
  });
});
