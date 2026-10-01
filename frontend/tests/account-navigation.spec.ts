import { expect, test } from "@playwright/test";
import { analyzeAccessibility } from "./fixtures/accessibility";

for (const width of [1320, 1024, 834, 390, 320]) {
  test(`anonymous account navigation stays accessible without an extra phone row at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 950 });
    await page.goto("/games/30000000-0000-4000-8000-000000000005/resident-evil-requiem");
    await expect(page.getByRole("heading", { level: 1, name: "Resident Evil Requiem" })).toBeVisible();
    if (width < 620) {
      const trigger = page.getByRole("button", { name: "Acceder a tu cuenta" });
      await expect(trigger).toBeVisible();
      const account = await trigger.boundingBox();
      const search = await page.getByRole("button", { name: "Buscar juegos" }).boundingBox();
      expect(Math.abs((account?.y ?? -100) - (search?.y ?? 100))).toBeLessThanOrEqual(2);
      await page.screenshot({ path: testInfo.outputPath(`product-anonymous-${width}.png`), fullPage: true });
      await trigger.focus();
      await page.keyboard.press("Enter");
      await expect(trigger).toHaveAttribute("aria-expanded", "true");
      await page.keyboard.press("Tab");
      await expect(page.getByRole("link", { name: "Iniciar sesión", exact: true })).toBeFocused();
    } else {
      await expect(page.getByRole("button", { name: "Acceder a tu cuenta" })).toBeHidden();
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
    if (width < 620) {
      await page.keyboard.press("Escape");
      await expect(page.getByRole("button", { name: "Acceder a tu cuenta" })).toBeFocused();
    }
    await page.emulateMedia({ reducedMotion: "reduce", forcedColors: "active" });
    await expect(width < 620 ? page.getByRole("button", { name: "Acceder a tu cuenta" }) : signin).toBeVisible();
  });
}
