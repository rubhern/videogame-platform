import { useQueryClient } from "@tanstack/react-query";
import { useState } from "react";

import { gameDetailsQueryOptions, type GameDetails } from "../game-details/game-details-api";

/**
 * Reads, on demand, the one game a list opens platform by platform (#574): a ranked game carries
 * no releases, and the platform sheet needs them. One public game read per request, reused while
 * fresh, never one per listed game.
 */
export function usePlatformRatingGame() {
  const queryClient = useQueryClient();
  const [state, setState] = useState<"idle" | "loading" | "failed">("idle");

  async function load(gameId: string): Promise<GameDetails | null> {
    setState("loading");
    try {
      const game = await queryClient.fetchQuery({ ...gameDetailsQueryOptions(gameId), staleTime: 60_000 });
      setState("idle");
      return game;
    } catch {
      setState("failed");
      return null;
    }
  }

  return { load, loading: state === "loading", failed: state === "failed", reset: () => setState("idle") };
}
