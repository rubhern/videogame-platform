import { expect, test, type APIRequestContext, type Browser, type Page } from "@playwright/test";
import { analyzeAccessibility } from "./fixtures/accessibility";

const applicationOrigin = new URL(process.env.PLAYWRIGHT_BASE_URL ?? "http://application:8080").origin;
const keycloakOrigin = process.env.KEYCLOAK_TEST_ORIGIN ?? "";
const adminUsername = process.env.KEYCLOAK_TEST_ADMIN_USERNAME ?? "";
const adminPassword = process.env.KEYCLOAK_TEST_ADMIN_PASSWORD ?? "";
const mailpitOrigin = process.env.MAILPIT_API_ORIGIN ?? "";
const realmPath = "/admin/realms/videogame-platform";

// The production identity copy, which the shared realm imports too.
const emailSent =
  "Si el email corresponde a una cuenta, recibirás en breve un enlace para crear una nueva contraseña.";
const linkRefused = /El enlace ha caducado o ya se ha usado\./;
const linkInvalid = "Este enlace no es válido para crear una nueva contraseña.";
// Keycloak shares one password template between both journeys and natively picks the
// instruction: activation while the account still holds UPDATE_PASSWORD, recovery otherwise.
const passwordTitle = "Elige tu contraseña";
// Both show the approved policy (#473) before anything is typed.
const requirement = "Usa al menos 8 caracteres y evita contraseñas habituales.";
const activationInstruction = `Último paso para activar tu cuenta: crea la contraseña con la que iniciarás sesión. ${requirement}`;
const recoveryInstruction = `Escribe tu nueva contraseña y confírmala. ${requirement}`;

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
  // The policy minimum, with a non-ASCII letter.
  const newPassword = "Clave-ñ8";
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
  await expect(page.getByText(/Indica tu email\. Si corresponde a una cuenta/)).toBeVisible();
  expect((await analyzeAccessibility(page)).violations).toEqual([]);
  await page.locator("#username").fill(player.username);
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
  await expect(page.getByRole("heading", { name: passwordTitle, exact: true })).toBeVisible();
  await expect(page.getByText(recoveryInstruction, { exact: true })).toBeVisible();
  await expect(page.getByText(/Último paso para activar/)).toHaveCount(0);
  await expect(page.getByText("Cerrar sesión en otros dispositivos")).toBeHidden();
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

test("a new player establishes a first password with activation copy, not recovery copy", async ({ page, request }) => {
  const email = `activation-${crypto.randomUUID()}@example.test`;
  const password = "Primera-Clave-2026!";
  await page.setViewportSize({ width: 390, height: 900 });
  await page.goto("/auth/start?returnTo=%2F&intent=register");
  await page.locator("#email").fill(email);
  await page.locator('input[name="termsAccepted"]').check();
  await page.locator("#kc-register-form [type=submit]").click();
  await expect(page.locator("body")).toContainText("Verifica tu email");

  const [mail] = await waitForMessages(request, email, 1);
  expect(mail?.Subject).toBe("Verifica tu email de Gameómetro");
  expect(mail?.Text).not.toContain("nueva contraseña");
  await page.goto(resetLink(mail));
  await expect(page.getByRole("heading", { name: passwordTitle, exact: true })).toBeVisible();
  await expect(page.getByText(activationInstruction, { exact: true })).toBeVisible();
  await expect(page.getByText(/Escribe tu nueva contraseña/)).toHaveCount(0);
  await expect(page.getByText(/nueva contraseña|recupera/i)).toHaveCount(0);
  await expect(page.getByLabel("Contraseña", { exact: true })).toBeVisible();
  // Owner decision: no "sign out other devices" option on either password journey.
  await expect(page.getByText("Cerrar sesión en otros dispositivos")).toBeHidden();
  expect((await analyzeAccessibility(page)).violations).toEqual([]);
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);

  // A rejected password keeps the journey-neutral title and names the problem under its field.
  for (const [rejected, message] of [
    ["Corta-7", "La contraseña debe tener al menos 8 caracteres."],
    // The common-password denylist compares case-insensitively.
    ["PassWord123", "Esa contraseña es demasiado habitual. Elige otra más difícil de adivinar."],
  ]) {
    await page.locator("#password-new").fill(rejected ?? "");
    await page.locator("#password-confirm").fill(rejected ?? "");
    await page.keyboard.press("Enter");
    await expect(page.getByRole("heading", { name: passwordTitle, exact: true })).toBeVisible();
    // Keycloak reports a policy refusal as the page message; its live length check may repeat it.
    await expect(page.getByText(message ?? "", { exact: true }).first()).toBeVisible();
    await expect(page.getByText(/nueva contraseña|recupera/i)).toHaveCount(0);
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
  }
  await page.locator("#password-new").fill(password);
  await page.locator("#password-confirm").fill(password);
  await page.keyboard.press("Enter");

  // Activation resumes the original authorization request through the BFF callback; a first
  // sign-in lands on the product welcome.
  await expect(page).toHaveURL(`${applicationOrigin}/bienvenida`);
  expect(await sessionState(page)).toMatchObject({ authenticated: true });
});

test("an expired verification link sends the new player to recovery, which still activates the account", async ({
  page,
  request,
}) => {
  const email = `expired-activation-${crypto.randomUUID()}@example.test`;
  const token = await adminToken(request);
  const realm = await (await request.get(`${keycloakOrigin}${realmPath}`, { headers: token })).json();
  const lifespan = realm.actionTokenGeneratedByUserLifespan as number;
  await page.setViewportSize({ width: 390, height: 900 });
  await page.goto("/auth/start?returnTo=%2F&intent=register");
  await page.locator("#email").fill(email);
  await page.locator('input[name="termsAccepted"]').check();
  await updateRealm(request, { actionTokenGeneratedByUserLifespan: 1 });
  try {
    await page.locator("#kc-register-form [type=submit]").click();
    await expect(page.locator("body")).toContainText("Verifica tu email");
  } finally {
    await updateRealm(request, { actionTokenGeneratedByUserLifespan: lifespan });
  }
  const [verification] = await waitForMessages(request, email, 1);
  await page.waitForTimeout(2_500);

  // In the registering browser the account has no password yet, so the message names the way on.
  await page.goto(resetLink(verification));
  await expect(
    page.getByText("El enlace ha caducado o ya se ha usado. Pide uno nuevo desde ¿Has olvidado tu contraseña?", { exact: true }),
  ).toBeVisible();
  expect((await analyzeAccessibility(page)).violations).toEqual([]);
  await page.getByRole("link", { name: "¿Has olvidado tu contraseña?", exact: true }).click();
  await page.locator("#username").fill(email);
  await page.getByRole("button", { name: "Continuar", exact: true }).click();
  await expect(page.getByText(emailSent, { exact: true })).toBeVisible();

  // Recovery never bypasses verification: its link first sends a fresh verification email.
  const [recovery] = await waitForMessages(request, email, 2);
  expect(recovery?.Subject).toBe("Crea una nueva contraseña de Gameómetro");
  await page.goto(resetLink(recovery));
  await expect(page.locator("body")).toContainText("Verifica tu email");
  await expect(page.locator("#password-new")).toHaveCount(0);
  const [fresh] = await waitForMessages(request, email, 3);
  expect(fresh?.Subject).toBe("Verifica tu email de Gameómetro");
  await page.goto(resetLink(fresh));
  await expect(page.getByRole("heading", { name: passwordTitle, exact: true })).toBeVisible();
  await expect(page.getByText(activationInstruction, { exact: true })).toBeVisible();
  await page.locator("#password-new").fill("Activada-Tarde-2026");
  await page.locator("#password-confirm").fill("Activada-Tarde-2026");
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(`${applicationOrigin}/bienvenida`);
  expect(await sessionState(page)).toMatchObject({ authenticated: true });
});

test("unknown accounts, email addresses and repeated requests disclose nothing", async ({ browser, request }) => {
  const player = await createPlayer(request);
  const responses: string[] = [];
  for (const identifier of [`absent-${player.email}`, player.email, player.email]) {
    const context = await browser.newContext();
    const page = await context.newPage();
    await requestRecovery(page, identifier);
    responses.push(await page.locator(".kc-feedback-text, .pf-v5-c-alert__title").first().innerText());
    await context.close();
  }
  expect(new Set(responses)).toEqual(new Set([emailSent]));

  // Sign-in is by email, so the unknown address sends nothing while each request for the real
  // one delivers its own link. Keycloak sends before it responds.
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
  await page.locator("#username").fill(identifier);
  await page.getByRole("button", { name: "Continuar", exact: true }).click();
  await expect(page.getByText(emailSent, { exact: true })).toBeVisible();
}

async function signIn(browser: Browser, username: string, password: string) {
  const context = await browser.newContext();
  const page = await context.newPage();
  try {
    await page.goto("/auth/start");
    await page.locator("#username").fill(username);
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
  const username = `recovery-${crypto.randomUUID()}@example.test`; // email as username, as in production
  const player = { username, email: username, password: "Antigua-Clave-2026!" };
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
