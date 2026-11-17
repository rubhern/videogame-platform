import type { components, operations } from "../../shared/api/generated/schema";
import {
  productApiClient,
  type ProductApiClient,
} from "../../shared/api/product-api-client";

export type PlatformGamesQuery = operations["browsePlatformGames"]["parameters"]["query"];
export type PlatformGamesSort = components["schemas"]["PlatformGamesSort"];
type Problem = components["schemas"]["Problem"];

/** Every platform catalogue read; scores on its cards change with each rating command. */
export const PLATFORM_GAMES_KEY = ["platform-games"] as const;

export class PlatformGamesApiError extends Error {
  readonly code: Problem["code"] | "PLATFORM_GAMES_REQUEST_FAILED";
  readonly correlationId: string | null;
  readonly status: number;

  constructor(
    status: number,
    code: Problem["code"] | "PLATFORM_GAMES_REQUEST_FAILED",
    correlationId: string | null,
  ) {
    super("The platform games request failed.");
    this.name = "PlatformGamesApiError";
    this.status = status;
    this.code = code;
    this.correlationId = correlationId;
  }
}

/** UC-019: one page of the local games of a platform scope (Explorar catálogo, #389). */
export async function getPlatformGames(
  query: PlatformGamesQuery,
  client: ProductApiClient = productApiClient,
  signal?: AbortSignal,
) {
  const { data, error, response } = await client.GET("/platform-games", {
    params: { query },
    ...(signal === undefined ? {} : { signal }),
  });

  if (data !== undefined) {
    return data;
  }

  throw new PlatformGamesApiError(
    response.status,
    error?.code ?? "PLATFORM_GAMES_REQUEST_FAILED",
    error?.correlationId ?? response.headers.get("X-Correlation-ID"),
  );
}

export type PlatformGamesPage = Awaited<ReturnType<typeof getPlatformGames>>;
