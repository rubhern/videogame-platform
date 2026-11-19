import type { GameDetails } from "./game-details-api";
import { gameStructuredData } from "./game-structured-data";

/**
 * The game's structured data for search engines that run JavaScript. The JSON is a text child,
 * never parsed as markup, and `<` is escaped so the payload can never close its own element.
 */
export function GameStructuredData({ game }: { game: GameDetails }) {
  const json = JSON.stringify(gameStructuredData(game, window.location.origin)).replace(
    /</g,
    "\\u003c",
  );
  return <script type="application/ld+json">{json}</script>;
}
