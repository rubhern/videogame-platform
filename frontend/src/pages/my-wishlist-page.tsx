import { useInfiniteQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useEffect, useMemo, useRef } from "react";
import { Link } from "react-router-dom";

import { toCover } from "../features/releases/releases-view-model";
import { authenticationStartUrl } from "../features/session/auth-entry";
import { useSession } from "../features/session/use-session";
import {
  listWishlist, moveWish, WishlistError, wishlistSessionScope, WISHLIST_KEY, WISHLIST_PAGE_SIZE,
} from "../features/wishlist/wishlist-api";
import { useWishlistReorder, type MoveCommand } from "../features/wishlist/use-wishlist-reorder";
import { WishlistActions, WishlistButton } from "../features/wishlist/wishlist-controls";
import { useDocumentTitle } from "../shared/browser/use-document-title";
import { formatReleaseDate } from "../shared/catalogue/release-date";
import { CatalogueCover } from "../shared/ui/catalogue-cover";
import { HeroTitle } from "../shared/ui/hero-title";

const WISHLIST_PATH = "/mis-deseados";
const timingLabels = { upcoming: "Próximo lanzamiento", released: "Lanzamiento anterior" } as const;

export function MyWishlistPage() {
  useDocumentTitle("Mis deseados · Gameómetro");
  const heading = useRef<HTMLDivElement>(null);
  const session = useSession({ refetchOnMount: false });
  const nextBatchFocus = useRef<number | null>(null);
  const handles = useRef(new Map<string, HTMLButtonElement>());
  const client = useQueryClient();
  const csrfToken = session.data?.authenticated === true && !session.isError ? session.data.csrfToken : null;
  const scope = wishlistSessionScope(session.data);
  const query = useInfiniteQuery({
    queryKey: [...WISHLIST_KEY, "page", scope],
    initialPageParam: 1,
    queryFn: ({ pageParam, signal }) => listWishlist({ page: pageParam, pageSize: WISHLIST_PAGE_SIZE }, signal),
    getNextPageParam: ({ page }) => page.number < page.totalPages ? page.number + 1 : undefined,
    enabled: session.data?.authenticated === true && !session.isError,
    retry: false,
    gcTime: 0,
  });

  const reorder = useMutation({
    mutationFn: async ({ source, target, move }: MoveCommand) => {
      if (csrfToken === null) throw new Error("Wishlist session unavailable");
      await moveWish({ gameId: source.gameId, move, csrfToken });
      return { source, target };
    },
    retry: false,
    onSuccess: async ({ source }) => {
      await client.invalidateQueries({ queryKey: [...WISHLIST_KEY, "page"] });
      requestAnimationFrame(() => (handles.current.get(source.gameId) ?? heading.current)?.focus());
    },
    onError: () => { void query.refetch(); },
  });
  const pages = query.data?.pages;
  const items = useMemo(() => (
    [...new Map((pages ?? []).flatMap((page) => page.items).map((game) => [game.gameId, game])).values()]
  ), [pages]);
  const totalItems = pages?.at(-1)?.page.totalItems ?? 0;
  const rows = items.map((game, index) => ({
    gameId: game.gameId,
    title: game.canonicalTitle ?? "Juego no disponible",
    position: index + 1,
  }));
  const drag = useWishlistReorder(rows, scope, reorder.isPending || query.isFetchingNextPage, (command) => {
    reorder.mutate(command);
  });
  useEffect(() => {
    const index = nextBatchFocus.current;
    if (index === null || query.isFetchingNextPage) return;
    nextBatchFocus.current = null;
    const newItem = items[index];
    if (newItem) handles.current.get(newItem.gameId)?.focus();
  }, [items, query.isFetchingNextPage]);

  const expired = query.error instanceof WishlistError && query.error.status === 401;
  const needsLogin = !session.isPending && !session.isError
    && (session.data?.authenticated !== true || expired);

  return (
    <section className="hero-page" aria-labelledby="wishlist-title">
      <div className="page-container wishlist-section">
        <header className="page-head">
          <HeroTitle id="wishlist-title" title="Mis deseados" />
        </header>
        {session.isError || (query.isError && pages === undefined && !expired) ? (
          <div className="notice notice-danger" role="alert">
            <h2>No se pudieron cargar tus deseados</h2>
            <button className="button" type="button" onClick={() => {
              if (session.isError) void session.refetch();
              else void query.refetch();
            }}>Reintentar</button>
          </div>
        ) : needsLogin ? (
          <div className="notice notice-info">
            <h2>Inicia sesión para ver tus deseados</h2>
            <a className="button button-primary" href={authenticationStartUrl(WISHLIST_PATH)}>
              Iniciar sesión
            </a>
          </div>
        ) : session.isPending || pages === undefined ? (
          <div className="wishlist-loading">
            <p className="result-count" role="status">Cargando tus deseados…</p>
            <ul aria-hidden="true" className="wishlist-ledger">
              {Array.from({ length: 5 }, (_, index) => (
                <li key={index} className="wishlist-ledger-item">
                  <span className="skeleton wishlist-loading-number" />
                  <span className="skeleton wishlist-loading-cover" />
                  <span className="skeleton wishlist-loading-title" />
                </li>
              ))}
            </ul>
          </div>
        ) : (
          <>
            <div ref={heading} tabIndex={-1} className="result-count" role="status">
              {totalItems === 1 ? "1 juego" : `${totalItems} juegos`}
            </div>
            {items.length === 0 ? (
              <div className="notice notice-empty">
                <h2>Aún no has guardado juegos</h2>
                <p>Marca «La quiero» en un juego para encontrarlo aquí.</p>
                <Link className="button" to="/lanzamientos">Explorar lanzamientos</Link>
              </div>
            ) : (
              <WishlistActions batches={[]}>
                {reorder.isError ? (
                  <p className="wishlist-action-error" role="alert">
                    No se pudo confirmar el cambio. Revisa el orden antes de intentarlo de nuevo.
                  </p>
                ) : (
                  <p className="sr-only" role="status">
                    {drag.announcement || (reorder.isSuccess
                      ? `${reorder.data.source.title}, puesto ${reorder.data.target.position}` : "")}
                  </p>
                )}
                <p className="wishlist-order-hint" id="wishlist-order-hint">
                  Arrastra por el asa o toca dos asas para elegir el destino.
                  <span className="sr-only">Con el teclado, pulsa Espacio, usa las flechas y confirma con Espacio. Escape cancela.</span>
                </p>
                <ol className="wishlist-ledger" aria-label="Juegos deseados" role="list">
                  {items.map((game, index) => {
                    const row = rows[index];
                    if (!row) return null;
                    const { position, title } = row;
                    const to = game.slug === undefined ? undefined : `/games/${game.gameId}/${game.slug}`;
                    const dropEdge = drag.source && drag.target?.gameId === game.gameId
                      ? drag.source.position < position ? "after" : "before" : undefined;
                    return (
                      <li key={game.gameId} className="wishlist-ledger-item"
                        data-wishlist-position={position}
                        data-wishlist-drop={dropEdge}
                        data-wishlist-grabbed={drag.source?.gameId === game.gameId ? "true" : undefined}
                        data-prominent={position <= 3 ? "true" : undefined}>
                        <span className="wishlist-position">
                          <span className="sr-only">Puesto </span>{position}
                        </span>
                        <CatalogueCover caption={false} cover={toCover(game.primaryCover)} {...(to ? { to } : {})} />
                        <div className="wishlist-record">
                          <h2 className="wishlist-game-title">{to ? <Link to={to}>{title}</Link> : title}</h2>
                          <p className="wishlist-game-date">
                            {game.timing === "undated"
                              ? formatReleaseDate(game.releaseDate)
                              : `${timingLabels[game.timing]} · ${formatReleaseDate(game.releaseDate)}`}
                          </p>
                        </div>
                        <div className="wishlist-order-actions">
                          <button className="wishlist-drag-handle" type="button"
                            aria-label={`Mover ${title}`} aria-describedby="wishlist-order-hint"
                            aria-disabled={reorder.isPending || query.isFetchingNextPage || undefined} title="Cambiar de puesto"
                            ref={(element) => {
                              if (element) handles.current.set(game.gameId, element);
                              else handles.current.delete(game.gameId);
                            }}
                            {...drag.handle(row)}>
                            <span aria-hidden="true" className="wishlist-drag-dots" />
                          </button>
                          <WishlistButton compact gameId={game.gameId} title={title} remove
                            onRemoved={() => heading.current?.focus()} />
                        </div>
                      </li>
                    );
                  })}
                </ol>
              </WishlistActions>
            )}
            {query.hasNextPage ? (
              <div className="wishlist-more">
                {query.isFetchNextPageError ? (
                  <p className="wishlist-action-error" role="alert">No se pudieron cargar más juegos.</p>
                ) : null}
                <button className="button" type="button"
                  aria-disabled={reorder.isPending || query.isFetching || undefined}
                  onClick={() => {
                    if (reorder.isPending || query.isFetching) return;
                    nextBatchFocus.current = items.length;
                    void query.fetchNextPage({ cancelRefetch: false });
                  }}>
                  Mostrar más
                </button>
                <span className="sr-only" role="status">{query.isFetchingNextPage ? "Cargando más juegos…" : ""}</span>
              </div>
            ) : null}
          </>
        )}
      </div>
    </section>
  );
}
