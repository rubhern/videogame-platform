import { writeFileSync } from "node:fs";
import { expect, test, type APIRequestContext, type BrowserContext, type Page } from "@playwright/test";

// Runs only inside scripts/validate-production-identity.sh: the packaged BFF and the production
// realm on separate synthetic HTTPS origins with the canonical issuer, and a Mailpit sink.
const applicationOrigin = new URL(process.env.PLAYWRIGHT_BASE_URL ?? "https://gameometro.test").origin;
const identityOrigin = process.env.PRODUCTION_IDENTITY_ORIGIN ?? "";
const mailpitOrigin = process.env.MAILPIT_API_ORIGIN ?? "";
const inventoryFile = process.env.COOKIE_INVENTORY_FILE ?? "";
const sensitiveFile = process.env.SENSITIVE_VALUES_FILE ?? "";
const issuer = `${identityOrigin}/realms/gameometro`;
const identityHost = identityOrigin ? new URL(identityOrigin).hostname : "";
const sessionCookie = "__Host-vgp_session";
const ratedGame = "30000000-0000-4000-8000-000000000005";
// Keycloak 26.7's browser-flow cookies; an unknown name fails the journey until it is reviewed.
const keycloakCookies = new Set(["AUTH_SESSION_ID", "KC_AUTH_SESSION_HASH", "KC_RESTART", "KEYCLOAK_IDENTITY", "KEYCLOAK_LOCALE", "KEYCLOAK_SESSION", "KEYCLOAK_REMEMBER_ME"]);

test.skip(!identityOrigin || !mailpitOrigin, "The production identity topology is not active.");
// The runtime exercise installs its disposable CA in the browser trust store.
// The older identity-only harness retains its existing synthetic-origin behaviour.
test.use({ ignoreHTTPSErrors: process.env.PRODUCTION_RUNTIME_TRUSTED_TLS !== "true" });
test.describe.configure({ mode: "serial" });

interface CookieRecord {
  origin: string;
  name: string;
  domain: string;
  path: string;
  lifetime: string;
  httpOnly: boolean;
  secure: boolean;
  sameSite: string;
  valueBytes: number;
  jwtClaims?: string[];
}

interface StorageRecord {
  origin: string;
  documentCookie: string[];
  localStorage: number;
  sessionStorage: number;
  indexedDb: number;
}

const inventory: { stage: string; cookies: CookieRecord[]; storage?: StorageRecord }[] = [];
const sensitive: string[] = [];
const requestedHosts = new Set<string>();
let identityActionRequests = 0;
const rateRefusals: { origin: "identity" | "product"; retryAfterSeconds: number | null }[] = [];

test.afterAll(() => {
  if (inventoryFile) writeFileSync(inventoryFile, JSON.stringify({ requestedHosts: [...requestedHosts].sort(), identityActionRequests, rateRefusals, inventory }, null, 2));
  if (sensitiveFile) writeFileSync(sensitiveFile, sensitive.map((value) => `${value}\n`).join(""), { flag: "a" });
});

test.afterEach(async ({ browser }, info) => {
  if (info.status === info.expectedStatus || !inventoryFile) return;
  const lines = [...new Set(info.errors.flatMap((error) =>
    [...(error.stack ?? "").matchAll(/production-identity\.spec\.ts:(\d+)/g)].map((match) => Number(match[1])),
  ))];
  // Only fixed classifications/counts; diagnostics must also survive a closed page.
  writeFileSync(inventoryFile + ".failure.json", JSON.stringify({ status: info.status, browserConnected: browser.isConnected(), lines, rateRefusals,
    identityActionRequests, lastStage: inventory.at(-1)?.stage ?? null }));
});

test("registration, session, CSRF, ownership and logout keep tokens out of the browser on separate origins", async ({
  browser,
  request,
}) => {
  const first = await browser.newContext();
  const page = await first.newPage();
  observe(page);

  await page.goto("/");
  await expect(page.getByRole("link", { name: "Iniciar sesión", exact: true })).toBeVisible();
  await record(first, page, "anonymous catalogue visit");
  expect(await first.cookies(applicationOrigin), "an anonymous visit stores nothing").toEqual([]);
  const landing = await page.request.get("/");
  expect(landing.headers()["strict-transport-security"]).toContain("max-age=");

  const email = syntheticEmail("register");
  const password = syntheticPassword();
  await page.goto("/auth/start?intent=register");
  await expect(page).toHaveURL(new RegExp(`^${escape(issuer)}/`));
  await record(first, page, "Keycloak registration form");
  await page.locator("#email").fill(email);
  await page.locator('input[name="termsAccepted"]').check();
  await page.locator("#kc-register-form [type=submit]").click();
  await expect(page.locator("body")).toContainText("Verifica tu email");
  await record(first, page, "registration submitted, verification pending");

  await openEmailAction(page, request, email, 1);
  await expect(page.locator("#password-new")).toBeVisible();
  await record(first, page, "email verification link, password step");
  await page.locator("#password-new").fill(password);
  await page.locator("#password-confirm").fill(password);
  await page.locator("#kc-passwd-update-form [type=submit]").click();
  await completeFirstSignIn(page);

  await expect(page).toHaveURL(`${applicationOrigin}/`);
  const signedIn = await sessionState(page);
  expect(signedIn).toEqual({ authenticated: true, csrfToken: expect.any(String) });
  await record(first, page, "signed in after verification");
  const [cookie] = await first.cookies(applicationOrigin);
  expect(await first.cookies(applicationOrigin)).toHaveLength(1);
  expect(cookie).toMatchObject({ name: sessionCookie, domain: new URL(applicationOrigin).hostname, path: "/", expires: -1, httpOnly: true, secure: true, sameSite: "Lax" });
  expect(cookie?.value.split(".")).not.toHaveLength(3);
  expect(await browserState(page)).toEqual({ origin: applicationOrigin, documentCookie: [], localStorage: 0, sessionStorage: 0, indexedDb: 0 });

  const csrfToken = signedIn.csrfToken ?? "";
  const rating = await page.evaluate(
    async ({ game, token }) => {
      const answer = await fetch(`/api/v1/me/ratings/${game}`, {
        method: "PUT",
        credentials: "same-origin",
        headers: { "Content-Type": "application/json", "If-None-Match": "*", "X-CSRF-Token": token },
        body: JSON.stringify({ value: 7 }),
      });
      return answer.status;
    },
    { game: ratedGame, token: csrfToken },
  );
  expect(rating).toBe(201);

  // The identity origin is the same site as the application, so SameSite=Lax does not stop a
  // request from it; the BFF's Origin and Fetch-Metadata checks must.
  const identityPage = await first.newPage();
  observe(identityPage);
  await identityPage.goto(`${issuer}/.well-known/openid-configuration`);
  await identityPage.evaluate(async (target) => {
    await fetch(`${target}/api/v1/session`, { method: "POST", mode: "no-cors", credentials: "include" });
  }, applicationOrigin);
  await identityPage.close();
  expect(await sessionState(page), "a same-site cross-origin logout is refused").toMatchObject({ authenticated: true });
  const withoutToken = await page.evaluate(async () => (await fetch("/api/v1/session", { method: "POST", credentials: "same-origin" })).status);
  expect(withoutToken).toBe(403);
  expect(await sessionState(page)).toMatchObject({ authenticated: true });

  // A second account sees none of the first account's ratings.
  const second = await browser.newContext();
  const other = await second.newPage();
  observe(other);
  const otherEmail = syntheticEmail("owner");
  await other.goto("/auth/start?intent=register");
  await other.locator("#email").fill(otherEmail);
  await other.locator('input[name="termsAccepted"]').check();
  await other.locator("#kc-register-form [type=submit]").click();
  await openEmailAction(other, request, otherEmail, 1);
  const otherPassword = syntheticPassword();
  await other.locator("#password-new").fill(otherPassword);
  await other.locator("#password-confirm").fill(otherPassword);
  await other.locator("#kc-passwd-update-form [type=submit]").click();
  await completeFirstSignIn(other);
  await expect(other).toHaveURL(`${applicationOrigin}/`);
  expect(await other.evaluate(async (game) => (await fetch(`/api/v1/me/ratings/${game}`)).status, ratedGame)).toBe(404);
  expect(await page.evaluate(async (game) => (await fetch(`/api/v1/me/ratings/${game}`)).status, ratedGame)).toBe(200);
  const otherSession = await sessionState(other);
  expect(await other.evaluate(async ({ game, token }) => (await fetch(`/api/v1/me/ratings/${game}?userId=forged-owner`, {
    method: "PUT", credentials: "same-origin",
    headers: { "Content-Type": "application/json", "If-None-Match": "*", "X-CSRF-Token": token },
    body: JSON.stringify({ value: 3 }),
  })).status, { game: ratedGame, token: otherSession.csrfToken ?? "" })).toBe(201);
  expect(await page.evaluate(async (game) => (await (await fetch(`/api/v1/me/ratings/${game}`)).json()).value, ratedGame)).toBe(7);
  const otherRating = await other.request.get(`/api/v1/me/ratings/${ratedGame}`);
  expect(await other.evaluate(async ({ game, token, version }) => (await fetch(`/api/v1/me/ratings/${game}`, {
    method: "DELETE", credentials: "same-origin", headers: { "X-CSRF-Token": token, "If-Match": version },
  })).status, { game: ratedGame, token: otherSession.csrfToken ?? "", version: otherRating.headers()["etag"] ?? "" })).toBe(200);
  await second.close();

  await page.getByRole("button", { name: "Mi cuenta" }).click();
  // The page already shows the anonymous header at `/` before its document leaves for the
  // identity provider's logout, so wait for the document that the round trip brings back.
  const returned = page.waitForResponse((response) => response.request().isNavigationRequest()
    && response.request().frame() === page.mainFrame() && response.url() === `${applicationOrigin}/`);
  await page.getByRole("button", { name: "Cerrar sesión" }).click();
  await returned;
  await page.waitForLoadState("load");
  await expect(page).toHaveURL(`${applicationOrigin}/`);
  await expect(page.getByRole("link", { name: "Iniciar sesión", exact: true })).toBeVisible();
  await record(first, page, "after logout");
  expect(await first.cookies(applicationOrigin)).toEqual([]);
  // Keycloak scopes its cookies to the realm path, so read the whole jar rather than by URL.
  const identityAfterLogout = (await first.cookies()).filter((stored) => stored.domain === identityHost).map((stored) => stored.name);
  expect(identityAfterLogout).not.toContain("KEYCLOAK_IDENTITY");
  expect(identityAfterLogout).not.toContain("KEYCLOAK_SESSION");
  await page.goto("/auth/start");
  await expect(page.locator("#kc-form-login")).toBeVisible();
  const oldSession = await request.get(`${applicationOrigin}/api/v1/session`, { headers: { Cookie: `${sessionCookie}=${cookie?.value}` } });
  expect(await oldSession.json()).toMatchObject({ authenticated: false });
  await page.locator("#username").fill(email);
  await page.locator("#password").fill(password);
  await expect(page.locator("#rememberMe")).not.toBeChecked();
  await page.locator("#rememberMe").check();
  await page.locator("#kc-login").click();
  await expect(page).toHaveURL(`${applicationOrigin}/`);
  expect(await sessionState(page)).toMatchObject({ authenticated: true });
  await record(first, page, "remembered password login after logout");
  const rememberedCookies = await first.cookies();
  const identity = rememberedCookies.find(cookie => cookie.name === "KEYCLOAK_IDENTITY");
  const hint = rememberedCookies.find(cookie => cookie.name === "KEYCLOAK_REMEMBER_ME");
  expect(identity).toMatchObject({ secure: true, httpOnly: true, sameSite: "None" });
  expect(identity?.expires).toBeGreaterThan(Date.now() / 1000);
  expect(hint).toMatchObject({ secure: true, httpOnly: true, sameSite: "None" });
  expect((hint?.expires ?? 0) - Date.now() / 1000).toBeGreaterThan(364 * 24 * 60 * 60);
  expect((await first.cookies(applicationOrigin))[0]?.expires).toBe(-1);
  await first.close();
  const restarted = await browser.newContext();
  await restarted.addCookies(rememberedCookies.filter(cookie => cookie.expires > 0));
  const resumed = await restarted.newPage();
  observe(resumed);
  await resumed.goto(applicationOrigin);
  expect(await sessionState(resumed)).toEqual({ authenticated: false });
  await resumed.goto(`${applicationOrigin}/auth/login/keycloak`);
  await expect(resumed).toHaveURL(`${applicationOrigin}/`);
  expect(await sessionState(resumed)).toMatchObject({ authenticated: true });
  await record(restarted, resumed, "remembered HTTPS re-entry after simulated restart");
  await restarted.clearCookies({ name: sessionCookie });
  await resumed.goto(`${applicationOrigin}/auth/logout`);
  await expect(resumed.locator("#kc-logout")).toBeVisible();
  await resumed.locator("#kc-logout").click();
  await expect(resumed).toHaveURL(`${applicationOrigin}/`);
  await record(restarted, resumed, "remembered HTTPS logout without BFF cookie");
  expect((await restarted.cookies()).filter(cookie => ["KEYCLOAK_IDENTITY", "KEYCLOAK_SESSION", "KEYCLOAK_REMEMBER_ME"].includes(cookie.name))).toEqual([]);
  await restarted.close();
});

test("password recovery returns through the canonical issuer with the same cookie rules", async ({ browser, request }) => {
  const context = await browser.newContext();
  const page = await context.newPage();
  observe(page);
  const email = syntheticEmail("recovery");
  await page.goto("/auth/start?intent=register");
  await page.locator("#email").fill(email);
  await page.locator('input[name="termsAccepted"]').check();
  await page.locator("#kc-register-form [type=submit]").click();
  await openEmailAction(page, request, email, 1);
  const forgotten = syntheticPassword();
  await page.locator("#password-new").fill(forgotten);
  await page.locator("#password-confirm").fill(forgotten);
  await page.locator("#kc-passwd-update-form [type=submit]").click();
  await completeFirstSignIn(page);
  await expect(page).toHaveURL(`${applicationOrigin}/`);
  await context.close();

  const device = await browser.newContext();
  const recovering = await device.newPage();
  observe(recovering);
  await recovering.goto("/auth/start");
  await recovering.locator('a[href*="/login-actions/reset-credentials"]').click();
  await record(device, recovering, "recovery request form");
  await recovering.locator("#username").fill(email);
  await recovering.locator("#kc-reset-password-form [type=submit]").click();
  await expect(recovering.locator("#kc-form-login")).toBeVisible();
  await openEmailAction(recovering, request, email, 2);
  await expect(recovering.locator("#password-new")).toBeVisible();
  await record(device, recovering, "recovery link, new password step");
  const renewed = syntheticPassword();
  await recovering.locator("#password-new").fill(renewed);
  await recovering.locator("#password-confirm").fill(renewed);
  await recovering.locator("#kc-passwd-update-form [type=submit]").click();
  await expect(recovering).toHaveURL(`${applicationOrigin}/`);
  expect(await sessionState(recovering)).toMatchObject({ authenticated: true });
  await record(device, recovering, "signed in after recovery");
  expect(await browserState(recovering)).toMatchObject({ documentCookie: [], localStorage: 0, sessionStorage: 0, indexedDb: 0 });
  await device.close();
});

test("every stored cookie is first-party, Secure and host-only, and only known hosts were contacted", () => {
  const all = inventory.flatMap((entry) => entry.cookies);
  const observed = new Set(all.map((stored) => stored.name));
  // The inventory is meaningful only if it saw both parties' sessions.
  for (const name of [sessionCookie, "AUTH_SESSION_ID", "KEYCLOAK_IDENTITY"]) expect(observed.has(name), name).toBe(true);
  for (const stored of all) {
    expect(stored.secure, `${stored.name} is Secure`).toBe(true);
    expect(stored.domain.startsWith("."), `${stored.name} is host-only`).toBe(false);
    if (stored.origin === applicationOrigin) {
      expect(stored).toMatchObject({ name: sessionCookie, path: "/", httpOnly: true, sameSite: "Lax", lifetime: "session" });
    } else {
      expect(stored.origin).toBe(identityOrigin);
      expect(keycloakCookies.has(stored.name), `${stored.name} is a reviewed Keycloak cookie`).toBe(true);
      expect(stored.path.startsWith("/realms/gameometro")).toBe(true);
    }
  }
  const allowed = new Set([new URL(applicationOrigin).host, new URL(identityOrigin).host, "images.igdb.com"]);
  expect([...requestedHosts].filter((host) => !allowed.has(host))).toEqual([]);
});

async function openEmailAction(page: Page, request: APIRequestContext, email: string, count: number) {
  const response = await page.goto(await actionLink(request, email, count));
  expect(response?.status(), "the native emailed action is admitted").toBe(200);
}

async function completeFirstSignIn(page: Page) {
  await expect(page).toHaveURL(`${applicationOrigin}/bienvenida`);
  await page.getByRole("button", { name: "Ahora no", exact: true }).click();
  await expect(page).toHaveURL(`${applicationOrigin}/`);
}

function observe(page: Page) {
  page.on("response", (response) => {
    const origin = new URL(response.url()).origin;
    if (response.status() !== 429 || ![identityOrigin, applicationOrigin].includes(origin) || rateRefusals.length >= 16) return;
    const retry = response.headers()["retry-after"] ?? "";
    rateRefusals.push({ origin: origin === identityOrigin ? "identity" : "product",
      retryAfterSeconds: /^\d{1,3}$/.test(retry) ? Number(retry) : null });
  });
  page.on("request", (sent) => {
    const url = new URL(sent.url());
    if (url.protocol.startsWith("http")) requestedHosts.add(url.host);
    if (url.origin === identityOrigin && ["action-token", "required-action"].some((action) => url.pathname === `/realms/gameometro/login-actions/${action}`)) {
      identityActionRequests += 1;
    }
  });
}

async function record(context: BrowserContext, page: Page, stage: string) {
  const now = Date.now() / 1000;
  const cookies: CookieRecord[] = [];
  // The whole jar: a URL filter would hide Keycloak's realm-path cookies.
  for (const stored of await context.cookies()) {
    const entry: CookieRecord = {
      origin: stored.domain === new URL(applicationOrigin).hostname ? applicationOrigin : stored.domain === identityHost ? identityOrigin : stored.domain,
      name: stored.name,
      domain: stored.domain,
      path: stored.path,
      lifetime: stored.expires < 0 ? "session" : `${Math.round(stored.expires - now)}s`,
      httpOnly: stored.httpOnly,
      secure: stored.secure,
      sameSite: stored.sameSite,
      valueBytes: stored.value.length,
    };
    const claims = jwtClaimNames(stored.value);
    if (claims) entry.jwtClaims = claims;
    cookies.push(entry);
  }
  inventory.push({ stage, cookies, storage: await browserState(page) });
}

async function browserState(page: Page): Promise<StorageRecord> {
  return page.evaluate(async () => ({
    origin: location.origin,
    // Names only: a script-readable value is itself the finding.
    documentCookie: document.cookie ? document.cookie.split("; ").map((pair) => pair.split("=")[0] ?? "") : [],
    localStorage: localStorage.length,
    sessionStorage: sessionStorage.length,
    indexedDb: (await indexedDB.databases()).length,
  }));
}

function jwtClaimNames(value: string): string[] | undefined {
  const parts = value.split(".");
  if (parts.length !== 3) return undefined;
  try {
    return Object.keys(JSON.parse(Buffer.from(parts[1] ?? "", "base64url").toString("utf8")) as object).sort();
  } catch {
    return undefined;
  }
}

async function sessionState(page: Page) {
  return page.evaluate(async () => {
    const response = await fetch("/api/v1/session", { credentials: "same-origin", headers: { Accept: "application/json" } });
    return (await response.json()) as { authenticated: boolean; csrfToken?: string };
  });
}

async function actionLink(request: APIRequestContext, address: string, count: number) {
  const query = { query: `to:"${address}"` };
  await expect
    .poll(async () => ((await (await request.get(`${mailpitOrigin}/api/v1/search`, { params: query })).json()).messages ?? []).length)
    .toBeGreaterThanOrEqual(count);
  const [newest] = (await (await request.get(`${mailpitOrigin}/api/v1/search`, { params: query })).json()).messages as { ID: string }[];
  const message = (await (await request.get(`${mailpitOrigin}/api/v1/message/${newest?.ID}`)).json()) as { Text: string };
  const link = /https:\/\/\S+\/login-actions\/action-token\?\S+/.exec(message.Text)?.[0] ?? "";
  expect(link.startsWith(`${issuer}/login-actions/action-token?`), "email links use the canonical issuer").toBe(true);
  sensitive.push(new URL(link).searchParams.get("key") ?? "");
  return link;
}

function syntheticEmail(purpose: string) {
  const email = `${purpose}-${crypto.randomUUID()}@example.test`;
  sensitive.push(email);
  return email;
}

function syntheticPassword() {
  const password = `Gm-${crypto.randomUUID()}`;
  sensitive.push(password);
  return password;
}

function escape(value: string) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
