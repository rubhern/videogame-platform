import { thermalBand, thermalLabels, type ThermalBand } from "../../shared/score/thermal-band";
import { formatPersonalScore } from "../ratings/personal-score";
import type { getPublicProfileSection, getPublicProfileStatistics, PublicProfileSection } from "./public-profile-api";

/**
 * A public profile as a shareable image (#619). The card is built only from what an anonymous
 * visitor reads at `/u/{alias}` — the public profile, its statistics and its shared sections — so
 * it can never carry a field the player has not published. Each block belongs to one shared
 * section and appears only when the player chose it and the profile still offers it.
 */

export type ShareFormat = "post" | "story";

/** The two shapes a player can export: a 4:5 feed post and a 9:16 story. */
export const SHARE_FORMATS: Record<ShareFormat, { width: number; height: number; label: string; ratio: string }> = {
  post: { width: 1080, height: 1350, label: "Publicación", ratio: "4:5" },
  story: { width: 1080, height: 1920, label: "Historia", ratio: "9:16" },
};

/** Which rated games lead the image: the highest scores or the latest ratings. */
export type GamesOrder = "top" | "recent";

export type ShareChoices = {
  games: boolean;
  gamesOrder: GamesOrder;
  scoring: boolean;
  wishlist: boolean;
  format: ShareFormat;
};

export type ShareBlock = "games" | "scoring" | "wishlist";

type ProfileGame = Awaited<ReturnType<typeof getPublicProfileSection>>["items"][number];
type ProfileStatistics = Awaited<ReturnType<typeof getPublicProfileStatistics>>;

/** What the public profile currently offers, read exactly as a visitor reads it. */
export type PublicShareSource = {
  alias: string;
  sections: readonly PublicProfileSection[];
  statistics: ProfileStatistics | null;
  /** The first page of shared ratings in the chosen order. */
  ratedGames: readonly ProfileGame[] | null;
  /** The first page of Deseados in the player's saved order. */
  wishedGames: readonly ProfileGame[] | null;
};

export type CardArt = { url: string; provider: boolean };

export type CardGame = {
  title: string;
  score: number;
  band: ThermalBand;
  /** Competition rank among the highest scores (equal scores share it); null for latest ratings. */
  rank: number | null;
  cover: CardArt;
};

export type ProfileCard = {
  alias: string;
  format: ShareFormat;
  /** `gameometro.com/u/alias`, printed on the image so a recipient can open it. */
  link: string;
  /** The absolute profile address the share text carries. */
  url: string;
  games: { order: GamesOrder; items: CardGame[] } | null;
  scoring: { total: number; mean: number | null; band: ThermalBand | null; distribution: number[] } | null;
  wishlist: { total: number; covers: (CardArt & { title: string })[] } | null;
};

export const MAX_CARD_GAMES = 3;
/** Covers a wishlist row shows before the rest collapse into one `+N` tile. */
export const MAX_WISHLIST_COVERS = 6;
const FALLBACK_COVER = "/assets/covers/fallback.svg";

export const blockLabels: Record<ShareBlock, string> = {
  games: "Juegos puntuados",
  scoring: "Así puntúo",
  wishlist: "Mis deseados",
};

export const gamesOrderLabels: Record<GamesOrder, string> = {
  top: "Mis notas más altas",
  recent: "Mis últimas notas",
};

function toArt(game: ProfileGame): CardArt {
  const provider = "attribution" in game.primaryCover && game.primaryCover.attribution !== null;
  return { url: provider ? game.primaryCover.url : FALLBACK_COVER, provider };
}

/** Saved games that are still in the catalogue: a removed game has no title and stays out. */
function available(games: readonly ProfileGame[] | null): (ProfileGame & { canonicalTitle: string })[] {
  return (games ?? []).filter((game): game is ProfileGame & { canonicalTitle: string } =>
    typeof game.canonicalTitle === "string" && game.canonicalTitle.length > 0);
}

function toCardGames(games: readonly ProfileGame[] | null, order: GamesOrder): CardGame[] {
  const rated = available(games)
    .filter((game): game is ProfileGame & { canonicalTitle: string; score: number } => typeof game.score === "number")
    .slice(0, MAX_CARD_GAMES);
  return rated.flatMap((game) => {
    const band = thermalBand(game.score);
    if (band === null) return [];
    const rank = order === "top" ? 1 + rated.filter((other) => other.score > game.score).length : null;
    return [{ title: game.canonicalTitle, score: game.score, band, rank, cover: toArt(game) }];
  });
}

/**
 * Why a block cannot appear, or null when it can. A block needs its section to be shared now and
 * something in it to show; nothing is invented for an empty section.
 */
export function blockUnavailability(source: PublicShareSource, block: ShareBlock): string | null {
  const ratings = source.sections.includes("ratings");
  const wishlist = source.sections.includes("wishlist");
  if (block === "wishlist") {
    if (!wishlist) return "Tu perfil no muestra tus deseados.";
    return (source.statistics?.wishlistCount ?? 0) > 0 && available(source.wishedGames).length > 0
      ? null : "Aún no tienes juegos deseados.";
  }
  if (!ratings) return "Tu perfil no muestra tus puntuaciones.";
  if (block === "scoring") return (source.statistics?.ratings?.total ?? 0) > 0 ? null : "Aún no has puntuado juegos.";
  return toCardGames(source.ratedGames, "top").length > 0 ? null : "Aún no has puntuado juegos.";
}

/** The visible profile address without its scheme: `gameometro.com/u/alias`. */
export function profileLink(url: string): string {
  const parsed = new URL(url);
  return `${parsed.host}${parsed.pathname}`;
}

/** The card the player previews and exports, from the public source and the chosen blocks only. */
export function toProfileCard(source: PublicShareSource, choices: ShareChoices, url: string): ProfileCard {
  const wants = (block: ShareBlock) => choices[block] && blockUnavailability(source, block) === null;
  const ratings = source.statistics?.ratings;
  const wished = available(source.wishedGames);
  return {
    alias: source.alias,
    format: choices.format,
    link: profileLink(url),
    url,
    games: wants("games") ? { order: choices.gamesOrder, items: toCardGames(source.ratedGames, choices.gamesOrder) } : null,
    scoring: wants("scoring") && ratings ? {
      total: ratings.total,
      mean: ratings.mean ?? null,
      band: ratings.mean === undefined ? null : thermalBand(ratings.mean),
      distribution: [...ratings.distribution],
    } : null,
    wishlist: wants("wishlist") ? {
      total: source.statistics?.wishlistCount ?? wished.length,
      covers: wished.slice(0, MAX_WISHLIST_COVERS).map((game) => ({ ...toArt(game), title: game.canonicalTitle })),
    } : null,
  };
}

/** The public sections a card reads, which must still be shared when the image is generated. */
export function cardSections(card: ProfileCard): PublicProfileSection[] {
  const sections: PublicProfileSection[] = [];
  if (card.games !== null || card.scoring !== null) sections.push("ratings");
  if (card.wishlist !== null) sections.push("wishlist");
  return sections;
}

const count = new Intl.NumberFormat("es-ES");

function games(total: number) {
  return total === 1 ? "1 juego" : `${count.format(total)} juegos`;
}

/** The image's content in words, which the drawn pixels cannot carry for assistive technology. */
export function describeCard(card: ProfileCard): string {
  const parts = [`Perfil de @${card.alias} en Gameómetro, en formato ${SHARE_FORMATS[card.format].label.toLowerCase()}`];
  if (card.games !== null) {
    const items = card.games.items.map((game) =>
      `${game.rank === null ? "" : `${game.rank}.º `}${game.title}, ${formatPersonalScore(game.score)} de 10, ${thermalLabels[game.band]}`);
    parts.push(`${gamesOrderLabels[card.games.order]}: ${items.join("; ")}`);
  }
  if (card.scoring !== null) {
    const { total, mean, band, distribution } = card.scoring;
    const spread = distribution
      .map((bucket, index) => [index + 1, bucket] as const)
      .filter(([, bucket]) => bucket > 0)
      .reverse()
      .map(([score, bucket]) => `${count.format(bucket)} con un ${score}`);
    parts.push(`Así puntúo: ${games(total)} puntuados${mean === null || band === null
      ? "" : `, media de ${formatPersonalScore(mean)}, ${thermalLabels[band]}`}; reparto: ${spread.join(", ")}`);
  }
  if (card.wishlist !== null) {
    parts.push(`Mis deseados: ${games(card.wishlist.total)}, entre ellos ${card.wishlist.covers.map((cover) => cover.title).join(", ")}`);
  }
  parts.push(card.link);
  if (hasProviderArt(card)) parts.push("Datos e imágenes: IGDB");
  return `${parts.join(". ")}.`;
}

/** Whether the image draws a provider cover, which it then credits. */
export function hasProviderArt(card: ProfileCard): boolean {
  return (card.games?.items.some((game) => game.cover.provider) ?? false)
    || (card.wishlist?.covers.some((cover) => cover.provider) ?? false);
}

export const SHARE_MESSAGE = "¡Mira mi perfil de jugador en Gameómetro!";

/** The text that travels with the image, so a recipient can open the profile. */
export function shareText(card: Pick<ProfileCard, "url">): string {
  return `${SHARE_MESSAGE}\n${card.url}`;
}

/** `gameometro-luna-8-publicacion.png`: the public alias and the format, nothing else. */
export function cardFileName(card: Pick<ProfileCard, "alias" | "format">): string {
  return `gameometro-${card.alias}-${card.format === "story" ? "historia" : "publicacion"}.png`;
}
