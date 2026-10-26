import { Link } from "react-router-dom";

import { GameGenres } from "../../shared/catalogue/game-genres";
import { PlatformMarks } from "../../shared/catalogue/platform-marks";
import { CatalogueCover } from "../../shared/ui/catalogue-cover";
import { thermalBand } from "../../shared/score/thermal-band";
import { hiddenPlatformsLabel } from "../search/game-search-view-model";
import type { RankedGame } from "./game-ranking-view-model";
import { RankingReading } from "./ranking-reading";

/**
 * One place from the fourth on, as a ledger line: the place, the cover, the record and the reading
 * that placed it. Like `Mis puntuaciones`, a Gameómetro line's edge takes its score's temperature,
 * so the column reads as a thermometer; an IGDB line keeps a neutral edge.
 */
export function RankingRow({
  game,
  linkState,
  titleRef,
}: {
  game: RankedGame;
  linkState: unknown;
  /** The first line of a batch loaded on request receives focus. */
  titleRef?: (element: HTMLAnchorElement | null) => void;
}) {
  const gamePath = `/games/${game.gameId}/${game.slug}`;
  const band = game.reading.source === "gameometro" ? thermalBand(game.reading.value) : null;
  return (
    <article className="catalogue-card ranking-row" data-thermal={band ?? undefined}>
      <p aria-hidden="true" className="ranking-position" data-digits={String(game.position).length}>
        {game.position}
      </p>
      <CatalogueCover caption={false} cover={game.cover} linkState={linkState} to={gamePath} />
      <div className="ranking-record">
        <h3 className="card-title ranking-title">
          <Link ref={titleRef} state={linkState} to={gamePath}>
            <span className="sr-only">{`Puesto ${game.position}:`}</span>{" "}
            {game.title}
          </Link>
        </h3>
        <div className="ranking-facts">
          <p className="ranking-year">
            <span className="sr-only">Lanzamiento: </span>
            {game.year}
          </p>
          <GameGenres genres={game.genres} />
        </div>
      </div>
      {game.platforms.length === 0 ? null : (
        <PlatformMarks
          label={`Plataformas de ${game.title}`}
          marks={game.platforms.map((platform) => ({ key: platform.id, ...platform }))}
          more={{ count: game.hiddenPlatformCount, label: hiddenPlatformsLabel(game.hiddenPlatformCount) }}
        />
      )}
      <RankingReading reading={game.reading} title={game.title} variant="row" />
    </article>
  );
}
