import { expect, test, type Page } from "@playwright/test";

import { analyzeAccessibility } from "./fixtures/accessibility";

const directory = [
  { platformId: "android-id", name: "Android" },
  { platformId: "ios-id", name: "iOS" },
  { platformId: "linux-id", name: "Linux" },
  { platformId: "mac-id", name: "Mac" },
  { platformId: "quest-id", name: "Meta Quest 3" },
  { platformId: "10000000-0000-4000-8000-000000000002", name: "Nintendo Switch 2" },
  { platformId: "switch-id", name: "Nintendo Switch" },
  { platformId: "playdate-id", name: "Playdate" },
  { platformId: "ps4-id", name: "PlayStation 4" },
  { platformId: "10000000-0000-4000-8000-000000000001", name: "PlayStation 5" },
  { platformId: "psvr2-id", name: "PlayStation VR2" },
  { platformId: "steamvr-id", name: "SteamVR" },
  { platformId: "10000000-0000-4000-8000-000000000003", name: "Windows PC" },
  { platformId: "10000000-0000-4000-8000-000000000004", name: "Xbox Series X|S" },
  { platformId: "xone-id", name: "Xbox One" },
];

const featured = ["Nintendo Switch 2", "PlayStation 5", "Xbox Series X|S", "Windows PC"];
const offered = [
  "Android", "iOS", "Linux", "Mac", "PlayStation 4", "Meta Quest 3", "Nintendo Switch", "Xbox One", "PlayStation VR2",
];

async function mockApi(page: Page) {
  await page.route("**/api/v1/session", (route) => route.fulfill({ json: { authenticated: false } }));
  await page.route("**/api/v1/platforms", (route) => route.fulfill({ json: { items: directory } }));
}

const cards = (page: Page) => page.getByRole("list", { name: "Plataformas destacadas" }).getByRole("article");
const others = (page: Page) => page.getByRole("region", { name: "Otras plataformas" });

test.beforeEach(async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await mockApi(page);
});

test("the header reaches Plataformas and a direct load or refresh renders it", async ({ page }) => {
  await page.setViewportSize({ width: 1320, height: 900 });
  await page.route("**/api/v1/featured-releases*", (route) => route.abort());
  await page.goto("/lanzamientos/destacados");
  const nav = page.getByRole("navigation", { name: "Principal" });
  await nav.getByRole("link", { name: "Plataformas" }).click();
  await expect(page).toHaveURL(/\/plataformas$/);
  await expect(page.getByRole("heading", { level: 1, name: "Plataformas" })).toBeVisible();
  await expect(nav.getByRole("link", { name: "Plataformas" })).toHaveAttribute("aria-current", "page");

  await page.goto("/plataformas?plataforma=ps4-id");
  await page.reload();
  await expect(others(page).getByRole("list", { name: "Destinos de PlayStation 4" })).toBeVisible();
  await expect(others(page).getByRole("button", { name: "PlayStation 4" })).toHaveAttribute("aria-expanded", "true");
});

test("a phone reaches Plataformas through the Menú", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/topometro");
  await page.getByRole("button", { name: "Menú" }).click();
  await page.getByRole("link", { name: "Plataformas" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Plataformas" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Menú" })).toHaveAttribute("aria-expanded", "false");
});

test("a platform opens its destinations in place by keyboard, keeps the reading position and Back closes it", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/plataformas");
  await expect(others(page).getByRole("button")).toHaveText(offered);
  const ps4 = others(page).getByRole("button", { name: "PlayStation 4" });
  await ps4.scrollIntoViewIfNeeded();
  const before = await page.evaluate(() => scrollY);
  await ps4.focus();
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/\?plataforma=ps4-id$/);
  await expect(ps4).toHaveAttribute("aria-expanded", "true");
  await expect(ps4).toBeFocused();
  expect(await page.evaluate(() => scrollY)).toBe(before);
  const destinations = others(page).getByRole("list", { name: "Destinos de PlayStation 4" });
  await expect(destinations).toBeInViewport();
  await expect(destinations.getByRole("listitem"))
    .toHaveText([/Próximos destacados\s*Próximamente/, /TOPómetro\s*Próximamente/, /Explorar catálogo\s*Próximamente/]);

  await page.keyboard.press("Space");
  await expect(page).toHaveURL(/\/plataformas$/);
  await expect(destinations).toHaveCount(0);
  await page.goBack();
  await expect(destinations).toBeVisible();
  await page.goBack();
  await expect(page).toHaveURL(/\/plataformas$/);
  await expect(ps4).toHaveAttribute("aria-expanded", "false");
});

for (const width of [320, 360, 390, 834, 1320]) {
  test(`reflows the showcase and Otras without overflow at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: width >= 1024 ? 900 : 844 });
    await page.goto("/plataformas?plataforma=psvr2-id");
    await expect(cards(page).getByRole("heading", { level: 2 })).toHaveText(featured);

    const boxes = await cards(page).evaluateAll((elements) =>
      elements.map((element) => element.getBoundingClientRect()).map(({ x, y, width: w }) => ({ x, y, w })));
    const columns = width >= 1024 ? 4 : width >= 560 ? 2 : 1;
    expect(new Set(boxes.map((box) => Math.round(box.y))).size).toBe(4 / columns);
    if (width >= 1024) {
      // The four stages fit in the first screen.
      expect(Math.max(...(await cards(page).evaluateAll((elements) =>
        elements.map((element) => element.getBoundingClientRect().bottom))))).toBeLessThan(900);
    }

    // Every large logo loads and keeps its proportions inside its stage.
    for (const image of await page.locator(".platform-card-stage img").all()) {
      expect(await image.evaluate((element: HTMLImageElement) => element.complete && element.naturalWidth > 0)).toBe(true);
    }
    // Labels never break inside a word, and destinations stay comfortable to touch.
    for (const row of await page.locator(".platform-destination-row").all()) {
      const box = await row.boundingBox();
      expect(box?.height ?? 0).toBeGreaterThanOrEqual(44);
      expect(await row.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true);
    }
    for (const button of await others(page).getByRole("button").all()) {
      expect((await button.boundingBox())?.height ?? 0).toBeGreaterThanOrEqual(44);
    }
    // The open platform's neighbours keep their compact height.
    const open = await others(page).locator(".other-platform[data-open]").boundingBox();
    const closed = await others(page).locator(".other-platform:not([data-open])").first().boundingBox();
    expect(open?.height ?? 0).toBeGreaterThan(closed?.height ?? 0);
    expect(closed?.height ?? 0).toBeLessThan(60);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath(`plataformas-${width}.png`), fullPage: true });
    expect((await analyzeAccessibility(page)).violations).toEqual([]);

    await page.emulateMedia({ forcedColors: "active" });
    await expect(cards(page).first().getByRole("heading", { level: 2 })).toBeVisible();
    await expect(others(page).getByRole("button", { name: "PlayStation VR2" })).toBeVisible();
  });
}
