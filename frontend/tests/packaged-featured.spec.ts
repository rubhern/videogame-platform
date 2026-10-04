import { expect, test, type Page } from "@playwright/test";

import { analyzeAccessibility } from "./fixtures/accessibility";

const igdbHosts = new Set(["api.igdb.com", "images.igdb.com", "id.twitch.tv"]);

function trackProviderRequests(page: Page): string[] {
  const providerRequests: string[] = [];
  page.on("request", (browserRequest) => {
    const url = new URL(browserRequest.url());
    if (igdbHosts.has(url.hostname) || url.pathname.toLowerCase().includes("igdb")) {
      providerRequests.push(browserRequest.url());
    }
  });
  return providerRequests;
}

test("the packaged featured releases read local PostgreSQL month by month", async ({ page }) => {
  const providerRequests = trackProviderRequests(page);

  await page.goto("/");

  // The browser gate pins the clock to 13 August 2026, and the deterministic seed holds no
  // qualifying release that month: the landing route says so instead of failing.
  await expect(page.getByRole("heading", { level: 1, name: "Lanzamientos del mes" })).toBeVisible();
  await expect(
    page.getByRole("navigation", { name: "Lanzamientos" }).getByRole("link", { name: "Destacados" }),
  ).toHaveAttribute("aria-current", "page");
  await expect(page.getByRole("heading", { name: "No hay lanzamientos en agosto de 2026" })).toBeVisible();

  await page.getByRole("link", { name: "Mes siguiente: septiembre de 2026" }).click();
  await expect(page).toHaveURL(/\/\?month=2026-09$/);
  await expect(page.getByRole("article", { name: "Marvel's Wolverine" })).toBeVisible();
  expect((await analyzeAccessibility(page)).violations).toEqual([]);

  await page.getByRole("link", { name: "Mes siguiente: octubre de 2026" }).click();
  const crimson = page.getByRole("article", { name: "Crimson Desert" });
  await expect(crimson).toBeVisible();
  // October holds a single ranked game: no slot is filled artificially.
  await expect(page.getByRole("region", { name: "Otros lanzamientos destacados" })).toHaveCount(0);
  await expect(crimson.getByText("oct 2026", { exact: true })).toBeVisible();

  await crimson.getByRole("link", { name: "Ver ficha de Crimson Desert" }).click();
  await expect(page).toHaveURL(/\/games\/30000000-0000-4000-8000-00000000000a\/crimson-desert$/);
  await page.goBack();
  await expect(page).toHaveURL(/\/\?month=2026-10$/);

  await page.getByRole("navigation", { name: "Lanzamientos" }).getByRole("link", { name: "Recientes" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Lanzamientos recientes" })).toBeVisible();
  expect(providerRequests).toEqual([]);
});
