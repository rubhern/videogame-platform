import type { components } from "../../shared/api/generated/schema";
import { productApiClient, type ProductApiClient } from "../../shared/api/product-api-client";

export type SynchronizationStatus = components["schemas"]["CatalogueSynchronizationStatus"];
export type CurrentRun = components["schemas"]["CurrentSynchronizationRun"];
export type FinishedRun = components["schemas"]["FinishedSynchronizationRun"];
export type SynchronizationCoverage = components["schemas"]["CatalogueSynchronizationCoverage"];
export type CoveragePeriod = components["schemas"]["CoveragePeriod"];

/** Session-scoped: logout removes every `["me"]` query, so operator evidence never outlives it. */
export const ADMINISTRATION_KEY = ["me", "catalogue-administration"] as const;

/** A failed administration read; `code` is the stable Problem code when the server sent one. */
export class AdministrationError extends Error {
  constructor(
    readonly code: components["schemas"]["ProblemCode"] | null,
    readonly correlationId: string | null,
  ) {
    super("Catalogue administration could not be read.");
  }
}

function failure(response: Response, error: { code?: components["schemas"]["ProblemCode"]; correlationId?: string } | undefined) {
  return new AdministrationError(
    error?.code ?? (response.status === 401 ? "AUTHENTICATION_REQUIRED" : response.status === 403 ? "ACCESS_DENIED" : null),
    error?.correlationId ?? response.headers.get("X-Correlation-ID"),
  );
}

/** The run in flight, if any, and the bounded recent run history. */
export async function getSynchronizationStatus(signal?: AbortSignal,
  client: ProductApiClient = productApiClient): Promise<SynchronizationStatus> {
  const { data, error, response } = await client.GET("/admin/catalogue/synchronization", {
    cache: "no-store", ...(signal ? { signal } : {}),
  }).catch(() => { throw new AdministrationError(null, null); });
  if (data !== undefined) return data;
  throw failure(response, error);
}

/** Covered and never-covered periods of an inclusive ISO date range, bounded by the server. */
export async function getSynchronizationCoverage(range: { from: string; to: string }, signal?: AbortSignal,
  client: ProductApiClient = productApiClient): Promise<SynchronizationCoverage> {
  const { data, error, response } = await client.GET("/admin/catalogue/synchronization-coverage", {
    params: { query: range }, cache: "no-store", ...(signal ? { signal } : {}),
  }).catch(() => { throw new AdministrationError(null, null); });
  if (data !== undefined) return data;
  throw failure(response, error);
}
