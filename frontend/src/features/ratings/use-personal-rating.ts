import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import {
  type GameDetails,
  gameDetailsQueryKey,
} from "../game-details/game-details-api";
import { SESSION_QUERY_KEY } from "../session/use-session";
import { RATING_INSIGHTS_KEY } from "./rating-insights-api";
import { MY_RATINGS_KEY } from "./my-ratings-api";
import {
  deleteMyRating,
  getMyRating,
  type PersonalRating,
  putMyRating,
  type RatingCommandError,
  type RatingStatistics,
  type RatingWrite,
} from "./personal-rating-api";

function personalRatingQueryKey(gameId: string) {
  return ["me", "ratings", gameId] as const;
}

/**
 * Owns the current user's rating for one game as server state.
 *
 * <p>Enabled only for an authenticated session: the anonymous case has no personal state and
 * must not trigger a `401`.
 */
export function usePersonalRating(gameId: string, enabled: boolean) {
  return useQuery<PersonalRating | null, RatingCommandError>({
    queryKey: personalRatingQueryKey(gameId),
    queryFn: ({ signal }) => getMyRating(gameId, undefined, signal),
    enabled,
    retry: false,
    staleTime: 0,
  });
}

export type RatingCommand = (
  | { type: "save"; csrfToken: string; write: RatingWrite }
  | { type: "delete"; csrfToken: string }) & { currentRating?: PersonalRating };

type CommandOutcome =
  | { type: "saved"; rating: PersonalRating }
  | { type: "deleted" };

/**
 * Executes one conditional rating command and reconciles personal and aggregate state from its
 * response.
 *
 * <p>The precondition comes from the cached personal rating: `If-None-Match: *` when the user has
 * none, otherwise the current strong `If-Match`. Success writes the returned personal rating and
 * aggregate statistics into the cache, so the community panel never re-reads the publicly cached
 * game representation. Commands are never retried automatically.
 */
export function useRatingCommand(gameId: string) {
  const queryClient = useQueryClient();
  const personalKey = personalRatingQueryKey(gameId);

  function applyStatistics(ratingStatistics: RatingStatistics) {
    queryClient.setQueryData<GameDetails>(gameDetailsQueryKey(gameId), (game) =>
      game ? { ...game, ratingStatistics } : game,
    );
  }

  return useMutation<CommandOutcome, RatingCommandError, RatingCommand>({
    retry: false,
    mutationFn: async (command) => {
      const current =
        command.currentRating ?? queryClient.getQueryData<PersonalRating | null>(personalKey) ?? null;
      const context = { gameId, csrfToken: command.csrfToken };
      if (command.type === "delete") {
        if (current === null) {
          throw new Error("A rating must exist before it can be deleted.");
        }
        const statistics = await deleteMyRating(context, current.entityTag);
        queryClient.setQueryData(personalKey, null);
        applyStatistics(statistics);
        return { type: "deleted" };
      }
      const result = await putMyRating(
        context,
        command.write,
        current === null
          ? { create: true }
          : { create: false, entityTag: current.entityTag },
      );
      queryClient.setQueryData(personalKey, result.personalRating);
      applyStatistics(result.ratingStatistics);
      return { type: "saved", rating: result.personalRating };
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: MY_RATINGS_KEY });
      void queryClient.invalidateQueries({ queryKey: RATING_INSIGHTS_KEY });
      void queryClient.invalidateQueries({ queryKey: ["releases"] });
      void queryClient.invalidateQueries({ queryKey: ["featured-releases"] });
      void queryClient.invalidateQueries({ queryKey: ["game-search"] });
    },
    onError: (error) => {
      // The local view of the personal rating or session is stale: re-read it (a read, never a
      // repeated command) so the user sees the winning state before deciding again.
      if (error.kind === "conflict") {
        void queryClient.invalidateQueries({ queryKey: personalKey });
      }
      // The personal collection has no manual refresh: re-read it whenever the outcome is unknown.
      if (error.kind === "conflict" || error.kind === "ambiguous") {
        void queryClient.invalidateQueries({ queryKey: MY_RATINGS_KEY });
        void queryClient.invalidateQueries({ queryKey: RATING_INSIGHTS_KEY });
        void queryClient.invalidateQueries({ queryKey: ["releases"] });
        void queryClient.invalidateQueries({ queryKey: ["featured-releases"] });
        void queryClient.invalidateQueries({ queryKey: ["game-search"] });
      }
      if (error.kind === "authentication" || error.kind === "csrf") {
        void queryClient.invalidateQueries({ queryKey: SESSION_QUERY_KEY });
      }
      // A platform the game no longer releases on: re-read the game, whose releases list them.
      if (error.kind === "ineligible" || error.kind === "platform") {
        void queryClient.invalidateQueries({
          queryKey: gameDetailsQueryKey(gameId),
        });
      }
    },
  });
}
