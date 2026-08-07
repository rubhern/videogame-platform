import createClient from "openapi-fetch";

import type { paths } from "./generated/schema";

const DEFAULT_API_BASE_URL = "/api/v1";

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
