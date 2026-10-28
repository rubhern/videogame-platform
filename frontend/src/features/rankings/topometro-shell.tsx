import { useEffect, useRef } from "react";
import { Link } from "react-router-dom";
import { PlatformNavigation } from "../platforms/platform-navigation";

import { TopometroLogo } from "../../shared/brand/brand-logo";
import { useGameLinkState } from "../../shared/navigation/use-game-link-state";
import { StageLight } from "../../shared/ui/stage-light";
import { type PlatformContext } from "../platforms/platform-context";
import type { RankingView } from "./game-ranking-view-model";
import { RankingPodium } from "./ranking-podium";
import { RankingRow } from "./ranking-row";
import { rankingPath, type RankingSource } from "./ranking-search";
import { RankingSourceSwitch } from "./ranking-source-switch";

export type TopometroState =
  | { status: "loading" }
  | { status: "catalogue-not-ready" }
  | { status: "error"; correlationId: string | null }
  | {
      status: "ready";
      view: RankingView;
      /** The places the next page holds, or null once the last page is loaded. */
      next: { from: number; to: number } | null;
      loadingMore: boolean;
      /** A further page failed; the loaded places stay and the request can be retried. */
      moreFailed: { correlationId: string | null } | null;
    };

/** A platform's TOPómetro (#387): its context and the other platforms on offer to switch to. */
export type TopometroPlatform = {
  context: PlatformContext;
  /** The other platforms the directory holds games for; null while it is read or unavailable. */
  others: readonly PlatformContext[] | null;
};

type TopometroShellProps = {
  source: RankingSource;
  /** Absent for the global TOPómetro. */
  platform?: TopometroPlatform | undefined;
  state: TopometroState;
  onRetry: () => void;
  onLoadMore: () => void;
};

const count = new Intl.NumberFormat("es-ES", { useGrouping: "always" });

function EmptyRanking({ source, platform }: { source: RankingSource; platform: PlatformContext | undefined }) {
  if (platform !== undefined) return <EmptyPlatformRanking platform={platform} source={source} />;
  return source === "gameometro" ? (
    <div className="notice notice-empty ranking-notice">
      <p className="notice-kicker">TOPómetro de la comunidad</p>
      <h2>Aún no hay ningún juego puntuado</h2>
      <p>
        La comunidad todavía no ha puntuado ningún juego lanzado. Puntúa los que conoces desde su
        ficha y estrena este TOPómetro.
      </p>
      <Link className="text-link" preventScrollReset to={rankingPath("igdb")}>
        Ver el TOPómetro de Jugadores IGDB
      </Link>
    </div>
  ) : (
    <div className="notice notice-empty ranking-notice">
      <p className="notice-kicker">TOPómetro de Jugadores IGDB</p>
      <h2>Aún no hay juegos con valoraciones de IGDB</h2>
      <p>Ningún juego lanzado tiene todavía una valoración de jugadores en IGDB.</p>
      <Link className="text-link" preventScrollReset to={rankingPath("gameometro")}>
        Ver el TOPómetro de la comunidad
      </Link>
    </div>
  );
}

/**
 * No game out on the platform holds a score from the source yet. The other source of the same
 * platform and the global TOPómetro remain one step away; nothing else fills the podium.
 */
function EmptyPlatformRanking({ source, platform }: { source: RankingSource; platform: PlatformContext }) {
  const other: RankingSource = source === "gameometro" ? "igdb" : "gameometro";
  return (
    <div className="notice notice-empty ranking-notice">
      <p className="notice-kicker">
        {source === "gameometro" ? "TOPómetro de la comunidad" : "TOPómetro de Jugadores IGDB"}
      </p>
      <h2>
        {source === "gameometro"
          ? `Aún no hay juegos de ${platform.name} puntuados`
          : `Aún no hay juegos de ${platform.name} con valoraciones de IGDB`}
      </h2>
      <p>
        {source === "gameometro"
          ? `La comunidad todavía no ha puntuado ningún juego lanzado en ${platform.name}.`
          : `Ningún juego lanzado en ${platform.name} tiene todavía suficientes valoraciones de jugadores en IGDB.`}
      </p>
      <div className="ranking-notice-links">
        <Link className="text-link" preventScrollReset to={rankingPath(other, platform.key)}>
          {other === "gameometro" ? "Ver la Comunidad Gameómetro" : "Ver Jugadores IGDB"} en {platform.name}
        </Link>
        <Link className="text-link" to={rankingPath(source)}>
          Ver el TOPómetro de todas las plataformas
        </Link>
      </div>
    </div>
  );
}


function RankingLoading() {
  return (
    <div className="ranking-loading">
      <p className="sr-only" role="status">
        Cargando el TOPómetro…
      </p>
      <div aria-hidden="true" className="ranking-podium ranking-podium-loading" data-places={3}>
        {[1, 2, 3].map((place) => (
          <div className="podium-place" data-place={place} key={place}>
            <div className="podium-card">
              <div className="skeleton skeleton-cover" />
              <div className="podium-record">
                <div className="skeleton skeleton-title" />
                <div className="skeleton skeleton-meta skeleton-meta-short" />
              </div>
              <div className="podium-step" />
            </div>
          </div>
        ))}
      </div>
      <RowPlaceholders count={4} />
    </div>
  );
}

function RowPlaceholders({ count: rows }: { count: number }) {
  return (
    <div aria-hidden="true" className="ranking-placeholders">
      {Array.from({ length: rows }, (_, index) => (
        <div className="ranking-row ranking-row-loading" key={index}>
          <div className="skeleton ranking-position-skeleton" />
          <div className="skeleton skeleton-cover" />
          <div className="ranking-record">
            <div className="skeleton skeleton-title" />
            <div className="skeleton skeleton-meta" />
          </div>
        </div>
      ))}
    </div>
  );
}

/**
 * TOPómetro (#386): the best released games by one source of player ratings. The podium and the
 * ledger below are one ranked sequence read page by page; only **Cargar más juegos** reads the
 * next page, never scrolling, and focus moves to the first game of the batch it brought.
 *
 * A platform's TOPómetro shares its contextual navigation with catalogue and upcoming highlights.
 * Its source and further pages stay scoped to the platform.
 */
export function TopometroShell({ source, platform, state, onRetry, onLoadMore }: TopometroShellProps) {
  const linkState = useGameLinkState();
  const rowLinks = useRef<(HTMLAnchorElement | null)[]>([]);
  // The index of the first line of a batch the visitor asked for, focused once it arrives.
  const focusFrom = useRef<number | null>(null);

  const ready = state.status === "ready" ? state : null;
  const rows = ready?.view.rows ?? [];

  useEffect(() => {
    const first = focusFrom.current;
    if (first === null || rows.length <= first) return;
    focusFrom.current = null;
    rowLinks.current[first]?.focus();
  }, [rows.length]);

  // The stage is lit by the podium's own covers, never by a placeholder.
  const lights =
    ready === null
      ? []
      : ready.view.podium.flatMap((game) => (game.cover.kind === "fallback" ? [] : [game.cover.url]));

  return (
    <section
      aria-labelledby="topometro-title"
      className="hero-page topometro-page"
      data-scope={platform === undefined ? undefined : "platform"}
      data-source={source}
    >
      <StageLight images={lights} />
      <div className="page-container topometro-section">
        {platform === undefined ? (
          <header className="topometro-head">
            <h1 className="topometro-title" id="topometro-title">
              <span className="sr-only">TOPómetro</span>
              <TopometroLogo className="topometro-logo" />
            </h1>
            <RankingSourceSwitch source={source} />
          </header>
        ) : (
          <header className="topometro-head topometro-head-platform platform-identity-box" data-logo={platform.context.logo}>
            <PlatformNavigation context={platform.context} active="topometro" others={platform.others}
              pathForPlatform={(context) => rankingPath(source, context.key)} />
            <h1 className="topometro-title topometro-identity" id="topometro-title">
              <span className="sr-only">{`TOPómetro de ${platform.context.name}`}</span>
              <TopometroLogo className="topometro-logo" />
            </h1>
            <RankingSourceSwitch platformKey={platform.context.key} source={source} />
          </header>
        )}

        {state.status === "loading" ? <RankingLoading /> : null}

        {state.status === "catalogue-not-ready" ? (
          <div className="notice notice-info ranking-notice" role="alert">
            <p className="notice-kicker">Datos en preparación</p>
            <h2>El TOPómetro todavía no está disponible</h2>
            <p>Aún no tenemos datos válidos de juegos, pero tranquilo que llegarán</p>
            <button className="button button-primary" onClick={onRetry} type="button">
              Reintentar
            </button>
          </div>
        ) : null}

        {state.status === "error" ? (
          <div className="notice notice-danger ranking-notice" role="alert">
            <p className="notice-kicker">Error de carga</p>
            <h2>No se pudo cargar el TOPómetro</h2>
            <p>A Gameómetro le está costando un poco. Prueba más tarde</p>
            <button className="button button-danger" onClick={onRetry} type="button">
              Reintentar
            </button>
            {state.correlationId === null ? null : (
              <p className="notice-reference">Referencia para soporte: {state.correlationId}</p>
            )}
          </div>
        ) : null}

        {ready !== null && ready.view.loaded === 0 ? (
          <EmptyRanking platform={platform?.context} source={source} />
        ) : null}

        {ready !== null && ready.view.loaded > 0 ? (
          <>
            <h2 className="sr-only">Podio</h2>
            <RankingPodium games={ready.view.podium} />

            {rows.length === 0 ? null : (
              <div className="ranking-ledger">
                <h2 className="sr-only">Clasificación</h2>
                <ol aria-label="Clasificación desde el cuarto puesto" className="ranking-list">
                  {rows.map((game, index) => (
                    <li key={game.gameId} value={game.position}>
                      <RankingRow
                        game={game}
                        linkState={linkState}
                        titleRef={(element) => {
                          rowLinks.current[index] = element;
                        }}
                      />
                    </li>
                  ))}
                </ol>
              </div>
            )}

            {ready.loadingMore ? <RowPlaceholders count={3} /> : null}

            <div className="ranking-more">
              <p className="sr-only" role="status">
                {ready.loadingMore
                  ? "Cargando más juegos…"
                  : `Mostrando ${count.format(ready.view.loaded)} de ${count.format(ready.view.total)} ${
                      ready.view.total === 1 ? "juego" : "juegos"
                    }`}
              </p>
              {ready.moreFailed === null ? null : (
                <div className="notice notice-danger ranking-notice" role="alert">
                  <p className="notice-kicker">Error de carga</p>
                  <h3>No se pudieron cargar más juegos</h3>
                  <p>Los puestos que ya ves siguen siendo válidos.</p>
                  <button className="button button-danger" onClick={onLoadMore} type="button">
                    Reintentar
                  </button>
                  {ready.moreFailed.correlationId === null ? null : (
                    <p className="notice-reference">Referencia para soporte: {ready.moreFailed.correlationId}</p>
                  )}
                </div>
              )}
              {ready.next !== null && ready.moreFailed === null ? (
                <button
                  className="ranking-load-more"
                  disabled={ready.loadingMore}
                  onClick={() => {
                    focusFrom.current = rows.length;
                    onLoadMore();
                  }}
                  type="button"
                >
                  <span className="ranking-load-more-label">Cargar más juegos</span>
                  <span aria-hidden="true" className="ranking-load-more-places">
                    {ready.next.from === ready.next.to
                      ? `Puesto ${count.format(ready.next.from)}`
                      : `Puestos ${count.format(ready.next.from)}–${count.format(ready.next.to)}`}
                    <span className="ranking-load-more-chevron" />
                  </span>
                </button>
              ) : null}
              {ready.next === null ? <p className="ranking-end">Has llegado al final del TOPómetro</p> : null}
            </div>
          </>
        ) : null}
      </div>
    </section>
  );
}
