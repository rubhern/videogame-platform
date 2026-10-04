import { expect, test, type Locator } from "@playwright/test";

import { analyzeAccessibility } from "./fixtures/accessibility";
import { releasePage } from "./fixtures/releases";

/** The vertical centre of a header control, which tells the row it sits on. */
async function rowCentre(control: Locator) {
  const box = await control.boundingBox();
  if (!box) throw new Error("The header control is not rendered.");
  return box.y + box.height / 2;
}

async function expectOnFirstRow(page: import("@playwright/test").Page, controls: Locator[]) {
  const firstRow = await rowCentre(page.getByRole("link", { name: "Gameómetro · Inicio" }));
  for (const control of controls) {
    expect(Math.abs((await rowCentre(control)) - firstRow)).toBeLessThanOrEqual(2);
  }
}

for (const width of [320, 390, 834, 1320]) {
  test(`authenticated header menu stays accessible at ${width}px`, async ({ page }) => {
    let authenticated = true;
    let csrfHeader: string | null = null;

    await page.setViewportSize({ width, height: 900 });
    await page.route("**/api/v1/session", async (route) => {
      if (route.request().method() === "POST") {
        csrfHeader = route.request().headers()["x-csrf-token"] ?? null;
        authenticated = false;
        await route.fulfill({ status: 204 });
        return;
      }
      await route.fulfill({ json: authenticated
        ? { authenticated: true, csrfToken: "header-csrf" }
        : { authenticated: false } });
    });
    await page.route("**/api/v1/releases?*", (route) => route.fulfill({ json: releasePage() }));
    await page.goto("/?view=recent");

    const trigger = page.getByRole("button", { name: "Mi cuenta" });
    await expect(trigger).toBeVisible();
    await expect(trigger).toHaveAttribute("aria-expanded", "false");
    expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
    // The account never takes a row of its own: it shares the first row with the identity and
    // navigation, and on phones with the search entry too, so the header is one row there.
    const releases = page.getByRole("navigation", { name: "Lanzamientos" });
    await expectOnFirstRow(page, [
      trigger,
      ...(width >= 360 ? [releases] : []),
      ...(width < 620 ? [page.getByRole("button", { name: "Buscar juegos" })] : []),
    ]);
    if (width < 360) {
      // Below 360px the three release sections take a full-width row of their own.
      const row = await releases.boundingBox();
      expect(row?.width ?? 0).toBeGreaterThan(width - 40);
      expect(await rowCentre(releases)).toBeGreaterThan(await rowCentre(trigger));
    }

    await trigger.focus();
    await page.keyboard.press("Enter");
    await expect(trigger).toHaveAttribute("aria-expanded", "true");
    await expect(page.getByRole("link", { name: "Mis puntuaciones" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Cerrar sesión" })).toBeVisible();
    expect((await analyzeAccessibility(page)).violations).toEqual([]);

    await page.keyboard.press("Tab");
    await expect(page.getByRole("link", { name: "Mis puntuaciones" })).toBeFocused();
    await page.keyboard.press("Escape");
    await expect(trigger).toBeFocused();
    await expect(trigger).toHaveAttribute("aria-expanded", "false");

    await trigger.click();
    await page.getByRole("button", { name: "Cerrar sesión" }).click();
    await expect(trigger).toHaveCount(0);
    expect(csrfHeader).toBe("header-csrf");
  });
}

for (const width of [620, 667, 834]) {
  test(`anonymous account entry shares the first header row at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.route("**/api/v1/session", (route) => route.fulfill({ json: { authenticated: false } }));
    await page.route("**/api/v1/releases?*", (route) => route.fulfill({ json: releasePage() }));
    await page.goto("/?view=recent");

    const signin = page.getByRole("link", { name: "Iniciar sesión", exact: true });
    await expect(signin).toBeVisible();
    await expectOnFirstRow(page, [
      signin,
      page.getByRole("link", { name: "Crear cuenta", exact: true }),
      page.getByRole("navigation", { name: "Lanzamientos" }),
    ]);
    expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
  });
}
