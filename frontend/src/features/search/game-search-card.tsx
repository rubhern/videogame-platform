import { Link } from "react-router-dom";

import { GameGenres } from "../../shared/catalogue/game-genres";
import { PlatformMarks } from "../../shared/catalogue/platform-marks";
import { CatalogueCover } from "../../shared/ui/catalogue-cover";
import { DiscoveryScores } from "../../shared/score/discovery-scores";
import { hiddenPlatformsLabel, type GameSearchResult } from "./game-search-view-model";

type GameSearchCardProps = {
  result: GameSearchResult;
};

/**
 * One entry of the catalogue index (#188): the cover beside its record — the known year or range,
 * the title as the only keyboard stop besides the score attribution, an explaining alias, the
 * discovery readings (#279), up to two genres and the compact platform summary. Its height never
 * depends on how many releases the game has; the game page owns the release context and the
 * cover attribution.
 */
export function GameSearchCard({ result }: GameSearchCardProps) {
  const gamePath = `/games/${result.gameId}/${result.slug}`;
  const { platforms, hiddenPlatformCount } = result;

  return (
    <article className="catalogue-card search-card">
      <CatalogueCover caption={false} cover={result.cover} to={gamePath} />
      <div className="search-card-record">
        <p className="search-card-year">
          <span className="sr-only">Lanzamiento: </span>
          {result.year}
        </p>
        <h3 className="card-title">
          <Link to={gamePath}>{result.title}</Link>
        </h3>
        {result.matchedAlias === null ? null : (
          <p className="search-card-alias">
            Coincidencia: <span>{result.matchedAlias}</span>
          </p>
        )}
        <DiscoveryScores scores={result.scores} title={result.title} />
        <GameGenres genres={result.genres} />
        {platforms.length === 0 ? null : (
          <PlatformMarks
            label={`Plataformas de ${result.title}`}
            marks={platforms.map((platform) => ({ key: platform.id, ...platform }))}
            more={{ count: hiddenPlatformCount, label: hiddenPlatformsLabel(hiddenPlatformCount) }}
          />
        )}
      </div>
    </article>
  );
}
