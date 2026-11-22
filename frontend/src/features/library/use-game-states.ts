import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import type { SessionState } from "../session/session-api";
import { SESSION_QUERY_KEY, useSession } from "../session/use-session";
import { wishlistSessionScope, WISHLIST_KEY } from "../wishlist/wishlist-api";
import {
  changeGameState, LIBRARY_KEY, LIBRARY_STATES_KEY, LibraryError, readGameStates, type LibraryState,
} from "./library-api";

export type GameStates = {
  /** Null while signed out or unknown; a missing game has no state. */
  states: ReadonlyMap<string, LibraryState> | null;
  csrfToken: string | null;
  failed: boolean;
  retry: () => void;
};

/** The signed-in player's state for one bounded page of games (at most 100). */
export function useGameStates(gameIds: readonly string[]): GameStates {
  const session = useSession({ refetchOnMount: false });
  const csrfToken = session.data?.authenticated === true && !session.isError ? session.data.csrfToken : null;
  const scope = wishlistSessionScope(session.data);
  const ids = [...new Set(gameIds)].sort();
  const query = useQuery({
    queryKey: [...LIBRARY_STATES_KEY, scope, ids],
    queryFn: ({ signal }) => readGameStates(ids, signal),
    enabled: csrfToken !== null && ids.length > 0,
    gcTime: 0,
    staleTime: 30_000,
    retry: false,
  });
  return {
    states: csrfToken === null || query.isError ? null : query.data ?? null,
    csrfToken,
    failed: csrfToken !== null && query.isError,
    retry: () => { void query.refetch(); },
  };
}

function holdsGame(queryKey: readonly unknown[], gameId: string) {
  const ids = queryKey[LIBRARY_STATES_KEY.length + 1];
  return Array.isArray(ids) && ids.includes(gameId);
}

export function useGameStateCommand(onChanged?: (state: LibraryState | null) => void) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: changeGameState,
    retry: false,
    onSuccess: async (_, input) => {
      const session = client.getQueryData<SessionState>(SESSION_QUERY_KEY);
      if (!session?.authenticated || session.csrfToken !== input.csrfToken) return;
      await client.cancelQueries({
        queryKey: LIBRARY_STATES_KEY, predicate: (query) => holdsGame(query.queryKey, input.gameId),
      });
      for (const [key, current] of client.getQueriesData<Map<string, LibraryState>>({ queryKey: LIBRARY_STATES_KEY })) {
        if (current === undefined || !holdsGame(key, input.gameId)) continue;
        const next = new Map(current);
        if (input.state === null) next.delete(input.gameId);
        else next.set(input.gameId, input.state);
        client.setQueryData(key, next);
      }
      onChanged?.(input.state);
      // Deseado is one of the states, so the wishlist and its hearts may have changed too.
      await Promise.all([
        client.invalidateQueries({ queryKey: LIBRARY_KEY, predicate: (query) => query.queryKey[2] !== "states" }),
        client.invalidateQueries({ queryKey: WISHLIST_KEY }),
      ]);
    },
    onError: (error, input) => {
      if (error instanceof LibraryError && (error.status === 401 || error.status === 403)) {
        void client.invalidateQueries({ queryKey: SESSION_QUERY_KEY });
      }
      void client.invalidateQueries({
        queryKey: LIBRARY_KEY,
        predicate: (query) => query.queryKey[2] !== "states" || holdsGame(query.queryKey, input.gameId),
      });
    },
  });
}
