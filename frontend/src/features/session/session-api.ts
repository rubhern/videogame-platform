import {
  productApiClient,
  type ProductApiClient,
} from "../../shared/api/product-api-client";

export type SessionState =
  | { authenticated: false }
  | { authenticated: true; csrfToken: string };

/** Reads the minimal BFF session state. Tokens and identity never reach the browser. */
export async function getSession(
  client: ProductApiClient = productApiClient,
  signal?: AbortSignal,
): Promise<SessionState> {
  const { data } = await client.GET("/session", {
    ...(signal ? { signal } : {}),
  });
  if (data && data.authenticated === true && "csrfToken" in data) {
    return { authenticated: true, csrfToken: data.csrfToken };
  }
  return { authenticated: false };
}

export class LogoutError extends Error {
  constructor() {
    super("The logout request failed.");
  }
}

/** Terminates the BFF session with the session-bound CSRF proof. */
export async function logout(
  csrfToken: string,
  client: ProductApiClient = productApiClient,
): Promise<void> {
  const { response } = await client.POST("/session", {
    params: { header: { "X-CSRF-Token": csrfToken } },
  });
  if (!response.ok) {
    throw new LogoutError();
  }
}
