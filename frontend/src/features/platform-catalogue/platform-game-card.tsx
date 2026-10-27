import { Link } from "react-router-dom";

import { NeedleIcon } from "../../shared/brand/needle-icon";
import { GameGenres } from "../../shared/catalogue/game-genres";
import { PlatformMarks } from "../../shared/catalogue/platform-marks";
import { platformIcon } from "../../shared/catalogue/taxonomy-icons";
import { useGameLinkState } from "../../shared/navigation/use-game-link-state";
import { DiscoveryScores } from "../../shared/score/discovery-scores";
import { CatalogueCover } from "../../shared/ui/catalogue-cover";
import type { PlatformGameState, PlatformGameView } from "./platform-catalogue-view-model";

/** The short state beside the date; a game that is out needs none. */
const stateTags: Record<PlatformGameState, string | null> = {
  released: null,
  upcoming: "Próximo",
  undated: "Próximo",
  delayed: "Retrasado",
};

/** The presented release read in full, so the date line can stay visual. */
function releaseSentence(game: PlatformGameView): string {
  switch (game.state) {
    case "released":
      return `Lanzado: ${game.date}, ${game.region}`;
    case "upcoming":
      return `Próximo lanzamiento: ${game.date}, ${game.region}`;
    case "undated":
      return `Próximo lanzamiento, ${game.date.toLocaleLowerCase("es")}`;
    case "delayed":
      return `Retrasado; fecha anterior: ${game.date}, ${game.region}`;
  }
}

type PlatformGameCardProps = {
  game: PlatformGameView;
  /** A family's games may come from different platforms of it; one platform needs no mark. */
  showPlatform: boolean;
};

/**
 * One game of the platform: the cover leads, then the date of its release on the platform under
 * the fire needle with its state when it is not out yet, the title as the card's only keyboard
 * stop, an explaining alias, the score readings, the release's region and up to two genres.
 */
export function PlatformGameCard({ game, showPlatform }: PlatformGameCardProps) {
  const gamePath = `/games/${game.gameId}/${game.slug}`;
  const linkState = useGameLinkState();
  const tag = stateTags[game.state];

  return (
    <article className="catalogue-card platform-game-card" data-state={game.state}>
      <CatalogueCover caption={false} cover={game.cover} linkState={linkState} to={gamePath} />
      <p aria-hidden="true" className="card-date" title={game.date}>
        <NeedleIcon className="card-date-needle" />
        <span className="card-date-value">{game.shortDate}</span>
        {tag === null ? null : <span className="platform-game-state">{tag}</span>}
      </p>
      <h3 className="card-title">
        <Link state={linkState} to={gamePath}>
          {game.title}
        </Link>
      </h3>
      {game.matchedAlias === null ? null : (
        <p className="card-alias">
          Coincidencia: <span>{game.matchedAlias}</span>
        </p>
      )}
      <DiscoveryScores scores={game.scores} title={game.title} />
      <div className="card-release">
        <span className="sr-only">{releaseSentence(game)}</span>
        {showPlatform ? (
          <PlatformMarks
            label={`Plataforma de ${game.title}`}
            marks={[
              {
                key: game.platform.id,
                name: game.platform.name,
                icon: platformIcon(game.platform.id, game.platform.name),
              },
            ]}
          />
        ) : null}
        <span aria-hidden="true" className="card-region">{game.region}</span>
      </div>
      <GameGenres genres={game.genres} />
    </article>
  );
}
