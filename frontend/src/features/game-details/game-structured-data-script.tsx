import { StructuredDataScript } from "../../shared/seo/structured-data-script";
import type { GameDetails } from "./game-details-api";
import { gameStructuredData } from "./game-structured-data";

export function GameStructuredData({ game }: { game: GameDetails }) {
  return <StructuredDataScript data={gameStructuredData(game, window.location.origin)} />;
}
