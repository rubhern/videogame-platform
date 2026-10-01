import { expect, test, type Page } from "@playwright/test";

import { analyzeAccessibility } from "./fixtures/accessibility";
import { releasePage } from "./fixtures/releases";
import { gameDetailsFixture } from "../src/test/game-details-fixture";

/**
 * Gameómetro identity and thermal language at the browser boundary (#152): the product name and
 * icons the browser shows, the brand's place in the header at the review widths, and the
 * community mean read as a temperature at the band edges. The API is scripted at the network
 * boundary.
 */

const gamePath = "/games/30000000-0000-4000-8000-000000000005/resident-evil-requiem";

async function anonymous(page: Page) {
  await page.route("**/api/v1/session", (route) =>
    route.fulfill({ json: { authenticated: false } }),
  );
}

async function gameWithMean(page: Page, mean: number) {
  const game = gameDetailsFixture();
  game.ratingStatistics = {
    status: "available",
    mean,
    count: 12,
    distribution: { "1": 0, "2": 0, "3": 0, "4": 0, "5": 0, "6": 0, "7": 0, "8": 0, "9": 0, "10": 12 },
  };
  await page.route("**/api/v1/games/*", (route) => route.fulfill({ json: game }));
}

test("the browser names the product Gameómetro and shows its icons", async ({ page }) => {
  await anonymous(page);
  await page.route("**/api/v1/releases?*", (route) => route.fulfill({ json: releasePage() }));
  await page.goto("/");

  await expect(page).toHaveTitle("Gameómetro");
  for (const [selector, type] of [
    ['link[rel="icon"]', "image/svg+xml"],
    ['link[rel="apple-touch-icon"]', "image/png"],
  ]) {
    const href = await page.locator(selector).getAttribute("href");
    const icon = await page.request.get(href ?? "");
    expect(icon.ok()).toBe(true);
    expect(icon.headers()["content-type"]).toContain(type);
  }
});

for (const width of [320, 390, 834, 1320]) {
  test(`the identity keeps its place in the header at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await anonymous(page);
    await page.route("**/api/v1/releases?*", (route) => route.fulfill({ json: releasePage() }));
    await page.goto("/");

    const brand = page.getByRole("link", { name: "Gameómetro · Inicio" });
    await expect(brand).toBeVisible();
    // The compact mark alone carries the identity below the narrowest common phone.
    await expect(brand.locator(".brand-wordmark")).toBeVisible({ visible: width >= 375 });
    await expect(page.getByRole("contentinfo")).toContainText("Gameómetro");
    expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
  });
}

for (const [mean, label, band] of [
  [2, "Congelado", "freeze"],
  [2.1, "Frío", "cold"],
  [8, "Caliente", "hot"],
  [8.1, "Ardiendo", "burn"],
  [10, "Ardiendo", "burn"],
] as const) {
  test(`a community mean of ${mean} reads as ${label}`, async ({ page }) => {
    await anonymous(page);
    await gameWithMean(page, mean);
    await page.goto(gamePath);

    const community = page.locator(".game-community-score");
    const shown = mean.toLocaleString("es-ES", { minimumFractionDigits: 1, maximumFractionDigits: 1 });
    await expect(community.getByLabel(`Nota media: ${shown} de 10`)).toBeVisible();
    await expect(community.getByText(`Temperatura: ${label}`, { exact: true })).toBeVisible();
    await expect(community).toHaveAttribute("data-thermal", band);
  });
}

test("a burning reading stays accessible and still under reduced motion", async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await anonymous(page);
  await gameWithMean(page, 9.1);
  await page.goto(gamePath);

  const community = page.locator(".game-community-score");
  await expect(community.getByText("Temperatura: Ardiendo", { exact: true })).toBeVisible();
  // Reduced motion is the default: the needle stands at its reading and the embers do not breathe.
  expect(await community.evaluate((panel) => panel.getAnimations({ subtree: true }).length)).toBe(0);
  expect((await analyzeAccessibility(page)).violations).toEqual([]);
});
