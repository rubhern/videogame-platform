import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import {
  type GameDetails,
  gameDetailsQueryKey,
} from "../game-details/game-details-api";
import { SESSION_QUERY_KEY } from "../session/use-session";
import {
  deleteMyRating,
  getMyRating,
  type PersonalRating,
  putMyRating,
  type RatingCommandError,
  type RatingStatistics,
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

export type RatingCommand =
  | { type: "save"; csrfToken: string; value: number }
  | { type: "delete"; csrfToken: string };

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
        queryClient.getQueryData<PersonalRating | null>(personalKey) ?? null;
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
        command.value,
        current === null
          ? { create: true }
          : { create: false, entityTag: current.entityTag },
      );
      queryClient.setQueryData(personalKey, result.personalRating);
      applyStatistics(result.ratingStatistics);
      return { type: "saved", rating: result.personalRating };
    },
    onError: (error) => {
      // The local view of the personal rating or session is stale: re-read it (a read, never a
      // repeated command) so the user sees the winning state before deciding again.
      if (error.kind === "conflict") {
        void queryClient.invalidateQueries({ queryKey: personalKey });
      }
      if (error.kind === "authentication" || error.kind === "csrf") {
        void queryClient.invalidateQueries({ queryKey: SESSION_QUERY_KEY });
      }
      if (error.kind === "ineligible") {
        void queryClient.invalidateQueries({
          queryKey: gameDetailsQueryKey(gameId),
        });
      }
    },
  });
}
