import { expect, test } from "@playwright/test";

import { analyzeAccessibility } from "./fixtures/accessibility";
import { featuredReleases, featuredWeekReleases, releasePage } from "./fixtures/releases";

test.use({ hasTouch: true });

test.beforeEach(async ({ page }) => {
  await page.route("**/api/v1/session", route => route.fulfill({ json: { authenticated: false } }));
  await page.route("**/api/v1/featured-releases?*", route => {
    const params = new URL(route.request().url()).searchParams;
    return route.fulfill({ json: params.has("month") || params.get("granularity") === "month"
      ? featuredReleases({ items: [] }) : featuredWeekReleases({ items: [] }) });
  });
  await page.route("**/api/v1/releases?*", route => route.fulfill({ json: releasePage() }));
  await page.route("**/api/v1/release-calendar**", route => route.fulfill({ status: 503, json: { code: "CATALOGUE_NOT_READY" } }));
});

for (const width of [320, 390, 834, 1320]) {
  test(`release disclosure supports touch, keyboard, active routes and history at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto("/?month=2026-08");
    await expect(page).toHaveURL(/\/lanzamientos\/destacados\?month=2026-08$/);
    await expect(page).toHaveTitle("Destacados del mes · Gameómetro");
    const nav = page.getByRole("navigation", { name: "Principal" });
    const trigger = nav.getByRole("button", { name: "Lanzamientos" });
    await expect(nav.getByRole("link", { name: "TOPómetro" })).toHaveCount(0);
    await trigger.tap();
    await expect(trigger).toHaveAttribute("aria-expanded", "true");
    await expect(nav.getByRole("link")).toHaveText(["Destacados", "Explorar", "Calendario"]);
    await expect(nav.getByRole("link", { name: "Destacados", exact: true })).toHaveAttribute("aria-current", "page");
    const panel = page.locator(".releases-panel");
    const box = await panel.boundingBox();
    if (!box) throw new Error("The releases panel is not rendered");
    expect(box.x).toBeGreaterThanOrEqual(0);
    expect(box.x + box.width).toBeLessThanOrEqual(width);
    expect(await trigger.evaluate(element => element.getBoundingClientRect().height)).toBeGreaterThanOrEqual(44);
    expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
    await page.screenshot({ path: testInfo.outputPath(`releases-menu-${width}.png`) });

    await trigger.focus();
    await page.keyboard.press("Tab");
    await expect(nav.getByRole("link", { name: "Destacados", exact: true })).toBeFocused();
    await page.keyboard.press("Escape");
    await expect(trigger).toBeFocused();
    await expect(trigger).toHaveAttribute("aria-expanded", "false");
    await page.keyboard.press("Space");
    await page.keyboard.press("Tab");
    await page.keyboard.press("Tab");
    await expect(nav.getByRole("link", { name: "Explorar", exact: true })).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(/\/lanzamientos$/);
    await expect(trigger).toHaveAttribute("aria-expanded", "false");
    await expect(page.getByRole("main")).toBeFocused();
    await trigger.click();
    await expect(nav.getByRole("link", { name: "Explorar", exact: true })).toHaveAttribute("aria-current", "page");
    await page.goBack();
    await expect(page).toHaveURL(/\/lanzamientos\/destacados\?month=2026-08$/);
    await expect(trigger).toHaveAttribute("aria-expanded", "false");
    await page.goForward();
    await expect(page).toHaveURL(/\/lanzamientos$/);
    await trigger.tap();
    await nav.getByRole("link", { name: "Calendario", exact: true }).tap();
    await expect(page).toHaveURL(/\/lanzamientos\/calendario$/);
    await trigger.click();
    await expect(nav.getByRole("link", { name: "Calendario", exact: true })).toHaveAttribute("aria-current", "page");
    await page.getByRole("contentinfo").click();
    await expect(trigger).toHaveAttribute("aria-expanded", "false");
    await trigger.click();
    await trigger.focus();
    for (let i = 0; i < 4; i++) await page.keyboard.press("Tab");
    await expect(trigger).toHaveAttribute("aria-expanded", "false");
  });
}

test("direct canonical links reload and forced colours keep current navigation and focus usable", async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.goto("/lanzamientos/destacados?week=2026-W33");
  await page.reload();
  await expect(page.getByRole("heading", { name: "Destacados de la semana" })).toBeVisible();
  const trigger = page.getByRole("button", { name: "Lanzamientos", exact: true });
  await trigger.focus();
  await page.keyboard.press("Enter");
  await page.keyboard.press("Tab");
  const current = page.getByRole("navigation", { name: "Principal" }).getByRole("link", { name: "Destacados", exact: true });
  await expect(current).toBeFocused();
  await expect(current).toHaveAttribute("aria-current", "page");
  expect((await analyzeAccessibility(page)).violations).toEqual([]);
  await page.emulateMedia({ forcedColors: "active", reducedMotion: "reduce" });
  await expect(current).toBeFocused();
  await expect(current).toHaveCSS("outline-style", "solid");
});

for (const forcedColors of ["none", "active"] as const) {
  test(`route and skip-link focus never draw a page outline with forced colours ${forcedColors}`, async ({ page }) => {
    await page.emulateMedia({ forcedColors, reducedMotion: "reduce" });
    await page.goto("/");
    await expect(page).toHaveURL(/\/lanzamientos\/destacados$/);
    const main = page.getByRole("main");
    await expect(main).toBeFocused();
    await expect(main).toHaveCSS("outline-style", "none");
    await expect(main).toHaveCSS("box-shadow", "none");
    await page.screenshot({ path: test.info().outputPath(`main-focus-${forcedColors}.png`) });

    const skip = page.getByRole("link", { name: "Saltar al contenido" });
    await skip.focus();
    await expect(skip).toHaveCSS("outline-style", "solid");
    await page.keyboard.press("Enter");
    await expect(main).toBeFocused();
    await expect(main).toHaveCSS("outline-style", "none");
    await page.keyboard.press("Tab");
    const week = page.getByRole("navigation", { name: "Periodo de Destacados" }).getByRole("link", { name: "Semana" });
    await expect(week).toBeFocused();
    await expect(week).toHaveCSS("outline-style", "solid");

    const releases = page.getByRole("button", { name: "Lanzamientos", exact: true });
    await releases.focus();
    await page.keyboard.press("Enter");
    await page.keyboard.press("Tab");
    await page.keyboard.press("Tab");
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(/\/lanzamientos$/);
    await expect(main).toBeFocused();
    await expect(main).toHaveCSS("outline-style", "none");
  });
}

for (const path of ["/", "/lanzamientos/destacados", "/lanzamientos/destacados?granularity=month"]) {
  test(`cold navigation keeps the featured loading surface while its read is pending: ${path}`, async ({ page }) => {
    await page.route("**/api/v1/featured-releases*", () => undefined);
    await page.goto(path);
    await expect(page.getByRole("status")).toHaveText("Cargando Destacados…");
    await expect(page.getByRole("heading", {
      level: 1, name: path.includes("month") ? "Destacados del mes" : "Destacados de la semana",
    })).toBeVisible();
    await expect(page.getByRole("article")).toHaveCount(0);
  });
}
