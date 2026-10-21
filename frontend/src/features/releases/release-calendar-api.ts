import type { operations } from "../../shared/api/generated/schema";
import {
  productApiClient,
  type ProductApiClient,
} from "../../shared/api/product-api-client";
import { ReleasesApiError } from "./releases-api";

export type ReleaseCalendarQuery = NonNullable<
  operations["getReleaseCalendar"]["parameters"]["query"]
>;

/** One month's per-day game counts (#250); a failure carries the release reads' own error. */
export async function getReleaseCalendar(
  query: ReleaseCalendarQuery,
  client: ProductApiClient = productApiClient,
) {
  const { data, error, response } = await client.GET("/release-calendar", {
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

export type ReleaseCalendar = Awaited<ReturnType<typeof getReleaseCalendar>>;
