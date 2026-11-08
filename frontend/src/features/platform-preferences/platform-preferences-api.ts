import type { components } from "../../shared/api/generated/schema";
import {
  productApiClient,
  type ProductApiClient,
} from "../../shared/api/product-api-client";
import type { PlatformOption } from "./interests";

export type PlatformPreferences = components["schemas"]["PlatformPreferences"];

/** Why a preferred-platform read or command did not succeed, in the UI's closed vocabulary. */
export type PlatformPreferencesFailure = "authentication" | "csrf" | "validation" | "unavailable";

export class PlatformPreferencesError extends Error {
  constructor(
    readonly kind: PlatformPreferencesFailure,
    readonly correlationId: string | null,
    /** The Problem Details code, when the response carried one (`CATALOGUE_NOT_READY`). */
    readonly code: string | null = null,
  ) {
    super(`The platform preferences request failed (${kind}).`);
  }
}

function failure(status: number, correlationId: string | null, code: string | null = null): PlatformPreferencesError {
  if (status === 401) return new PlatformPreferencesError("authentication", correlationId, code);
  if (status === 403) return new PlatformPreferencesError("csrf", correlationId, code);
  if (status === 400 || status === 422) return new PlatformPreferencesError("validation", correlationId, code);
  return new PlatformPreferencesError("unavailable", correlationId, code);
}

/** The platforms of the local taxonomy that a visitor may choose; a public, cacheable read. */
export async function listPlatforms(
  signal?: AbortSignal,
  client: ProductApiClient = productApiClient,
): Promise<readonly PlatformOption[]> {
  const { data, error, response } = await client.GET("/platforms", signal ? { signal } : {});
  if (data !== undefined) return data.items;
  throw failure(
    response.status,
    error?.correlationId ?? response.headers.get("X-Correlation-ID"),
    error?.code ?? null,
  );
}

/** The session user's own preferred platforms; the server derives the user. */
export async function getMyPlatformPreferences(
  signal?: AbortSignal,
  client: ProductApiClient = productApiClient,
): Promise<PlatformPreferences> {
  const { data, error, response } = await client.GET("/me/platform-preferences", signal ? { signal } : {});
  // A body without the contract's shape is never treated as the user's preferences.
  if (data !== undefined && Array.isArray(data.platforms)) return data;
  if (response.ok) throw new PlatformPreferencesError("unavailable", response.headers.get("X-Correlation-ID"));
  throw failure(response.status, error?.correlationId ?? response.headers.get("X-Correlation-ID"));
}

/** Replaces the whole set; an empty one clears it and still records that a choice was made. */
export async function putMyPlatformPreferences(
  platformIds: readonly string[],
  csrfToken: string,
  client: ProductApiClient = productApiClient,
): Promise<PlatformPreferences> {
  const { data, error, response } = await client.PUT("/me/platform-preferences", {
    params: { header: { "X-CSRF-Token": csrfToken } },
    body: { platformIds: [...platformIds] },
  });
  if (data !== undefined) return data;
  throw failure(response.status, error?.correlationId ?? response.headers.get("X-Correlation-ID"));
}
