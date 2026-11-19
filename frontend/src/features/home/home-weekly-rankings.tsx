import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useId, useState } from "react";
import { Link } from "react-router-dom";

import { useGameLinkState } from "../../shared/navigation/use-game-link-state";
import { CatalogueCover } from "../../shared/ui/catalogue-cover";
import type { GameDetails } from "../game-details/game-details-api";
import { QuickRatePop } from "../rankings/quick-rate-pop";
import { QuickRateStamp } from "../rankings/quick-rate-stamp";
import { useListRatingResume } from "../rankings/use-list-rating-resume";
import { useListPersonalRatings } from "../rankings/use-ranking-personal-ratings";
import { PlatformRatingDialog } from "../ratings/platform-rating-dialog";
import { ineligibleReasons } from "../ratings/rating-eligibility";
import { useSession } from "../session/use-session";
import { HOME_ACTIVITY_KEY, HomeRankingError, homeRankingOptions, type HomeRankedGame, type HomeRankingKind } from "./home-activity-api";
import { HOME_PATH } from "./home-path";
import { HomeNotice, HomeSectionHead } from "./home-section";

const formatter = new Intl.NumberFormat("es-ES");
const labels = {
  votes: { title: "Más votados", singular: "voto", plural: "votos" },
  visits: { title: "Más visitados", singular: "visita", plural: "visitas" },
};

type RankedGame = HomeRankedGame & { position: number };
type Selection = { game: RankedGame; anchor: HTMLButtonElement; details?: GameDetails };
type PersonalOf = ReturnType<typeof useListPersonalRatings>;
type RankingQuery = ReturnType<typeof useWeeklyRanking>;

function useWeeklyRanking(kind: HomeRankingKind) {
  return useQuery(homeRankingOptions(kind));
}

function Ranking({
  kind,
  query,
  onOpen,
  personalOf,
}: {
  kind: HomeRankingKind;
  query: RankingQuery;
  onOpen: (selection: Selection) => void;
  personalOf: PersonalOf;
}) {
  const id = useId();
  const [expanded, setExpanded] = useState(false);
  const linkState = useGameLinkState();
  const label = labels[kind];
  const games = query.data ?? [];
  const failure = query.error instanceof HomeRankingError ? query.error : null;
  const notReady = failure?.code === "CATALOGUE_NOT_READY";

  function row(game: HomeRankedGame, position: number) {
    const path = `/games/${game.gameId}/${game.slug}`;
    return (
      <li className="home-activity-row" data-leading={position === 1} key={game.gameId} value={position}>
        {position === 1 && game.cover.kind === "provider" ? (
          <img alt="" aria-hidden="true" className="home-activity-art" loading="lazy" src={game.cover.url} />
        ) : null}
        <span aria-hidden="true" className="home-activity-place">{position}</span>
        <CatalogueCover caption={false} cover={game.cover} />
        <div className="home-activity-record">
          <Link state={linkState} to={path}>
            <span className="sr-only">Puesto {position}. </span>{game.title}
          </Link>
          <p>{formatter.format(game.count)} {game.count === 1 ? label.singular : label.plural}</p>
          {game.ratingEligibility.eligible ? null : (
            <p className="home-activity-ineligible">{ineligibleReasons[game.ratingEligibility.reason]}</p>
          )}
        </div>
        {game.ratingEligibility.eligible ? (
          <QuickRateStamp
            onOpen={({ currentTarget }) => onOpen({ game: { ...game, position }, anchor: currentTarget })}
            state={personalOf(game.gameId)}
            title={game.title}
            variant="row"
          />
        ) : null}
      </li>
    );
  }

  return (
    <section aria-labelledby={id} className="home-activity-list" data-kind={kind}>
      <header>
        <h3 id={id}>{label.title}</h3>
      </header>
      {query.isPending ? (
        <>
          <p className="sr-only" role="status">Cargando {label.title.toLocaleLowerCase("es-ES")}…</p>
          <div aria-hidden="true" className="home-activity-loading">
            <span className="skeleton skeleton-cover" />
            <span className="skeleton skeleton-title" />
          </div>
        </>
      ) : null}
      {query.isError && query.data === undefined ? (
        <HomeNotice
          actions={<button className="button" onClick={() => void query.refetch()} type="button">Reintentar</button>}
          correlationId={notReady ? null : (failure?.correlationId ?? null)}
          heading="h4"
          kicker={label.title}
          title={notReady ? "Los juegos todavía no están disponibles" : "No se pudo cargar el ranking"}
          tone={notReady ? "info" : "danger"}
        />
      ) : null}
      {query.isSuccess && games.length === 0 ? (
        <p>{kind === "votes" ? "Aún no hay votos en este periodo." : "Aún no hay visitas medidas en este periodo."}</p>
      ) : null}
      {games.length > 0 ? (
        <ol aria-label={label.title} className="home-activity-rows" role="list">
          {games.slice(0, 3).map((game, index) => row(game, index + 1))}
        </ol>
      ) : null}
      {games.length > 3 ? (
        <>
          <button
            aria-controls={`${id}-more`}
            aria-expanded={expanded}
            className="button home-activity-more"
            onClick={() => setExpanded(!expanded)}
            type="button"
          >
            {expanded ? "Mostrar menos" : `Ver los ${games.length}`}<span className="sr-only"> · {label.title}</span>
          </button>
          <ol
            aria-label={`${label.title}, puestos 4 a ${games.length}`}
            className="home-activity-rows home-activity-tail"
            data-expanded={expanded}
            id={`${id}-more`}
            role="list"
            start={4}
          >
            {games.slice(3).map((game, index) => row(game, index + 4))}
          </ol>
        </>
      ) : null}
    </section>
  );
}

export function HomeWeeklyRankings() {
  const votes = useWeeklyRanking("votes");
  const visits = useWeeklyRanking("visits");
  const queryClient = useQueryClient();
  const session = useSession();
  const csrfToken = session.data?.authenticated === true ? session.data.csrfToken : null;
  const allGames = [...(votes.data ?? []), ...(visits.data ?? [])];
  const ids = [...new Set(allGames.map((game) => game.gameId))].sort();
  const personalOf = useListPersonalRatings([ids], csrfToken !== null);
  const [selection, setSelection] = useState<Selection | null>(null);
  const resume = useListRatingResume({
    csrfToken,
    sessionPending: session.isPending,
    titleOf: (gameId) => allGames.find((game) => game.gameId === gameId)?.title ?? null,
    context: "home",
    onSaved: () => {
      void queryClient.invalidateQueries({ queryKey: HOME_ACTIVITY_KEY, refetchType: "none" });
    },
  });

  function close(current: Selection, changed: boolean) {
    setSelection(null);
    current.anchor.focus({ preventScroll: true });
    if (changed) {
      // Keep positions stable while the player is using a list.
      void queryClient.invalidateQueries({ queryKey: HOME_ACTIVITY_KEY, refetchType: "none" });
    }
  }

  const own = selection === null ? null : personalOf(selection.game.gameId);

  return (
    <section aria-labelledby="home-activity-title" className="home-activity">
      <HomeSectionHead id="home-activity-title" title="Pulso de la semana" readout="Últimos 7 días" />
      {resume.notice === null ? null : (
        <p className="quick-rate-notice" data-tone={resume.notice.tone} role="status">{resume.notice.text}</p>
      )}
      <div className="home-activity-grid">
        <Ranking kind="votes" onOpen={setSelection} personalOf={personalOf} query={votes} />
        <Ranking kind="visits" onOpen={setSelection} personalOf={personalOf} query={visits} />
      </div>
      {selection !== null && selection.details === undefined && own !== null ? (
        <QuickRatePop
          anchor={selection.anchor}
          context="home"
          csrfToken={csrfToken}
          game={selection.game}
          onClose={(changed) => close(selection, changed)}
          onPlatforms={(details) => setSelection({ ...selection, details })}
          returnTo={HOME_PATH}
          state={own}
        />
      ) : null}
      {selection?.details !== undefined ? (
        <PlatformRatingDialog
          csrfToken={csrfToken}
          game={selection.details}
          onClose={(saved) => close(selection, saved !== null)}
          rating={own?.status === "known" ? own.rating : null}
          returnTo={HOME_PATH}
        />
      ) : null}
      {resume.recovered === null ? null : (
        <PlatformRatingDialog
          csrfToken={csrfToken}
          game={resume.recovered.details}
          onClose={resume.clearRecovered}
          rating={resume.recovered.rating}
          recoveredPlatforms={resume.recovered.platforms}
          returnTo={HOME_PATH}
        />
      )}
    </section>
  );
}
