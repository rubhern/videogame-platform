import { expect, test } from "@playwright/test";
import { analyzeAccessibility } from "./fixtures/accessibility";

const applicationOrigin = new URL(process.env.PLAYWRIGHT_BASE_URL ?? "http://application:8080").origin;
const username = process.env.OIDC_TEST_USERNAME;
const password = process.env.OIDC_TEST_PASSWORD;

test.skip(!username || !password, "The real Keycloak compatibility topology is not active.");

test("real Keycloak smoke account establishes and terminates only an opaque BFF session", async ({
  context,
  page,
}) => {
  const browserRequests: string[] = [];
  page.on("request", (request) => browserRequests.push(request.url()));

  await page.goto("/auth/login/keycloak");
  await expect(page).toHaveURL(/\/realms\/videogame-platform\/protocol\/openid-connect\/auth/);
  const authorizationSessionCookie = (
    await context.cookies(applicationOrigin)
  ).find((cookie) => cookie.name === "vgp_session");
  expect(authorizationSessionCookie).toBeDefined();

  await page.getByLabel("Usuario", { exact: true }).fill(username ?? "");
  await page.locator("#password").fill(password ?? "");
  await page.getByRole("button", { name: "Iniciar sesión", exact: true }).click();

  await expect(page).toHaveURL(`${applicationOrigin}/`);
  expect(browserRequests).not.toContainEqual(
    expect.stringMatching(/\/login-actions\/required-action(?:\?|$)/),
  );
  const authenticatedSession = await sessionState(page);
  expect(authenticatedSession).toEqual({
    authenticated: true,
    csrfToken: expect.any(String),
  });
  if (!("csrfToken" in authenticatedSession)) {
    throw new Error("The authenticated session did not expose CSRF material.");
  }

  const applicationCookies = await context.cookies(applicationOrigin);
  expect(applicationCookies).toHaveLength(1);
  const sessionCookie = applicationCookies[0];
  expect(sessionCookie?.name).toBe("vgp_session");
  expect(sessionCookie?.httpOnly).toBe(true);
  expect(sessionCookie?.secure).toBe(false);
  expect(sessionCookie?.sameSite).toBe("Lax");
  expect(sessionCookie?.domain).toBe(new URL(applicationOrigin).hostname);
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
  expect(await context.cookies(applicationOrigin)).toEqual([]);
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

test("account entry returns to the game without inventing a rating and logout preserves browsing", async ({ page }) => {
  const gamePath = "/games/30000000-0000-4000-8000-000000000005/resident-evil-requiem?platformId=platform_ps5&regionId=region_europe";
  await page.goto(gamePath);
  await page.getByRole("link", { name: "Iniciar sesión", exact: true }).click();
  await expect(page).toHaveURL(/\/realms\/videogame-platform\/protocol\/openid-connect\/auth/);
  await page.getByLabel("Usuario", { exact: true }).fill(username ?? "");
  await page.locator("#password").fill(password ?? "");
  await page.getByRole("button", { name: "Iniciar sesión", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(gamePath.replace("?", "\\?")));
  await expect(page.getByText(/Puntuación guardada/)).toHaveCount(0);
  const pending = await page.request.get("/auth/rating-intent");
  expect(pending.status()).toBe(404);
  await page.getByRole("button", { name: "Mi cuenta" }).click();
  await expect(page.getByRole("link", { name: "Mis puntuaciones", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Cerrar sesión" }).click();
  await expect(page.getByRole("link", { name: "Iniciar sesión", exact: true })).toBeVisible();
  await expect(page).toHaveURL(new RegExp(gamePath.replace("?", "\\?")));
});

for (const width of [1320, 390, 320]) {
  test(`Gameómetro sign-in, registration, recovery and errors render accessibly at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 950 });
    await page.goto("/auth/start");
    await expect(page.locator("#kc-header-wrapper")).toHaveText("Gameómetro");
    await expect(page.locator("html")).toHaveAttribute("lang", "es");
    await expect(page.getByText(/VideoGame Platform/i)).toHaveCount(0);
    await expect(page.getByText("Tu criterio.", { exact: true })).toBeVisible();
    await expect(page.getByRole("link", { name: "Volver al catálogo", exact: true })).toHaveAttribute("href", `${applicationOrigin}/`);
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath(`keycloak-login-${width}.png`), fullPage: true });
    await page.getByRole("link", { name: "Crear cuenta", exact: true }).click();
    await expect(page.getByRole("heading", { name: "Empieza tu historia." })).toBeVisible();
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath(`keycloak-registration-${width}.png`), fullPage: true });
    await page.getByRole("link", { name: /Volver.*iniciar sesión/ }).click();
    await page.getByRole("link", { name: /Olvidado|olvidado/ }).click();
    await expect(page.getByRole("heading", { name: "Recupera tu acceso" })).toBeVisible();
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
    await page.screenshot({ path: testInfo.outputPath(`keycloak-recovery-${width}.png`), fullPage: true });
    await page.getByRole("link", { name: /Volver.*iniciar sesión/ }).click();
    await page.locator("#username").fill("nonexistent-review-player");
    await page.locator("#password").fill("incorrect-synthetic-password");
    await page.getByRole("button", { name: "Iniciar sesión", exact: true }).click();
    await expect(page.locator(".kc-feedback-text").first()).toBeVisible();
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
    await page.screenshot({ path: testInfo.outputPath(`keycloak-error-${width}.png`), fullPage: true });
    await page.emulateMedia({ reducedMotion: "reduce" });
    expect(await page.evaluate(() => document.getAnimations().filter((animation) => animation.playState === "running").length)).toBe(0);
    await page.emulateMedia({ forcedColors: "active" });
    await expect(page.getByRole("heading", { name: "Bienvenido, Jugón" })).toBeVisible();
  });

  test(`Gameómetro password reset renders accessibly at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 950 });
    await page.goto("/auth/start");
    // Exercise Keycloak's inherited required-action template without adding account management.
    const authorizationUrl = new URL(page.url());
    authorizationUrl.searchParams.set("kc_action", "UPDATE_PASSWORD");
    await page.goto(authorizationUrl.toString());
    await page.getByLabel("Usuario", { exact: true }).fill(username ?? "");
    await page.locator("#password").fill(password ?? "");
    await page.getByRole("button", { name: "Iniciar sesión", exact: true }).click();
    await expect(page.getByRole("heading", { name: "Elige una nueva contraseña" })).toBeVisible();
    await expect(page.locator("#password-new")).toBeVisible();
    await expect(page.locator("#password-confirm")).toBeVisible();
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath(`keycloak-reset-${width}.png`), fullPage: true });
  });
}

test("an external return target falls back to the landing page after real authentication", async ({ page }) => {
  await page.goto("/auth/start?returnTo=" + encodeURIComponent("//attacker.example/steal"));
  await page.getByLabel("Usuario", { exact: true }).fill(username ?? "");
  await page.locator("#password").fill(password ?? "");
  await page.getByRole("button", { name: "Iniciar sesión", exact: true }).click();
  await expect(page).toHaveURL(`${applicationOrigin}/`);
  expect(await sessionState(page)).toMatchObject({ authenticated: true });
  const unicodeReturn = await page.request.get(
    "/auth/start?returnTo=" + encodeURIComponent("/search?q=Pokémon"),
    { maxRedirects: 0 },
  );
  expect(unicodeReturn.status()).toBe(302);
  expect(unicodeReturn.headers().location).toBe("/search?q=Pok%C3%A9mon");
});

test("direct personal-ratings account entry and logout return to sensible product pages", async ({ page }, testInfo) => {
  await page.goto("/auth/start?returnTo=%2Fmis-puntuaciones");
  await page.getByLabel("Usuario", { exact: true }).fill(username ?? "");
  await page.locator("#password").fill(password ?? "");
  await page.getByRole("button", { name: "Iniciar sesión", exact: true }).click();
  await expect(page).toHaveURL(`${applicationOrigin}/mis-puntuaciones`);
  await expect(page.getByRole("heading", { name: "Mis puntuaciones", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Mi cuenta" }).click();
  expect((await analyzeAccessibility(page)).violations).toEqual([]);
  await page.screenshot({ path: testInfo.outputPath("product-account-desktop.png"), fullPage: true });
  await page.setViewportSize({ width: 390, height: 950 });
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  expect((await analyzeAccessibility(page)).violations).toEqual([]);
  await page.screenshot({ path: testInfo.outputPath("product-account-mobile.png"), fullPage: true });
  await page.getByRole("button", { name: "Cerrar sesión" }).click();
  await expect(page).toHaveURL(`${applicationOrigin}/`);
  await expect(page.getByRole("button", { name: "Acceder a tu cuenta" })).toBeVisible();
});

for (const width of [1320, 390]) {
  test(`create account opens Keycloak registration directly at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 950 });
    await page.goto("/games/30000000-0000-4000-8000-000000000005/resident-evil-requiem");
    if (width < 620) await page.getByRole("button", { name: "Acceder a tu cuenta" }).click();
    await page.getByRole("link", { name: "Crear cuenta", exact: true }).click();
    await expect(page.getByRole("heading", { name: "Empieza tu historia." })).toBeVisible();
    expect(new URL(page.url()).searchParams.get("prompt")).toBe("create");
    expect(new URL(page.url()).searchParams.get("code_challenge_method")).toBe("S256");
    await expect(page.locator("html")).toHaveAttribute("lang", "es");
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
  });
}

test("direct self-registration authenticates and returns to the game without a rating intent", async ({ page }) => {
  const game = "/games/30000000-0000-4000-8000-000000000005/resident-evil-requiem";
  await page.goto(game);
  await page.getByRole("link", { name: "Crear cuenta", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Empieza tu historia." })).toBeVisible();
  const unique = `account-${Date.now()}`;
  await page.locator("#firstName").fill("Synthetic");
  await page.locator("#lastName").fill("Player");
  await page.locator("#email").fill(`${unique}@example.test`);
  await page.locator("#username").fill(unique);
  await page.locator("#password").fill("Str0ng-Passw0rd!");
  await page.locator("#password-confirm").fill("Str0ng-Passw0rd!");
  await page.getByRole("button", { name: "Crear cuenta", exact: true }).click();
  await expect(page).toHaveURL(`${applicationOrigin}${game}`);
  expect(await sessionState(page)).toMatchObject({ authenticated: true });
  expect((await page.request.get("/auth/rating-intent")).status()).toBe(404);
  await expect(page.getByRole("button", { name: "Mi cuenta" })).toBeVisible();
});

test("legacy login is only a server redirect into the hosted sign-in flow", async ({ page }) => {
  const response = await page.request.get("/login?returnTo=%2Fsearch%3Fq%3Dzelda", { maxRedirects: 0 });
  expect(response.status()).toBe(302);
  expect(response.headers().location).toBe("/auth/login/keycloak");
  expect((await response.body()).length).toBe(0);
  await page.goto("/login");
  await expect(page.getByRole("heading", { name: "Bienvenido, Jugón" })).toBeVisible();
});
