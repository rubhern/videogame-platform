import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { useLocation, useSearchParams } from "react-router-dom";

import { trackEvent } from "../analytics/events";
import { gameDetailsQueryOptions, type GameDetails } from "../game-details/game-details-api";
import { getMyRating, type PersonalRating } from "../ratings/personal-rating-api";
import { formatPersonalScore } from "../ratings/personal-score";
import { personalRatingQueryKey } from "../ratings/use-personal-rating";
import { getPendingRatingIntent, type PlatformSelection } from "../session/rating-intent";
import { GAME_RANKING_KEY } from "./game-ranking-api";
import { quickRateFailures } from "./quick-rate-failures";
import { useQuickRatingCommand } from "./use-ranking-personal-ratings";

export type ListResumeNotice = { tone: "success" | "info" | "error"; text: string };

/** A recovered platform selection the player still has to confirm over an existing rating. */
export type RecoveredPlatforms = { details: GameDetails; rating: PersonalRating; platforms: PlatformSelection[] };

const outcomes: Record<string, string> = {
  expired: "La selección para puntuar caducó. Vuelve a elegir tu puntuación.",
  cancelled: "No se completó el inicio de sesión. Tu puntuación no se ha guardado.",
  invalid: "La selección para puntuar no era válida. Vuelve a elegir tu puntuación.",
};
const UNAVAILABLE = "No se pudo recuperar tu selección. Vuelve a elegir tu puntuación.";
const BY_PLATFORM =
  "Ya tenías ese juego puntuado por plataforma, así que no hemos cambiado nada. Cámbialo desde sus plataformas.";

/**
 * Finishes, on the list itself, a rating a visitor started there (#574). The sign-in returns to
 * the list with `rating-intent=resumed`; the single-use server selection is read once and
 * applied as the game page would: a general score is saved over a direct rating but never over
 * platform scores, and a platform selection is saved when the game has no rating yet, otherwise
 * offered in the platform sheet for review. Any other return outcome only says what happened.
 * The marker is removed from the address once handled, so a reload never resumes it again.
 */
export function useListRatingResume({
  csrfToken,
  sessionPending,
  titleOf,
}: {
  csrfToken: string | null;
  sessionPending: boolean;
  titleOf: (gameId: string) => string | null;
}) {
  const queryClient = useQueryClient();
  const command = useQuickRatingCommand();
  const [searchParams, setSearchParams] = useSearchParams();
  const location = useLocation();
  const [notice, setNotice] = useState<ListResumeNotice | null>(null);
  const [recovered, setRecovered] = useState<RecoveredPlatforms | null>(null);
  const handled = useRef(false);

  const marker = searchParams.get("rating-intent");
  const resumed = marker === "resumed";
  const intent = useQuery({
    queryKey: ["rating-intent", "list"],
    queryFn: ({ signal }) => getPendingRatingIntent(signal),
    enabled: resumed && csrfToken !== null,
    gcTime: 0,
    retry: false,
    staleTime: Infinity,
  });

  function clearMarker() {
    const next = new URLSearchParams(searchParams);
    next.delete("rating-intent");
    setSearchParams(next, { replace: true, preventScrollReset: true, state: location.state });
  }

  async function apply(token: string) {
    const pending = intent.data;
    if (pending === null || pending === undefined) {
      setNotice({ tone: "info", text: outcomes.expired ?? UNAVAILABLE });
      return;
    }
    const { gameId } = pending;
    let current: PersonalRating | null;
    try {
      current = await queryClient.fetchQuery({
        queryKey: personalRatingQueryKey(gameId),
        queryFn: ({ signal }) => getMyRating(gameId, undefined, signal),
        staleTime: 0,
      });
    } catch {
      setNotice({ tone: "error", text: UNAVAILABLE });
      return;
    }
    const title = titleOf(gameId);
    const saved = (rating: PersonalRating | null) => {
      void queryClient.invalidateQueries({ queryKey: GAME_RANKING_KEY });
      if (rating === null) return;
      trackEvent("quick_rating", { list: "topometro", action: current === null ? "create" : "update" });
      const score = `${formatPersonalScore(rating.contribution)}/10`;
      setNotice({
        tone: "success",
        text: title === null ? `Puntuación guardada: ${score}.` : `Puntuación guardada: ${title}, ${score}.`,
      });
    };
    const failed = (error: { kind: keyof typeof quickRateFailures }) =>
      setNotice({ tone: "error", text: quickRateFailures[error.kind] });

    if (pending.platforms === undefined) {
      if (current?.mode === "platforms") {
        setNotice({ tone: "info", text: BY_PLATFORM });
      } else if (current?.value === pending.value) {
        saved(current);
      } else {
        command.mutate(
          { type: "save", gameId, csrfToken: token, current, value: pending.value },
          { onSuccess: saved, onError: failed },
        );
      }
      return;
    }
    if (current === null) {
      command.mutate(
        { type: "save-platforms", gameId, csrfToken: token, current: null, platforms: pending.platforms },
        { onSuccess: saved, onError: failed },
      );
      return;
    }
    try {
      const details = await queryClient.fetchQuery({ ...gameDetailsQueryOptions(gameId), staleTime: 60_000 });
      setRecovered({ details, rating: current, platforms: pending.platforms });
    } catch {
      setNotice({ tone: "error", text: UNAVAILABLE });
    }
  }

  const ready = marker !== null && !sessionPending && (!resumed || csrfToken === null || intent.isFetched);
  function handle(arrival: string) {
    clearMarker();
    if (arrival !== "resumed") {
      const text = outcomes[arrival];
      if (text !== undefined) setNotice({ tone: "info", text });
    } else if (csrfToken === null || intent.isError) {
      setNotice({ tone: "info", text: csrfToken === null ? (outcomes.expired ?? UNAVAILABLE) : UNAVAILABLE });
    } else {
      void apply(csrfToken);
    }
  }

  useEffect(() => {
    if (!ready || handled.current || marker === null) return;
    handled.current = true;
    handle(marker);
    // The selection is single-use: handling it once on arrival must never repeat on later renders.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready]);

  return {
    notice: command.isPending ? { tone: "info" as const, text: "Guardando tu puntuación…" } : notice,
    dismiss: () => setNotice(null),
    recovered,
    clearRecovered: () => setRecovered(null),
  };
}
