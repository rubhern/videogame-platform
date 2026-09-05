import type { components, operations } from "../../shared/api/generated/schema";
import { productApiClient, type ProductApiClient } from "../../shared/api/product-api-client";

export type GameSearchQuery = operations["searchGames"]["parameters"]["query"];
type Problem = components["schemas"]["Problem"];

export class GameSearchApiError extends Error {
  readonly code: Problem["code"] | "GAME_SEARCH_REQUEST_FAILED";
  readonly correlationId: string | null;
  readonly status: number | null;

  constructor(
    status: number | null,
    code: Problem["code"] | "GAME_SEARCH_REQUEST_FAILED",
    correlationId: string | null,
  ) {
    super("The catalogue search request failed.");
    this.name = "GameSearchApiError";
    this.status = status;
    this.code = code;
    this.correlationId = correlationId;
  }
}

export async function searchGames(
  query: GameSearchQuery,
  client: ProductApiClient = productApiClient,
) {
  const { data, error, response } = await client.GET("/games", { params: { query } })
    .catch(() => {
      // Network and response-decoding failures have no usable HTTP Problem.
      throw new GameSearchApiError(null, "GAME_SEARCH_REQUEST_FAILED", null);
    });

  if (data !== undefined) {
    return data;
  }

  throw new GameSearchApiError(
    response.status,
    error?.code ?? "GAME_SEARCH_REQUEST_FAILED",
    error?.correlationId ?? response.headers.get("X-Correlation-ID"),
  );
}

export type GameSearchPage = Awaited<ReturnType<typeof searchGames>>;
