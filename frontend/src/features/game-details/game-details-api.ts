import type { components } from "../../shared/api/generated/schema";
import { productApiClient, type ProductApiClient } from "../../shared/api/product-api-client";

type Problem = components["schemas"]["Problem"];
export class GameDetailsApiError extends Error {
  constructor(
    readonly code: Problem["code"] | "GAME_DETAILS_REQUEST_FAILED",
    readonly correlationId: string | null,
  ) {
    super("The public game request failed.");
  }
}
export async function getGameDetails(
  gameId: string,
  client: ProductApiClient = productApiClient,
  signal?: AbortSignal,
) {
  const { data, error, response } = await client
    .GET("/games/{gameId}", {
      params: { path: { gameId } },
      ...(signal ? { signal } : {}),
    })
    .catch(() => {
      throw new GameDetailsApiError("GAME_DETAILS_REQUEST_FAILED", null);
    });
  if (data !== undefined) return data;
  throw new GameDetailsApiError(
    error?.code ?? "GAME_DETAILS_REQUEST_FAILED",
    error?.correlationId ?? response.headers.get("X-Correlation-ID"),
  );
}
export type GameDetails = Awaited<ReturnType<typeof getGameDetails>>;
