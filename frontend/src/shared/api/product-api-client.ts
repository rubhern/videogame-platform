import createClient from "openapi-fetch";

import type { paths } from "./generated/schema";

const DEFAULT_API_BASE_URL =
  typeof window === "undefined" ? "/api/v1" : new URL("/api/v1", window.location.origin).toString();

type ProductApiClientOptions = {
  baseUrl?: string;
  fetch?: typeof globalThis.fetch;
};

/**
 * Creates the low-level, contract-typed same-origin transport.
 *
 * Feature slices must wrap this client in product-facing functions instead of
 * importing it directly into React components.
 */
export function createProductApiClient(options: ProductApiClientOptions = {}) {
  return createClient<paths>({
    baseUrl: options.baseUrl ?? DEFAULT_API_BASE_URL,
    credentials: "same-origin",
    ...(options.fetch === undefined ? {} : { fetch: options.fetch }),
  });
}

export type ProductApiClient = ReturnType<typeof createProductApiClient>;

export const productApiClient = createProductApiClient({
  fetch: (...args) => globalThis.fetch(...args),
});
