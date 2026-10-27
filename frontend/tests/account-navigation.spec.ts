import { expect, test } from "@playwright/test";
import { analyzeAccessibility } from "./fixtures/accessibility";

for (const width of [1320, 1024, 834, 390, 320]) {
  test(`anonymous account navigation stays accessible without an extra phone row at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 950 });
    await page.goto("/games/30000000-0000-4000-8000-000000000005/resident-evil-requiem");
    await expect(page.getByRole("heading", { level: 1, name: "Resident Evil Requiem" })).toBeVisible();
    if (width < 720) {
      // The compact header carries the anonymous entry in the Menú sheet's account dock (#358).
      const menu = page.getByRole("button", { name: "Menú" });
      await expect(menu).toBeVisible();
      const account = await menu.boundingBox();
      const search = await page.getByRole("button", { name: "Buscar juegos" }).boundingBox();
      expect(Math.abs((account?.y ?? -100) - (search?.y ?? 100))).toBeLessThanOrEqual(2);
      await page.screenshot({ path: testInfo.outputPath(`product-anonymous-${width}.png`), fullPage: true });
      await menu.focus();
      await page.keyboard.press("Enter");
      await expect(menu).toHaveAttribute("aria-expanded", "true");
      // Destacados, Explorar, Calendario, TOPómetro and Plataformas come before the account.
      for (let step = 0; step < 6; step++) await page.keyboard.press("Tab");
      await expect(page.getByRole("link", { name: "Iniciar sesión", exact: true })).toBeFocused();
    } else {
      await expect(page.getByRole("button", { name: "Menú" })).toHaveCount(0);
      await page.getByRole("link", { name: "Iniciar sesión", exact: true }).focus();
      if (width >= 1200) {
        const account = await page.getByRole("link", { name: "Iniciar sesión", exact: true }).boundingBox();
        const search = await page.getByRole("search").boundingBox();
        expect(Math.abs((account?.y ?? -100) - (search?.y ?? 100))).toBeLessThanOrEqual(3);
      }
    }
    const signin = page.getByRole("link", { name: "Iniciar sesión", exact: true });
    const register = page.getByRole("link", { name: "Crear cuenta", exact: true });
    await expect(signin).toHaveAttribute("href", /^\/auth\/start\?/);
    await expect(register).toHaveAttribute("href", /intent=register/);
    await page.keyboard.press("Tab");
    await expect(register).toBeFocused();
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath(`product-entry-${width}.png`), fullPage: true });
    if (width < 720) {
      await page.keyboard.press("Escape");
      await expect(page.getByRole("button", { name: "Menú" })).toBeFocused();
    }
    await page.emulateMedia({ reducedMotion: "reduce", forcedColors: "active" });
    await expect(width < 720 ? page.getByRole("button", { name: "Menú" }) : signin).toBeVisible();
  });
}
