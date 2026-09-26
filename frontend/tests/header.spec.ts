import { expect, test } from "@playwright/test";

import { analyzeAccessibility } from "./fixtures/accessibility";
import { releasePage } from "./fixtures/releases";

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
    await page.goto("/");

    const trigger = page.getByRole("button", { name: "Mi cuenta" });
    await expect(trigger).toBeVisible();
    await expect(trigger).toHaveAttribute("aria-expanded", "false");
    expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);

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
