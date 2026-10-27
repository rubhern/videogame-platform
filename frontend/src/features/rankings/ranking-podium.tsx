import { Link } from "react-router-dom";

import { PlatformMarks } from "../../shared/catalogue/platform-marks";
import { useGameLinkState } from "../../shared/navigation/use-game-link-state";
import { CatalogueCover } from "../../shared/ui/catalogue-cover";
import { hiddenPlatformsLabel } from "../search/game-search-view-model";
import type { RankedGame } from "./game-ranking-view-model";
import { RankingReading } from "./ranking-reading";

/**
 * Places 1–3 of the ranked sequence on one stage. Reading and focus order run 1, 2, 3; wide
 * screens set the winner between its two followers. Places are told apart by height, scale and
 * how strongly their step catches the light, never by a colour that could read as a score.
 */
export function RankingPodium({ games }: { games: readonly RankedGame[] }) {
  const linkState = useGameLinkState();
  return (
    <ol aria-label="Podio" className="ranking-podium" data-places={games.length}>
      {games.map((game) => {
        const gamePath = `/games/${game.gameId}/${game.slug}`;
        return (
          <li className="podium-place" data-place={game.position} key={game.gameId} value={game.position}>
            <article className="catalogue-card podium-card">
              <CatalogueCover caption={false} cover={game.cover} linkState={linkState} to={gamePath} />
              <div className="podium-record">
                <h3 className="card-title podium-title">
                  <Link state={linkState} to={gamePath}>
                    <span className="sr-only">{`Puesto ${game.position}:`}</span>{" "}
                    {game.title}
                  </Link>
                </h3>
                <div className="podium-meta">
                  <p className="podium-year">
                    <span className="sr-only">Lanzamiento: </span>
                    {game.year}
                  </p>
                  {game.platforms.length === 0 ? null : (
                    <PlatformMarks
                      label={`Plataformas de ${game.title}`}
                      marks={game.platforms.map((platform) => ({ key: platform.id, ...platform }))}
                      more={{ count: game.hiddenPlatformCount, label: hiddenPlatformsLabel(game.hiddenPlatformCount) }}
                    />
                  )}
                </div>
                <RankingReading reading={game.reading} title={game.title} variant="podium" />
              </div>
              <div aria-hidden="true" className="podium-step">
                <span className="podium-numeral">{game.position}</span>
              </div>
            </article>
          </li>
        );
      })}
    </ol>
  );
}
