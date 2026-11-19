import { useMutation, useQueries, useQueryClient } from "@tanstack/react-query";

import { gameDetailsQueryKey } from "../game-details/game-details-api";
import { PLATFORM_GAMES_KEY } from "../platform-catalogue/platform-games-api";
import { MY_RATINGS_KEY, listMyRatings } from "../ratings/my-ratings-api";
import {
  deleteMyRating,
  putMyRating,
  type PersonalRating,
  type RatingCommandError,
} from "../ratings/personal-rating-api";
import { RATING_INSIGHTS_KEY } from "../ratings/rating-insights-api";
import { personalRatingQueryKey } from "../ratings/use-personal-rating";
import type { PlatformSelection } from "../session/rating-intent";
import { SESSION_QUERY_KEY } from "../session/use-session";

/**
 * The signed-in player's own ratings of the games a list shows (#574), one cache entry per ranking
 * page. It lives under the personal collection's key, so any rating command elsewhere (the game
 * page, `Mis puntuaciones`) re-reads it.
 */
export const LIST_PERSONAL_RATINGS_KEY = [...MY_RATINGS_KEY, "games"] as const;

type RatingsByGame = Readonly<Record<string, PersonalRating>>;

/** What a list knows about the player's own rating of one of its games. */
export type ListPersonalRating =
  | { status: "anonymous" }
  | { status: "checking" }
  /** The read failed: the game can still be rated; a create that finds a rating is a conflict. */
  | { status: "unknown" }
  | { status: "known"; rating: PersonalRating | null };

/**
 * One bounded read per loaded ranking page: `GET /me/ratings?gameIds=…` for exactly that page's
 * games, never one request per game. Anonymous visitors read nothing.
 */
export function useListPersonalRatings(pages: readonly (readonly string[])[], authenticated: boolean) {
  return useQueries({
    queries: pages.map((gameIds) => ({
      queryKey: [...LIST_PERSONAL_RATINGS_KEY, [...gameIds]],
      queryFn: async ({ signal }: { signal: AbortSignal }): Promise<RatingsByGame> => {
        const page = await listMyRatings({ gameIds: [...gameIds], page: 1, pageSize: gameIds.length }, signal);
        return Object.fromEntries(page.items.map((item) => [item.game.gameId, item.personalRating]));
      },
      enabled: authenticated && gameIds.length > 0,
      retry: false,
      staleTime: 30_000,
    })),
    combine: (results) => (gameId: string): ListPersonalRating => {
      if (!authenticated) return { status: "anonymous" };
      const index = pages.findIndex((ids) => ids.includes(gameId));
      const result = index < 0 ? undefined : results[index];
      if (result === undefined || result.isPending) return { status: "checking" };
      if (result.data === undefined) return { status: "unknown" };
      return { status: "known", rating: result.data[gameId] ?? null };
    },
  });
}

export type QuickRatingCommand = { gameId: string; csrfToken: string; current: PersonalRating | null } & (
  | { type: "save"; value: number }
  /** A visitor's platform selection recovered after sign-in (#574): written only when none exists. */
  | { type: "save-platforms"; platforms: PlatformSelection[] }
  | { type: "delete" }
);

/**
 * A general score saved or removed from a list. The precondition is the list's own reading of the
 * rating (`If-None-Match: *` or its strong `If-Match`); commands are never retried. Success writes
 * the outcome into the list's and the game's personal caches, so the stamp changes at once, and
 * marks every other reading of personal or community scores stale. The ranking itself is left to
 * the caller, which refreshes it once when the player is done instead of after every vote.
 */
export function useQuickRatingCommand() {
  const queryClient = useQueryClient();

  function remember(gameId: string, rating: PersonalRating | null) {
    // Only the pages that list the game hold its rating.
    for (const [key, data] of queryClient.getQueriesData<RatingsByGame>({ queryKey: LIST_PERSONAL_RATINGS_KEY })) {
      const ids = key[LIST_PERSONAL_RATINGS_KEY.length];
      if (data === undefined || !Array.isArray(ids) || !ids.includes(gameId)) continue;
      const others = Object.entries(data).filter(([id]) => id !== gameId);
      queryClient.setQueryData<RatingsByGame>(
        key,
        Object.fromEntries(rating === null ? others : [...others, [gameId, rating]]),
      );
    }
    queryClient.setQueryData(personalRatingQueryKey(gameId), rating);
  }

  function invalidateReadings(gameId: string) {
    void queryClient.invalidateQueries({
      queryKey: MY_RATINGS_KEY,
      predicate: (query) => query.queryKey[2] !== LIST_PERSONAL_RATINGS_KEY[2],
    });
    void queryClient.invalidateQueries({ queryKey: RATING_INSIGHTS_KEY });
    void queryClient.invalidateQueries({ queryKey: gameDetailsQueryKey(gameId) });
    void queryClient.invalidateQueries({ queryKey: ["releases"] });
    void queryClient.invalidateQueries({ queryKey: ["featured-releases"] });
    void queryClient.invalidateQueries({ queryKey: ["game-search"] });
    void queryClient.invalidateQueries({ queryKey: PLATFORM_GAMES_KEY });
  }

  return useMutation<PersonalRating | null, RatingCommandError, QuickRatingCommand>({
    retry: false,
    mutationFn: async (command) => {
      const context = { gameId: command.gameId, csrfToken: command.csrfToken };
      if (command.type === "delete") {
        if (command.current === null) throw new Error("A rating must exist before it can be deleted.");
        await deleteMyRating(context, command.current.entityTag);
        return null;
      }
      const result = await putMyRating(
        context,
        // A general score leaves saved played platforms as they are.
        command.type === "save" ? { value: command.value } : { platforms: command.platforms },
        command.current === null ? { create: true } : { create: false, entityTag: command.current.entityTag },
      );
      return result.personalRating;
    },
    onSuccess: (rating, command) => {
      remember(command.gameId, rating);
      invalidateReadings(command.gameId);
    },
    onError: (error, command) => {
      // The list's reading of the rating is stale or the outcome unknown: re-read it, never repeat.
      if (error.kind === "conflict" || error.kind === "ambiguous") {
        void queryClient.invalidateQueries({ queryKey: LIST_PERSONAL_RATINGS_KEY });
        void queryClient.invalidateQueries({ queryKey: personalRatingQueryKey(command.gameId) });
        invalidateReadings(command.gameId);
      }
      if (error.kind === "authentication" || error.kind === "csrf") {
        void queryClient.invalidateQueries({ queryKey: SESSION_QUERY_KEY });
      }
    },
  });
}
