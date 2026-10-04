import type { operations } from "../../shared/api/generated/schema";
import {
  productApiClient,
  type ProductApiClient,
} from "../../shared/api/product-api-client";
import { ReleasesApiError } from "./releases-api";

export type FeaturedReleasesQuery = NonNullable<
  operations["getFeaturedReleases"]["parameters"]["query"]
>;

/** Reads the automatic featured releases of one month from the local catalogue (UC-010). */
export async function getFeaturedReleases(
  query: FeaturedReleasesQuery,
  client: ProductApiClient = productApiClient,
) {
  const { data, error, response } = await client.GET("/featured-releases", {
    params: { query },
  });

  if (data !== undefined) {
    return data;
  }

  throw new ReleasesApiError(
    response.status,
    error?.code ?? "RELEASES_REQUEST_FAILED",
    error?.correlationId ?? response.headers.get("X-Correlation-ID"),
  );
}

export type FeaturedReleasesResponse = Awaited<ReturnType<typeof getFeaturedReleases>>;
