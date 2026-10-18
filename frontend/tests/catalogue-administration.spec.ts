import { expect, test, type Page } from "@playwright/test";

import { analyzeAccessibility } from "./fixtures/accessibility";

/**
 * Catalogue administration against a scripted same-origin BFF: reflow, keyboard drill-down and
 * automated accessibility. The real Keycloak authority journey lives in
 * `catalogue-administration-identity.spec.ts`; the server's authorization is proven there and in
 * the backend integration tests, never here.
 */

const counters = {
  createdGames: 3, updatedGames: 41, unchangedGames: 512, deferredGames: 0, failedGames: 2,
  createdReleases: 7, updatedReleases: 55, unchangedReleases: 1290, deletedReleases: 1,
  providerRequests: 38, providerRetries: 1,
};

async function install(page: Page) {
  await page.route("**/api/v1/session", (route) =>
    route.fulfill({ json: { authenticated: true, csrfToken: "browser-csrf", capabilities: ["catalogue_administration"] } }),
  );
  await page.route("**/api/v1/admin/catalogue/synchronization", (route) => route.fulfill({
    headers: { "Cache-Control": "no-store" },
    json: {
      currentRun: { runId: "run-now", from: "2026-09-20", to: "2026-11-15", startedAt: "2026-10-18T04:00:00Z",
        lastHeartbeatAt: "2026-10-18T04:03:00Z", stalled: true },
      recentRuns: [
        { runId: "run-1", from: "1997-12-01", to: "1997-12-31", startedAt: "2026-10-17T04:00:00Z",
          completedAt: "2026-10-17T04:06:41Z", result: "partial", outcomeCode: "SYNCHRONIZATION_COMPLETED_WITH_FAILURES", counters },
        { runId: "run-2", from: "1998-01-01", to: "1998-01-31", startedAt: "2026-10-16T04:00:00Z",
          completedAt: "2026-10-16T04:45:00Z", result: "abandoned", outcomeCode: "SYNCHRONIZATION_ABANDONED" },
        { runId: "run-3", from: "1996-01-01", to: "1996-12-31", startedAt: "2026-10-15T04:00:00Z",
          completedAt: "2026-10-15T05:00:00Z", result: "failed", outcomeCode: "SYNCHRONIZATION_FAILED", counters },
      ],
    },
  }));
  await page.route(/\/api\/v1\/admin\/catalogue\/synchronization-coverage\?/, (route) => {
    const url = new URL(route.request().url());
    const from = url.searchParams.get("from") ?? "";
    const to = url.searchParams.get("to") ?? "";
    const scan = (completed: string, failedGames: number) => ({ runId: "run-1", scanStartedAt: completed, scanCompletedAt: completed, failedGames });
    return route.fulfill({
      headers: { "Cache-Control": "no-store" },
      json: { from, to, periods: [
        { from, to: "1997-11-30", status: "never_covered" },
        { from: "1997-12-01", to: "1997-12-15", status: "covered", lastScan: scan(new Date().toISOString(), 2) },
        { from: "1997-12-16", to: "1997-12-31", status: "covered", lastScan: scan("2025-01-10T04:00:00Z", 0) },
        { from: "1998-01-01", to, status: "never_covered" },
      ] },
    });
  });
}

for (const width of [1320, 834, 390, 320]) {
  test(`catalogue administration reflows without page overflow at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await install(page);
    await page.goto("/administracion/catalogo");
    await expect(page.getByRole("heading", { name: /^Completa con juegos fallidos/ })).toBeVisible();
    const runsOverflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(runsOverflow).toBeLessThanOrEqual(0);

    await page.goto("/administracion/catalogo?tab=cobertura&year=1997&month=12");
    await expect(page.getByRole("heading", { name: "Periodos de diciembre de 1997" })).toBeVisible();
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(0);
  });
}

test("catalogue administration states its readings in words and passes automated accessibility", async ({ page }) => {
  await install(page);
  await page.goto("/administracion/catalogo");

  await expect(page.getByText("En curso, sin señales recientes")).toBeVisible();
  await expect(page.getByRole("heading", { name: /^Completa con juegos fallidos/ })).toBeVisible();
  await expect(page.getByRole("heading", { name: /^Abandonada/ })).toBeVisible();
  await expect(page.getByRole("heading", { name: /^Fallida/ })).toBeVisible();
  expect((await analyzeAccessibility(page)).violations).toEqual([]);

  await page.getByRole("tab", { name: "Cobertura" }).click();
  await expect(page).toHaveURL(/\?tab=cobertura$/);
  await page.goto("/administracion/catalogo?tab=cobertura&year=1997");
  const months = page.getByRole("table", { name: "Cobertura por mes de 1997" });
  await expect(months.getByRole("row", { name: /^Diciembre/ })).toContainText("Cubierto");
  await expect(months.getByRole("row", { name: /^Noviembre/ })).toContainText("Nunca cubierto");

  const results = await analyzeAccessibility(page);
  expect(results.violations).toEqual([]);
});

test("the coverage calendar drills down from a year to a month by keyboard", async ({ page }) => {
  await install(page);
  await page.goto("/administracion/catalogo?tab=cobertura&year=1997");
  const december = page.getByRole("table", { name: "Cobertura por mes de 1997" }).getByRole("link", { name: "Diciembre" });
  await december.focus();
  await page.keyboard.press("Enter");

  await expect(page).toHaveURL(/\?tab=cobertura&year=1997&month=12$/);
  await expect(page.getByRole("heading", { name: "Periodos de diciembre de 1997" })).toBeFocused();
  await expect(page.getByRole("region", { name: "Periodos de diciembre de 1997" }).getByRole("listitem")).toHaveCount(2);
});
