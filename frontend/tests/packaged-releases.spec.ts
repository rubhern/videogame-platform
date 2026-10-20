import { expect, test, type Page } from "@playwright/test";

import { analyzeAccessibility } from "./fixtures/accessibility";
import { openReleaseControls } from "./fixtures/releases";
import type { ReleasePage } from "../src/features/releases/releases-api";

const igdbHosts = new Set(["api.igdb.com", "igdb.com", "images.igdb.com", "www.igdb.com"]);

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
  const results = await analyzeAccessibility(page);
  expect(results.violations).toEqual([]);
}

async function horizontalOverflow(page: Page) {
  return page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
}

test("the packaged release explorer reads PostgreSQL through the same-origin API", async ({
  page,
  request,
}) => {
  const providerRequests = trackProviderRequests(page);

  const releasesResponsePromise = page.waitForResponse((response) => {
    const url = new URL(response.url());
    return url.pathname === "/api/v1/releases";
  });

  await page.goto("/lanzamientos?pageSize=6");
  const releasesResponse = await releasesResponsePromise;
  expect(releasesResponse.status()).toBe(200);
  expect(releasesResponse.request().resourceType()).toBe("fetch");
  // Only the API knows today, so the current week is requested without a range.
  expect(new URL(releasesResponse.url()).search).toBe("?page=1&pageSize=6");

  const releasePage = (await releasesResponse.json()) as ReleasePage;
  expect(releasePage.evaluatedOn).toBe("2026-08-13");
  // 13 August 2026 is a Thursday: the default range is its Monday-to-Sunday week.
  expect(releasePage.window).toEqual({ from: "2026-08-10", to: "2026-08-16" });
  // The fixed local seed has no release in that week.
  expect(releasePage.page).toEqual({ number: 1, size: 6, totalItems: 0, totalPages: 0 });
  expect(releasePage.availableFilters.platforms).toEqual([]);
  expect(releasePage.availableFilters.regions).toEqual([]);

  await expect(page.getByRole("region", { name: "Explorar lanzamientos" })).toBeVisible();
  await expect(page.locator(".release-period")).toContainText(
    "Del 10 de agosto de 2026 al 16 de agosto de 2026",
  );
  await expect(page.getByLabel("Desde")).toHaveValue("2026-08-10");
  await expect(page.getByLabel("Hasta")).toHaveValue("2026-08-16");
  await expect(page.locator(".result-count")).toHaveText("0 juegos");
  await expect(page.getByText("Sin lanzamientos para esta selección")).toBeVisible();
  await expectNoAccessibilityViolations(page);

  await test.step("a typed past range lists what happened in it, soonest first", async () => {
    await page.getByLabel("Desde").fill("2026-02-01");
    await page.getByLabel("Hasta").fill("2026-05-01");
    await page.getByRole("button", { name: "Aplicar" }).click();
    await expect(page).toHaveURL(/\/lanzamientos\?from=2026-02-01&to=2026-05-01&pageSize=6$/);
    await expect(page.locator(".result-count")).toHaveText("5 juegos · Página 1 de 1");
    // Partial periods that have ended belong to the range without the opt-in, which a range
    // wholly in the past never offers.
    await expect(releaseTitles(page)).toHaveText([
      "Resident Evil Requiem",
      "Subnautica 2",
      "Metroid Prime 4: Beyond",
      "Crimson Desert",
      "Pragmata",
    ]);
    await expect(page.getByRole("checkbox", { name: "Incluir fechas aproximadas" })).toHaveCount(0);
    await expectNoAccessibilityViolations(page);
  });

  await test.step("Hype reorders the range before pagination, missing Hypes last", async () => {
    const order = page.getByRole("combobox", { name: /Ordenar por/ });
    await order.click();
    await page.getByRole("option", { name: "Hype" }).click();
    await expect(page).toHaveURL(/sort=hype/);
    await expect(releaseTitles(page)).toHaveText([
      "Crimson Desert",
      "Resident Evil Requiem",
      "Subnautica 2",
      "Metroid Prime 4: Beyond",
      "Pragmata",
    ]);
  });

  await test.step("a future range shows exact days unless approximate dates are included", async () => {
    await page.goto("/lanzamientos?from=2026-10-01&to=2026-12-31&pageSize=6");
    await expect(page.locator(".result-count")).toHaveText("0 juegos");
    const optIn = page.getByRole("checkbox", { name: "Incluir fechas aproximadas" });
    await expect(optIn).not.toBeChecked();
    await optIn.focus();
    await page.keyboard.press("Space");
    await expect(page).toHaveURL(/includeApproximateDates=true/);
    await expect(optIn).toBeChecked();
    await expect(releaseTitles(page)).toHaveText(["Crimson Desert", "Subnautica 2"]);
    // Each keeps its real precision; no day is invented, and TBA never joins a range.
    await expect(releaseCard(page, "Crimson Desert").getByText("octubre de 2026").first()).toBeAttached();
    await expect(releaseCard(page, "Subnautica 2").getByText("4.º trimestre de 2026")).toBeAttached();
    await expect(page.getByText("Fecha por confirmar")).toHaveCount(0);
    await expectNoAccessibilityViolations(page);
  });

  await test.step("a day picked on the bar keeps its week around it", async () => {
    await page.goto("/lanzamientos?from=2026-02-23&to=2026-03-01&pageSize=6");
    const days = page.getByRole("toolbar", { name: "Elegir un día del periodo" });
    await days.getByRole("button", { name: /^viernes, 27 de febrero de 2026/ }).click();
    await expect(page).toHaveURL(/from=2026-02-27&to=2026-02-27/);
    // The exact day comes first; a first quarter that has ended overlaps the day too, keeping
    // its own precision rather than an invented day.
    await expect(releaseTitles(page)).toHaveText(["Resident Evil Requiem", "Subnautica 2"]);
    await expect(releaseCard(page, "Subnautica 2").locator(".card-date")).toHaveText("T1 2026");
    await expect(days.getByRole("button")).toHaveCount(7);
    await expect(days.getByRole("button", { name: /^viernes, 27 de febrero de 2026/ })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    await page.getByRole("button", { name: /Ver todo el periodo/ }).click();
    await expect(page).toHaveURL(/from=2026-02-23&to=2026-03-01/);
  });

  await test.step("an out-of-range shared page recovers to the last page", async () => {
    await page.goto("/lanzamientos?from=2026-02-01&to=2026-05-01&page=99&pageSize=1");
    await expect(page.locator(".result-count")).toHaveText(
      "5 juegos · La página 99 ya no está disponible",
    );
    await page.getByRole("link", { name: "Ir a la última página" }).click();
    await expect(page).toHaveURL(/from=2026-02-01&to=2026-05-01&page=5&pageSize=1/);
    await expect(releaseTitles(page)).toHaveText(["Pragmata"]);
    await expect(page.getByRole("heading", { level: 2, name: "Resultados" })).toBeFocused();
  });

  await test.step("the keyboard reaches a game and its way back returns to the same page", async () => {
    await page.keyboard.press("Tab");
    const gameLink = page.locator(".card-title a", { hasText: "Pragmata" }).first();
    await expect(gameLink).toBeFocused();
    await gameLink.press("Enter");
    await expect(page).toHaveURL(/\/games\/30000000-0000-4000-8000-000000000006\/pragmata$/);
    await expect(page.getByRole("heading", { level: 1, name: "Pragmata" })).toBeVisible();
    await expect(page.getByRole("main")).toBeFocused();
    const back = page.getByRole("link", { name: "Volver a lanzamientos" }).last();
    await back.focus();
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(/\/lanzamientos\?from=2026-02-01&to=2026-05-01&page=5&pageSize=1$/);
    await expect(releaseTitles(page)).toHaveText(["Pragmata"]);
  });

  for (const serverOwnedPath of ["/api", "/auth", "/actuator"]) {
    const response = await request.get(serverOwnedPath);
    expect(response.headers()["content-type"] ?? "").not.toContain("text/html");
    expect(await response.text()).not.toContain('<div id="root"></div>');
  }

  expect(providerRequests).toEqual([]);
});

test("the packaged release explorer stays usable from phone to desktop", async ({ page }) => {
  const viewports = [
    { name: "phone", width: 320, height: 720 },
    { name: "tablet", width: 768, height: 1024 },
    { name: "desktop", width: 1280, height: 900 },
  ];

  for (const viewport of viewports) {
    await test.step(`${viewport.name} (${viewport.width}px)`, async () => {
      await page.setViewportSize({ width: viewport.width, height: viewport.height });
      await page.goto("/lanzamientos?from=2026-08-01&to=2026-10-31&pageSize=6");

      await expect(
        page.getByRole("heading", { level: 1, name: "Explorar lanzamientos" }),
      ).toBeVisible();
      await expect(releaseTitles(page)).toHaveText(["Marvel's Wolverine"]);
      await openReleaseControls(page);
      await expect(page.getByLabel("Desde")).toBeVisible();
      await expect(page.getByRole("combobox", { name: /Ordenar por/ })).toBeVisible();
      await expect(page.getByRole("combobox", { name: /^Plataforma:/ })).toBeVisible();
      await expect(page.getByRole("toolbar", { name: "Elegir un día del periodo" })).toBeVisible();
      // The 92-day bar scrolls inside itself; the page never scrolls sideways.
      expect(await horizontalOverflow(page)).toBeLessThanOrEqual(0);
      await expectNoAccessibilityViolations(page);
    });
  }
});
