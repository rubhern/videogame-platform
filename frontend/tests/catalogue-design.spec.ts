import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";

import { pragmata, releasePage } from "./fixtures/releases";

async function expectAccessibleLayout(page: Page) {
  await page.evaluate(() => document.fonts.ready);
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth),
  ).toBeLessThanOrEqual(0);
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
}

async function expectComfortableCatalogueMetadata(page: Page) {
  const sizes = await page
    .locator(
      ".card-platform, .card-source, .cover-caption, .badge, .filter-label, .filter-chip",
    )
    .evaluateAll((elements) => elements.map((element) => Number.parseFloat(getComputedStyle(element).fontSize)));
  expect(Math.min(...sizes)).toBeGreaterThanOrEqual(11);
}

// Controlled HTTP responses isolate visual/keyboard states; packaged tests own the real API journey.
for (const width of [320, 390, 834, 1320]) {
  test(`catalogue layout, contrast and keyboard navigation at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.route("**/api/v1/releases?*", async (route) => {
      const query = new URL(route.request().url()).searchParams;
      await route.fulfill({ json: releasePage({
        items: [pragmata, {
          ...pragmata,
          slug: "long-title",
          canonicalTitle: "Una aventura extraordinariamente larga: más allá del horizonte",
          release: { ...pragmata.release, releaseId: "40000000-0000-4000-8000-000000000007", freshnessStatus: "stale", reviewStatus: "required", releaseDate: { precision: "unknown", value: null } },
        }],
        page: { number: Number(query.get("page") ?? 1), size: 6, totalItems: 8, totalPages: 2 },
      }) });
    });
    await page.goto("/");
    await expect(page.getByRole("link", { name: "Ver Pragmata" })).toBeVisible();
    await expectAccessibleLayout(page);
    await expectComfortableCatalogueMetadata(page);
    await expect(page.locator(".stale-banner")).toBeVisible();
    expect((await page.locator(".stale-banner").boundingBox())?.height).toBeLessThanOrEqual(56);
    await page.keyboard.press("Tab");
    await expect(page.getByRole("link", { name: "Saltar al contenido" })).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(page.getByRole("main")).toBeFocused();
    const next = page.getByRole("link", { name: "Página siguiente" });
    await next.focus();
    await expect(next).toHaveCSS("outline-style", "solid");
    await page.keyboard.press("Enter");
    await expect(page.getByRole("heading", { name: "Resultados", exact: true })).toBeFocused();
    await expect(page).toHaveURL(/page=2/);
    await expect(page.getByRole("link", { name: "Página siguiente" })).toHaveCount(0);
    await page
      .getByRole("list", { name: "Filtrar por plataforma" })
      .getByRole("link", { name: "Windows PC" })
      .click();
    await expect(page).toHaveURL(/platformId=windows-pc/);
    await expect(page).not.toHaveURL(/page=2/);
    await page.getByRole("link", { name: "Quitar filtros" }).click();
    await expectAccessibleLayout(page);
    await page.getByRole("navigation", { name: "Secciones principales" }).getByRole("link", { name: "Buscar", exact: true }).click();
    await expect(page.getByRole("searchbox")).toBeVisible();
    await expectAccessibleLayout(page);
  });
}

for (const state of ["loading", "empty", "not-ready", "error"] as const) {
  test(`${state} uses accessible catalogue surfaces and recovery`, async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    let retry = false;
    await page.route("**/api/v1/releases?*", async (route) => {
      if (state === "loading") return; // Kept pending until the page is closed, without timing sleeps.
      if (retry || state === "empty") {
        return route.fulfill({ json: releasePage({ items: [], page: { number: 1, size: 6, totalItems: 0, totalPages: 0 } }) });
      }
      return route.fulfill({ status: 503, json: {
        type: "about:blank", title: "Unavailable", status: 503,
        code: state === "not-ready" ? "CATALOGUE_NOT_READY" : "INTERNAL_ERROR",
        category: "dependency", correlationId: "visual-check-reference",
      } });
    });
    await page.goto("/?platformId=windows-pc");
    if (state === "loading") {
      await expect(page.getByRole("status")).toHaveText("Cargando lanzamientos…");
      await expect(page.getByRole("article")).toHaveCount(0);
      await page.emulateMedia({ reducedMotion: "reduce" });
    } else if (state === "empty") {
      await expect(page.getByRole("heading", { name: "Sin lanzamientos para esta selección" })).toBeVisible();
      await expect(page.getByRole("link", { name: "Quitar filtros" })).toHaveCount(2);
    } else if (state === "not-ready") {
      await expect(page.locator(".notice-info")).toHaveAttribute("role", "status");
    } else {
      await expect(page.getByRole("alert")).toBeVisible();
    }
    await expectAccessibleLayout(page);
    if (state === "not-ready" || state === "error") {
      retry = true;
      await page.getByRole("button", { name: "Reintentar" }).click();
      await expect(page.getByRole("heading", { name: "Sin lanzamientos para esta selección" })).toBeVisible();
    }
  });
}
