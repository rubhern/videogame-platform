import { useMutation, useQueries, useQueryClient } from "@tanstack/react-query";
import { useContext, type ReactNode } from "react";
import { useLocation } from "react-router-dom";

import { trackEvent } from "../analytics/events";
import { authenticationStartUrl } from "../session/auth-entry";
import type { SessionState } from "../session/session-api";
import { SESSION_QUERY_KEY, useSession } from "../session/use-session";
import {
  changeWish, readWishlistStatus, WishlistError, wishlistSessionScope, WISHLIST_KEY, WISHLIST_STATUS_KEY,
} from "./wishlist-api";
import { WishlistContext } from "./wishlist-context";

export function WishlistActions({
  children, batches,
}: {
  children: ReactNode;
  /** Each batch is one bounded catalogue page. */
  batches: readonly (readonly string[])[];
}) {
  const session = useSession({ refetchOnMount: false });
  const csrfToken = session.data?.authenticated && !session.isError ? session.data.csrfToken : null;
  const scope = wishlistSessionScope(session.data);
  const pages = batches.filter((ids) => ids.length > 0);
  const statuses = useQueries({
    queries: pages.map((ids) => ({
      queryKey: [...WISHLIST_STATUS_KEY, scope, ids],
      queryFn: ({ signal }: { signal: AbortSignal }) => readWishlistStatus([...ids], signal),
      enabled: csrfToken !== null,
      gcTime: 0,
      staleTime: 30_000,
      retry: false,
    })),
  });
  const savedByGame = new Map<string, boolean>();
  for (const [index, ids] of pages.entries()) {
    const result = statuses[index];
    if (csrfToken === null || result?.data === undefined || result.isError) continue;
    const saved = new Set(result.data);
    for (const id of ids) savedByGame.set(id, saved.has(id));
  }
  const failures = csrfToken === null ? [] : statuses.filter((status) => status.isError);
  return (
    <WishlistContext value={{
      pending: session.isPending || session.isError,
      csrfToken,
      savedByGame,
    }}>
      {failures.length > 0 ? (
        <p className="wishlist-read-error" role="status">
          No se pudo comprobar qué juegos tienes en deseados.{" "}
          <button className="text-link" type="button" onClick={() => {
            for (const failure of failures) void failure.refetch();
          }}>Reintentar</button>
        </p>
      ) : null}
      {children}
    </WishlistContext>
  );
}

type WishlistButtonProps = {
  gameId: string;
  title: string;
  compact?: boolean;
  hero?: boolean;
  remove?: boolean;
  onRemoved?: () => void;
};

export function WishlistButton(props: WishlistButtonProps) {
  const account = useContext(WishlistContext);
  return account === null ? null : <AvailableWishlistButton {...props} account={account} />;
}

function hasGame(queryKey: readonly unknown[], gameId: string) {
  const ids = queryKey[WISHLIST_STATUS_KEY.length + 1];
  return Array.isArray(ids) && ids.includes(gameId);
}

function AvailableWishlistButton({
  account, gameId, title, compact = false, hero = false, remove = false, onRemoved,
}: WishlistButtonProps & { account: NonNullable<React.ContextType<typeof WishlistContext>> }) {
  const location = useLocation();
  const client = useQueryClient();
  const command = useMutation({
    mutationFn: changeWish,
    retry: false,
    onSuccess: async (_, input) => {
      const session = client.getQueryData<SessionState>(SESSION_QUERY_KEY);
      if (!session?.authenticated || session.csrfToken !== input.csrfToken) return;

      await client.cancelQueries({
        queryKey: WISHLIST_STATUS_KEY,
        predicate: (query) => hasGame(query.queryKey, input.gameId),
      });
      for (const [key, current] of client.getQueriesData<string[]>({ queryKey: WISHLIST_STATUS_KEY })) {
        if (current === undefined || !hasGame(key, gameId)
            || key[WISHLIST_STATUS_KEY.length] !== wishlistSessionScope(session)) continue;
        const others = current.filter((id) => id !== gameId);
        client.setQueryData(key, input.saved ? [...others, gameId] : others);
      }
      trackEvent("wishlist_changed", { action: input.saved ? "add" : "remove" });
      if (remove) onRemoved?.();
      return client.invalidateQueries({
        queryKey: WISHLIST_KEY,
        predicate: (query) => query.queryKey[2] !== "status",
      });
    },
    onError: (error, input) => {
      if (error instanceof WishlistError && (error.status === 401 || error.status === 403)) {
        void client.invalidateQueries({ queryKey: SESSION_QUERY_KEY });
      }
      void client.invalidateQueries({
        queryKey: WISHLIST_KEY,
        predicate: (query) => query.queryKey[2] !== "status" || hasGame(query.queryKey, input.gameId),
      });
    },
  });

  const saved = remove ? true : account.savedByGame.get(gameId);
  const name = remove ? "Quitar de mis deseados" : "La quiero";
  const icon = <span aria-hidden="true" className="wishlist-mark" />;
  const iconOnly = compact || hero;
  const contents = iconOnly ? icon : <>{icon}{name}</>;
  const className = hero ? "wishlist-button wishlist-button-hero"
    : compact ? "wishlist-button wishlist-button-compact" : "wishlist-button";
  if (!account.pending && account.csrfToken === null) {
    return remove ? null : (
      <a
        aria-label={`${name}: ${title} (iniciar sesión)`}
        className={className}
        href={authenticationStartUrl(location.pathname + location.search)}
        title={iconOnly ? name : undefined}
      >{contents}</a>
    );
  }
  const blocked = account.pending || account.csrfToken === null || saved === undefined || command.isPending;
  return (
    <span className="wishlist-action">
      <button
        aria-busy={command.isPending || undefined}
        aria-disabled={blocked || undefined}
        aria-label={`${name}: ${title}`}
        aria-pressed={remove ? undefined : saved}
        data-saved={saved ? "true" : undefined}
        className={className}
        onClick={() => {
          if (!blocked && account.csrfToken !== null) {
            command.mutate({ gameId, csrfToken: account.csrfToken, saved: !saved });
          }
        }}
        title={iconOnly ? name : undefined}
        type="button"
      >
        {contents}
      </button>
      {command.isError ? <span className="wishlist-action-error" role="alert">
        No se pudo actualizar. Comprueba el estado antes de volver a intentarlo.
      </span> : null}
    </span>
  );
}

/** The preference travels in the URL, while selection and data stay principal-scoped. */
export function WishlistFilter({ checked, onChange }: {
  checked: boolean;
  onChange: (value: boolean) => void;
}) {
  return (
    <label className="wishlist-filter">
      <input checked={checked} type="checkbox" onChange={(event) => onChange(event.target.checked)} />
      Solo mis deseados
    </label>
  );
}
