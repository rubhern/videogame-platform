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

/** BFF navigation route that ends the browser's identity-provider session after `logout`. */
export const LOGOUT_COMPLETION_PATH = "/auth/logout";

/**
 * Full document navigation through the BFF to the identity provider's logout and back to the
 * application root, so the next sign-in on this browser asks for credentials again.
 */
export function completeLogout(): void {
  window.location.assign(LOGOUT_COMPLETION_PATH);
}
