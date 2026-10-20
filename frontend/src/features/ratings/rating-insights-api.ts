import type { components } from "../../shared/api/generated/schema";
import { productApiClient, type ProductApiClient } from "../../shared/api/product-api-client";

export type RatingInsights = Awaited<ReturnType<typeof getRatingInsights>>;
export type GenreInsight = RatingInsights["mostRatedGenres"][number];
export type RatingExtreme = NonNullable<RatingInsights["highest"]>;
export const RATING_INSIGHTS_KEY = ["me", "rating-insights"] as const;

export class RatingInsightsError extends Error {
  constructor(readonly code: components["schemas"]["Problem"]["code"] | null,
    readonly correlationId: string | null) { super("Personal rating statistics could not be loaded."); }
}

export async function getRatingInsights(signal?: AbortSignal, client: ProductApiClient = productApiClient) {
  const { data, error, response } = await client.GET("/me/rating-insights", {
    cache: "no-store", ...(signal ? { signal } : {}),
  }).catch(() => { throw new RatingInsightsError(null, null); });
  if (data !== undefined) return data;
  throw new RatingInsightsError(error?.code ?? null,
    error?.correlationId ?? response.headers.get("X-Correlation-ID"));
}
