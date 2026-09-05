import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";

import type { GameSearchPage } from "../src/features/search/game-search-api";

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

function resultTitles(page: Page) {
  return page
    .getByRole("list", { name: "Resultados de la búsqueda" })
    .getByRole("heading", { level: 3 });
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

async function search(page: Page, query: string) {
  const responsePromise = page.waitForResponse(
    (response) => new URL(response.url()).pathname === "/api/v1/games",
  );
  await page.getByRole("searchbox", { name: "Buscar en el catálogo" }).fill(query);
  await page.getByRole("button", { name: "Buscar" }).click();
  return responsePromise;
}

test("the packaged catalogue search reads PostgreSQL through the same-origin API", async ({
  page,
}) => {
  const providerRequests = trackProviderRequests(page);

  await page.goto("/");
  await page
    .getByRole("navigation", { name: "Secciones principales" })
    .getByRole("link", { name: "Buscar" })
    .click();

  await expect(page.getByRole("heading", { level: 1, name: "Buscar juegos" })).toBeVisible();

  await test.step("no search runs until the visitor asks for one", async () => {
    await expect(
      page.getByText(
        "Escribe un título o un título alternativo aprobado para buscar en el catálogo.",
      ),
    ).toBeVisible();
    await expect(page.getByRole("list", { name: "Resultados de la búsqueda" })).toHaveCount(0);
  });

  await test.step("an exact canonical title returns its game with release context", async () => {
    const response = await search(page, "Resident Evil Requiem");
    expect(response.status()).toBe(200);
    expect(response.request().resourceType()).toBe("fetch");

    const body = (await response.json()) as GameSearchPage;
    expect(body.page).toEqual({ number: 1, size: 6, totalItems: 1, totalPages: 1 });

    await expect(page).toHaveURL(/\/search\?q=Resident\+Evil\+Requiem$/);
    await expect(page.getByRole("heading", { level: 2, name: "Resultados" })).toBeFocused();
    await expect(page.getByRole("status")).toHaveText(
      "1 juego del catálogo local · Página 1 de 1",
    );
    await expect(resultTitles(page)).toHaveText(["Resident Evil Requiem"]);
    await expect(page.getByText("PlayStation 5 · Europe")).toBeVisible();
    await expect(page.getByText("27 de febrero de 2026")).toBeVisible();
  });

  await expectNoAccessibilityViolations(page);

  await test.step("a diacritic-free query matches the accented canonical title", async () => {
    await search(page, "ghost of yotei");

    await expect(resultTitles(page)).toHaveText(["Ghost of Yōtei"]);
  });

  await test.step("an approved alias matches and the match context is explained", async () => {
    await search(page, "the witcher 4");

    await expect(resultTitles(page)).toHaveText(["The Witcher IV"]);
    await expect(page.getByText("Coincide con el título alternativo The Witcher 4")).toBeVisible();
  });

  await test.step("a partial query matches on a word prefix", async () => {
    await search(page, "hollow kni");

    await expect(resultTitles(page)).toHaveText(["Hollow Knight: Silksong"]);
  });

  await test.step("an ambiguous query keeps every matching game separate", async () => {
    await search(page, "2");

    await expect(page.getByRole("status")).toHaveText(
      "2 juegos del catálogo local · Página 1 de 1",
    );
    await expect(resultTitles(page)).toHaveText([
      "Death Stranding 2: On the Beach",
      "Subnautica 2",
    ]);
  });

  await test.step("a title outside the bounded catalogue returns no result", async () => {
    const response = await search(page, "Elden Ring");
    const body = (await response.json()) as GameSearchPage;
    expect(body.items).toEqual([]);

    await expect(
      page.getByText(/Ningún juego del catálogo local coincide con .Elden Ring./),
    ).toBeVisible();
    await expect(resultTitles(page)).toHaveCount(0);
    await expect(page.getByRole("alert")).toHaveCount(0);
    await expectNoAccessibilityViolations(page);
  });

  await test.step("an alias that is not approved stays unsearchable", async () => {
    await search(page, "samus");

    await expect(resultTitles(page)).toHaveCount(0);
  });

  await test.step("pagination stays deterministic and shareable", async () => {
    await page.goto("/search?q=b&pageSize=2");

    await expect(page.getByRole("status")).toHaveText(
      "4 juegos del catálogo local · Página 1 de 2",
    );
    const firstPage = await resultTitles(page).allTextContents();

    await page.getByRole("link", { name: "Página siguiente" }).click();

    await expect(page).toHaveURL(/\/search\?q=b&page=2&pageSize=2$/);
    await expect(page.getByRole("heading", { level: 2, name: "Resultados" })).toBeFocused();
    await expect(page.getByRole("status")).toHaveText(
      "4 juegos del catálogo local · Página 2 de 2",
    );
    await expect(resultTitles(page)).toHaveCount(2);
    const secondPage = await resultTitles(page).allTextContents();
    expect(secondPage).not.toEqual(firstPage);
    expect(new Set([...firstPage, ...secondPage]).size).toBe(4);
  });

  await test.step("a shared result URL restores the same search", async () => {
    await page.goto("/search?q=the+witcher+4");

    await expect(resultTitles(page)).toHaveText(["The Witcher IV"]);
    await expect(page.getByRole("searchbox", { name: "Buscar en el catálogo" })).toHaveValue(
      "the witcher 4",
    );
  });

  await test.step("the search is keyboard operable end to end", async () => {
    await page.goto("/search");
    await page.getByRole("searchbox", { name: "Buscar en el catálogo" }).focus();
    await page.keyboard.type("pragmata");
    await page.keyboard.press("Enter");

    await expect(resultTitles(page)).toHaveText(["Pragmata"]);
    await page.keyboard.press("Tab");
    await expect(page.getByRole("link", { name: "Ver Pragmata" })).toBeFocused();
  });

  await test.step("the search page works at phone, tablet and desktop sizes", async () => {
    for (const viewport of [
      { width: 320, height: 640 },
      { width: 768, height: 1024 },
      { width: 1440, height: 900 },
    ]) {
      await page.setViewportSize(viewport);
      await page.goto("/search?q=2");

      await expect(resultTitles(page)).toHaveCount(2);
      expect(await horizontalOverflow(page)).toBeLessThanOrEqual(0);
      await expectNoAccessibilityViolations(page);
    }
  });

  expect(providerRequests).toEqual([]);
});
