import {
  productApiClient,
  type ProductApiClient,
} from "../../shared/api/product-api-client";

/** The deployment's analytics configuration; `null` means this deployment measures nothing. */
export type AnalyticsConfiguration = { measurementId: string | null };

/** Reads the public, non-personal analytics configuration (UC-022, #507). */
export async function getAnalyticsConfiguration(
  client: ProductApiClient = productApiClient,
  signal?: AbortSignal,
): Promise<AnalyticsConfiguration> {
  const { data, response } = await client.GET("/web-analytics", {
    ...(signal ? { signal } : {}),
  });
  if (!response.ok || data === undefined) {
    throw new Error("The analytics configuration request failed.");
  }
  return { measurementId: data.googleAnalytics?.measurementId ?? null };
}

export const ANALYTICS_CONFIGURATION_QUERY_KEY = ["web-analytics"] as const;

export function analyticsConfigurationQueryOptions() {
  return {
    queryKey: ANALYTICS_CONFIGURATION_QUERY_KEY,
    queryFn: ({ signal }: { signal: AbortSignal }) => getAnalyticsConfiguration(undefined, signal),
    // A failed read simply leaves analytics off; nothing retries or blocks the product.
    retry: false,
    staleTime: Infinity,
  };
}
