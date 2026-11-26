import { expect, test, type Locator, type Page } from "@playwright/test";

import { analyzeAccessibility } from "./fixtures/accessibility";
import { featuredWeekReleases, releasePage } from "./fixtures/releases";

/** The vertical centre of a header control, which tells the row it sits on. */
async function rowCentre(control: Locator) {
  const box = await control.boundingBox();
  if (!box) throw new Error("The header control is not rendered.");
  return box.y + box.height / 2;
}

async function expectSameRow(reference: Locator, controls: Locator[]) {
  const row = await rowCentre(reference);
  for (const control of controls) {
    expect(Math.abs((await rowCentre(control)) - row)).toBeLessThanOrEqual(2);
  }
}

async function expectNoPageOverflow(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
}

async function mockDiscovery(page: Page, session: () => object) {
  await page.route("**/api/v1/session", async (route) => {
    await route.fulfill({ json: session() });
  });
  await page.route("**/api/v1/releases?*", (route) => route.fulfill({ json: releasePage() }));
  await page.route("**/api/v1/featured-releases?*", route => route.fulfill({ json: featuredWeekReleases({ items: [] }) }));
  await page.route("**/api/v1/web-analytics", route => route.fulfill({ json: {} }));
  await page.route("**/api/v1/platforms", route => route.fulfill({ json: { items: [] } }));
}

const brand = (page: Page) => page.getByRole("link", { name: "Gameómetro · Inicio" });

for (const width of [320, 360, 390]) {
  test(`compact header keeps one row with the account and the menu apart at ${width}px`, async ({ page }, testInfo) => {
    let authenticated = true;
    let csrfHeader: string | null = null;
    await page.setViewportSize({ width, height: 760 });
    await mockDiscovery(page, () => authenticated
      ? { authenticated: true, csrfToken: "header-csrf", capabilities: ["catalogue_administration"] }
      : { authenticated: false });
    await page.route("**/api/v1/session", async (route) => {
      if (route.request().method() !== "POST") return route.fallback();
      csrfHeader = route.request().headers()["x-csrf-token"] ?? null;
      authenticated = false;
      await route.fulfill({ status: 204 });
    });
    // A fixture browser follows the BFF logout completion back into the anonymous shell.
    await page.route("**/auth/logout", route => route.fulfill({ status: 302, headers: { location: "/" } }));
    await page.goto("/lanzamientos");
    await expect(page.getByRole("button", { name: "Rechazar todo" })).toBeVisible();

    const menu = page.getByRole("button", { name: "Menú" });
    const account = page.getByRole("button", { name: "Mi cuenta" });
    const search = page.getByRole("button", { name: "Buscar juegos" });
    await expect(menu).toHaveAttribute("aria-expanded", "false");
    await expect(account).toHaveAttribute("aria-expanded", "false");
    // The full lockup, search, the account and the menu share the only row, in that order.
    await expectSameRow(brand(page), [search, account, menu]);
    expect((await brand(page).boundingBox())?.width ?? 0).toBeGreaterThan(120);
    expect((await account.boundingBox())?.x ?? 0).toBeLessThan((await menu.boundingBox())?.x ?? 0);
    await expect(page.getByRole("navigation", { name: "Principal" })).toHaveCount(0);
    for (const control of [menu, account, search]) {
      expect(Math.round((await control.boundingBox())?.height ?? 0)).toBeGreaterThanOrEqual(44);
    }
    await expectNoPageOverflow(page);

    await menu.focus();
    await page.keyboard.press("Enter");
    await expect(menu).toHaveAttribute("aria-expanded", "true");
    const nav = page.getByRole("navigation", { name: "Principal" });
    await expect(nav.getByRole("heading")).toHaveText(["Lanzamientos", "Lo mejor", "Plataformas"]);
    await expect(nav.getByRole("link")).toHaveText(["Destacados", "Explorar", "Calendario", "TOPómetro", "Nintendo Switch 2", "PlayStation 5", "Xbox Series X|S", "Windows PC", "Ver todas las plataformas →"]);
    await expect(nav.getByRole("link", { name: "Explorar", exact: true })).toHaveAttribute("aria-current", "page");
    // Menú holds the navigation only; the account keeps its own sheet.
    await expect(page.getByRole("group", { name: "Mi cuenta" })).toHaveCount(0);
    // The sheet spans the viewport under the header row and scrolls within it when taller.
    const sheet = page.locator(".header-sheet-panel");
    const sheetBox = await sheet.boundingBox();
    expect(sheetBox?.x).toBe(0);
    expect(sheetBox?.width).toBe(width);
    expect(sheetBox?.y ?? 0).toBeGreaterThanOrEqual((await menu.boundingBox())?.y ?? 0);
    expect((sheetBox?.y ?? 0) + (sheetBox?.height ?? 0)).toBeLessThanOrEqual(760);
    // Every destination is a row of the same height and type, never shrunk to fit.
    for (const link of await nav.getByRole("link").all()) {
      expect(Math.round((await link.boundingBox())?.height ?? 0)).toBeGreaterThanOrEqual(44);
    }
    expect(Number.parseFloat(await nav.getByRole("link", { name: "Calendario" }).evaluate(link => getComputedStyle(link).fontSize))).toBeGreaterThanOrEqual(16);
    await expectNoPageOverflow(page);
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
    await page.screenshot({ path: testInfo.outputPath(`compact-menu-authenticated-${width}.png`) });

    await page.keyboard.press("Tab");
    await expect(nav.getByRole("link", { name: "Destacados", exact: true })).toBeFocused();
    await page.keyboard.press("Escape");
    await expect(menu).toBeFocused();
    await expect(menu).toHaveAttribute("aria-expanded", "false");

    // Only one sheet shows at a time: the account trigger closes the menu as it opens its own.
    await menu.click();
    await account.click();
    await expect(menu).toHaveAttribute("aria-expanded", "false");
    await expect(account).toHaveAttribute("aria-expanded", "true");
    const actions = page.getByRole("group", { name: "Mi cuenta" });
    await expect(actions.getByRole("link")).toHaveText(["Mi perfil", "Juegos", "Deseados", "Puntuaciones", "Estadísticas", "Plataformas", "Panel de administrador"]);
    await expect(actions.getByRole("group")).toHaveText([/^Tu actividad/, /^Ajustes/]);
    await expect(page.getByRole("navigation", { name: "Principal" })).toHaveCount(0);
    // Logout is the last action. While the consent notice is open the sheet scrolls to it; once the
    // notice is closed it shows without scrolling, down to 320×760.
    const logout = actions.getByRole("button", { name: "Cerrar sesión" });
    await page.keyboard.press("Escape");
    await page.getByRole("button", { name: "Rechazar todo" }).click();
    await account.click();
    await expect(account).toHaveAttribute("aria-expanded", "true");
    const logoutBox = await logout.boundingBox();
    expect((logoutBox?.y ?? 760) + (logoutBox?.height ?? 0)).toBeLessThanOrEqual(760);
    await expectNoPageOverflow(page);
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
    await page.screenshot({ path: testInfo.outputPath(`compact-account-authenticated-${width}.png`) });

    await logout.click();
    await expect.poll(() => csrfHeader).toBe("header-csrf");
    const entry = page.getByRole("button", { name: "Entrar" });
    await entry.click();
    await expect(page.getByRole("group", { name: "Tu cuenta" }).getByRole("link", { name: "Iniciar sesión" })).toBeVisible();
    await expectSameRow(brand(page), [search, entry, menu]);
    await expectNoPageOverflow(page);
  });
}

test("the compact sheets work by touch: the scrim dismisses without acting and links close them", async ({ browser }, testInfo) => {
  const context = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true });
  const page = await context.newPage();
  await mockDiscovery(page, () => ({ authenticated: false }));
  await page.goto("/lanzamientos/destacados");
  await expect(page.getByRole("button", { name: "Rechazar todo" })).toBeVisible();
  const menu = page.getByRole("button", { name: "Menú" });
  const account = page.getByRole("button", { name: "Entrar" });
  await menu.tap();
  await expect(menu).toHaveAttribute("aria-expanded", "true");
  // Motion is limited to the sheet's entrance and settles; nothing loops.
  await expect.poll(() => page.evaluate(() => document.getAnimations().filter(a => a.playState === "running").length)).toBe(0);
  await page.screenshot({ path: testInfo.outputPath("compact-menu-anonymous-390.png") });
  await expect(page.getByRole("group", { name: "Tu cuenta" })).toHaveCount(0);

  // A tap on the dimmed page closes the sheet and never reaches what lies beneath it.
  await page.touchscreen.tap(195, 820);
  await expect(menu).toHaveAttribute("aria-expanded", "false");
  await expect(page).toHaveURL(/\/lanzamientos\/destacados$/);

  // Without a session the account sheet offers the BFF entry back to the page shown.
  await account.tap();
  await expect(account).toHaveAttribute("aria-expanded", "true");
  const entry = page.getByRole("group", { name: "Tu cuenta" });
  await expect(entry.getByRole("link", { name: "Iniciar sesión" })).toHaveAttribute("href", /^\/auth\/start\?returnTo=%2Flanzamientos%2Fdestacados$/);
  await expect(entry.getByRole("link", { name: "Crear cuenta" })).toHaveAttribute("href", /intent=register/);
  await page.screenshot({ path: testInfo.outputPath("compact-account-anonymous-390.png") });
  await page.touchscreen.tap(195, 820);
  await expect(account).toHaveAttribute("aria-expanded", "false");

  await menu.tap();
  await page.getByRole("navigation", { name: "Principal" }).getByRole("link", { name: "Ver todas las plataformas" }).tap();
  await expect(page).toHaveURL(/\/plataformas$/);
  await expect(page.getByRole("heading", { level: 1, name: "Plataformas" })).toBeVisible();
  await expect(menu).toHaveAttribute("aria-expanded", "false");
  await expect(page.getByRole("main")).toBeFocused();
  await menu.tap();
  await expect(page.getByRole("navigation", { name: "Principal" }).getByRole("link", { name: "Ver todas las plataformas" })).toHaveAttribute("aria-current", "page");
  await page.goBack();
  await expect(page).toHaveURL(/\/lanzamientos\/destacados$/);
  await expect(menu).toHaveAttribute("aria-expanded", "false");

  // Search stays one tap away, and opening it closes the sheet.
  await menu.tap();
  await page.getByRole("button", { name: "Buscar juegos" }).tap();
  await expect(page.getByRole("dialog", { name: "Buscar juegos" })).toBeVisible();
  await expect(menu).toHaveAttribute("aria-expanded", "false");
  await context.close();
});

test("the compact sheets respect reduced motion and forced colours", async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 760 });
  await page.emulateMedia({ reducedMotion: "reduce" });
  await mockDiscovery(page, () => ({ authenticated: false }));
  await page.goto("/lanzamientos/destacados");
  const menu = page.getByRole("button", { name: "Menú" });
  await menu.click();
  expect(await page.evaluate(() => document.getAnimations().length)).toBe(0);
  expect((await analyzeAccessibility(page)).violations).toEqual([]);
  await page.emulateMedia({ reducedMotion: "reduce", forcedColors: "active" });
  const current = page.getByRole("navigation", { name: "Principal" }).getByRole("link", { name: "Destacados", exact: true });
  await expect(current).toHaveCSS("outline-style", "solid");
  await expect(page.locator(".header-sheet-panel")).toHaveCSS("border-top-style", "solid");
  await page.getByRole("button", { name: "Entrar" }).click();
  expect(await page.evaluate(() => document.getAnimations().length)).toBe(0);
  await expect(page.locator(".header-sheet-panel")).toHaveCSS("border-top-style", "solid");
});

for (const width of [620, 680]) {
  test(`compact header shows the search field itself from 620px at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await mockDiscovery(page, () => ({ authenticated: false }));
    await page.goto("/lanzamientos");
    const field = page.getByRole("combobox", { name: "Buscar juegos" });
    await expect(field).toBeVisible();
    await expect(page.getByRole("button", { name: "Buscar juegos" })).toBeHidden();
    await expectSameRow(brand(page), [field, page.getByRole("button", { name: "Entrar" }), page.getByRole("button", { name: "Menú" })]);
    expect((await field.boundingBox())?.width ?? 0).toBeGreaterThan(200);
    await expectNoPageOverflow(page);
  });
}

for (const width of [720, 834, 1024, 1180, 1320]) {
  for (const authenticated of [false, true]) {
    test(`visible navigation keeps its type and the account its row at ${width}px, ${authenticated ? "signed in" : "anonymous"}`, async ({ page }, testInfo) => {
      await page.setViewportSize({ width, height: 900 });
      await mockDiscovery(page, () => authenticated ? { authenticated: true, csrfToken: "c" } : { authenticated: false });
      await page.goto("/lanzamientos");

      const nav = page.getByRole("navigation", { name: "Principal" });
      const releases = nav.getByRole("button", { name: "Lanzamientos", exact: true });
      const best = nav.getByRole("button", { name: "Lo mejor" });
      const platforms = nav.getByRole("button", { name: "Plataformas" });
      const account = authenticated
        ? page.getByRole("button", { name: "Mi cuenta" })
        : page.getByRole("link", { name: "Iniciar sesión", exact: true });
      const searchField = page.getByRole("search");
      await expect(account).toBeVisible();
      await expect(page.getByRole("button", { name: "Menú" })).toHaveCount(0);
      await expectSameRow(brand(page), [account, ...(authenticated ? [] : [page.getByRole("link", { name: "Crear cuenta", exact: true })])]);
      // Tablet sets the sections and search on a second row; desktop keeps one row.
      if (width < 1024) {
        expect(await rowCentre(releases)).toBeGreaterThan(await rowCentre(account) + 20);
        await expectSameRow(releases, [best, platforms, searchField]);
      } else {
        await expectSameRow(brand(page), [releases, best, platforms, searchField]);
      }
      // Search gives way first, never below its minimum.
      expect((await searchField.boundingBox())?.width ?? 0).toBeGreaterThanOrEqual(width < 1024 ? 240 : 200);
      for (const section of [releases, best, platforms]) {
        expect(Number.parseFloat(await section.evaluate(element => getComputedStyle(element).fontSize))).toBeGreaterThanOrEqual(14);
      }
      await expectNoPageOverflow(page);
      await page.screenshot({ path: testInfo.outputPath(`header-${authenticated ? "authenticated" : "anonymous"}-${width}.png`) });

      // The Plataformas panel sets its four tiles two by two and stays within the viewport.
      await platforms.click();
      const tiles = nav.getByRole("listitem").getByRole("link");
      await expect(tiles).toHaveText(["Nintendo Switch 2", "PlayStation 5", "Xbox Series X|S", "Windows PC"]);
      await expectSameRow(tiles.nth(0), [tiles.nth(1)]);
      await expectSameRow(tiles.nth(2), [tiles.nth(3)]);
      expect(await rowCentre(tiles.nth(2))).toBeGreaterThan(await rowCentre(tiles.nth(0)) + 20);
      for (const tile of await tiles.all()) {
        expect(Math.round((await tile.boundingBox())?.height ?? 0)).toBeGreaterThanOrEqual(44);
      }
      const platformsPanel = await page.locator(".platforms-panel").boundingBox();
      expect(platformsPanel?.x ?? -1).toBeGreaterThanOrEqual(0);
      expect((platformsPanel?.x ?? 0) + (platformsPanel?.width ?? width + 1)).toBeLessThanOrEqual(width);
      await page.keyboard.press("Escape");
      await expect(platforms).toHaveAttribute("aria-expanded", "false");

      // Lo mejor holds TOPómetro, named by its lockup at the rows' cap height, never an icon's.
      await best.click();
      const topometro = nav.getByRole("link", { name: "TOPómetro" });
      await expect(topometro).toHaveAttribute("href", "/topometro");
      expect(Math.round((await topometro.locator("svg").first().boundingBox())?.width ?? 0)).toBeGreaterThan(60);
      await page.keyboard.press("Escape");
      await expect(best).toHaveAttribute("aria-expanded", "false");

      if (authenticated) {
        await account.focus();
        await page.keyboard.press("Enter");
        await expect(account).toHaveAttribute("aria-expanded", "true");
        await page.keyboard.press("Tab");
        await expect(page.getByRole("link", { name: "Mi perfil", exact: true })).toBeFocused();
        await expect(page.getByRole("link", { name: "Mi perfil", exact: true })).toHaveAttribute("href", "/mi-perfil");
        // Tu actividad follows the profile, its entries without the possessive the group states.
        const activity = page.getByRole("group", { name: "Tu actividad" });
        await page.keyboard.press("Tab");
        await expect(activity.getByRole("link", { name: "Juegos", exact: true })).toHaveAttribute("href", "/mis-juegos");
        await expect(activity.getByRole("link", { name: "Juegos", exact: true })).toBeFocused();
        await page.keyboard.press("Tab");
        const wishlist = activity.getByRole("link", { name: "Deseados", exact: true });
        await expect(wishlist).toBeFocused();
        await expect(wishlist).toHaveAttribute("href", "/mis-deseados");
        await page.keyboard.press("Tab");
        await expect(activity.getByRole("link", { name: "Puntuaciones", exact: true })).toBeFocused();
        expect((await analyzeAccessibility(page)).violations).toEqual([]);
        await page.keyboard.press("Escape");
        await expect(account).toBeFocused();
        await expect(account).toHaveAttribute("aria-expanded", "false");
      }
    });
  }
}

test("Plataformas is a visible section that opens its index", async ({ page }) => {
  await page.setViewportSize({ width: 1320, height: 900 });
  await mockDiscovery(page, () => ({ authenticated: false }));
  await page.goto("/lanzamientos");
  const nav = page.getByRole("navigation", { name: "Principal" });
  await nav.getByRole("button", { name: "Plataformas" }).click();
  await nav.getByRole("link", { name: "Ver todas las plataformas" }).click();
  await expect(page).toHaveURL(/\/plataformas$/);
  await expect(page.getByRole("heading", { level: 1, name: "Plataformas" })).toBeVisible();
  await expect(nav.getByRole("button", { name: "Plataformas" })).toHaveAttribute("aria-current", "page");
  await expect(nav.getByRole("button", { name: "Lanzamientos", exact: true })).not.toHaveAttribute("aria-current");
  expect((await analyzeAccessibility(page)).violations).toEqual([]);
});
