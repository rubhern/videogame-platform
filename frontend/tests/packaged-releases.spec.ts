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
  return page.getByRole("list", { name: /lanzamientos/i }).getByRole("heading", { level: 3 });
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

  await page.goto("/");
  const releasesResponse = await releasesResponsePromise;
  expect(releasesResponse.status()).toBe(200);
  expect(releasesResponse.request().resourceType()).toBe("fetch");
  expect(releasesResponse.url()).toContain("page=1&pageSize=6");

  const releasePage = (await releasesResponse.json()) as ReleasePage;
  expect(releasePage.evaluatedOn).toBe("2026-08-13");
  expect(releasePage.window).toEqual({ from: "2026-02-13", to: "2026-08-13" });
  expect(releasePage.page).toEqual({ number: 1, size: 6, totalItems: 8, totalPages: 2 });
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
  await expect(
    page.getByText(
      "Del 13 de febrero de 2026 al 13 de agosto de 2026. Ventana evaluada el 13 de agosto de 2026.",
    ),
  ).toBeVisible();
  await expect(page.getByRole("status")).toHaveText(
    "8 lanzamientos en la ventana · Página 1 de 2",
  );
  await expect(releaseTitles(page)).toHaveText([
    "Pragmata",
    "Pragmata",
    "Crimson Desert",
    "Metroid Prime 4: Beyond",
    "Metroid Prime 4: Beyond",
    "Subnautica 2",
  ]);

  await test.step("every stored date precision is rendered without inventing a date", async () => {
    await expect(page.getByText("2.º trimestre de 2026")).toHaveCount(2);
    await expect(page.getByText("mayo de 2026")).toBeVisible();
    await expect(page.getByText("16 de abril de 2026")).toHaveCount(2);
    await expect(page.getByText("1.er trimestre de 2026")).toBeVisible();
  });

  await test.step("stale local data is shown as usable, not as a failure", async () => {
    await expect(
      page.getByText(
        "Algunos lanzamientos muestran los últimos datos locales válidos, que ya están desactualizados.",
      ),
    ).toBeVisible();
    await expect(page.getByText("Datos locales desactualizados")).toHaveCount(1);
    await expect(page.getByRole("alert")).toHaveCount(0);
  });

  await test.step("an approved cover reference without attribution uses the product fallback", async () => {
    await expect(
      page.getByRole("img", { name: "Carátula oficial no disponible" }),
    ).toBeVisible();
  });

  await expectNoAccessibilityViolations(page);

  await test.step("pagination reaches the incomplete last page and focuses the results", async () => {
    await page.getByRole("link", { name: "Página siguiente" }).click();

    await expect(page).toHaveURL(/\?page=2$/);
    await expect(page.getByRole("heading", { level: 2, name: "Resultados" })).toBeFocused();
    await expect(page.getByRole("status")).toHaveText(
      "8 lanzamientos en la ventana · Página 2 de 2",
    );
    await expect(releaseTitles(page)).toHaveText([
      "Resident Evil Requiem",
      "Resident Evil Requiem",
    ]);
    await expect(page.getByText("6 de marzo de 2026")).toBeVisible();
    await expect(page.getByText("27 de febrero de 2026")).toBeVisible();
    await expect(page.getByText("Windows PC · Worldwide")).toBeVisible();
    await expect(page.getByText("PlayStation 5 · Europe")).toBeVisible();
    await expect(page.getByRole("link", { name: "Página siguiente" })).toHaveCount(0);
  });

  await test.step("a platform filter narrows the result set and returns to the first page", async () => {
    await page.getByLabel("Plataforma").selectOption({ label: "Windows PC" });

    await expect(page).toHaveURL(/\?platformId=[0-9a-f-]+$/);
    await expect(page.getByRole("status")).toHaveText(
      "3 lanzamientos en la ventana · Página 1 de 1",
    );
    await expect(releaseTitles(page)).toHaveText([
      "Pragmata",
      "Crimson Desert",
      "Resident Evil Requiem",
    ]);
    await expect(page.getByLabel("Plataforma")).toHaveValue(/.+/);
  });

  await test.step("an unmatched filter combination explains the empty result", async () => {
    await page.getByLabel("Región").selectOption({ label: "Europe" });

    await expect(
      page.getByText(
        "Ningún lanzamiento del catálogo local coincide con esta ventana y estos filtros.",
      ),
    ).toBeVisible();
    await expect(releaseTitles(page)).toHaveCount(0);
    await expect(page.getByLabel("Plataforma")).toHaveValue(/.+/);
    await expectNoAccessibilityViolations(page);
  });

  await test.step("filters can be cleared", async () => {
    await page.getByRole("link", { name: "Quitar filtros" }).first().click();

    await expect(page).toHaveURL(/\/$/);
    await expect(releaseTitles(page)).toHaveCount(6);
    await expect(page.getByLabel("Plataforma")).toHaveValue("");
    await expect(page.getByLabel("Región")).toHaveValue("");
  });

  await test.step("the upcoming window keeps announced and delayed releases separate", async () => {
    await page.getByRole("link", { name: "Próximos" }).click();

    await expect(
      page.getByRole("heading", { level: 1, name: "Próximos lanzamientos" }),
    ).toBeVisible();
    await expect(
      page.getByText(
        "Del 13 de agosto de 2026 al 13 de febrero de 2027. Ventana evaluada el 13 de agosto de 2026.",
      ),
    ).toBeVisible();
    await expect(page.getByRole("status")).toHaveText(
      "8 lanzamientos en la ventana · Página 1 de 2",
    );
    await expect(releaseTitles(page)).toHaveText([
      "Marvel's Wolverine",
      "Crimson Desert",
      "Crimson Desert",
      "Subnautica 2",
      "Fable",
      "Subnautica 2",
    ]);
    await expect(page.getByText("25 de septiembre de 2026")).toBeVisible();
    await expect(page.getByText("octubre de 2026")).toHaveCount(2);
    await expect(page.getByText("Retrasado")).toBeVisible();
  });

  await test.step("an unconfirmed date stays explicit on the last upcoming page", async () => {
    await page.getByRole("link", { name: "Página siguiente" }).click();

    await expect(page).toHaveURL(/view=upcoming&page=2/);
    await expect(releaseTitles(page)).toHaveText(["The Witcher IV", "The Witcher IV"]);
    await expect(page.getByText("2027", { exact: true })).toBeVisible();
    await expect(page.getByText("Fecha por confirmar")).toBeVisible();
    await expect(page.getByText("Información pendiente de revisión")).toHaveCount(2);
  });

  await test.step("an out-of-range shared page recovers directly to the last page", async () => {
    await page.goto("/?view=upcoming&page=99&pageSize=1");

    await expect(page.getByRole("status")).toHaveText(
      "8 lanzamientos en la ventana · La página 99 ya no está disponible",
    );
    await expect(
      page.getByText("La página solicitada ya no está disponible para estos resultados."),
    ).toBeVisible();

    await page.getByRole("link", { name: "Ir a la última página" }).click();

    await expect(page).toHaveURL(/view=upcoming&page=8&pageSize=1/);
    await expect(releaseTitles(page)).toHaveText(["The Witcher IV"]);
    await expect(page.getByRole("heading", { level: 2, name: "Resultados" })).toBeFocused();
  });

  await test.step("the keyboard reaches a game from the focused results", async () => {
    await page.keyboard.press("Tab");
    const gameLink = page.getByRole("link", { name: "Ver The Witcher IV" }).first();
    await expect(gameLink).toBeFocused();

    await gameLink.press("Enter");
    await expect(page).toHaveURL(/\/games\/the-witcher-iv$/);
    await expect(
      page.getByRole("heading", { level: 1, name: "Detalle de juego todavía no disponible" }),
    ).toBeVisible();
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
      await page.goto("/");

      await expect(
        page.getByRole("heading", { level: 1, name: "Lanzamientos recientes" }),
      ).toBeVisible();
      await expect(releaseTitles(page)).toHaveCount(6);
      await expect(page.getByLabel("Plataforma")).toBeVisible();
      await expect(page.getByRole("link", { name: "Página siguiente" })).toBeVisible();
      expect(await horizontalOverflow(page)).toBeLessThanOrEqual(0);
      await expectNoAccessibilityViolations(page);
    });
  }
});
