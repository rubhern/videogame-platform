import type { components, operations } from "../../shared/api/generated/schema";
import {
  productApiClient,
  type ProductApiClient,
} from "../../shared/api/product-api-client";

export type ReleasesQuery = operations["listReleases"]["parameters"]["query"];
type Problem = components["schemas"]["Problem"];

export class ReleasesApiError extends Error {
  readonly code: Problem["code"] | "RELEASES_REQUEST_FAILED";
  readonly correlationId: string | null;
  readonly status: number;

  constructor(
    status: number,
    code: Problem["code"] | "RELEASES_REQUEST_FAILED",
    correlationId: string | null,
  ) {
    super("The releases request failed.");
    this.name = "ReleasesApiError";
    this.status = status;
    this.code = code;
    this.correlationId = correlationId;
  }
}

export async function getReleases(
  query: ReleasesQuery,
  client: ProductApiClient = productApiClient,
) {
  const { data, error, response } = await client.GET("/releases", {
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

export type ReleasePage = Awaited<ReturnType<typeof getReleases>>;
