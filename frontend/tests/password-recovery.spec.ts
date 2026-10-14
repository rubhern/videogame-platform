import { expect, test, type APIRequestContext, type Browser, type Page } from "@playwright/test";
import { analyzeAccessibility } from "./fixtures/accessibility";

const applicationOrigin = new URL(process.env.PLAYWRIGHT_BASE_URL ?? "http://application:8080").origin;
const keycloakOrigin = process.env.KEYCLOAK_TEST_ORIGIN ?? "";
const adminUsername = process.env.KEYCLOAK_TEST_ADMIN_USERNAME ?? "";
const adminPassword = process.env.KEYCLOAK_TEST_ADMIN_PASSWORD ?? "";
const mailpitOrigin = process.env.MAILPIT_API_ORIGIN ?? "";
const realmPath = "/admin/realms/videogame-platform";

const emailSent =
  "Si el usuario corresponde a una cuenta, recibirás en breve un email con un enlace para crear una nueva contraseña.";
const linkRefused = /El enlace ha caducado o ya se ha usado\./;
const linkInvalid = "Este enlace no es válido para crear una nueva contraseña.";

test.skip(
  !keycloakOrigin || !adminUsername || !adminPassword || !mailpitOrigin,
  "The real Keycloak and Mailpit recovery topology is not active.",
);
// The expiry case changes a realm-wide token lifespan; keep this journey's cases ordered.
test.describe.configure({ mode: "serial" });

interface Player {
  username: string;
  email: string;
  password: string;
}

test("a player recovers access by email on mobile and returns to the page they asked for", async ({
  browser,
  page,
  request,
}) => {
  const player = await createPlayer(request);
  const newPassword = "Nueva-Clave-2026!";
  const applicationPosts: string[] = [];
  page.on("request", (sent) => {
    if (sent.url().startsWith(applicationOrigin) && sent.method() !== "GET") {
      applicationPosts.push(`${sent.method()} ${sent.url()} ${sent.postData() ?? ""}`);
    }
  });

  await page.setViewportSize({ width: 390, height: 900 });
  await page.goto("/auth/start?returnTo=%2Fmis-puntuaciones");
  const forgotLink = page.getByRole("link", { name: "¿Has olvidado tu contraseña?", exact: true });
  await forgotLink.focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("heading", { name: "Recupera tu acceso" })).toBeVisible();
  await expect(page.getByText(/Indica tu usuario\. Si corresponde a una cuenta/)).toBeVisible();
  expect((await analyzeAccessibility(page)).violations).toEqual([]);
  await page.getByLabel("Usuario", { exact: true }).fill(player.username);
  await page.keyboard.press("Enter");

  await expect(page.getByRole("heading", { name: "Bienvenido, Jugón" })).toBeVisible();
  await expect(page.getByText(emailSent, { exact: true })).toBeVisible();
  expect((await analyzeAccessibility(page)).violations).toEqual([]);
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);

  const [mail] = await waitForMessages(request, player.email, 1);
  expect(mail?.Subject).toBe("Crea una nueva contraseña de Gameómetro");
  expect(mail?.From).toEqual({ Name: "Gameómetro", Address: "no-reply@gameometro.invalid" });
  expect(mail?.Text).toContain("El enlace caduca en 5 minutos y solo puede usarse una vez.");
  expect(mail?.Text).not.toContain(player.password);
  const link = resetLink(mail);
  expect(new URL(link).origin).toBe(keycloakOrigin);

  await page.goto(link);
  await expect(page.getByRole("heading", { name: "Elige una nueva contraseña" })).toBeVisible();
  await expect(page.getByText("Escribe tu nueva contraseña y confírmala.", { exact: true })).toBeVisible();
  expect((await analyzeAccessibility(page)).violations).toEqual([]);
  await page.locator("#password-new").fill(newPassword);
  await page.locator("#password-confirm").fill("No-Coincide-2026!");
  await page.keyboard.press("Enter");
  await expect(page.locator("#input-error-password-confirm")).toBeVisible();
  expect((await analyzeAccessibility(page)).violations).toEqual([]);
  await page.locator("#password-new").fill(newPassword);
  await page.locator("#password-confirm").fill(newPassword);
  await page.keyboard.press("Enter");

  // The original authorization request resumes through the BFF callback, so the player lands
  // where they started with an opaque server session and no browser-held credential material.
  await expect(page).toHaveURL(`${applicationOrigin}/mis-puntuaciones`);
  await expect(page.getByRole("heading", { name: "Mis puntuaciones", exact: true })).toBeVisible();
  expect(await sessionState(page)).toMatchObject({ authenticated: true });
  const cookies = await page.context().cookies(applicationOrigin);
  expect(cookies.map((cookie) => [cookie.name, cookie.httpOnly])).toEqual([["vgp_session", true]]);
  expect(
    await page.evaluate(() => ({
      cookie: document.cookie,
      local: window.localStorage.length,
      session: window.sessionStorage.length,
    })),
  ).toEqual({ cookie: "", local: 0, session: 0 });
  expect(applicationPosts.join("\n")).not.toContain(newPassword);
  expect(applicationPosts.join("\n")).not.toContain("key=");

  // Each emailed link works once, even for whoever holds a copy of it.
  const otherDevice = await browser.newContext();
  const otherPage = await otherDevice.newPage();
  await otherPage.goto(link);
  await expect(otherPage.getByText(linkRefused)).toBeVisible();
  await expect(otherPage.locator("#password-new")).toHaveCount(0);
  await expect(otherPage.locator("html")).toHaveAttribute("lang", "es");
  expect((await analyzeAccessibility(otherPage)).violations).toEqual([]);
  await otherDevice.close();

  expect(await signIn(browser, player.username, player.password)).toBe("rejected");
  expect(await signIn(browser, player.username, newPassword)).toBe("authenticated");
});

test("unknown accounts, email addresses and repeated requests disclose nothing", async ({ browser, request }) => {
  const player = await createPlayer(request);
  const responses: string[] = [];
  for (const identifier of [`absent-${player.username}`, player.email, player.username, player.username]) {
    const context = await browser.newContext();
    const page = await context.newPage();
    await requestRecovery(page, identifier);
    responses.push(await page.locator(".kc-feedback-text, .pf-v5-c-alert__title").first().innerText());
    await context.close();
  }
  expect(new Set(responses)).toEqual(new Set([emailSent]));

  // Sign-in is by username, so the email address finds no account and sends nothing. Keycloak
  // sends before it responds, so only the two username requests have delivered distinct links.
  const messages = await waitForMessages(request, player.email, 2);
  expect(messages).toHaveLength(2);
  expect(new Set(messages.map(resetLink)).size).toBe(2);
});

test("expired and tampered links fail closed with a Spanish explanation", async ({ browser, request }) => {
  const player = await createPlayer(request);
  const token = await adminToken(request);
  const realm = await (await request.get(`${keycloakOrigin}${realmPath}`, { headers: token })).json();
  const lifespan = realm.actionTokenGeneratedByUserLifespan as number;

  const context = await browser.newContext({ viewport: { width: 390, height: 900 } });
  const page = await context.newPage();
  await updateRealm(request, { actionTokenGeneratedByUserLifespan: 1 });
  try {
    await requestRecovery(page, player.username);
  } finally {
    await updateRealm(request, { actionTokenGeneratedByUserLifespan: lifespan });
  }
  const [expired] = await waitForMessages(request, player.email, 1);
  await page.waitForTimeout(2_500);
  await page.goto(resetLink(expired));
  await expect(page.getByText(linkRefused)).toBeVisible();
  await expect(page.locator("#password-new")).toHaveCount(0);
  expect((await analyzeAccessibility(page)).violations).toEqual([]);
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await context.close();

  const requester = await browser.newContext();
  await requestRecovery(await requester.newPage(), player.username);
  await requester.close();
  const [fresh] = await waitForMessages(request, player.email, 2);
  const link = new URL(resetLink(fresh));
  const key = link.searchParams.get("key") ?? "";
  link.searchParams.set("key", `${key.slice(0, -4)}${key.endsWith("AAAA") ? "BBBB" : "AAAA"}`);
  // A forged signature is rejected before any authentication session is resumed.
  const tamperedContext = await browser.newContext();
  const tamperedPage = await tamperedContext.newPage();
  await tamperedPage.goto(link.toString());
  await expect(tamperedPage.getByText(linkInvalid, { exact: true })).toBeVisible();
  await expect(tamperedPage.locator("#password-new")).toHaveCount(0);

  await tamperedPage.goto(`${keycloakOrigin}/realms/videogame-platform/login-actions/action-token?key=not-a-token`);
  await expect(tamperedPage.locator("#kc-error-message")).toBeVisible();
  await expect(tamperedPage.locator("#password-new")).toHaveCount(0);
  expect((await analyzeAccessibility(tamperedPage)).violations).toEqual([]);
  await tamperedContext.close();

  expect(await signIn(browser, player.username, player.password)).toBe("authenticated");
});

async function requestRecovery(page: Page, identifier: string) {
  await page.goto("/auth/start");
  await page.getByRole("link", { name: "¿Has olvidado tu contraseña?", exact: true }).click();
  await page.getByLabel("Usuario", { exact: true }).fill(identifier);
  await page.getByRole("button", { name: "Continuar", exact: true }).click();
  await expect(page.getByText(emailSent, { exact: true })).toBeVisible();
}

async function signIn(browser: Browser, username: string, password: string) {
  const context = await browser.newContext();
  const page = await context.newPage();
  try {
    await page.goto("/auth/start");
    await page.getByLabel("Usuario", { exact: true }).fill(username);
    await page.locator("#password").fill(password);
    await page.getByRole("button", { name: "Iniciar sesión", exact: true }).click();
    await page.waitForURL((url) => url.origin === applicationOrigin || url.pathname.includes("/login-actions/"));
    if (new URL(page.url()).origin !== applicationOrigin) {
      await expect(page.locator(".kc-feedback-text, #input-error").first()).toBeVisible();
      return "rejected";
    }
    return (await sessionState(page)).authenticated ? "authenticated" : "rejected";
  } finally {
    await context.close();
  }
}

async function sessionState(page: Page) {
  return page.evaluate(async () => {
    const response = await fetch("/api/v1/session", { credentials: "same-origin", headers: { Accept: "application/json" } });
    return (await response.json()) as { authenticated: boolean };
  });
}

async function adminToken(request: APIRequestContext) {
  const response = await request.post(`${keycloakOrigin}/realms/master/protocol/openid-connect/token`, {
    form: { client_id: "admin-cli", grant_type: "password", username: adminUsername, password: adminPassword },
  });
  expect(response.status()).toBe(200);
  return { Authorization: `Bearer ${(await response.json()).access_token as string}` };
}

async function updateRealm(request: APIRequestContext, changes: Record<string, unknown>) {
  const response = await request.put(`${keycloakOrigin}${realmPath}`, {
    data: changes,
    headers: await adminToken(request),
  });
  expect(response.status()).toBe(204);
}

async function createPlayer(request: APIRequestContext): Promise<Player> {
  const username = `recovery-${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
  const player = { username, email: `${username}@example.test`, password: "Antigua-Clave-2026!" };
  const response = await request.post(`${keycloakOrigin}${realmPath}/users`, {
    headers: await adminToken(request),
    data: {
      username,
      email: player.email,
      emailVerified: true,
      enabled: true,
      firstName: "Synthetic",
      lastName: "Recovery",
      credentials: [{ type: "password", value: player.password, temporary: false }],
    },
  });
  expect(response.status()).toBe(201);
  return player;
}

interface MailpitMessage {
  ID: string;
  Subject: string;
  From: { Name: string; Address: string };
  Text: string;
}

async function searchMessages(request: APIRequestContext, query: string) {
  const response = await request.get(`${mailpitOrigin}/api/v1/search`, { params: { query } });
  expect(response.status()).toBe(200);
  return ((await response.json()).messages ?? []) as { ID: string }[];
}

/** Returns the newest messages first, once at least the expected number has arrived. */
async function waitForMessages(request: APIRequestContext, address: string, count: number) {
  await expect.poll(async () => (await searchMessages(request, `to:"${address}"`)).length).toBeGreaterThanOrEqual(count);
  const summaries = await searchMessages(request, `to:"${address}"`);
  return Promise.all(
    summaries.map(async ({ ID }) => (await (await request.get(`${mailpitOrigin}/api/v1/message/${ID}`)).json()) as MailpitMessage),
  );
}

function resetLink(message: MailpitMessage | undefined) {
  const match = /https?:\/\/\S+\/login-actions\/action-token\?\S+/.exec(message?.Text ?? "");
  expect(match, "the recovery email carries a Keycloak action-token link").not.toBeNull();
  return match?.[0] ?? "";
}
