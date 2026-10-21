import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";

import { analyzeAccessibility } from "./fixtures/accessibility";

/**
 * Browser evidence for the release calendar (#250) against the packaged application, its seeded
 * PostgreSQL catalogue and the pinned clock (Thursday 13 August 2026 in Madrid).
 */

async function horizontalOverflow(page: Page) {
  return page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
}

test("the highlights calendar includes more games and colours by summed Hype", async ({ page }) => {
  const calendarResponse = page.waitForResponse(
    (response) => new URL(response.url()).pathname === "/api/v1/release-calendar",
  );
  await page.goto("/lanzamientos/calendario?month=2026-09");
  expect((await calendarResponse).status()).toBe(200);

  await expect(page.getByRole("heading", { level: 1, name: "Calendario de destacados" })).toBeVisible();
  const grid = page.getByRole("table", { name: "Lanzamientos de Septiembre 2026" });
  await expect(grid).toBeVisible();
  // A single highlighted game with Hype 61 is yellow: colour no longer reflects its count.
  const friday = grid.getByRole("button", { name: /viernes, 25 de septiembre de 2026: 1 lanzamiento/ });
  await expect(friday).toBeVisible();
  await expect(friday).toHaveAttribute("data-density", "2");
  await expect(friday).toHaveAccessibleDescription("Interés moderado");
  await expect(page.getByRole("heading", { level: 2, name: "Viernes, 25 de septiembre" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Ver todos los lanzamientos del día" })).toBeVisible();
  const calendarBody = await (await page.request.get("/api/v1/release-calendar?month=2026-09")).json();
  expect(calendarBody.days).toEqual([{ date: "2026-09-25", gameCount: 1, totalHype: 61 }]);
  await friday.focus();
  await page.keyboard.press("ArrowLeft");
  await page.keyboard.press("ArrowUp");
  await expect(grid.getByRole("button", { name: /jueves, 17 de septiembre/ })).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/month=2026-09&day=2026-09-17/);

  await page.goto("/lanzamientos/calendario?month=2026-09&day=2026-09-25");
  const fullDayRequest = page.waitForRequest((request) => new URL(request.url()).pathname === "/api/v1/releases");
  await page.getByRole("link", { name: "Ver todos los lanzamientos del día" }).click();
  await expect(page).toHaveURL(/from=2026-09-25&to=2026-09-25&sort=hype$/);
  expect(new URL((await fullDayRequest).url()).searchParams.has("featuredOnly")).toBe(false);
  await expect(page.locator(".releases-footer .result-count")).toHaveText(/^1 juego/);
  await expect(page.getByText("Solo destacados")).toHaveCount(0);
});

test("the calendar never goes back before the current month", async ({ page }) => {
  await page.goto("/lanzamientos/calendario?month=2026-07");
  await expect(page).toHaveURL(/\/lanzamientos\/calendario$/);
  await expect(page.locator(".calendar-period")).toHaveText("Agosto 2026");
  await expect(
    page.getByRole("navigation", { name: "Mes del calendario" }).getByRole("link", { name: "Mes anterior" }),
  ).toHaveAttribute("aria-disabled", "true");
});

for (const viewport of [
  { width: 1320, height: 900 },
  { width: 834, height: 1112 },
  { width: 390, height: 844 },
  { width: 320, height: 720 },
]) {
  test(`the calendar reflows without overflow and passes axe at ${viewport.width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize(viewport);
    await page.goto("/lanzamientos/calendario?month=2026-09&day=2026-09-25");
    await expect(page.getByRole("heading", { level: 2, name: "Viernes, 25 de septiembre" })).toBeVisible();

    expect(await horizontalOverflow(page)).toBe(0);
    const day = page.getByRole("button", { name: /viernes, 25 de septiembre/ });
    const box = await day.boundingBox();
    expect(box?.height ?? 0).toBeGreaterThanOrEqual(44);
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
    await page.screenshot({ path: testInfo.outputPath(`release-calendar-${viewport.width}.png`), fullPage: true });
  });
}

test("forced colours keep the chosen day and its count without colour", async ({ page }) => {
  await page.emulateMedia({ forcedColors: "active" });
  await page.goto("/lanzamientos/calendario?month=2026-09&day=2026-09-25");
  const chosen = page.getByRole("button", { name: /viernes, 25 de septiembre/ });
  await expect(chosen).toHaveAttribute("aria-pressed", "true");
  await expect(chosen).toHaveCSS("outline-style", "solid");
  // axe reads authored palette values in forced colours; keep every structural rule.
  expect((await new AxeBuilder({ page }).disableRules(["color-contrast"]).analyze()).violations).toEqual([]);
});
