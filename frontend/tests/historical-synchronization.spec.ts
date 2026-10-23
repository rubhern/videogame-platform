import { expect, test, type Page } from "@playwright/test";
import { analyzeAccessibility } from "./fixtures/accessibility";

async function install(page: Page, state = "stopped") {
  await page.route("**/api/v1/session", (route) => route.fulfill({ json: {
    authenticated: true, csrfToken: "csrf", capabilities: ["catalogue_administration"],
  } }));
  await page.route("**/api/v1/admin/catalogue/synchronization", (route) => route.fulfill({ json: { recentRuns: [] } }));
  await page.route("**/api/v1/admin/catalogue/historical-synchronization", (route) => route.fulfill({ json: state === "not_started" ? { state } : {
    state, backfillId: "target-1", from: "2017-03-01", to: "2026-09-30", coveredMonths: "skip",
    currentFrom: "2020-11-01", currentTo: "2020-11-30", totalMonths: 115, completedMonths: 44,
    skippedMonths: 2, remainingMonths: 71, progressPercent: 38, processedGames: 3000,
    processedReleases: 6000, dataQualityFailedGames: 3, startedAt: "2026-10-23T07:00:00Z",
    lastActivityAt: "2026-10-23T08:00:00Z", elapsedSeconds: 3600, lastReason: "HISTORICAL_STOPPED",
  } }));
}
for (const width of [1320, 834, 390, 320]) {
  test(`historical progress and confirmations reflow at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 }); await install(page);
    await page.goto("/administracion/catalogo?tab=historica");
    await expect(page.getByText("44 de 115; 2 omitidos por cobertura")).toBeVisible();
    await page.getByRole("button", { name: "Reanudar histórico" }).click();
    await expect(page.getByRole("button", { name: "Cancelar", exact: true })).toBeFocused();
    expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(0);
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
    await page.keyboard.press("Escape"); await expect(page.getByRole("button", { name: "Reanudar histórico" })).toBeFocused();
    await page.screenshot({ path: `test-results/historical-${width}.png`, fullPage: true });
  });
}
test("historical form and state remain usable with forced colours and keyboard tabs", async ({ page }) => {
  await install(page, "not_started"); await page.emulateMedia({ forcedColors: "active", reducedMotion: "reduce" });
  await page.goto("/administracion/catalogo?tab=historica");
  await expect(page.getByRole("button", { name: "Iniciar histórico" })).toBeVisible();
  const tab = page.getByRole("tab", { name: "Sincronización histórica" });
  await tab.focus(); await page.keyboard.press("Home"); await expect(page.getByRole("tab", { name: "Ejecuciones" })).toBeFocused();
  await page.keyboard.press("End"); await expect(tab).toBeFocused();
  await expect(page.getByLabel("Desde", { exact: true })).toBeVisible();
  await expect(page.getByLabel("Desde", { exact: true })).toBeEnabled();
  await page.screenshot({ path: "test-results/historical-forced-colours.png", fullPage: true });
  // Axe evaluates author colours against forced system backgrounds; check normal-mode
  // contrast separately, retaining the native-control/keyboard assertions above in forced colours.
  await page.emulateMedia({ forcedColors: "none" });
  expect((await analyzeAccessibility(page)).violations).toEqual([]);
});
