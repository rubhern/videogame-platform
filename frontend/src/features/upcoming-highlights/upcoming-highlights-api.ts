import type { components } from "../../shared/api/generated/schema";
import {
  productApiClient,
  type ProductApiClient,
} from "../../shared/api/product-api-client";

type Problem = components["schemas"]["Problem"];

/** Every platform's upcoming highlights read. */
export const UPCOMING_HIGHLIGHTS_KEY = ["platform-upcoming-highlights"] as const;

export class UpcomingHighlightsApiError extends Error {
  readonly code: Problem["code"] | "UPCOMING_HIGHLIGHTS_REQUEST_FAILED";
  readonly correlationId: string | null;
  readonly status: number;

  constructor(
    status: number,
    code: Problem["code"] | "UPCOMING_HIGHLIGHTS_REQUEST_FAILED",
    correlationId: string | null,
  ) {
    super("The upcoming highlights request failed.");
    this.name = "UpcomingHighlightsApiError";
    this.status = status;
    this.code = code;
    this.correlationId = correlationId;
  }
}

/** UC-020: the most anticipated games still to come on one platform scope (#388). */
export async function getPlatformUpcomingHighlights(
  platformIds: readonly string[],
  client: ProductApiClient = productApiClient,
  signal?: AbortSignal,
) {
  const { data, error, response } = await client.GET("/platform-upcoming-highlights", {
    params: { query: { platformIds: [...platformIds] } },
    ...(signal === undefined ? {} : { signal }),
  });

  if (data !== undefined) {
    return data;
  }

  throw new UpcomingHighlightsApiError(
    response.status,
    error?.code ?? "UPCOMING_HIGHLIGHTS_REQUEST_FAILED",
    error?.correlationId ?? response.headers.get("X-Correlation-ID"),
  );
}

export type UpcomingHighlightsResponse = Awaited<ReturnType<typeof getPlatformUpcomingHighlights>>;
