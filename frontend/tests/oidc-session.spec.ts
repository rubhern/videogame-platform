import { expect, test } from "@playwright/test";

const username = process.env.OIDC_TEST_USERNAME;
const password = process.env.OIDC_TEST_PASSWORD;

test.skip(!username || !password, "The real Keycloak compatibility topology is not active.");

test("real Keycloak establishes and terminates only an opaque BFF session", async ({
  context,
  page,
}) => {
  const browserRequests: string[] = [];
  page.on("request", (request) => browserRequests.push(request.url()));

  await page.goto("/auth/login/keycloak");
  await expect(page).toHaveURL(/\/realms\/videogame-platform\/protocol\/openid-connect\/auth/);
  const authorizationSessionCookie = (
    await context.cookies("http://application:8080")
  ).find((cookie) => cookie.name === "vgp_session");
  expect(authorizationSessionCookie).toBeDefined();

  await page.getByLabel("Username").fill(username ?? "");
  await page.locator("#password").fill(password ?? "");
  await page.getByRole("button", { name: "Sign In" }).click();

  await expect(page).toHaveURL(/http:\/\/application:8080\/$/);
  const authenticatedSession = await sessionState(page);
  expect(authenticatedSession).toEqual({
    authenticated: true,
    csrfToken: expect.any(String),
  });
  if (!("csrfToken" in authenticatedSession)) {
    throw new Error("The authenticated session did not expose CSRF material.");
  }

  const applicationCookies = await context.cookies("http://application:8080");
  expect(applicationCookies).toHaveLength(1);
  const sessionCookie = applicationCookies[0];
  expect(sessionCookie?.name).toBe("vgp_session");
  expect(sessionCookie?.httpOnly).toBe(true);
  expect(sessionCookie?.secure).toBe(false);
  expect(sessionCookie?.sameSite).toBe("Lax");
  expect(sessionCookie?.domain).toBe("application");
  expect(sessionCookie?.value.split(".")).not.toHaveLength(3);
  expect(sessionCookie?.value).not.toBe(authorizationSessionCookie?.value);

  const browserVisibleState = await page.evaluate(() => ({
    cookie: document.cookie,
    localStorage: { ...window.localStorage },
    sessionStorage: { ...window.sessionStorage },
    url: window.location.href,
  }));
  expect(browserVisibleState.cookie).toBe("");
  expect(browserVisibleState.localStorage).toEqual({});
  expect(browserVisibleState.sessionStorage).toEqual({});
  expect(browserVisibleState.url).not.toMatch(
    /(?:access_token|refresh_token|id_token|code|state|session_state)=/i,
  );
  expect(browserRequests).not.toContainEqual(
    expect.stringMatching(/\/protocol\/openid-connect\/token(?:\?|$)/),
  );

  const rejectedLogout = await page.evaluate(async () => {
    const response = await fetch("/api/v1/session", {
      method: "POST",
      credentials: "same-origin",
    });
    return { body: await response.json(), status: response.status };
  });
  expect(rejectedLogout.status).toBe(403);
  expect(rejectedLogout.body).toMatchObject({ code: "CSRF_VALIDATION_FAILED" });
  expect(await sessionState(page)).toMatchObject({ authenticated: true });

  const logoutStatus = await page.evaluate(async (csrfToken) => {
    const response = await fetch("/api/v1/session", {
      method: "POST",
      credentials: "same-origin",
      headers: { "X-CSRF-Token": csrfToken },
    });
    return response.status;
  }, authenticatedSession.csrfToken);
  expect(logoutStatus).toBe(204);
  expect(await sessionState(page)).toEqual({ authenticated: false });
  expect(await context.cookies("http://application:8080")).toEqual([]);
});

async function sessionState(page: import("@playwright/test").Page) {
  return page.evaluate(async () => {
    const response = await fetch("/api/v1/session", {
      credentials: "same-origin",
      headers: { Accept: "application/json" },
    });
    if (!response.ok) {
      throw new Error(`Session request failed with HTTP ${response.status}.`);
    }
    return (await response.json()) as
      | { authenticated: false }
      | { authenticated: true; csrfToken: string };
  });
}
