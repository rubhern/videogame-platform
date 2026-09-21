import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";

import { pragmata, pragmataRelease, releasePage } from "./fixtures/releases";

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
      ".card-platform, .badge, .filter-label, .filter-chip",
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
          releases: [{ ...pragmataRelease, releaseId: "40000000-0000-4000-8000-000000000007", freshnessStatus: "stale", reviewStatus: "required", releaseDate: { precision: "unknown", value: null } }],
        }],
        page: { number: Number(query.get("page") ?? 1), size: 6, totalItems: 8, totalPages: 2 },
      }) });
    });
    await page.goto("/?pageSize=6");
    await expect(page.getByRole("link", { name: "Pragmata", exact: true })).toBeVisible();
    await expectAccessibleLayout(page);
    await expectComfortableCatalogueMetadata(page);
    await expect(page.locator(".stale-banner")).toHaveCount(0);
    await expect(page.getByText("Datos locales desactualizados")).toHaveCount(0);
    await expect(page.getByText("Información pendiente de revisión")).toBeVisible();
    await expect(page.locator(".catalogue-card .cover-caption")).toHaveCount(0);
    await expect(page.locator(".catalogue-card").getByText(/^Fuente:/)).toHaveCount(0);
    await expect(page.locator(".catalogue-card").getByText("Ver ficha →")).toHaveCount(0);
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
    await page.getByRole("combobox", { name: /^Plataforma:/ }).click();
    await page.getByRole("option", { name: "Windows PC" }).click();
    await expect(page).toHaveURL(/platformId=windows-pc/);
    await expect(page).not.toHaveURL(/page=2/);
    await page.getByRole("link", { name: "Quitar filtros" }).click();
    await expectAccessibleLayout(page);
    if (width < 620) {
      const logo = await page.getByRole("link", { name: "VideoGame Platform · Inicio" }).boundingBox();
      const recent = await page.getByRole("link", { name: "Recientes" }).boundingBox();
      const upcoming = await page.getByRole("link", { name: "Próximos", exact: true }).boundingBox();
      const search = await page.getByRole("button", { name: "Buscar juegos" }).boundingBox();
      expect(logo?.y).toBe(recent?.y);
      expect(recent?.y).toBe(upcoming?.y);
      expect(upcoming?.y).toBe(search?.y);
      await page.getByRole("button", { name: "Buscar juegos" }).click();
      await expect(page.getByRole("dialog", { name: "Buscar juegos" })).toBeVisible();
      await expect(page.getByRole("searchbox", { name: "Buscar en el catálogo" })).toBeFocused();
      await page.keyboard.press("Escape");
      await expect(page.getByRole("dialog", { name: "Buscar juegos" })).not.toBeVisible();
      await expect(page.getByRole("button", { name: "Buscar juegos" })).toBeFocused();
      await page.keyboard.press("/");
      await expect(page.getByRole("searchbox", { name: "Buscar en el catálogo" })).toBeFocused();
    }
    const catalogueSearch = page.getByRole("searchbox", { name: "Buscar en el catálogo" });
    await catalogueSearch.fill("Pragmata");
    await catalogueSearch.press("Enter");
    if (width >= 620) await expect(page.getByRole("searchbox")).toBeVisible();
    await expectAccessibleLayout(page);
  });
}

for (const view of ["recent", "upcoming"] as const) {
test(`${view} desktop defaults to two rows of six games`, async ({ page }) => {
  await page.setViewportSize({ width: 1320, height: 950 });
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.route("**/api/v1/releases?*", async (route) => {
    expect(new URL(route.request().url()).searchParams.get("pageSize")).toBe("12");
    await route.fulfill({ json: releasePage({
      view,
      window: view === "recent"
        ? { from: "2026-02-13", to: "2026-08-13" }
        : { from: "2026-08-13", to: "2027-02-13" },
      items: Array.from({ length: 12 }, (_, index) => ({
        ...pragmata,
        gameId: `30000000-0000-4000-8000-${String(index + 1).padStart(12, "0")}`,
        canonicalTitle: `Juego ${index + 1}`,
        releases: [
          {
            ...pragmataRelease,
            releaseId: `40000000-0000-4000-8000-${String(index + 1).padStart(12, "0")}`,
          },
        ],
      })),
      page: { number: 1, size: 12, totalItems: 12, totalPages: 1 },
    }) });
  });

  await page.goto(view === "recent" ? "/" : "/?view=upcoming");
  await expect(page.locator(".release-grid > li")).toHaveCount(12);
  expect(await page.locator(".release-grid").evaluate((grid) =>
    getComputedStyle(grid).gridTemplateColumns.split(" ").length,
  )).toBe(6);
  const rows = await page.locator(".release-grid > li").evaluateAll((items) =>
    items.map((item) => item.getBoundingClientRect().top),
  );
  expect(new Set(rows).size).toBe(2);
  await expect(page.getByRole("combobox", { name: /^Plataforma:/ })).toBeVisible();
  await expect(page.getByRole("combobox", { name: /^Región:/ })).toBeVisible();
  await expect(page.locator(".release-period")).toBeVisible();
  await expect(page.getByRole("link", { name: view === "recent" ? "Ver próximos" : "Ver recientes" })).toBeVisible();
  await expectAccessibleLayout(page);

  await page.getByRole("combobox", { name: /^Plataforma:/ }).focus();
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/platformId=playstation-5/);
  await expect(page.getByRole("combobox", { name: /^Plataforma:/ })).toContainText("PlayStation 5");
});
}

test("upcoming keeps its compact layout and keyboard filters on a phone", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.route("**/api/v1/releases?*", async (route) => {
    await route.fulfill({ json: releasePage({
      view: "upcoming",
      window: { from: "2026-08-13", to: "2027-02-13" },
      items: [{
        ...pragmata,
        releases: [
          {
            ...pragmataRelease,
            releaseDate: { precision: "day", value: "2026-09-25" },
            status: "announced",
          },
        ],
      }],
    }) });
  });

  await page.goto("/?view=upcoming");
  await expect(page.getByRole("heading", { level: 1, name: "Próximos lanzamientos" })).toBeVisible();
  await expect(page.locator(".release-period-compact")).toHaveText("Del 13/08/2026 al 13/02/2027");
  const kicker = await page.locator(".releases-kicker-row .eyebrow").boundingBox();
  const period = await page.locator(".release-period").boundingBox();
  const platform = await page.getByRole("combobox", { name: /^Plataforma:/ }).boundingBox();
  const region = await page.getByRole("combobox", { name: /^Región:/ }).boundingBox();
  expect(Math.abs((kicker?.y ?? 0) + (kicker?.height ?? 0) / 2
    - (period?.y ?? 0) - (period?.height ?? 0) / 2)).toBeLessThan(2);
  expect(platform?.y).toBe(region?.y);
  await expectAccessibleLayout(page);
  await page.getByRole("combobox", { name: /^Región:/ }).focus();
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/regionId=worldwide/);
  await expect(page.getByRole("combobox", { name: /^Región:/ })).toContainText("Mundial");
});

test("phone search closes cleanly when the layout widens", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.route("**/api/v1/releases?*", (route) => route.fulfill({ json: releasePage() }));
  await page.goto("/");
  await page.getByRole("button", { name: "Buscar juegos" }).click();
  await expect(page.getByRole("dialog", { name: "Buscar juegos" })).toBeVisible();

  await page.setViewportSize({ width: 834, height: 844 });
  await expect(page.getByRole("dialog", { name: "Buscar juegos" })).not.toBeVisible();
  await expect(page.getByRole("searchbox", { name: "Buscar en el catálogo" })).toBeFocused();
});

for (const state of ["loading", "empty", "not-ready", "error"] as const) {
  test(`${state} uses accessible catalogue surfaces and recovery`, async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    let retry = false;
    await page.route("**/api/v1/releases?*", async (route) => {
      if (state === "loading") return; // Kept pending until the page is closed, without timing sleeps.
      if (retry || state === "empty") {
        return route.fulfill({ json: releasePage({ items: [], page: { number: 1, size: 12, totalItems: 0, totalPages: 0 } }) });
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
