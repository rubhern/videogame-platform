import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useRef } from "react";
import { Link, useSearchParams } from "react-router-dom";

import { GameStateButton } from "../features/library/game-state-button";
import {
  LIBRARY_KEY, LIBRARY_PAGE_SIZE, LIBRARY_STATES, LibraryError, libraryStateFromSlug, libraryStateLabels,
  libraryStateSlugs, listLibrary, type LibraryState,
} from "../features/library/library-api";
import { RatedCompletion } from "../features/library/rated-completion";
import { toCover } from "../features/releases/releases-view-model";
import { authenticationStartUrl } from "../features/session/auth-entry";
import { useSession } from "../features/session/use-session";
import { wishlistSessionScope } from "../features/wishlist/wishlist-api";
import { useDocumentTitle } from "../shared/browser/use-document-title";
import { CatalogueCover } from "../shared/ui/catalogue-cover";
import { HeroTitle } from "../shared/ui/hero-title";

const LIBRARY_PATH = "/mis-juegos";
const formatDate = (value: string) => new Intl.DateTimeFormat("es-ES", {
  day: "numeric", month: "long", year: "numeric", timeZone: "Europe/Madrid",
}).format(new Date(value));

function libraryHref(state: LibraryState | undefined, page = 1) {
  const params = new URLSearchParams();
  if (state) params.set("estado", libraryStateSlugs[state]);
  if (page > 1) params.set("page", String(page));
  const query = params.toString();
  return query ? `${LIBRARY_PATH}?${query}` : LIBRARY_PATH;
}

function countLabel(count: number) {
  return count === 1 ? "1 juego" : `${count} juegos`;
}

/** Mis juegos (UC-026): the player's private states, most recently changed first. */
export function MyLibraryPage() {
  useDocumentTitle("Mis juegos · Gameómetro");
  const [params] = useSearchParams();
  const state = libraryStateFromSlug(params.get("estado"));
  const requested = Number(params.get("page"));
  const page = Number.isInteger(requested) && requested >= 1 ? requested : 1;
  const results = useRef<HTMLDivElement>(null);
  const session = useSession({ refetchOnMount: false });
  const csrfToken = session.data?.authenticated === true && !session.isError ? session.data.csrfToken : null;
  const scope = wishlistSessionScope(session.data);
  const query = useQuery({
    queryKey: [...LIBRARY_KEY, "page", scope, state ?? "all", page],
    queryFn: ({ signal }) => listLibrary({ ...(state ? { state } : {}), page, pageSize: LIBRARY_PAGE_SIZE }, signal),
    enabled: csrfToken !== null,
    placeholderData: keepPreviousData,
    retry: false,
    gcTime: 0,
  });
  const data = query.data;
  const expired = query.error instanceof LibraryError && query.error.status === 401;
  const needsLogin = !session.isPending && !session.isError && (csrfToken === null || expired);
  const showing = data !== undefined && !query.isPlaceholderData;

  return (
    <section className="hero-page" aria-labelledby="library-title">
      <div className="page-container library-section">
        <header className="page-head">
          <HeroTitle id="library-title" title="Mis juegos" />
        </header>
        {session.isError || (query.isError && data === undefined && !expired) ? (
          <div className="notice notice-danger" role="alert">
            <h2>No se pudieron cargar tus juegos</h2>
            <button className="button" type="button" onClick={() => {
              if (session.isError) void session.refetch();
              else void query.refetch();
            }}>Reintentar</button>
          </div>
        ) : needsLogin ? (
          <div className="notice notice-info">
            <h2>Inicia sesión para ver tus juegos</h2>
            <a className="button button-primary" href={authenticationStartUrl(libraryHref(state, page))}>
              Iniciar sesión
            </a>
          </div>
        ) : data === undefined ? (
          <div className="library-loading">
            <p className="result-count" role="status">Cargando tus juegos…</p>
            <ul aria-hidden="true" className="library-ledger">
              {Array.from({ length: 5 }, (_, index) => (
                <li key={index} className="library-ledger-item">
                  <span className="skeleton library-loading-cover" />
                  <span className="skeleton library-loading-title" />
                </li>
              ))}
            </ul>
          </div>
        ) : (
          <>
            {csrfToken ? <RatedCompletion count={data.ratedWithoutState} csrfToken={csrfToken} /> : null}
            <nav aria-label="Filtrar por estado" className="library-filters">
              <ul>
                {[undefined, ...LIBRARY_STATES].map((value) => {
                  const count = value === undefined ? data.counts.total : data.counts[value];
                  return (
                    <li key={value ?? "all"}>
                      <Link aria-current={value === state ? "page" : undefined} className="library-filter"
                        data-state={value} to={libraryHref(value)}>
                        {value === undefined ? "Todos" : libraryStateLabels[value]}
                        <span className="library-filter-count"><span className="sr-only">: </span>{count}</span>
                      </Link>
                    </li>
                  );
                })}
              </ul>
            </nav>
            <div ref={results} tabIndex={-1} className="result-count" role="status">
              {query.isFetching && !showing ? "Cargando tus juegos…" : countLabel(data.page.totalItems)}
            </div>
            {data.items.length === 0 ? (
              data.counts.total === 0 ? (
                <div className="notice notice-empty">
                  <h2>Aún no tienes juegos con estado</h2>
                  <p>Elige un estado en la ficha de un juego o en «Mis puntuaciones» para encontrarlo aquí.</p>
                  <Link className="button" to="/lanzamientos">Explorar lanzamientos</Link>
                </div>
              ) : (
                <div className="notice notice-empty">
                  <h2>{data.page.totalItems > 0 ? "Esta página ya no tiene juegos"
                    : `No tienes juegos en «${state ? libraryStateLabels[state] : "Todos"}»`}</h2>
                  <Link className="button" to={libraryHref(data.page.totalItems > 0 ? state : undefined)}>
                    {data.page.totalItems > 0 ? "Volver a la primera página" : "Ver todos"}
                  </Link>
                </div>
              )
            ) : (
              <ul aria-busy={query.isFetching || undefined} aria-label="Tus juegos" className="library-ledger">
                {data.items.map((game) => {
                  const title = game.canonicalTitle ?? "Juego no disponible";
                  const to = game.slug === undefined ? undefined : `/games/${game.gameId}/${game.slug}`;
                  return (
                    <li key={game.gameId} className="library-ledger-item" data-state={game.state}>
                      <CatalogueCover caption={false} cover={toCover(game.primaryCover)} {...(to ? { to } : {})} />
                      <div className="library-record">
                        <h2 className="library-game-title">{to ? <Link to={to}>{title}</Link> : title}</h2>
                        <p className="library-game-since">
                          Desde el{" "}
                          <time dateTime={game.stateChangedAt}>{formatDate(game.stateChangedAt)}</time>
                        </p>
                      </div>
                      <GameStateButton
                        compact
                        csrfToken={csrfToken}
                        gameId={game.gameId}
                        ratingOffer={to ? <Link className="text-link" to={to}>¿Qué te ha parecido? Puntúalo</Link> : undefined}
                        released={game.released}
                        state={game.state}
                        title={title}
                        onChanged={(next) => {
                          // A row that leaves this view hands focus back to the count.
                          if (next === null || (state !== undefined && next !== state)) results.current?.focus();
                        }}
                      />
                    </li>
                  );
                })}
              </ul>
            )}
            {data.page.number > 1 || data.page.number < data.page.totalPages ? (
              <nav className="pagination" aria-label="Paginación de mis juegos">
                {data.page.number > 1 ? (
                  <Link className="button view-action view-action-back"
                    to={libraryHref(state, Math.min(data.page.number - 1, Math.max(data.page.totalPages, 1)))}>
                    <span aria-hidden="true">←</span> Página anterior
                  </Link>
                ) : null}
                {data.page.number < data.page.totalPages ? (
                  <Link className="button view-action" to={libraryHref(state, data.page.number + 1)}>
                    Página siguiente <span aria-hidden="true">→</span>
                  </Link>
                ) : null}
              </nav>
            ) : null}
          </>
        )}
      </div>
    </section>
  );
}
