import { expect, test, type Page } from "@playwright/test";

import { analyzeAccessibility } from "./fixtures/accessibility";

const gamePath =
  "/games/30000000-0000-4000-8000-000000000005/resident-evil-requiem";

const username = process.env.OIDC_TEST_USERNAME;
const password = process.env.OIDC_TEST_PASSWORD;

const reading = (page: Page) => page.getByRole("button", { name: /^Tu puntuación/ });

/** Opens the keypad from the compact reading and picks a value. */
async function pick(page: Page, value: number) {
  await reading(page).click();
  await page
    .getByRole("dialog", { name: /^Tu puntuación de / })
    .getByRole("button", { name: String(value), exact: true })
    .click();
}

test("anonymous browsing offers account entry and preserves the rating boundary", async ({
  page,
}) => {
  await page.goto(gamePath);
  await expect(
    page.getByRole("heading", { level: 1, name: "Resident Evil Requiem" }),
  ).toBeVisible();

  await expect(page.getByRole("link", { name: "Iniciar sesión", exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "Crear cuenta", exact: true })).toBeVisible();
  await expect(page.getByText("Mi cuenta")).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "Cerrar sesión" }),
  ).toHaveCount(0);

  // The compact reading opens an accessible, enabled scale for an eligible game.
  await expect(reading(page)).toBeEnabled();
  await reading(page).click();
  await expect(
    page.getByRole("group", { name: "Nota del 1 al 10" }),
  ).toBeVisible();
  await expect(page.getByRole("button", { name: "8", exact: true })).toBeEnabled();
  await expect(
    page.getByRole("button", { name: /^(Puntuar|Actualizar|Guardar nota)$/ }),
  ).toHaveCount(0);
  expect((await analyzeAccessibility(page)).violations).toEqual([]);
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
    await pick(page, 8);

    // Authentication is hosted by Keycloak, not a product login page.
    await expect(page).toHaveURL(
      /\/realms\/videogame-platform\/protocol\/openid-connect\/auth/,
    );
    await page.getByLabel("Usuario", { exact: true }).fill(username ?? "");
    await page.locator("#password").fill(password ?? "");
    await page.getByRole("button", { name: "Iniciar sesión", exact: true }).click();

    // Back on the same game: the chosen value is persisted once through the conditional
    // contract, and personal and community state update together.
    await expect(page).toHaveURL(new RegExp(gamePath));
    await expect(page.getByRole("status").filter({ hasText: "Puntuación guardada: 8/10." })).toHaveCount(1);
    await expect(reading(page)).toHaveAccessibleName(/^Tu puntuación Caliente 8\/10/);
    await expect(page.getByText("Mi cuenta")).toBeVisible();
    const community = page.getByRole("region", {
      name: "Puntuación de la comunidad",
    });
    await expect(community.getByLabel(/Nota media: 8,0 de 10/)).toBeVisible();
    expect((await analyzeAccessibility(page)).violations).toEqual([]);

    // A reload reads the persisted rating back with a fresh ETag and resumes nothing.
    await page.reload();
    await expect(reading(page)).toHaveAccessibleName(/^Tu puntuación Caliente 8\/10/);
    await expect(page.getByText(/Puntuación guardada/)).toHaveCount(0);

    // Update with the current ETag by picking another value.
    await pick(page, 9);
    await expect(page.getByRole("status").filter({ hasText: "Puntuación guardada: 9/10." })).toHaveCount(1);
    await expect(community.getByLabel(/Nota media: 9,0 de 10/)).toBeVisible();

    // Delete with the new ETag.
    await reading(page).click();
    await page.getByRole("button", { name: "Eliminar puntuación" }).click();
    await expect(page.getByRole("status").filter({ hasText: "Puntuación eliminada." })).toHaveCount(1);
    await expect(reading(page)).toHaveAccessibleName("Tu puntuación: sin nota. Puntuar");
    await expect(community.getByText("Sin nota todavía")).toBeVisible();

    // Logout returns the header to the anonymous state and clears the session cookie.
    await page.getByRole("button", { name: "Mi cuenta" }).click();
    await page.getByRole("button", { name: "Cerrar sesión" }).click();
    await expect(page.getByText("Mi cuenta")).toHaveCount(0);
    await expect(await context.cookies(new URL(process.env.PLAYWRIGHT_BASE_URL ?? "http://application:8080").origin)).toEqual([]);
  });


  test("Mis puntuaciones supports search, direct maintenance and a real concurrent ETag conflict", async ({ page, context }, testInfo) => {
    await page.goto(gamePath);
    await pick(page, 8);
    await page.getByLabel("Usuario", { exact: true }).fill(username ?? "");
    await page.locator("#password").fill(password ?? "");
    await page.getByRole("button", { name: "Iniciar sesión", exact: true }).click();
    await expect(reading(page)).toHaveAccessibleName(/^Tu puntuación Caliente 8\/10/);
    await page.getByRole("button", { name: "Mi cuenta" }).click();
    await page.getByRole("link", { name: "Mis puntuaciones", exact: true }).click();
    await expect(page.getByRole("heading", { name: "Mis puntuaciones", exact: true })).toBeVisible();
    await expect(page.getByText("8/10", { exact: true })).toBeVisible();

    for (const width of [1320, 834, 390, 320]) {
      await page.setViewportSize({ width, height: 950 });
      await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      expect((await analyzeAccessibility(page)).violations).toEqual([]);
      await page.screenshot({ path: testInfo.outputPath(`my-ratings-${width}.png`), fullPage: true });
    }
    const search = page.getByRole("search", { name: "Buscar en mis puntuaciones" });
    await search.getByRole("searchbox").fill("doesnotexist");
    await search.getByRole("button").click();
    await expect(page.getByText("No hay puntuaciones que coincidan con tu búsqueda")).toBeVisible();
    await page.getByRole("button", { name: "Limpiar búsqueda" }).click();
    await expect(page.getByText("8/10", { exact: true })).toBeVisible();
    const score = page.getByRole("button", { name: /^Tu puntuación/ });
    await score.focus();
    await page.keyboard.press("Enter");
    const editor = page.getByRole("dialog", { name: /^Tu puntuación de / });
    await expect(editor.getByRole("button", { name: "8", exact: true })).toBeFocused();
    await editor.getByRole("button", { name: "9", exact: true }).click();
    await editor.getByRole("button", { name: "Guardar nota" }).click();
    await expect(score).toHaveAccessibleName(/^Tu puntuación Ardiendo 9\/10/);

    // Another request in the same real authenticated session wins before this page writes.
    const session = await (await context.request.get("/api/v1/session")).json() as { csrfToken: string };
    const ratingUrl = "/api/v1/me/ratings/30000000-0000-4000-8000-000000000005";
    const current = await (await context.request.get(ratingUrl)).json() as { entityTag: string };
    const winner = await context.request.put(ratingUrl, { data: { value: 6 },
      headers: { "If-Match": current.entityTag, "X-CSRF-Token": session.csrfToken, "Origin": new URL(page.url()).origin } });
    expect(winner.status()).toBe(200);
    await score.click();
    await editor.getByRole("button", { name: "Eliminar puntuación" }).click();
    await editor.getByRole("button", { name: "Sí, eliminar" }).click();
    await expect(editor.getByRole("alert")).toContainText("cambió en otra sesión");
    // The collection re-reads itself after the conflict; no refresh action exists.
    await expect(score).toHaveAccessibleName(/^Tu puntuación Templado 6\/10/);
    await expect(page.getByRole("button", { name: "Actualizar resultados" })).toHaveCount(0);
    await expect(editor.getByRole("button", { name: "Eliminar puntuación" })).toBeEnabled();
    await editor.getByRole("button", { name: "Eliminar puntuación" }).click();
    await editor.getByRole("button", { name: "Sí, eliminar" }).click();
    await expect(page.getByText("Todavía no has puntuado ningún juego")).toBeVisible();
    await expect(page.getByRole("heading", { name: "Resultados de mis puntuaciones" })).toBeFocused();
    expect(await page.evaluate(() => ({ local: localStorage.length, session: sessionStorage.length })))
      .toEqual({ local: 0, session: 0 });
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
  });

  test("a new visitor self-registers through Keycloak and resumes the same game and value", async ({
    page,
  }) => {
    await page.goto(gamePath);
    await pick(page, 7);

    // Keycloak hosts registration; the product exposes no registration page.
    await expect(page).toHaveURL(
      /\/realms\/videogame-platform\/protocol\/openid-connect\/auth/,
    );
    await page.getByRole("link", { name: "Crear cuenta", exact: true }).click();

    const unique = `player-${Date.now()}`;
    await page.locator("#firstName").fill("New");
    await page.locator("#lastName").fill("Visitor");
    await page.locator("#email").fill(`${unique}@localhost.invalid`);
    await page.locator("#username").fill(unique);
    await page.locator("#password").fill("Str0ng-Passw0rd!");
    await page.locator("#password-confirm").fill("Str0ng-Passw0rd!");
    await page.getByRole("button", { name: "Crear cuenta", exact: true }).click();

    // First-time registration completes authentication and persists the chosen value.
    await expect(page).toHaveURL(new RegExp(gamePath));
    await expect(page.getByRole("status").filter({ hasText: "Puntuación guardada: 7/10." })).toHaveCount(1);
    await expect(reading(page)).toHaveAccessibleName(/^Tu puntuación Caliente 7\/10/);
    await expect(page.getByText("Mi cuenta")).toBeVisible();
  });
});
