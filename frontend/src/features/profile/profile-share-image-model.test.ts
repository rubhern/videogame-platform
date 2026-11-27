import { describe, expect, it } from "vitest";

import {
  blockUnavailability,
  cardFileName,
  cardSections,
  describeCard,
  profileLink,
  shareText,
  toProfileCard,
  type PublicShareSource,
  type ShareChoices,
} from "./profile-share-image-model";

const attribution = { label: "IGDB", sourceUrl: "https://www.igdb.com/games/example" };
const provider = (id: string) => ({
  kind: "provider" as const, url: `https://images.igdb.com/igdb/image/upload/t_cover_big_2x/${id}.jpg`,
  alternativeText: "Portada", attribution,
});
const fallback = { kind: "fallback" as const, url: "/assets/covers/fallback.svg", alternativeText: "Portada no disponible" };

type ProfileGame = NonNullable<PublicShareSource["ratedGames"]>[number];

function game(index: number, title: string | undefined, score?: number, cover: ProfileGame["primaryCover"] = provider(`c${index}`)): ProfileGame {
  return {
    gameId: `30000000-0000-4000-8000-00000000000${index}`,
    ...(title === undefined ? {} : { slug: `juego-${index}`, canonicalTitle: title }),
    primaryCover: cover,
    ...(score === undefined ? {} : { score }),
  };
}

const source: PublicShareSource = {
  alias: "luna-8",
  sections: ["ratings", "wishlist"],
  statistics: {
    ratings: { total: 12, mean: 7.4, lowest: 3, highest: 10, distribution: [0, 0, 1, 0, 1, 2, 3, 2, 2, 1] },
    wishlistCount: 9,
  },
  ratedGames: [game(1, "Alfa", 10), game(2, "Beta", 9), game(3, "Gamma", 9), game(4, "Delta", 8)],
  wishedGames: [game(5, "Épsilon"), game(6, undefined), game(7, "Zeta", undefined, fallback)],
};

const everything: ShareChoices = { games: true, gamesOrder: "top", scoring: true, wishlist: true, format: "post" };
const url = "https://gameometro.com/u/luna-8";

describe("profile share card", () => {
  it("builds every chosen block from the public responses alone", () => {
    const card = toProfileCard(source, everything, url);

    expect(card.link).toBe("gameometro.com/u/luna-8");
    expect(card.games?.items.map(({ title, score, band, rank }) => ({ title, score, band, rank }))).toEqual([
      { title: "Alfa", score: 10, band: "burn", rank: 1 },
      { title: "Beta", score: 9, band: "burn", rank: 2 },
      { title: "Gamma", score: 9, band: "burn", rank: 2 },
    ]);
    expect(card.scoring).toEqual({ total: 12, mean: 7.4, band: "hot", distribution: [0, 0, 1, 0, 1, 2, 3, 2, 2, 1] });
    // A game removed from the catalogue has no title and stays out; a fallback cover is the product's.
    expect(card.wishlist).toEqual({
      total: 9,
      covers: [
        { url: provider("c5").url, provider: true, title: "Épsilon" },
        { url: "/assets/covers/fallback.svg", provider: false, title: "Zeta" },
      ],
    });
    // Identifiers and slugs never reach the image or its description.
    expect(JSON.stringify(card)).not.toMatch(/30000000-|juego-\d/);
    expect(describeCard(card)).not.toMatch(/30000000-|juego-\d/);
  });

  it("leaves out unchosen blocks and blocks the profile does not share now", () => {
    const ratings = source.statistics?.ratings;
    const withoutWishlist: PublicShareSource = { ...source, sections: ["ratings"], statistics: ratings ? { ratings } : {} };
    const card = toProfileCard(withoutWishlist, { ...everything, scoring: false }, url);

    expect(card.games).not.toBeNull();
    expect(card.scoring).toBeNull();
    expect(card.wishlist).toBeNull();
    expect(cardSections(card)).toEqual(["ratings"]);
    expect(blockUnavailability(withoutWishlist, "wishlist")).toBe("Tu perfil no muestra tus deseados.");
  });

  it("explains empty or unshared sections instead of inventing content", () => {
    const empty: PublicShareSource = {
      alias: "nueva", sections: ["ratings", "wishlist"],
      statistics: { ratings: { total: 0, distribution: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0] }, wishlistCount: 0 },
      ratedGames: [], wishedGames: [],
    };
    expect(blockUnavailability(empty, "games")).toBe("Aún no has puntuado juegos.");
    expect(blockUnavailability(empty, "scoring")).toBe("Aún no has puntuado juegos.");
    expect(blockUnavailability(empty, "wishlist")).toBe("Aún no tienes juegos deseados.");
    expect(blockUnavailability({ ...empty, sections: ["wishlist"] }, "scoring")).toBe("Tu perfil no muestra tus puntuaciones.");

    const card = toProfileCard(empty, everything, "https://gameometro.com/u/nueva");
    expect([card.games, card.scoring, card.wishlist]).toEqual([null, null, null]);
    expect(cardSections(card)).toEqual([]);
    expect(describeCard(card)).toBe("Perfil de @nueva en Gameómetro, en formato publicación. gameometro.com/u/nueva.");
  });

  it("reads the latest ratings without a rank", () => {
    const card = toProfileCard(source, { ...everything, gamesOrder: "recent", format: "story" }, url);

    expect(card.games?.items.map((item) => item.rank)).toEqual([null, null, null]);
    expect(cardFileName(card)).toBe("gameometro-luna-8-historia.png");
    expect(describeCard(card)).toContain("Mis últimas notas: Alfa, 10 de 10, Ardiendo; Beta, 9 de 10, Ardiendo");
  });

  it("describes the image in words and names its provider credit", () => {
    const description = describeCard(toProfileCard(source, everything, url));

    expect(description).toBe(
      "Perfil de @luna-8 en Gameómetro, en formato publicación. "
      + "Mis notas más altas: 1.º Alfa, 10 de 10, Ardiendo; 2.º Beta, 9 de 10, Ardiendo; 2.º Gamma, 9 de 10, Ardiendo. "
      + "Así puntúo: 12 juegos puntuados, media de 7,4, Caliente; reparto: 1 con un 10, 2 con un 9, 2 con un 8, "
      + "3 con un 7, 2 con un 6, 1 con un 5, 1 con un 3. "
      + "Mis deseados: 9 juegos, entre ellos Épsilon, Zeta. gameometro.com/u/luna-8. Datos e imágenes: IGDB.",
    );
  });

  it("shares the profile address beside the image and names the file by alias and format only", () => {
    expect(profileLink("http://localhost:5173/u/luna-8")).toBe("localhost:5173/u/luna-8");
    expect(shareText({ url })).toBe("¡Mira mi perfil de jugador en Gameómetro!\nhttps://gameometro.com/u/luna-8");
    expect(cardFileName({ alias: "luna-8", format: "post" })).toBe("gameometro-luna-8-publicacion.png");
  });
});
