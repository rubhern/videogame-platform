import { Link } from "react-router-dom";

import { CatalogueCover } from "../../shared/ui/catalogue-cover";
import { SelectIcon } from "../../shared/ui/select-icon";
import { hiddenPlatformsLabel, type GameSearchResult } from "./game-search-view-model";

type GameSearchCardProps = {
  result: GameSearchResult;
};

/**
 * A bounded search result (#188): cover, title, an explaining alias, the year context and the
 * compact platform summary. Its height never depends on how many releases the game has; the
 * game page owns the complete release context and the cover attribution.
 */
export function GameSearchCard({ result }: GameSearchCardProps) {
  const gamePath = `/games/${result.gameId}/${result.slug}`;
  const { platforms, hiddenPlatformCount } = result;

  return (
    <article className="catalogue-card search-card">
      <CatalogueCover caption={false} cover={result.cover} to={gamePath} />
      <div className="card-body">
        <h3 className="card-title">
          <Link to={gamePath}>{result.title}</Link>
        </h3>
        {result.matchedAlias === null ? null : (
          <p className="search-card-alias">
            Coincidencia: <span>{result.matchedAlias}</span>
          </p>
        )}
        <p className="search-card-year">
          <span className="sr-only">Lanzamiento: </span>
          {result.year}
        </p>
        {platforms.length === 0 ? null : (
          <ul aria-label={`Plataformas de ${result.title}`} className="search-card-platforms">
            {platforms.map((platform) => (
              <li key={platform.id} title={platform.name}>
                <SelectIcon name={platform.icon} />
                <span className="sr-only">{platform.name}</span>
              </li>
            ))}
            {hiddenPlatformCount === 0 ? null : (
              <li className="search-card-platform-more" title={hiddenPlatformsLabel(hiddenPlatformCount)}>
                <span aria-hidden="true">+{hiddenPlatformCount}</span>
                <span className="sr-only">{hiddenPlatformsLabel(hiddenPlatformCount)}</span>
              </li>
            )}
          </ul>
        )}
      </div>
    </article>
  );
}
