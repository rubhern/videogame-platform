import { expect, test, type APIRequestContext, type Browser, type Page } from "@playwright/test";

// Real Keycloak and BFF (#247): the operator authority comes only from the BFF client's
// `catalogue-operator` role in the validated ID token. Accounts are synthetic and per test.
const applicationOrigin = new URL(process.env.PLAYWRIGHT_BASE_URL ?? "http://application:8080").origin;
const keycloakOrigin = process.env.KEYCLOAK_TEST_ORIGIN ?? "";
const adminUsername = process.env.KEYCLOAK_TEST_ADMIN_USERNAME ?? "";
const adminPassword = process.env.KEYCLOAK_TEST_ADMIN_PASSWORD ?? "";
const realmAdmin = `${keycloakOrigin}/admin/realms/videogame-platform`;
const STATUS = "/api/v1/admin/catalogue/synchronization";
const COVERAGE = "/api/v1/admin/catalogue/synchronization-coverage?from=1997-01-01&to=1997-12-31";

test.skip(
  !keycloakOrigin || !adminUsername || !adminPassword,
  "The real Keycloak identity topology is not active.",
);

interface Account {
  id: string;
  username: string;
  password: string;
}

test("only a session signed in with the catalogue operator role reads catalogue administration", async ({
  browser,
  request,
}) => {
  // Four real sign-ins through Keycloak, which shares this host with the parallel identity specs.
  test.setTimeout(120_000);
  const anonymous = await browser.newContext();
  const anonymousPage = await anonymous.newPage();
  await anonymousPage.goto("/");
  expect(await read(anonymousPage, STATUS)).toMatchObject({ status: 401, code: "AUTHENTICATION_REQUIRED" });
  await anonymous.close();

  const account = await createAccount(request);
  const player = await signIn(browser, account);
  expect(await session(player)).not.toHaveProperty("capabilities");
  expect(await read(player, STATUS)).toMatchObject({ status: 403, code: "ACCESS_DENIED" });
  expect(await read(player, COVERAGE)).toMatchObject({ status: 403, code: "ACCESS_DENIED" });
  await player.getByRole("button", { name: "Mi cuenta" }).click();
  await expect(player.getByRole("link", { name: "Mis puntuaciones" })).toBeVisible();
  await expect(player.getByRole("link", { name: "Panel de administrador" })).toHaveCount(0);
  await player.goto("/administracion/catalogo");
  await expect(player.getByRole("heading", { name: "Tu cuenta no puede administrar el catálogo" })).toBeVisible();

  // The role reaches the BFF only through a new sign-in's ID token.
  await setOperatorRole(request, account, true);
  expect(await read(player, STATUS)).toMatchObject({ status: 403 });
  const operator = await signIn(browser, account);
  expect(await session(operator)).toMatchObject({ capabilities: ["catalogue_administration"] });
  const status = await read(operator, STATUS);
  expect(status.status).toBe(200);
  expect(status.cacheControl).toBe("no-store");
  expect(JSON.stringify(status.body)).not.toMatch(/igdb|client_secret|access_token|8081/i);
  expect(await read(operator, COVERAGE)).toMatchObject({ status: 200 });
  await operator.goto("/");
  await operator.getByRole("button", { name: "Mi cuenta" }).click();
  await operator.getByRole("link", { name: "Panel de administrador" }).click();
  await expect(operator).toHaveURL(`${applicationOrigin}/administracion/catalogo`);
  await expect(operator.getByRole("heading", { name: "Ejecución actual" })).toBeVisible();
  await operator.getByRole("tab", { name: "Cobertura" }).click();
  await expect(operator.getByRole("table", { name: /^Cobertura por año/ })).toBeVisible();

  // Revoking the role stops the next sign-in; the role claim cannot come from the browser.
  await setOperatorRole(request, account, false);
  const revoked = await signIn(browser, account);
  expect(await read(revoked, STATUS)).toMatchObject({ status: 403, code: "ACCESS_DENIED" });

  await Promise.all([player, operator, revoked].map((page) => page.context().close()));
  await request.delete(`${realmAdmin}/users/${account.id}`, { headers: await adminToken(request) });
});

async function read(page: Page, path: string) {
  return page.evaluate(async (target) => {
    const response = await fetch(target, { credentials: "same-origin", headers: { Accept: "application/json" } });
    const body = (await response.json()) as { code?: string };
    return { status: response.status, code: body.code, cacheControl: response.headers.get("Cache-Control"), body };
  }, path);
}

async function session(page: Page) {
  return page.evaluate(async () => (await fetch("/api/v1/session", { credentials: "same-origin" })).json());
}

async function signIn(browser: Browser, account: Account): Promise<Page> {
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto("/auth/login/keycloak");
  await page.getByLabel("Usuario", { exact: true }).fill(account.username);
  await page.locator("#password").fill(account.password);
  await page.getByRole("button", { name: "Iniciar sesión", exact: true }).click();
  await expect(page).toHaveURL(`${applicationOrigin}/`);
  return page;
}

async function adminToken(request: APIRequestContext) {
  const response = await request.post(`${keycloakOrigin}/realms/master/protocol/openid-connect/token`, {
    form: { client_id: "admin-cli", grant_type: "password", username: adminUsername, password: adminPassword },
  });
  expect(response.status()).toBe(200);
  return { Authorization: `Bearer ${(await response.json()).access_token as string}` };
}

async function createAccount(request: APIRequestContext): Promise<Account> {
  const username = `operator-${crypto.randomUUID()}`;
  const password = "Operador-Catalogo-2026!";
  const created = await request.post(`${realmAdmin}/users`, {
    headers: await adminToken(request),
    data: {
      username,
      email: `${username}@example.test`,
      emailVerified: true,
      enabled: true,
      firstName: "Synthetic",
      lastName: "Operator",
      credentials: [{ type: "password", value: password, temporary: false }],
    },
  });
  expect(created.status()).toBe(201);
  const id = new URL(created.headers().location ?? "").pathname.split("/").pop() ?? "";
  expect(id).not.toBe("");
  return { id, username, password };
}

async function setOperatorRole(request: APIRequestContext, account: Account, granted: boolean) {
  const headers = await adminToken(request);
  const clients = (await (await request.get(`${realmAdmin}/clients?clientId=videogame-platform-bff`, { headers })).json()) as { id: string }[];
  const client = clients[0]?.id ?? "";
  const role = await (await request.get(`${realmAdmin}/clients/${client}/roles/catalogue-operator`, { headers })).json() as { id: string; name: string };
  const mapping = `${realmAdmin}/users/${account.id}/role-mappings/clients/${client}`;
  const data = [{ id: role.id, name: role.name }];
  const response = granted
    ? await request.post(mapping, { headers, data })
    : await request.delete(mapping, { headers, data });
  expect(response.status()).toBe(204);
}
