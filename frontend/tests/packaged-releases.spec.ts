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
  expect(releasePage.window).toEqual({ from: "2026-02-13", to: "2026-08-13" });
  // Eight recent releases group into five games; pagination is over games.
  expect(releasePage.page).toEqual({ number: 1, size: 6, totalItems: 5, totalPages: 1 });
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
    "Del 13 de febrero de 2026 al 13 de agosto de 2026",
  );
  await expect(page.getByText("Ventana evaluada el 13 de agosto de 2026")).toHaveCount(0);
  await expect(page.locator(".result-count")).toHaveText("5 juegos");
  await expect(page.getByText("Página 1 de 1")).toHaveCount(0);
  // Each game appears once, ordered by its first relevant release.
  await expect(releaseTitles(page)).toHaveText([
    "Pragmata",
    "Crimson Desert",
    "Metroid Prime 4: Beyond",
    "Subnautica 2",
    "Resident Evil Requiem",
  ]);

  await test.step("each card shows one compact date · platforms · region row for its first release", async () => {
    // Windows PC · Mundial is the first group of Pragmata, Crimson Desert and Resident Evil Requiem.
    await expect(page.getByText("Windows PC · Mundial")).toHaveCount(3);
    await expect(page.getByText("Nintendo Switch 2 · Europa")).toBeVisible();
    await expect(page.getByText("Xbox Series X|S · Norteamérica")).toBeVisible();
    // The visible row keeps each first release's date and precision, without inventing one.
    await expect(page.locator(".release-grid").getByText("2.º trimestre de 2026")).toHaveCount(1);
    await expect(page.getByText("mayo de 2026")).toBeVisible();
    await expect(page.getByText("16 de abril de 2026")).toBeVisible();
    await expect(page.getByText("1.er trimestre de 2026")).toBeVisible();
    await expect(page.getByText("6 de marzo de 2026")).toBeVisible();
    await expect(page.getByRole("link", { name: "Página siguiente" })).toHaveCount(0);
  });

  await test.step("stale local data is shown as usable, not as a failure", async () => {
    await expect(page.getByText(
      "Algunos lanzamientos usan la última copia local guardada y pueden estar desactualizados.",
    )).toHaveCount(0);
    await expect(page.getByText("Datos locales desactualizados")).toHaveCount(0);
    await expect(page.getByRole("alert")).toHaveCount(0);
  });

  await test.step("an approved cover reference without attribution uses the product fallback", async () => {
    await expect(
      page.getByRole("img", { name: "Carátula oficial no disponible" }),
    ).toBeVisible();
    await expect(page.locator(".catalogue-card .cover-caption")).toHaveCount(0);
  });

  await expectNoAccessibilityViolations(page);

  await test.step("hidden release groups collapse into an accessible popover that preserves the details", async () => {
    // Three games hide exactly one release each behind the overflow control.
    await expect(page.getByRole("button", { name: "+ 1 lanzamiento más" })).toHaveCount(3);
    const pragmata = releaseCard(page, "Pragmata");
    const trigger = pragmata.getByRole("button", { name: "+ 1 lanzamiento más" });
    await trigger.click();

    const dialog = pragmata.getByRole("dialog", { name: "Otros lanzamientos de Pragmata" });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByText("2.º trimestre de 2026")).toBeVisible();
    await expect(dialog.getByText("PlayStation 5 · Europa")).toBeVisible();

    await page.keyboard.press("Escape");
    await expect(pragmata.getByRole("dialog")).toHaveCount(0);
    await expect(trigger).toBeFocused();
  });

  await test.step("a platform filter narrows the result set and returns to the first page", async () => {
    await page.getByRole("combobox", { name: /^Plataforma:/ }).click();
    await page.getByRole("option", { name: "Windows PC" }).click();

    await expect(page).toHaveURL(/\?platformId=[0-9a-f-]+&pageSize=6$/);
    await expect(page.locator(".result-count")).toHaveText("3 juegos");
    await expect(releaseTitles(page)).toHaveText([
      "Pragmata",
      "Crimson Desert",
      "Resident Evil Requiem",
    ]);
    await expect(
      page.getByRole("combobox", { name: /^Plataforma:/ }),
    ).toContainText("Windows PC");
  });

  await test.step("an unmatched filter combination explains the empty result", async () => {
    await page.getByRole("combobox", { name: /^Región:/ }).click();
    await page.getByRole("option", { name: "Europa" }).click();

    await expect(
      page.getByText(
        "Ningún lanzamiento del catálogo local coincide con esta ventana y estos filtros.",
      ),
    ).toBeVisible();
    await expect(releaseTitles(page)).toHaveCount(0);
    await expect(
      page.getByRole("combobox", { name: /^Plataforma:/ }),
    ).toContainText("Windows PC");
    await expectNoAccessibilityViolations(page);
  });

  await test.step("filters can be cleared", async () => {
    await page.getByRole("link", { name: "Quitar filtros" }).first().click();

    await expect(page).toHaveURL(/\?pageSize=6$/);
    await expect(releaseTitles(page)).toHaveCount(5);
    await expect(
      page.getByRole("combobox", { name: /^Plataforma:/ }),
    ).toContainText("Todas");
    await expect(
      page.getByRole("combobox", { name: /^Región:/ }),
    ).toContainText("Todas");
  });

  await test.step("the upcoming window keeps announced and delayed releases separate", async () => {
    await page.getByRole("link", { name: "Próximos", exact: true }).click();

    await expect(
      page.getByRole("heading", { level: 1, name: "Próximos lanzamientos" }),
    ).toBeVisible();
    await expect(page.locator(".release-period")).toContainText(
      "Del 13 de agosto de 2026 al 13 de febrero de 2027",
    );
    await expect(page.locator(".result-count")).toHaveText("5 juegos");
    await expect(releaseTitles(page)).toHaveText([
      "Marvel's Wolverine",
      "Crimson Desert",
      "Subnautica 2",
      "Fable",
      "The Witcher IV",
    ]);
    // Marvel's Wolverine shows its precise day; Crimson Desert's two October releases share date
    // and region, so they collapse into one row with a single month date.
    await expect(page.getByText("25 de septiembre de 2026")).toBeVisible();
    await expect(page.getByText("octubre de 2026")).toBeVisible();
    await expect(page.getByText("PlayStation 5 · Xbox Series X|S · Europa")).toBeVisible();
    // Two games (Subnautica 2 and The Witcher IV) hide one further release each.
    await expect(page.getByRole("button", { name: "+ 1 lanzamiento más" })).toHaveCount(2);
    await expect(page.getByRole("link", { name: "Página siguiente" })).toHaveCount(0);
  });

  await test.step("an unconfirmed date stays explicit inside the game's popover", async () => {
    const witcher = releaseCard(page, "The Witcher IV");
    await witcher.getByRole("button", { name: "+ 1 lanzamiento más" }).click();

    const dialog = witcher.getByRole("dialog", { name: "Otros lanzamientos de The Witcher IV" });
    await expect(dialog.getByText("Fecha por confirmar")).toBeVisible();
    await expect(dialog.getByText("Windows PC · Sin región confirmada")).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(witcher.getByRole("dialog")).toHaveCount(0);
  });

  await test.step("an out-of-range shared page recovers directly to the last page", async () => {
    await page.goto("/?view=upcoming&page=99&pageSize=1");

    await expect(page.locator(".result-count")).toHaveText(
      "5 juegos · La página 99 ya no está disponible",
    );
    await expect(
      page.getByText("La página solicitada ya no está disponible para estos resultados."),
    ).toBeVisible();

    await page.getByRole("link", { name: "Ir a la última página" }).click();

    await expect(page).toHaveURL(/view=upcoming&page=5&pageSize=1/);
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
    // An upcoming game is not eligible: the inline control is disabled and says why.
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
      await expect(releaseTitles(page)).toHaveCount(5);
      await expect(page.getByRole("combobox", { name: /^Plataforma:/ })).toBeVisible();
      expect(await horizontalOverflow(page)).toBeLessThanOrEqual(0);
      await expectNoAccessibilityViolations(page);
    });
  }
});
