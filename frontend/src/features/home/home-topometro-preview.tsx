import { useState } from "react";
import { Link } from "react-router-dom";

import { BrandMark, TopometroLogo } from "../../shared/brand/brand-logo";
import { PlatformMarks } from "../../shared/catalogue/platform-marks";
import { useGameLinkState } from "../../shared/navigation/use-game-link-state";
import { CatalogueCover } from "../../shared/ui/catalogue-cover";
import { hiddenPlatformsLabel } from "../search/game-search-view-model";
import { GameRankingApiError } from "../rankings/game-ranking-api";
import { toRankingView, type RankedGame } from "../rankings/game-ranking-view-model";
import { RankingReading } from "../rankings/ranking-reading";
import { DEFAULT_RANKING_SOURCE, rankingPath, type RankingSource } from "../rankings/ranking-search";
import { useGameRankingQuery } from "../rankings/use-game-ranking-query";
import { HomeNotice, HomeSectionHead } from "./home-section";

const SOURCES: readonly { source: RankingSource; label: string }[] = [
  { source: "igdb", label: "Jugadores IGDB" },
  { source: "gameometro", label: "Comunidad Gameómetro" },
];

function PodiumLine({ game }: { game: RankedGame }) {
  const linkState = useGameLinkState();
  const gamePath = `/games/${game.gameId}/${game.slug}`;
  return (
    <article className="catalogue-card home-rank" data-place={game.position}>
      <p aria-hidden="true" className="home-rank-place">
        {game.position}
      </p>
      <CatalogueCover caption={false} cover={game.cover} linkState={linkState} to={gamePath} />
      <div className="home-rank-record">
        <h3 className="card-title home-rank-title">
          <Link state={linkState} to={gamePath}>
            <span className="sr-only">{`Puesto ${game.position}:`}</span> {game.title}
          </Link>
        </h3>
        <div className="home-rank-facts">
          <p className="home-rank-year">
            <span className="sr-only">{game.yearLabel}</span>
            <span aria-hidden="true">{game.year}</span>
          </p>
          {game.platforms.length === 0 ? null : (
            <PlatformMarks
              label={`Plataformas de ${game.title}`}
              marks={game.platforms.map((platform) => ({ key: platform.id, ...platform }))}
              more={{ count: game.hiddenPlatformCount, label: hiddenPlatformsLabel(game.hiddenPlatformCount) }}
            />
          )}
        </div>
      </div>
      <RankingReading reading={game.reading} title={game.title} variant="row" />
    </article>
  );
}

export function PodiumLoading() {
  return (
    <ol aria-hidden="true" className="home-podium">
      {[1, 2, 3].map((place) => (
        <li key={place}>
          <div className="home-rank home-rank-pending" data-place={place}>
            <span className="home-rank-place">{place}</span>
            <span className="skeleton skeleton-cover" />
            <div className="home-rank-record">
              <span className="skeleton skeleton-title" />
              <span className="skeleton skeleton-meta skeleton-meta-short" />
            </div>
            <span className="skeleton home-rank-reading-skeleton" />
          </div>
        </li>
      ))}
    </ol>
  );
}

/**
 * TOPómetro in brief (#382, #386): the podium of one source of player ratings at a time, Jugadores
 * IGDB first as on TOPómetro, each place with its own source's value, scale and exact count. The two
 * sources never mix, and Hype never enters. It reads TOPómetro's own first page, so the full ranking
 * opens on the same places; the choice of source stays in the preview and the ranking stays public.
 */
export function HomeTopometroPreview() {
  const [source, setSource] = useState<RankingSource>(DEFAULT_RANKING_SOURCE);
  const query = useGameRankingQuery(source);
  const other: RankingSource = source === "igdb" ? "gameometro" : "igdb";

  let body;
  if (query.isPending) {
    body = <PodiumLoading />;
  } else if (query.isError) {
    const error = query.error instanceof GameRankingApiError ? query.error : null;
    body =
      error?.code === "CATALOGUE_NOT_READY" ? (
        <HomeNotice
          actions={
            <button className="button" onClick={() => void query.refetch()} type="button">
              Reintentar
            </button>
          }
          kicker="Datos en preparación"
          title="Los juegos todavía no están disponibles"
          tone="info"
        />
      ) : (
        <HomeNotice
          actions={
            <button className="button button-danger" onClick={() => void query.refetch()} type="button">
              Reintentar
            </button>
          }
          correlationId={error?.correlationId ?? null}
          kicker="Error de carga"
          title="El TOPómetro no ha podido cargarse"
          tone="danger"
        />
      );
  } else {
    const podium = toRankingView(query.data.pages, source).podium;
    body =
      podium.length === 0 ? (
        <HomeNotice
          actions={
            <button className="button" onClick={() => setSource(other)} type="button">
              {other === "igdb" ? "Ver Jugadores IGDB" : "Ver la Comunidad Gameómetro"}
            </button>
          }
          kicker={source === "gameometro" ? "TOPómetro de la comunidad" : "TOPómetro de Jugadores IGDB"}
          title={
            source === "gameometro"
              ? "Aún no hay ningún juego puntuado"
              : "Aún no hay juegos con valoraciones de IGDB"
          }
          tone="empty"
        >
          <p>
            {source === "gameometro"
              ? "La comunidad todavía no ha puntuado ningún juego lanzado. Puntúa los que conoces desde su ficha."
              : "Ningún juego lanzado tiene todavía una valoración de jugadores en IGDB."}
          </p>
        </HomeNotice>
      ) : (
        <ol aria-label={`Podio de ${SOURCES.find((entry) => entry.source === source)?.label ?? ""}`} className="home-podium">
          {podium.map((game) => (
            <li key={game.gameId} value={game.position}>
              <PodiumLine game={game} />
            </li>
          ))}
        </ol>
      );
  }

  return (
    <section aria-labelledby="home-topometro-title" className="home-topometro">
      <HomeSectionHead
        action={{ label: "Ver TOPómetro", to: rankingPath(source) }}
        id="home-topometro-title"
        title={
          <>
            <span className="sr-only">TOPómetro</span>
            <TopometroLogo className="home-topometro-lockup" />
          </>
        }
      />
      {/* Whose ratings rank the games: one source at a time, each with its own mark. */}
      <div aria-label="Puntuaciones del TOPómetro" className="home-sources" role="group">
        {SOURCES.map((entry) => (
          <button
            aria-pressed={entry.source === source}
            className="home-source"
            data-source={entry.source}
            key={entry.source}
            onClick={() => setSource(entry.source)}
            type="button"
          >
            {entry.source === "igdb" ? (
              <img alt="" className="home-source-mark" height="22" src="/assets/providers/igdb.svg" width="44" />
            ) : (
              <BrandMark className="home-source-mark" />
            )}
            <span>{entry.label}</span>
          </button>
        ))}
      </div>
      {body}
    </section>
  );
}
