import { expect, test, type Page } from "@playwright/test";

import { analyzeAccessibility } from "./fixtures/accessibility";
import type { GameSearchPage } from "../src/features/search/game-search-api";
import { releasePage } from "./fixtures/releases";
import { gameDetailsFixture } from "../src/test/game-details-fixture";

type GameSummary = GameSearchPage["items"][number];

for (const width of [320, 390, 834, 1320]) {
  test(`the lit search heading survives reverse scrolling at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 740 });
    await page.emulateMedia({ reducedMotion: "no-preference" });
    await page.route("**/api/v1/session", route => route.fulfill({ json: { authenticated: false } }));
    // Use real image painting without depending on the provider CDN.
    await page.route("**/test-cover.svg", route => route.fulfill({
      contentType: "image/svg+xml",
      body: '<svg xmlns="http://www.w3.org/2000/svg" width="200" height="300"><path fill="#dbbe81" d="M0 0h200v300H0z"/></svg>',
    }));
    await page.route("**/api/v1/games?*", route => route.fulfill({ json: {
      items: Array.from({ length: 12 }, (_, index) => result(index + 1, {
        primaryCover: { kind: "provider", url: "/test-cover.svg", alternativeText: "Portada",
          attribution: { label: "IGDB", sourceUrl: "https://www.igdb.com" } },
      })),
      page: { number: 1, size: 12, totalItems: 12, totalPages: 1 },
    } }));
    await page.goto("/search?q=con");
    const heading = page.locator(".page-head");
    await expect(page.locator(".stage-light-source")).toHaveCount(3);
    await page.evaluate(() => document.fonts.ready);
    // Finished opacity animations must release their compositing effects. Retaining them
    // can leave mobile headings blank when off-screen tiles are painted again.
    await expect.poll(() => heading.evaluate(element => element.getAnimations().length)).toBe(0);
    await expect.poll(() => page.locator(".stage-light-source").first()
      .evaluate(element => element.getAnimations().length)).toBe(0);
    const before = await heading.screenshot();
    for (let pass = 0; pass < 2; pass++) {
      await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
      await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThan(0);
      // Mobile browser chrome changes the viewport while scrolling.
      await page.setViewportSize({ width, height: 820 });
      await page.evaluate(() => window.scrollTo(0, 0));
      await page.setViewportSize({ width, height: 740 });
      await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(0);
      expect(await heading.screenshot()).toEqual(before);
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  });
}

const ps5 = { platformId: "10000000-0000-4000-8000-000000000001", name: "PlayStation 5" };
const pc = { platformId: "10000000-0000-4000-8000-000000000003", name: "Windows PC" };
const xbox = { platformId: "10000000-0000-4000-8000-000000000004", name: "Xbox Series X|S" };

type Scores = NonNullable<GameSummary["scores"]>;
const external = (value: number, count: number): NonNullable<Scores["external"]> => ({
  source: "igdb", kind: "player", value, scale: 10, count,
  attribution: { label: "Jugadores IGDB", sourceUrl: "https://www.igdb.com/games/concepto" },
  observedAt: "2020-01-01T00:00:00Z",
});
const community = (mean: number, count: number): Scores["community"] => ({ status: "available", mean, count });

function result(index: number, overrides: Partial<GameSummary> = {}): GameSummary {
  const title = `Concepto ${index}`;
  return {
    gameId: `30000000-0000-4000-8000-${String(index).padStart(12, "0")}`,
    slug: `concepto-${index}`,
    canonicalTitle: title,
    genres: [{ genreId: "action", name: "Acción" }, { genreId: "adventure", name: "Aventura" }],
    primaryCover: {
      kind: "fallback",
      url: "/assets/covers/fallback.svg",
      alternativeText: `Portada no disponible de ${title}`,
      attribution: null,
    },
    // A long release sample must never reach the card: only the compact summary does.
    releaseContext: Array.from({ length: 4 }, () => ({
      platform: pc,
      region: { regionId: "20000000-0000-4000-8000-000000000002", name: "Europa" },
      releaseDate: { precision: "day", value: "2026-02-27" },
      status: "released",
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
      scores: { community: community(9, 1), external: external(8.8, 1) },
    }),
    result(2, {
      releaseSummary: { platforms: [ps5, pc, xbox], totalPlatforms: 5, earliestKnownYear: 2024, latestKnownYear: 2026 },
      scores: { community: community(10, 9876), external: external(10, 123456) },
    }),
    result(3, {
      releaseSummary: { platforms: [], totalPlatforms: 0 },
      scores: { community: { status: "unavailable", reasonCode: "RATING_STATISTICS_READ_FAILED" }, external: external(0, 1) },
    }),
    result(4, { scores: { community: community(6.5, 42) } }),
    result(5, { matchedAlias: "Concept Five", scores: { community: { status: "available", mean: null, count: 0 } } }),
    result(6),
  ],
  page: { number: 1, size: 6, totalItems: 16, totalPages: 3 },
};

async function scriptSearch(page: Page) {
  await page.route("**/api/v1/games/*", route => route.fulfill({ json: gameDetailsFixture() }));
  await page.route("**/api/v1/session", (route) => route.fulfill({ json: { authenticated: false } }));
  await page.route("**/api/v1/releases?*", (route) => route.fulfill({ json: releasePage() }));
  await page.route("**/api/v1/games?*", async (route) => {
    const number = Number(new URL(route.request().url()).searchParams.get("page") ?? 1);
    await route.fulfill({ json: { ...firstPage, page: { ...firstPage.page, number } } });
  });
}

/** Each card's sources sit under its title and above its genres, one compact row per source. */
async function expectStackedScores(page: Page) {
  const layouts = await cards(page).evaluateAll((items) => items.map((item) => {
    const list = item.querySelector(".discovery-scores");
    if (list === null) return null;
    const title = item.querySelector(".card-title")?.getBoundingClientRect();
    const genres = item.querySelector(".card-genres")?.getBoundingClientRect();
    const body = item.querySelector(".search-card-record")?.getBoundingClientRect();
    const box = list.getBoundingClientRect();
    return {
      belowTitle: title !== undefined && box.top >= title.bottom,
      aboveGenres: genres !== undefined && box.bottom <= genres.top,
      insideCard: body !== undefined && box.left >= body.left - 1 && box.right <= body.right + 1,
      sources: Array.from(list.children, (source) => {
        const rect = source.getBoundingClientRect();
        const mark = source.querySelector(".discovery-score-mark")?.getBoundingClientRect();
        return { top: rect.top, bottom: rect.bottom, markLeft: mark?.left ?? NaN,
          fits: source.scrollWidth <= source.clientWidth && rect.right <= box.right + 1 };
      }),
    };
  }));
  expect(layouts.map((layout) => layout?.sources.length ?? 0)).toEqual([2, 2, 1, 1, 0, 0]);
  for (const layout of layouts) {
    if (layout === null) continue;
    expect(layout).toMatchObject({ belowTitle: true, aboveGenres: true, insideCard: true });
    for (const source of layout.sources) expect(source.fits).toBe(true);
    const [first, second] = layout.sources;
    if (first !== undefined && second !== undefined) {
      expect(second.top).toBeGreaterThanOrEqual(first.bottom);
      expect(Math.abs(second.markLeft - first.markLeft)).toBeLessThanOrEqual(1);
    }
  }
}

async function expectAccessibleLayout(page: Page) {
  await page.evaluate(() => document.fonts.ready);
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth),
  ).toBeLessThanOrEqual(0);
  expect((await analyzeAccessibility(page)).violations).toEqual([]);
}

function cards(page: Page) {
  return page.getByRole("list", { name: "Resultados de la búsqueda" }).locator(":scope > li");
}

// Controlled HTTP responses isolate the redesigned card and page states (#188); the packaged
// search journey owns the real API, matching and ranking.
for (const [width, columns] of [[1320, 3], [834, 2], [390, 1], [320, 1]] as const) {
  test(`search results keep a bounded, scannable catalogue index at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.emulateMedia({ reducedMotion: "reduce" });
    await scriptSearch(page);
    await page.goto("/search?q=con");

    await expect(page.getByRole("heading", { level: 1, name: "Resultados para «con»" })).toBeVisible();
    await expect(cards(page).first().getByLabel("Géneros: Acción · Aventura")).toBeVisible();
    await expect(cards(page).first().locator(".card-genres")).toHaveCSS("white-space", "nowrap");
    await expect(page.getByText("Búsqueda de juegos")).toBeVisible();
    await expect(page.getByRole("status")).toHaveText("16 juegos · Página 1 de 3");
    await expect(cards(page)).toHaveCount(6);
    expect(await page.locator(".search-page .search-index").evaluate((grid) =>
      getComputedStyle(grid).gridTemplateColumns.split(" ").length,
    )).toBe(columns);

    // Every entry in a row shares one height, whatever its alias, platforms or title length.
    const boxes = await cards(page).evaluateAll((items) =>
      items.map((item) => {
        const box = item.getBoundingClientRect();
        return { top: Math.round(box.top), height: Math.round(box.height) };
      }),
    );
    for (const top of new Set(boxes.map((box) => box.top))) {
      expect(new Set(boxes.filter((box) => box.top === top).map((box) => box.height)).size).toBe(1);
    }

    await expectStackedScores(page);
    const firstScores = cards(page).first().getByRole("list", { name: /^Puntuaciones de / });
    await expect(firstScores.getByLabel("9,0 de 10")).toBeVisible();
    await expect(firstScores.getByLabel("8,8 de 10")).toBeVisible();
    await expect(firstScores.getByText("1 valoración", { exact: true })).toHaveCount(2);
    await expect(firstScores.getByRole("link", { name: /^Jugadores IGDB:/ }))
      .toHaveAttribute("href", "https://www.igdb.com/games/concepto");
    const largeScores = cards(page).nth(1).getByRole("list", { name: /^Puntuaciones de / });
    await expect(largeScores.getByText("9.876 valoraciones", { exact: true })).toBeAttached();
    await expect(largeScores.getByText("123.456 valoraciones", { exact: true })).toBeAttached();
    await expect(cards(page).nth(2).getByLabel("0,0 de 10")).toBeVisible();

    const overflowCard = cards(page).nth(1);
    await expect(overflowCard.getByRole("list", { name: "Plataformas de Concepto 2" }).getByRole("listitem")).toHaveCount(4);
    await expect(overflowCard.getByText("+2")).toBeVisible();
    await expect(overflowCard.getByText("2024–2026")).toBeVisible();
    await expect(cards(page).nth(2).getByText("Por confirmar")).toBeVisible();
    await expect(cards(page).nth(4).getByText("Coincidencia: Concept Five")).toBeVisible();
    await expect(page.locator(".search-card .cover-caption")).toHaveCount(0);
    await expect(page.getByText("27 de febrero de 2026")).toHaveCount(0);
    await expect(page.getByText("Datos locales desactualizados")).toHaveCount(0);

    const metadataSizes = await page
      .locator(".search-card-alias, .search-card-year, .platform-mark-more, .discovery-score-count")
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

  // The title link opens the game; an IGDB score adds only its attribution link after it.
  await page.keyboard.press("Tab");
  const firstTitle = page.getByRole("link", { name: firstPage.items[0]?.canonicalTitle ?? "", exact: true });
  await expect(firstTitle).toBeFocused();
  await page.keyboard.press("Tab");
  const attribution = cards(page).first().getByRole("link", { name: /^Jugadores IGDB:/ });
  await expect(attribution).toBeFocused();
  await expect(attribution).not.toHaveCSS("outline-style", "none");
  await page.keyboard.press("Tab");
  await expect(page.getByRole("link", { name: "Concepto 2", exact: true })).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/\/games\/30000000-0000-4000-8000-000000000002\/concepto-2$/);
});
