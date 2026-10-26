import type { components, operations } from "../../shared/api/generated/schema";
import { productApiClient, type ProductApiClient } from "../../shared/api/product-api-client";

export type GameRankingQuery = NonNullable<operations["rankGames"]["parameters"]["query"]>;
type Problem = components["schemas"]["Problem"];

/** Every cached page of every ranking source; rating commands invalidate them all. */
export const GAME_RANKING_KEY = ["game-ranking"] as const;

export class GameRankingApiError extends Error {
  readonly code: Problem["code"] | "GAME_RANKING_REQUEST_FAILED";
  readonly correlationId: string | null;
  readonly status: number | null;

  constructor(
    status: number | null,
    code: Problem["code"] | "GAME_RANKING_REQUEST_FAILED",
    correlationId: string | null,
  ) {
    super("The game ranking request failed.");
    this.name = "GameRankingApiError";
    this.status = status;
    this.code = code;
    this.correlationId = correlationId;
  }
}

export async function rankGames(
  query: GameRankingQuery,
  client: ProductApiClient = productApiClient,
  signal?: AbortSignal,
) {
  const { data, error, response } = await client
    .GET("/game-rankings", { params: { query }, signal: signal ?? null })
    .catch(() => {
      // Network and response-decoding failures have no usable HTTP Problem.
      throw new GameRankingApiError(null, "GAME_RANKING_REQUEST_FAILED", null);
    });

  if (data !== undefined) {
    return data;
  }

  throw new GameRankingApiError(
    response.status,
    error?.code ?? "GAME_RANKING_REQUEST_FAILED",
    error?.correlationId ?? response.headers.get("X-Correlation-ID"),
  );
}

export type GameRankingPage = Awaited<ReturnType<typeof rankGames>>;
