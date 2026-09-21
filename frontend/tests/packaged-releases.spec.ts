import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";

import type { ReleasePage } from "../src/features/releases/releases-api";

const igdbHosts = new Set(["api.igdb.com", "igdb.com", "images.igdb.com", "www.igdb.com"]);

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

function releaseTitles(page: Page) {
  // Grid cards are one game each; scope to the grid so nested lists never leak into the titles.
  return page.locator(".release-grid").getByRole("heading", { level: 3 });
}

function releaseCard(page: Page, title: string) {
  return page
    .locator(".release-grid > li")
    .filter({ has: page.getByRole("heading", { level: 3, name: title }) });
}

async function expectNoAccessibilityViolations(page: Page) {
  const results = await new AxeBuilder({ page }).analyze();
  expect(results.violations).toEqual([]);
}

async function horizontalOverflow(page: Page) {
  return page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
}

test("the packaged release discovery journey reads PostgreSQL through the same-origin API", async ({
  page,
  request,
}) => {
  const providerRequests = trackProviderRequests(page);

  const releasesResponsePromise = page.waitForResponse((response) => {
    const url = new URL(response.url());
    return url.pathname === "/api/v1/releases" && url.searchParams.get("view") === "recent";
  });

  await page.goto("/?pageSize=6");
  const releasesResponse = await releasesResponsePromise;
  expect(releasesResponse.status()).toBe(200);
  expect(releasesResponse.request().resourceType()).toBe("fetch");
  expect(releasesResponse.url()).toContain("page=1&pageSize=6");

  const releasePage = (await releasesResponse.json()) as ReleasePage;
  expect(releasePage.evaluatedOn).toBe("2026-08-13");
  expect(releasePage.window).toEqual({ from: "2026-08-07", to: "2026-08-13" });
  // The fixed local seed has no matching recent game in the default week.
  expect(releasePage.page).toEqual({ number: 1, size: 6, totalItems: 0, totalPages: 0 });
  expect(releasePage.availableFilters.platforms.map((platform) => platform.name)).toEqual([
    "Nintendo Switch 2",
    "PlayStation 5",
    "Windows PC",
    "Xbox Series X|S",
  ]);
  expect(releasePage.availableFilters.regions.map((region) => region.name)).toEqual([
    "Europe",
    "Japan",
    "North America",
    "Unknown",
    "Worldwide",
  ]);

  await expect(page.getByRole("region", { name: "Lanzamientos recientes" })).toBeVisible();
  await expect(page.locator(".release-period")).toContainText(
    "Del 7 de agosto de 2026 al 13 de agosto de 2026",
  );
  await expect(page.getByRole("combobox", { name: /^Periodo:/ })).toContainText("1 semana");
  await expect(page.locator(".result-count")).toHaveText("0 juegos");
  await expect(page.getByText("Sin lanzamientos para esta selección")).toBeVisible();
  await expectNoAccessibilityViolations(page);

  await test.step("the week selector keeps the range visible and resets pagination", async () => {
    await page.goto("/?view=upcoming&page=2&pageSize=6");
    const selector = page.getByRole("combobox", { name: /^Periodo:/ });
    await selector.focus();
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("End");
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(/view=upcoming&weeks=4&pageSize=6/);
    await expect(page).not.toHaveURL(/page=2/);
    await expect(page.locator(".release-period")).toContainText(
      "Del 13 de agosto de 2026 al 10 de septiembre de 2026",
    );
    await expect(selector).toContainText("4 semanas");
    await expectNoAccessibilityViolations(page);
  });

  await test.step("TBA remains explicit and is grouped as one game", async () => {
    await expect(page.locator(".result-count")).toHaveText("1 juego · Página 1 de 1");
    await expect(releaseTitles(page)).toHaveText(["The Witcher IV"]);
    const witcher = releaseCard(page, "The Witcher IV");
    await expect(witcher.getByText("Fecha por confirmar")).toBeVisible();
    await expect(witcher.getByText("Windows PC · Sin región confirmada")).toBeVisible();
  });

  await test.step("an out-of-range shared page recovers to the only game", async () => {
    await page.goto("/?view=upcoming&weeks=1&page=99&pageSize=1");
    await expect(page.locator(".result-count")).toHaveText(
      "1 juego · La página 99 ya no está disponible",
    );
    await page.getByRole("link", { name: "Ir a la última página" }).click();
    await expect(page).toHaveURL(/view=upcoming&weeks=1&pageSize=1/);
    await expect(releaseTitles(page)).toHaveText(["The Witcher IV"]);
    await expect(page.getByRole("heading", { level: 2, name: "Resultados" })).toBeFocused();
  });

  await test.step("the keyboard reaches a game from the focused results", async () => {
    await page.keyboard.press("Tab");
    const gameLink = page.locator(".card-title a", { hasText: "The Witcher IV" }).first();
    await expect(gameLink).toBeFocused();
    await gameLink.press("Enter");
    await expect(page).toHaveURL(/\/games\/30000000-0000-4000-8000-000000000008\/the-witcher-iv$/);
    await expect(page.getByRole("heading", { level: 1, name: "The Witcher IV" })).toBeVisible();
    await expect(page.getByRole("button", { name: "8", exact: true })).toBeDisabled();
    await expect(page.getByText(/Todavía no se puede puntuar/)).toBeVisible();
    await expect(page.getByRole("main")).toBeFocused();
  });

  for (const serverOwnedPath of ["/api", "/auth", "/actuator"]) {
    const response = await request.get(serverOwnedPath);
    expect(response.headers()["content-type"] ?? "").not.toContain("text/html");
    expect(await response.text()).not.toContain('<div id="root"></div>');
  }

  expect(providerRequests).toEqual([]);
});

test("the packaged releases page stays usable from phone to desktop", async ({ page }) => {
  const viewports = [
    { name: "phone", width: 320, height: 720 },
    { name: "tablet", width: 768, height: 1024 },
    { name: "desktop", width: 1280, height: 900 },
  ];

  for (const viewport of viewports) {
    await test.step(`${viewport.name} (${viewport.width}px)`, async () => {
      await page.setViewportSize({ width: viewport.width, height: viewport.height });
      await page.goto("/?pageSize=6");

      await expect(
        page.getByRole("heading", { level: 1, name: "Lanzamientos recientes" }),
      ).toBeVisible();
      await expect(releaseTitles(page)).toHaveCount(0);
      await expect(page.getByRole("combobox", { name: /^Periodo:/ })).toBeVisible();
      await expect(page.getByRole("combobox", { name: /^Plataforma:/ })).toBeVisible();
      expect(await horizontalOverflow(page)).toBeLessThanOrEqual(0);
      await expectNoAccessibilityViolations(page);
    });
  }
});
