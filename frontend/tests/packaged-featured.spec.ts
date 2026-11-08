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

  const wolverine = page.getByRole("article", { name: "Marvel's Wolverine" });
  // September holds a single ranked game: no slot is filled artificially.
  await expect(page.getByRole("region", { name: "Más lanzamientos destacados" })).toHaveCount(0);
  await expect(
    wolverine.getByRole("list", { name: "Lanzamiento" }).getByText("25 sep 2026", { exact: true }),
  ).toBeVisible();

  await wolverine.getByRole("link", { name: "Marvel's Wolverine" }).click();
  await expect(page).toHaveURL(/\/games\/30000000-0000-4000-8000-00000000000c\/[a-z0-9-]+$/);
  await page.goBack();
  await expect(page).toHaveURL(/\/lanzamientos\/destacados\?month=2026-09$/);

  // The game page's own way back names Destacados and returns to the month it was opened from.
  await wolverine.getByRole("link", { name: "Marvel's Wolverine" }).click();
  await page.getByRole("link", { name: "Volver a Destacados" }).click();
  await expect(page).toHaveURL(/\/lanzamientos\/destacados\?month=2026-09$/);
  await expect(page.getByRole("article", { name: "Marvel's Wolverine" })).toBeVisible();

  // October is known only to the month and has not ended: as in the explorer's default, nothing
  // is claimed for it, and the empty state says so rather than filling the page.
  await page.getByRole("link", { name: "Mes siguiente: octubre de 2026" }).click();
  await expect(page.getByRole("heading", { name: "No hay lanzamientos en octubre de 2026" })).toBeVisible();
  await expect(page.getByText("con fecha confirmada dentro de este mes")).toBeVisible();

  await page.getByRole("button", { name: "Lanzamientos", exact: true }).click();
  await page.getByRole("navigation", { name: "Principal" }).getByRole("link", { name: "Explorar", exact: true }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Explorar lanzamientos" })).toBeVisible();
  await expect(page).toHaveURL(/\/lanzamientos$/);
  expect(providerRequests).toEqual([]);
});
