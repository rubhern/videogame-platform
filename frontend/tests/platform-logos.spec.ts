import { expect, test } from "@playwright/test";

import { analyzeAccessibility } from "./fixtures/accessibility";
import { pragmata, pragmataRelease, releasePage } from "./fixtures/releases";

const platforms = [
  { platformId: "10000000-0000-4000-8000-000000000002", name: "Nintendo Switch 2" },
  { platformId: "10000000-0000-4000-8000-000000000001", name: "PlayStation 5" },
  { platformId: "10000000-0000-4000-8000-000000000004", name: "Xbox Series X|S" },
  { platformId: "10000000-0000-4000-8000-000000000003", name: "Windows PC" },
  { platformId: "android-id", name: "Android" },
  { platformId: "linux-id", name: "Linux" },
  { platformId: "mac-id", name: "macOS" },
  { platformId: "steamvr-id", name: "SteamVR" },
];

for (const density of [1, 2]) {
test.describe(`${density}× display`, () => {
  test.use({ deviceScaleFactor: density });
for (const width of [320, 390, 834, 1320]) {
  test(`platform identities, original artwork and credits remain usable at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 1000 });
    await page.emulateMedia({ reducedMotion: "reduce" });
    await page.route("**/api/v1/session", route => route.fulfill({ json: { authenticated: false } }));
    await page.route("**/api/v1/releases?*", route => route.fulfill({ json: releasePage({
      availableFilters: { platforms, regions: [{ regionId: "worldwide", name: "Mundial" }] },
      items: platforms.map((platform, index) => {
        const gameId = `30000000-0000-4000-8000-${String(index + 1).padStart(12, "0")}`;
        return {
          ...pragmata,
          gameId,
          canonicalTitle: `Juego ${index + 1}`,
          primaryCover: { ...pragmata.primaryCover, alternativeText: `Portada no disponible de Juego ${index + 1}` },
          releases: [{
            ...pragmataRelease,
            gameId,
            releaseId: `40000000-0000-4000-8000-${String(index + 1).padStart(12, "0")}`,
            platform,
          }],
        };
      }),
      page: { number: 1, size: 12, totalItems: 8, totalPages: 1 },
    }) }));
    await page.goto("/lanzamientos");
    await expect(page.getByRole("heading", { level: 1, name: "Explorar lanzamientos" })).toBeVisible();
    // Phones fold filters; activate the existing native control before inspecting its options.
    const filters = page.getByRole("button", { name: /Filtros/ });
    if (await filters.isVisible()) await filters.click();
    const platform = page.getByRole("combobox", { name: /Plataforma/ });
    await platform.focus();
    await page.keyboard.press("Enter");
    for (const entry of platforms) {
      await expect(page.getByRole("option", { name: entry.name, exact: true })).toBeAttached();
    }
    const android = page.getByRole("option", { name: "Android", exact: true }).locator("img");
    await expect(android).toHaveAttribute("src", "/assets/platforms/android.svg");
    await expect(android).toHaveCSS("object-fit", "contain");
    await expect(android).toHaveCSS("filter", "none");
    expect(await android.evaluate((image: HTMLImageElement) => image.complete && image.naturalWidth > 0)).toBe(true);
    expect(await page.locator('img[src="/assets/platforms/linux.svg"]').first()
      .evaluate((image: HTMLImageElement) => image.complete && image.naturalWidth > 0)).toBe(true);
    const windows = page.getByRole("option", { name: "Windows PC", exact: true }).locator("img");
    await expect(windows).toHaveAttribute("src", "/assets/platforms/windows.svg");
    await expect(windows).toHaveCSS("filter", "none");
    expect(await windows.evaluate((image: HTMLImageElement) => image.complete && image.naturalWidth > 0)).toBe(true);
    await expect(page.getByRole("option", { name: "SteamVR", exact: true }).locator(".platform-logo-text"))
      .toHaveText("SteamVR");
    await page.screenshot({ path: testInfo.outputPath(`platform-filter-${width}-${density}x.png`) });
    await page.keyboard.press("Escape");
    await expect(platform).toBeFocused();
    await expect(page.locator(".platform-marks").getByText("Windows PC", { exact: true }).first()).toBeVisible();
    await page.screenshot({ path: testInfo.outputPath(`application-cards-${width}-${density}x.png`), fullPage: true });
    const credits = page.getByText("Créditos de marcas", { exact: true });
    await credits.focus();
    await page.keyboard.press("Enter");
    await expect(page.locator(".platform-credits")).toHaveAttribute("open");
    await expect(page.getByRole("link", { name: "Creative Commons 3.0 Attribution License" })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
    await page.emulateMedia({ forcedColors: "active" });
    await expect(page.getByText("Juego 1", { exact: true })).toBeVisible();
    await expect(page.locator(".platform-logo-text").filter({ hasText: "SteamVR" }).first()).toBeAttached();
  });
}
});
}
