import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";

import type { GameSearchPage } from "../src/features/search/game-search-api";
import { releasePage } from "./fixtures/releases";

type GameSummary = GameSearchPage["items"][number];

const ps5 = { platformId: "10000000-0000-4000-8000-000000000001", name: "PlayStation 5" };
const pc = { platformId: "10000000-0000-4000-8000-000000000003", name: "Windows PC" };
const xbox = { platformId: "10000000-0000-4000-8000-000000000004", name: "Xbox Series X|S" };

function result(index: number, overrides: Partial<GameSummary> = {}): GameSummary {
  const title = `Concepto ${index}`;
  return {
    gameId: `30000000-0000-4000-8000-${String(index).padStart(12, "0")}`,
    slug: `concepto-${index}`,
    canonicalTitle: title,
    primaryCover: {
      kind: "fallback",
      url: "/assets/covers/fallback.svg",
      alternativeText: `Portada no disponible de ${title}`,
      attribution: null,
    },
    // A long release sample must never reach the card: only the compact summary does.
    releaseContext: Array.from({ length: 4 }, () => ({
      platform: pc,
      region: { regionId: "20000000-0000-4000-8000-000000000002", name: "Europe" },
      releaseDate: { precision: "day", value: "2026-02-27" },
      status: "released",
      freshnessStatus: "stale",
    })),
    releaseSummary: { platforms: [pc], totalPlatforms: 1, earliestKnownYear: 2026, latestKnownYear: 2026 },
    ...overrides,
  };
}

const firstPage: GameSearchPage = {
  items: [
    result(1, {
      canonicalTitle: "Una aventura extraordinariamente larga: más allá del horizonte de sucesos",
      matchedAlias: "Una aventura extraordinariamente larga, edición definitiva y completa",
    }),
    result(2, {
      releaseSummary: { platforms: [ps5, pc, xbox], totalPlatforms: 5, earliestKnownYear: 2024, latestKnownYear: 2026 },
    }),
    result(3, { releaseSummary: { platforms: [], totalPlatforms: 0 } }),
    result(4),
    result(5, { matchedAlias: "Concept Five" }),
    result(6),
  ],
  page: { number: 1, size: 6, totalItems: 16, totalPages: 3 },
};

async function scriptSearch(page: Page) {
  await page.route("**/api/v1/session", (route) => route.fulfill({ json: { authenticated: false } }));
  await page.route("**/api/v1/releases?*", (route) => route.fulfill({ json: releasePage() }));
  await page.route("**/api/v1/games?*", async (route) => {
    const number = Number(new URL(route.request().url()).searchParams.get("page") ?? 1);
    await route.fulfill({ json: { ...firstPage, page: { ...firstPage.page, number } } });
  });
}

async function expectAccessibleLayout(page: Page) {
  await page.evaluate(() => document.fonts.ready);
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth),
  ).toBeLessThanOrEqual(0);
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
}

function cards(page: Page) {
  return page.getByRole("list", { name: "Resultados de la búsqueda" }).locator(":scope > li");
}

// Controlled HTTP responses isolate the redesigned card and page states (#188); the packaged
// search journey owns the real API, matching and ranking.
for (const [width, columns] of [[1320, 6], [834, 4], [390, 2], [320, 2]] as const) {
  test(`search results keep a bounded, scannable grid at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.emulateMedia({ reducedMotion: "reduce" });
    await scriptSearch(page);
    await page.goto("/search?q=con");

    await expect(page.getByRole("heading", { level: 1, name: "Resultados para «con»" })).toBeVisible();
    await expect(page.getByText("Catálogo de juegos")).toBeVisible();
    await expect(page.getByRole("status")).toHaveText("16 juegos del catálogo local · Página 1 de 3");
    await expect(cards(page)).toHaveCount(6);
    expect(await page.locator(".search-page .release-grid").evaluate((grid) =>
      getComputedStyle(grid).gridTemplateColumns.split(" ").length,
    )).toBe(columns);

    // Every card in a row shares one height, whatever its alias, platforms or title length.
    const boxes = await cards(page).evaluateAll((items) =>
      items.map((item) => {
        const box = item.getBoundingClientRect();
        return { top: Math.round(box.top), height: Math.round(box.height) };
      }),
    );
    for (const top of new Set(boxes.map((box) => box.top))) {
      expect(new Set(boxes.filter((box) => box.top === top).map((box) => box.height)).size).toBe(1);
    }

    const overflowCard = cards(page).nth(1);
    await expect(overflowCard.getByRole("listitem")).toHaveCount(4);
    await expect(overflowCard.getByText("+2")).toBeVisible();
    await expect(overflowCard.getByText("2024–2026")).toBeVisible();
    await expect(cards(page).nth(2).getByText("Por confirmar")).toBeVisible();
    await expect(cards(page).nth(4).getByText("Coincidencia: Concept Five")).toBeVisible();
    await expect(page.locator(".search-card .cover-caption")).toHaveCount(0);
    await expect(page.getByText("27 de febrero de 2026")).toHaveCount(0);
    await expect(page.getByText("Datos locales desactualizados")).toHaveCount(0);

    const metadataSizes = await page
      .locator(".search-card-alias, .search-card-year, .search-card-platform-more")
      .evaluateAll((elements) => elements.map((element) => Number.parseFloat(getComputedStyle(element).fontSize)));
    expect(Math.min(...metadataSizes)).toBeGreaterThanOrEqual(11);

    await expect(page.getByRole("navigation", { name: "Paginación de resultados" })).toContainText("Página 1 de 3");
    await expectAccessibleLayout(page);
  });
}

test("search results stay keyboard navigable across pages and into a game", async ({ page }) => {
  await page.setViewportSize({ width: 1320, height: 900 });
  await scriptSearch(page);
  await page.goto("/search?q=con");

  await expect(cards(page)).toHaveCount(6);
  const next = page.getByRole("link", { name: "Página siguiente" });
  await next.focus();
  await expect(next).toHaveCSS("outline-style", "solid");
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/\/search\?q=con&page=2$/);
  await expect(page.getByRole("heading", { level: 2, name: "Resultados" })).toBeFocused();
  await expect(page.getByRole("navigation", { name: "Paginación de resultados" })).toContainText("Página 2 de 3");
  await expect(page.getByRole("link", { name: "Página anterior" })).toBeVisible();

  // Each card is one keyboard stop: its title link opens the game.
  await page.keyboard.press("Tab");
  const firstTitle = page.getByRole("link", { name: firstPage.items[0]?.canonicalTitle ?? "", exact: true });
  await expect(firstTitle).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(page.getByRole("link", { name: "Concepto 2", exact: true })).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/\/games\/30000000-0000-4000-8000-000000000002\/concepto-2$/);
});
