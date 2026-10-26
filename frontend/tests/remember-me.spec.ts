import { reachFeaturedAfterSignIn } from "./fixtures/sign-in-destination";
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { expect, test, type APIRequestContext, type Browser, type BrowserContext, type Page } from "@playwright/test";

const app = process.env.PLAYWRIGHT_BASE_URL ?? "http://application:8080";
const keycloak = process.env.KEYCLOAK_TEST_ORIGIN ?? "";
const issuer = `${keycloak}/realms/videogame-platform`;
const adminRealm = `${keycloak}/admin/realms/videogame-platform`;
const timed = process.env.REMEMBER_ME_TIMED === "true";
const idle = process.env.REMEMBER_ME_IDLE === "true";
const restart = process.env.REMEMBER_ME_RESTART_PHASE ?? "";
const evidence = "/evidence/restart-cookies.json";
test.skip(!keycloak, "Run with the disposable identity harness.");
test.describe.configure({ mode: "serial", timeout: 120_000 });

async function admin(request: APIRequestContext) {
  const token = await request.post(`${keycloak}/realms/master/protocol/openid-connect/token`, {
    form: { client_id: "admin-cli", grant_type: "password", username: process.env.KEYCLOAK_TEST_ADMIN_USERNAME ?? "",
      password: process.env.KEYCLOAK_TEST_ADMIN_PASSWORD ?? "" },
  });
  expect(token.status()).toBe(200);
  return { Authorization: `Bearer ${(await token.json()).access_token as string}` };
}

async function player(request: APIRequestContext) {
  const username = `remember-${crypto.randomUUID()}`;
  const password = `Remember-${crypto.randomUUID()}!`;
  const created = await request.post(`${adminRealm}/users`, { headers: await admin(request), data: {
    username, email: `${username}@example.test`, emailVerified: true, enabled: true,
    firstName: "Synthetic", lastName: "Remember", credentials: [{ type: "password", value: password, temporary: false }],
  } });
  expect(created.status()).toBe(201);
  return { username, password, id: new URL(created.headers().location ?? "").pathname.split("/").pop() ?? "" };
}

async function state(page: Page): Promise<{ authenticated: boolean; csrfToken?: string }> {
  return page.evaluate(async () => {
    const response = await fetch("/api/v1/session", { credentials: "same-origin" });
    if (!response.ok) throw new Error("Session unavailable");
    return response.json();
  });
}

async function entered(page: Page) {
  await reachFeaturedAfterSignIn(page, new URL(app).origin);
}

async function signIn(browser: Browser, request: APIRequestContext, remembered: boolean) {
  const account = await player(request);
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto(`${app}/auth/login/keycloak?rememberMe=true&gameometro_remember_me=true`);
  await expect(page.locator("#rememberMe")).not.toBeChecked();
  await page.locator("#username").fill(account.username);
  await page.locator("#password").fill(account.password);
  if (remembered) await page.locator("#rememberMe").check();
  await page.locator("#kc-login").click();
  await entered(page);
  expect(await state(page)).toMatchObject({ authenticated: true });
  return { context, page, account };
}

async function persistentRestart(browser: Browser, context: BrowserContext) {
  const cookies = (await context.cookies()).filter(cookie => cookie.expires > 0);
  await context.close();
  const restarted = await browser.newContext();
  await restarted.addCookies(cookies);
  return restarted;
}

test("sign-in separates Remember Me and password recovery at every supported width", async ({ browser }) => {
  test.skip(timed || idle || !!restart);
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto(`${app}/auth/login/keycloak`);
  await expect(page.locator("#rememberMe")).not.toBeChecked();
  for (const width of [320, 390, 834, 1320]) {
    await page.setViewportSize({ width, height: 960 });
    const remember = page.locator('label[for="rememberMe"]');
    const recovery = page.getByRole("link", { name: "¿Has olvidado tu contraseña?", exact: true });
    await expect(remember).toBeVisible();
    await expect(recovery).toBeVisible();
    const rememberBox = await remember.boundingBox();
    const recoveryBox = await recovery.boundingBox();
    expect(rememberBox).not.toBeNull();
    expect(recoveryBox).not.toBeNull();
    expect(recoveryBox?.y ?? 0).toBeGreaterThanOrEqual((rememberBox?.y ?? 0) + (rememberBox?.height ?? 0));
    expect(await remember.locator("span").evaluate(label => label.getBoundingClientRect().height)).toBeLessThan(30);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    await page.screenshot({ path: test.info().outputPath(`signin-${width}.png`), fullPage: true });
  }
  await context.close();
});

test("both modes retain session-only BFF cookies; only opt-in survives simulated browser restart", async ({ browser, request }) => {
  test.skip(timed || !!restart);
  for (const remembered of [false, true]) {
    const device = await signIn(browser, request, remembered);
    const [cookie] = await device.context.cookies(app);
    expect(cookie).toMatchObject({ name: "vgp_session", expires: -1, httpOnly: true, sameSite: "Lax" });
    const identity = (await device.context.cookies(`${issuer}/`)).find(cookie => cookie.name === "KEYCLOAK_IDENTITY");
    expect(identity).toBeDefined();
    expect((identity?.expires ?? -1) > 0).toBe(remembered);
    expect(await device.page.evaluate(() => [document.cookie, localStorage.length, sessionStorage.length])).toEqual(["", 0, 0]);
    await device.page.close();
    const tab = await device.context.newPage();
    await tab.goto(app);
    expect(await state(tab)).toMatchObject({ authenticated: true });
    const restarted = await persistentRestart(browser, device.context);
    const page = await restarted.newPage();
    await page.goto(app);
    expect(await state(page)).toEqual({ authenticated: false });
    if (!remembered) {
      for (const width of [320, 390, 834, 1320]) {
        await page.setViewportSize({ width, height: 900 });
        await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
      }
      await page.screenshot({ path: test.info().outputPath("anonymous-account-entry.png") });
    }
    await page.goto(`${app}/auth/login/keycloak`);
    if (remembered) {
      await entered(page);
      expect(await state(page)).toMatchObject({ authenticated: true });
      await restarted.clearCookies({ name: "vgp_session" });
      await restarted.clearCookies({ name: "KEYCLOAK_IDENTITY" });
      await page.goto(`${app}/auth/login/keycloak`);
      // A persistent username hint must not preselect the next credential form.
      await expect(page.locator("#rememberMe")).not.toBeChecked();
    } else await expect(page.locator("#username")).toBeVisible();
    await restarted.close();
  }
  const privateContext = await browser.newContext();
  const privatePage = await privateContext.newPage();
  await privatePage.goto(`${app}/auth/login/keycloak`);
  await expect(privatePage.locator("#username")).toBeVisible();
  await privateContext.close();
});

test("remembered logout isolates devices and owner revocation still ends every remaining BFF", async ({ browser, request }) => {
  test.skip(timed || !!restart);
  const first = await signIn(browser, request, true);
  const other = await signIn(browser, request, true);
  const csrf = (await state(first.page)).csrfToken ?? "";
  expect((await first.page.request.post(`${app}/api/v1/session`, { headers: { "X-CSRF-Token": csrf } })).status()).toBe(204);
  await first.page.goto(`${app}/auth/logout`);
  await entered(first.page);
  expect(await state(other.page)).toMatchObject({ authenticated: true });
  await first.page.goto(`${app}/auth/login/keycloak`);
  await expect(first.page.locator("#username")).toBeVisible();
  // Switch account on the logged-out browser; it must require credentials, not reuse old SSO.
  await first.page.locator("#username").fill(other.account.username);
  await first.page.locator("#password").fill(other.account.password);
  await first.page.locator("#rememberMe").check();
  await first.page.locator("#kc-login").click();
  await expect(first.page).toHaveURL(`${app}/lanzamientos/destacados`);
  const headers = await admin(request);
  expect((await request.put(`${adminRealm}/users/${other.account.id}`, { headers, data: { enabled: false } })).status()).toBe(204);
  expect((await request.post(`${adminRealm}/users/${other.account.id}/logout`, { headers })).status()).toBe(204);
  const bytes = createHash("md5").update(`${issuer}\n${other.account.id}`).digest();
  bytes[6] = ((bytes[6] ?? 0) & 15) | 48;
  bytes[8] = ((bytes[8] ?? 0) & 63) | 128;
  const hex = bytes.toString("hex");
  const userId = `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  const revoked = await request.post(`${process.env.APPLICATION_MANAGEMENT_ORIGIN}/actuator/sessionrevocation`, { data: { userId } });
  expect(revoked.status()).toBe(200);
  expect(await revoked.json()).toMatchObject({ revokedSessions: 2 });
  expect(await state(first.page)).toEqual({ authenticated: false });
  expect(await state(other.page)).toEqual({ authenticated: false });
  await other.page.goto(`${app}/auth/login/keycloak`);
  await expect(other.page.locator("#username")).toBeVisible();
  await first.context.close();
  await other.context.close();
});

for (const mode of ["ordinary", "remembered", "missing", "malformed"] as const) {
  test(`${mode}: timed absolute expiry cannot create an immediately expired silent BFF`, async ({ browser, request }) => {
    test.skip(!timed || !!restart);
    const device = await signIn(browser, request, mode !== "ordinary");
    const headers = await admin(request);
    const clients = await (await request.get(`${adminRealm}/clients?clientId=videogame-platform-bff`, { headers })).json();
    const client = clients[0] as { id: string; protocolMappers: { id: string; name: string; protocolMapper: string; config: object }[] };
    const mapper = client.protocolMappers.find(mapper => mapper.protocolMapper === "gameometro-remember-me");
    expect(mapper).toBeDefined();
    const mapperPath = `${adminRealm}/clients/${client.id}/protocol-mappers/models/${mapper?.id}`;
    try {
      if (mode === "missing") expect((await request.delete(mapperPath, { headers })).status()).toBe(204);
      if (mode === "malformed") expect((await request.put(mapperPath, { headers, data: { ...mapper,
        protocolMapper: "oidc-hardcoded-claim-mapper", config: { "claim.name": "gameometro_remember_me", "claim.value": "true", "jsonType.label": "String", "id.token.claim": "true" },
      } })).status()).toBe(204);
      // Polling keeps idle alive: the 15-second absolute bound must still win.
      await expect.poll(() => state(device.page), { timeout: 30_000, intervals: [1000] }).toEqual({ authenticated: false });
      await device.page.goto(`${app}/auth/login/keycloak`);
      if (mode === "remembered") {
        await entered(device.page);
        expect(await state(device.page)).toMatchObject({ authenticated: true });
      } else await expect(device.page.locator("#password")).toBeVisible();
    } finally {
      const currentHeaders = await admin(request);
      if (mode === "missing") await request.post(`${adminRealm}/clients/${client.id}/protocol-mappers/models`, { headers: currentHeaders, data: { ...mapper, id: undefined } });
      else if (mode === "malformed") await request.put(mapperPath, { headers: currentHeaders, data: mapper });
      await device.context.close();
    }
  });
}

test("timed idle expiry permits logout confirmation without a persistent 403", async ({ browser, request }) => {
  test.skip(!idle || !!restart);
  const device = await signIn(browser, request, true);
  const csrf = (await state(device.page)).csrfToken ?? "";
  // Tomcat rounds servlet idle timeout to whole minutes (minimum one). Use a real idle
  // minute with a longer absolute bound; second-scale overrides cannot prove idle expiry.
  await new Promise(resolve => setTimeout(resolve, 70_000));
  expect(await state(device.page)).toEqual({ authenticated: false });
  expect((await device.page.request.post(`${app}/api/v1/session`, { headers: { "X-CSRF-Token": csrf } })).status()).toBe(403);
  // Keep the last authenticated UI visible: the session expires before this click reaches the BFF.
  await device.page.getByRole("button", { name: "Mi cuenta", exact: true }).click();
  const rejectedLogout = device.page.waitForResponse(response =>
    new URL(response.url()).pathname === "/api/v1/session" && response.request().method() === "POST");
  await device.page.getByRole("button", { name: "Cerrar sesión", exact: true }).click();
  expect((await rejectedLogout).status()).toBe(403);
  await expect(device.page.locator("#kc-logout")).toBeVisible();
  await device.page.locator("#kc-logout").click();
  await expect(device.page).toHaveURL(`${app}/lanzamientos/destacados`);
  await device.page.goto(`${app}/auth/login/keycloak`);
  await expect(device.page.locator("#username")).toBeVisible();
  await device.context.close();
});

test("timed SSO idle can expire while BFF activity continues", async ({ browser, request }) => {
  test.skip(!timed || !!restart);
  const device = await signIn(browser, request, true);
  const headers = await admin(request);
  const saved = await (await request.get(adminRealm, { headers })).json() as { ssoSessionIdleTimeout: number; ssoSessionIdleTimeoutRememberMe: number };
  try {
    // Native Remember Me uses at least the ordinary idle bound, so scale both fields.
    expect((await request.put(adminRealm, { headers, data: { ssoSessionIdleTimeout: 1, ssoSessionIdleTimeoutRememberMe: 2 } })).status()).toBe(204);
    // No visit/refresh to Keycloak during this shortened equivalent of seven idle days.
    await new Promise(resolve => setTimeout(resolve, 3500));
    expect(await state(device.page)).toMatchObject({ authenticated: true });
    await device.context.clearCookies({ name: "vgp_session" });
    await device.page.goto(`${app}/auth/login/keycloak`);
    await expect(device.page.locator("#username")).toBeVisible();
  } finally {
    await request.put(adminRealm, { headers: await admin(request), data: { ssoSessionIdleTimeout: saved.ssoSessionIdleTimeout, ssoSessionIdleTimeoutRememberMe: saved.ssoSessionIdleTimeoutRememberMe } });
    await device.context.close();
  }
});

test("timed remembered renewal never resets the original global cap", async ({ browser, request }) => {
  test.skip(!timed || !!restart);
  const device = await signIn(browser, request, true);
  let renewals = 0;
  for (let attempt = 0; attempt < 5; attempt++) {
    await expect.poll(() => state(device.page), { timeout: 30_000, intervals: [1000] }).toEqual({ authenticated: false });
    await device.page.goto(`${app}/auth/login/keycloak`);
    if (new URL(device.page.url()).origin === new URL(issuer).origin) {
      await expect(device.page.locator("#password")).toBeVisible();
      break;
    }
    await entered(device.page);
    expect(await state(device.page)).toMatchObject({ authenticated: true });
    renewals++;
  }
  expect(renewals).toBeGreaterThan(0);
  await expect(device.page.locator("#password")).toBeVisible();
  await device.context.close();
});

test("restart continuity", async ({ browser, request }) => {
  test.skip(!restart);
  if (restart === "before") {
    const device = await signIn(browser, request, true);
    writeFileSync(evidence, JSON.stringify(await device.context.cookies()), { mode: 0o600 });
    await device.context.close();
    return;
  }
  const context = await browser.newContext();
  await context.addCookies(JSON.parse(readFileSync(evidence, "utf8")) as Awaited<ReturnType<BrowserContext["cookies"]>>);
  const page = await context.newPage();
  await page.goto(app);
  expect((await state(page)).authenticated).toBe(restart === "keycloak");
  await context.clearCookies({ name: "vgp_session" });
  await page.goto(`${app}/auth/login/keycloak`);
  await entered(page);
  expect(await state(page)).toMatchObject({ authenticated: true });
  writeFileSync(evidence, JSON.stringify(await context.cookies()), { mode: 0o600 });
  await context.close();
});
