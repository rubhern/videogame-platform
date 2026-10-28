import { expect, test, type Page } from "@playwright/test";

import { analyzeAccessibility } from "./fixtures/accessibility";

const igdbHosts = new Set(["api.igdb.com", "images.igdb.com", "id.twitch.tv"]);

function trackProviderRequests(page: Page): string[] {
  const providerRequests: string[] = [];
  page.on("request", (browserRequest) => {
    const url = new URL(browserRequest.url());
    // The product's own attribution mark is same-origin, never a provider request.
    const productAsset = url.pathname.startsWith("/assets/providers/");
    if (igdbHosts.has(url.hostname) || (!productAsset && url.pathname.toLowerCase().includes("igdb"))) {
      providerRequests.push(browserRequest.url());
    }
  });
  return providerRequests;
}

test("the packaged featured releases read local PostgreSQL week by week and month by month", async ({ page }) => {
  const providerRequests = trackProviderRequests(page);

  await page.goto("/lanzamientos/destacados");

  // The browser gate pins the clock to Thursday 13 August 2026, and the deterministic seed holds
  // no qualifying release in its ISO week: Destacados says so instead of failing.
  await expect(
    page.getByRole("heading", { level: 1, name: "Destacados de la semana" }),
  ).toBeVisible();
  await expect(
    page.getByRole("navigation", { name: "Principal" }).getByRole("button", { name: "Lanzamientos" }),
  ).toHaveAttribute("aria-current", "true");
  await expect(
    page.getByRole("heading", { name: "No hay lanzamientos en la semana del 10 al 16 de agosto de 2026" }),
  ).toBeVisible();
  await page.getByRole("link", { name: "Semana siguiente: la semana del 17 al 23 de agosto de 2026" }).click();
  await expect(page).toHaveURL(/\/lanzamientos\/destacados\?week=2026-W34$/);
  await expect(page.getByRole("link", { name: "Semana actual" })).toHaveAttribute("href", "/lanzamientos/destacados");
  expect((await analyzeAccessibility(page)).violations).toEqual([]);

  await page
    .getByRole("navigation", { name: "Periodo de Destacados" })
    .getByRole("link", { name: "Mes" })
    .click();
  await expect(page).toHaveURL(/\/lanzamientos\/destacados\?granularity=month$/);
  await expect(page.getByRole("heading", { level: 1, name: "Destacados del mes" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "No hay lanzamientos en agosto de 2026" })).toBeVisible();

  await page.getByRole("link", { name: "Mes siguiente: septiembre de 2026" }).click();
  await expect(page).toHaveURL(/\/lanzamientos\/destacados\?month=2026-09$/);
  await expect(page.getByRole("article", { name: "Marvel's Wolverine" })).toBeVisible();
  expect((await analyzeAccessibility(page)).violations).toEqual([]);

  await page.getByRole("link", { name: "Mes siguiente: octubre de 2026" }).click();
  const crimson = page.getByRole("article", { name: "Crimson Desert" });
  await expect(crimson).toBeVisible();
  // October holds a single ranked game: no slot is filled artificially.
  await expect(page.getByRole("region", { name: "Más lanzamientos destacados" })).toHaveCount(0);
  // The month-precision date stays in the release metadata; the temporal G only echoes it.
  await expect(
    crimson.getByRole("list", { name: "Lanzamiento" }).getByText("oct 2026", { exact: true }),
  ).toBeVisible();

  await crimson.getByRole("link", { name: "Crimson Desert" }).click();
  await expect(page).toHaveURL(/\/games\/30000000-0000-4000-8000-00000000000a\/crimson-desert$/);
  await page.goBack();
  await expect(page).toHaveURL(/\/lanzamientos\/destacados\?month=2026-10$/);

  // The game page's own way back names Destacados and returns to the month it was opened from.
  await crimson.getByRole("link", { name: "Crimson Desert" }).click();
  await page.getByRole("link", { name: "Volver a Destacados" }).click();
  await expect(page).toHaveURL(/\/lanzamientos\/destacados\?month=2026-10$/);
  await expect(page.getByRole("article", { name: "Crimson Desert" })).toBeVisible();

  await page.getByRole("button", { name: "Lanzamientos", exact: true }).click();
  await page.getByRole("navigation", { name: "Principal" }).getByRole("link", { name: "Explorar", exact: true }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Explorar lanzamientos" })).toBeVisible();
  await expect(page).toHaveURL(/\/lanzamientos$/);
  expect(providerRequests).toEqual([]);
});
