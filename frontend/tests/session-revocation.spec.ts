import { reachHomeAfterSignIn } from "./fixtures/sign-in-destination";
import { createHash } from "node:crypto";
import { expect, test, type APIRequestContext, type Browser, type Page } from "@playwright/test";

// Real Keycloak, BFF and private management port (#274, ADR-0027). Accounts are synthetic and
// created per test, so revoking or deleting one never touches another journey's session.
const applicationOrigin = new URL(process.env.PLAYWRIGHT_BASE_URL ?? "http://application:8080").origin;
const managementOrigin = process.env.APPLICATION_MANAGEMENT_ORIGIN ?? "";
const keycloakOrigin = process.env.KEYCLOAK_TEST_ORIGIN ?? "";
const adminUsername = process.env.KEYCLOAK_TEST_ADMIN_USERNAME ?? "";
const adminPassword = process.env.KEYCLOAK_TEST_ADMIN_PASSWORD ?? "";
const realm = "videogame-platform";
const realmAdmin = `${keycloakOrigin}/admin/realms/${realm}`;
const ratedGame = "30000000-0000-4000-8000-000000000005";

test.skip(
  !managementOrigin || !keycloakOrigin || !adminUsername || !adminPassword,
  "The real Keycloak and management-port topology is not active.",
);

interface Player {
  id: string;
  username: string;
  password: string;
}

interface Device {
  page: Page;
  close: () => Promise<void>;
}

test("revoking a disabled account ends its sessions on every device and no one else's", async ({
  browser,
  request,
}) => {
  const alice = await createPlayer(request, "alice");
  const bob = await createPlayer(request, "bob");
  const aliceLaptop = await signIn(browser, alice);
  const alicePhone = await signIn(browser, alice);
  const bobLaptop = await signIn(browser, bob);
  const phoneCsrf = await csrfToken(alicePhone.page);

  // The owner procedure: disable the account and end its Keycloak sessions, then the BFF's.
  await updatePlayer(request, alice, { enabled: false });
  await endKeycloakSessions(request, alice);
  expect(await revoke(request, alice)).toEqual({ status: 200, body: { revokedSessions: 2 } });

  // The phone's very next request is a rating write with its still-current CSRF token.
  const rejectedWrite = await alicePhone.page.evaluate(
    async ({ game, csrf }) => {
      const response = await fetch(`/api/v1/me/ratings/${game}`, {
        method: "PUT",
        credentials: "same-origin",
        headers: { "Content-Type": "application/json", "If-None-Match": "*", "X-CSRF-Token": csrf },
        body: JSON.stringify({ value: 8 }),
      });
      return { status: response.status, body: await response.json() };
    },
    { game: ratedGame, csrf: phoneCsrf },
  );
  expect(rejectedWrite).toMatchObject({ status: 401, body: { code: "AUTHENTICATION_REQUIRED" } });
  expect(await sessionState(aliceLaptop.page)).toEqual({ authenticated: false });
  expect(await sessionState(alicePhone.page)).toEqual({ authenticated: false });
  expect(await revoke(request, alice)).toEqual({ status: 200, body: { revokedSessions: 0 } });

  expect(await sessionState(bobLaptop.page)).toMatchObject({ authenticated: true });
  expect(await personalRatingsStatus(bobLaptop.page)).toBe(200);

  // A disabled account cannot open a new session either.
  await aliceLaptop.page.goto("/auth/login/keycloak");
  await submitCredentials(aliceLaptop.page, alice);
  await expect(aliceLaptop.page).toHaveURL(new RegExp(`/realms/${realm}/`));
  await expect(aliceLaptop.page.locator(".kc-feedback-text, #input-error").first()).toBeVisible();
  expect(await sessionState(await applicationPage(aliceLaptop))).toEqual({ authenticated: false });

  await Promise.all([aliceLaptop, alicePhone, bobLaptop].map((device) => device.close()));
});

test("revoking a deleted account ends the session it left open", async ({ browser, request }) => {
  const carol = await createPlayer(request, "carol");
  const carolLaptop = await signIn(browser, carol);

  const deleted = await request.delete(`${realmAdmin}/users/${carol.id}`, {
    headers: await adminToken(request),
  });
  expect(deleted.status()).toBe(204);
  expect(await revoke(request, carol)).toEqual({ status: 200, body: { revokedSessions: 1 } });

  expect(await sessionState(carolLaptop.page)).toEqual({ authenticated: false });
  expect(await personalRatingsStatus(carolLaptop.page)).toBe(401);
  await carolLaptop.close();
});

test("logout ends this browser's Keycloak session so the next sign-in asks for credentials", async ({
  browser,
  request,
}) => {
  const dana = await createPlayer(request, "dana");
  const shared = await signIn(browser, dana);
  const otherDevice = await signIn(browser, dana);
  expect(await keycloakSessions(request, dana)).toBe(2);
  const navigation: string[] = [];
  shared.page.on("request", (sent) => {
    if (sent.isNavigationRequest()) navigation.push(sent.url());
  });

  // The page shows the signed-out header before it navigates through the identity provider's
  // logout, so wait for the document that returns from Keycloak, not the one already at `/`.
  const returned = shared.page.waitForEvent(
    "framenavigated",
    (frame) =>
      frame === shared.page.mainFrame() &&
      frame.url() === `${applicationOrigin}/` &&
      navigation.some((url) => url.includes("/protocol/openid-connect/logout")),
  );
  await shared.page.getByRole("button", { name: "Mi cuenta" }).click();
  await shared.page.getByRole("button", { name: "Cerrar sesión" }).click();
  await returned;
  await shared.page.waitForLoadState("load");

  await expect(shared.page).toHaveURL(`${applicationOrigin}/`);
  await expect(shared.page.getByRole("navigation", { name: "Acceso a tu cuenta" }).getByRole("link", { name: "Iniciar sesión", exact: true })).toBeVisible();
  expect(await sessionState(shared.page)).toEqual({ authenticated: false });
  expect(await shared.page.context().cookies(applicationOrigin)).toEqual([]);
  const endSession = navigation.find((url) => url.includes("/protocol/openid-connect/logout"));
  expect(endSession).toBeDefined();
  expect(new URL(endSession ?? "").searchParams.get("post_logout_redirect_uri")).toBe(`${applicationOrigin}/`);
  expect(await keycloakSessions(request, dana)).toBe(1);

  // Another person on this device must type credentials: no silent single sign-on.
  await shared.page.goto("/auth/login/keycloak");
  await expect(shared.page.getByLabel("Usuario", { exact: true })).toBeVisible();
  await expect(shared.page.locator("#password")).toBeVisible();
  // The same account's other device is untouched in both the BFF and Keycloak.
  expect(await sessionState(otherDevice.page)).toMatchObject({ authenticated: true });

  await Promise.all([shared, otherDevice].map((device) => device.close()));
});

async function signIn(browser: Browser, player: Player): Promise<Device> {
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto("/auth/login/keycloak");
  await submitCredentials(page, player);
  await reachHomeAfterSignIn(page, applicationOrigin);
  expect(await sessionState(page)).toMatchObject({ authenticated: true });
  return { page, close: () => context.close() };
}

async function submitCredentials(page: Page, player: Player) {
  await page.getByLabel("Usuario", { exact: true }).fill(player.username);
  await page.locator("#password").fill(player.password);
  await page.getByRole("button", { name: "Iniciar sesión", exact: true }).click();
}

async function applicationPage(device: Device) {
  await device.page.goto("/");
  return device.page;
}

async function sessionState(page: Page) {
  return page.evaluate(async () => {
    const response = await fetch("/api/v1/session", {
      credentials: "same-origin",
      headers: { Accept: "application/json" },
    });
    return (await response.json()) as { authenticated: boolean; csrfToken?: string };
  });
}

async function csrfToken(page: Page) {
  const state = await sessionState(page);
  if (!state.csrfToken) throw new Error("The session exposed no CSRF material.");
  return state.csrfToken;
}

async function personalRatingsStatus(page: Page) {
  return page.evaluate(async () => (await fetch("/api/v1/me/ratings", { credentials: "same-origin" })).status);
}

async function revoke(request: APIRequestContext, player: Player) {
  const response = await request.post(`${managementOrigin}/actuator/sessionrevocation`, {
    data: { userId: productUserId(`${keycloakOrigin}/realms/${realm}`, player.id) },
  });
  return { status: response.status(), body: await response.json() };
}

/** The BFF's `UserId`: Java's name-based UUID of the validated `issuer + "\n" + subject`. */
function productUserId(issuer: string, subject: string) {
  const bytes = createHash("md5").update(`${issuer}\n${subject}`, "utf8").digest();
  bytes[6] = ((bytes[6] ?? 0) & 0x0f) | 0x30;
  bytes[8] = ((bytes[8] ?? 0) & 0x3f) | 0x80;
  const hex = bytes.toString("hex");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

async function adminToken(request: APIRequestContext) {
  const response = await request.post(`${keycloakOrigin}/realms/master/protocol/openid-connect/token`, {
    form: { client_id: "admin-cli", grant_type: "password", username: adminUsername, password: adminPassword },
  });
  expect(response.status()).toBe(200);
  return { Authorization: `Bearer ${(await response.json()).access_token as string}` };
}

async function createPlayer(request: APIRequestContext, name: string): Promise<Player> {
  const username = `revocation-${name}-${crypto.randomUUID()}`;
  const password = "Sesion-Revocada-2026!";
  const headers = await adminToken(request);
  const created = await request.post(`${realmAdmin}/users`, {
    headers,
    data: {
      username,
      email: `${username}@example.test`,
      emailVerified: true,
      enabled: true,
      firstName: "Synthetic",
      lastName: "Revocation",
      credentials: [{ type: "password", value: password, temporary: false }],
    },
  });
  expect(created.status()).toBe(201);
  const id = new URL(created.headers().location ?? "").pathname.split("/").pop() ?? "";
  expect(id).not.toBe("");
  return { id, username, password };
}

async function updatePlayer(request: APIRequestContext, player: Player, changes: Record<string, unknown>) {
  const response = await request.put(`${realmAdmin}/users/${player.id}`, {
    headers: await adminToken(request),
    data: changes,
  });
  expect(response.status()).toBe(204);
}

async function endKeycloakSessions(request: APIRequestContext, player: Player) {
  const response = await request.post(`${realmAdmin}/users/${player.id}/logout`, {
    headers: await adminToken(request),
  });
  expect(response.status()).toBe(204);
}

async function keycloakSessions(request: APIRequestContext, player: Player) {
  const response = await request.get(`${realmAdmin}/users/${player.id}/sessions`, {
    headers: await adminToken(request),
  });
  expect(response.status()).toBe(200);
  return ((await response.json()) as unknown[]).length;
}
