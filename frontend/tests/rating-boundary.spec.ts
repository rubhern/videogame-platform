import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

const gamePath =
  "/games/30000000-0000-4000-8000-000000000005/resident-evil-requiem";

const username = process.env.OIDC_TEST_USERNAME;
const password = process.env.OIDC_TEST_PASSWORD;

test("anonymous browsing exposes no login entry point but offers the rating boundary", async ({
  page,
}) => {
  await page.goto(gamePath);
  await expect(
    page.getByRole("heading", { level: 1, name: "Resident Evil Requiem" }),
  ).toBeVisible();

  // No general account/login entry point while anonymous.
  await expect(page.getByText("Mi cuenta")).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "Cerrar sesión" }),
  ).toHaveCount(0);

  // The inline, accessible rating scale is present and enabled for an eligible game.
  await expect(
    page.getByRole("group", { name: "Nota del 1 al 10" }),
  ).toBeVisible();
  await expect(page.getByRole("button", { name: "8", exact: true })).toBeEnabled();
  await expect(page.getByRole("button", { name: /Puntuar|Actualizar/ })).toHaveCount(0);
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
});

test.describe("real Keycloak rating journey", () => {
  // Both journeys rate the same game; serial order keeps the community assertions deterministic.
  test.describe.configure({ mode: "serial" });
  test.skip(
    !username || !password,
    "The real Keycloak compatibility topology is not active.",
  );

  test("authenticates from the rating boundary, persists the chosen value and updates and deletes it", async ({
    context,
    page,
  }) => {
    await page.goto(gamePath);
    await page.getByRole("button", { name: "8", exact: true }).click();

    // Authentication is hosted by Keycloak, not a product login page.
    await expect(page).toHaveURL(
      /\/realms\/videogame-platform\/protocol\/openid-connect\/auth/,
    );
    await page.getByLabel("Username").fill(username ?? "");
    await page.locator("#password").fill(password ?? "");
    await page.getByRole("button", { name: "Sign In" }).click();

    // Back on the same game: the chosen value is persisted once through the conditional
    // contract, and personal and community state update together.
    await expect(page).toHaveURL(new RegExp(gamePath));
    await expect(page.getByRole("status").filter({ hasText: "Puntuación guardada: 8/10." })).toHaveCount(1);
    await expect(
      page.getByRole("button", { name: "8", exact: true }),
    ).toHaveAttribute("aria-pressed", "true");
    await expect(page.getByText("Mi cuenta")).toBeVisible();
    const community = page.getByRole("region", {
      name: "Puntuaciones de la comunidad",
    });
    await expect(community.getByLabel(/Nota media: 8,0 de 10/)).toBeVisible();
    expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);

    // A reload reads the persisted rating back with a fresh ETag and resumes nothing.
    await page.reload();
    await expect(
      page.getByRole("button", { name: "8", exact: true }),
    ).toHaveAttribute("aria-pressed", "true");
    await expect(page.getByText(/Puntuación guardada/)).toHaveCount(0);

    // Update with the current ETag by pressing another value.
    await page.getByRole("button", { name: "9", exact: true }).click();
    await expect(page.getByRole("status").filter({ hasText: "Puntuación guardada: 9/10." })).toHaveCount(1);
    await expect(community.getByLabel(/Nota media: 9,0 de 10/)).toBeVisible();

    // Delete with the new ETag.
    await page.getByRole("button", { name: "Eliminar puntuación" }).click();
    await expect(page.getByRole("status").filter({ hasText: "Puntuación eliminada." })).toHaveCount(1);
    await expect(page.getByText("Selecciona una nota")).toBeVisible();
    await expect(community.getByText("Sin nota todavía")).toBeVisible();

    // Logout returns the header to the anonymous state and clears the session cookie.
    await page.getByRole("button", { name: "Mi cuenta" }).click();
    await page.getByRole("button", { name: "Cerrar sesión" }).click();
    await expect(page.getByText("Mi cuenta")).toHaveCount(0);
    await expect(await context.cookies("http://application:8080")).toEqual([]);
  });


  test("Mis puntuaciones supports search, direct maintenance and a real concurrent ETag conflict", async ({ page, context }, testInfo) => {
    await page.goto(gamePath);
    await page.getByRole("button", { name: "8", exact: true }).click();
    await page.getByLabel("Username").fill(username ?? "");
    await page.locator("#password").fill(password ?? "");
    await page.getByRole("button", { name: "Sign In" }).click();
    await expect(page.getByRole("button", { name: "8", exact: true })).toHaveAttribute("aria-pressed", "true");
    await page.getByRole("button", { name: "Mi cuenta" }).click();
    await page.getByRole("link", { name: "Mis puntuaciones", exact: true }).click();
    await expect(page.getByRole("heading", { name: "Mis puntuaciones", exact: true })).toBeVisible();
    await expect(page.getByText("8/10", { exact: true })).toBeVisible();

    for (const width of [1320, 834, 390, 320]) {
      await page.setViewportSize({ width, height: 950 });
      await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
      await page.screenshot({ path: testInfo.outputPath(`my-ratings-${width}.png`), fullPage: true });
    }
    const search = page.getByRole("search", { name: "Buscar en mis puntuaciones" });
    await search.getByRole("searchbox").fill("doesnotexist");
    await search.getByRole("button").click();
    await expect(page.getByText("No hay puntuaciones que coincidan con tu búsqueda")).toBeVisible();
    await page.getByRole("button", { name: "Limpiar búsqueda" }).click();
    await expect(page.getByText("8/10", { exact: true })).toBeVisible();
    await page.getByRole("button", { name: "Editar puntuación" }).focus();
    await page.keyboard.press("Enter");
    await expect(page.getByRole("combobox", { name: /^Nueva puntuación/ })).toBeFocused();
    await page.getByRole("combobox", { name: /^Nueva puntuación/ }).click();
    await page.getByRole("option", { name: "9/10" }).click();
    await page.getByRole("button", { name: "Guardar cambios" }).click();
    await expect(page.locator(".my-rating-value")).toHaveText("9/10");

    // Another request in the same real authenticated session wins before this page writes.
    const session = await (await context.request.get("/api/v1/session")).json() as { csrfToken: string };
    const ratingUrl = "/api/v1/me/ratings/30000000-0000-4000-8000-000000000005";
    const current = await (await context.request.get(ratingUrl)).json() as { entityTag: string };
    const winner = await context.request.put(ratingUrl, { data: { value: 6 },
      headers: { "If-Match": current.entityTag, "X-CSRF-Token": session.csrfToken, "Origin": new URL(page.url()).origin } });
    expect(winner.status()).toBe(200);
    await page.getByRole("button", { name: "Eliminar puntuación" }).click();
    await expect(page.getByRole("alert")).toContainText("cambió en otra sesión");
    await expect(page.getByText("6/10", { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "Eliminar puntuación" })).toBeDisabled();
    await page.getByRole("button", { name: "Actualizar resultados" }).click();
    await expect(page.getByRole("button", { name: "Eliminar puntuación" })).toBeEnabled();
    await page.getByRole("button", { name: "Eliminar puntuación" }).click();
    await expect(page.getByText("Todavía no has puntuado ningún juego")).toBeVisible();
    await expect(page.getByRole("heading", { name: "Resultados de mis puntuaciones" })).toBeFocused();
    expect(await page.evaluate(() => ({ local: localStorage.length, session: sessionStorage.length })))
      .toEqual({ local: 0, session: 0 });
    expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  });

  test("a new visitor self-registers through Keycloak and resumes the same game and value", async ({
    page,
  }) => {
    await page.goto(gamePath);
    await page.getByRole("button", { name: "7", exact: true }).click();

    // Keycloak hosts registration; the product exposes no registration page.
    await expect(page).toHaveURL(
      /\/realms\/videogame-platform\/protocol\/openid-connect\/auth/,
    );
    await page.getByRole("link", { name: "Register" }).click();

    const unique = `player-${Date.now()}`;
    await page.locator("#firstName").fill("New");
    await page.locator("#lastName").fill("Visitor");
    await page.locator("#email").fill(`${unique}@localhost.invalid`);
    await page.locator("#username").fill(unique);
    await page.locator("#password").fill("Str0ng-Passw0rd!");
    await page.locator("#password-confirm").fill("Str0ng-Passw0rd!");
    await page.getByRole("button", { name: "Register" }).click();

    // First-time registration completes authentication and persists the chosen value.
    await expect(page).toHaveURL(new RegExp(gamePath));
    await expect(page.getByRole("status").filter({ hasText: "Puntuación guardada: 7/10." })).toHaveCount(1);
    await expect(
      page.getByRole("button", { name: "7", exact: true }),
    ).toHaveAttribute("aria-pressed", "true");
    await expect(page.getByText("Mi cuenta")).toBeVisible();
  });
});
