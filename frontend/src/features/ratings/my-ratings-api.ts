import type { components, operations } from "../../shared/api/generated/schema";
import { productApiClient, type ProductApiClient } from "../../shared/api/product-api-client";

export type MyRatingsQuery = NonNullable<operations["listMyRatings"]["parameters"]["query"]>;
export type MyRatingsPage = Awaited<ReturnType<typeof listMyRatings>>;
export type MyRatingItem = MyRatingsPage["items"][number];
export const MY_RATINGS_KEY = ["me", "rating-list"] as const;

export class MyRatingsError extends Error {
  constructor(readonly code: components["schemas"]["Problem"]["code"] | null,
    readonly correlationId: string | null) { super("Personal ratings could not be loaded."); }
}

export async function listMyRatings(query: MyRatingsQuery, signal?: AbortSignal,
  client: ProductApiClient = productApiClient) {
  const { data, error, response } = await client.GET("/me/ratings", {
    params: { query }, cache: "no-store", ...(signal ? { signal } : {}),
  }).catch(() => { throw new MyRatingsError(null, null); });
  if (data !== undefined) return data;
  throw new MyRatingsError(error?.code ?? null,
    error?.correlationId ?? response.headers.get("X-Correlation-ID"));
}
