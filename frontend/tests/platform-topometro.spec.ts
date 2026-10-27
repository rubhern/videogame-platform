import { expect, test, type Page } from "@playwright/test";

import { analyzeAccessibility } from "./fixtures/accessibility";

type Source = "igdb_players" | "gameometro_community";

const PS5 = "10000000-0000-4000-8000-000000000001";
const directory = [
  { platformId: PS5, name: "PlayStation 5" },
  { platformId: "ps4-id", name: "PlayStation 4" },
  { platformId: "meta-quest-3-id", name: "Meta Quest 3" },
];
const TOTAL = 26;

const releaseDates = [
  { precision: "day", value: "2021-06-11" },
  { precision: "month", value: "2023-09" },
  { precision: "year", value: "2020" },
  { precision: "unknown", value: null },
] as const;

/** A platform's ranking: 26 games, long titles, each dated by its release on the platform. */
function rankingPage(source: Source, platformId: string, page: number, size: number) {
  const first = (page - 1) * size + 1;
  const name = directory.find((platform) => platform.platformId === platformId)?.name ?? "PlayStation 5";
  const items = [];
  for (let position = first; position < first + size && position <= TOTAL; position++) {
    const value = Math.round((9.6 - position * 0.1) * 10) / 10;
    const count = position % 5 === 2 ? 1 : 1100 * position;
    items.push({
      position,
      gameId: `game-${position}`,
      slug: `game-${position}`,
      canonicalTitle: position % 4 === 1
        ? `Una aventura con un título muy largo para una plataforma ${position}`
        : `Juego ${position}`,
      primaryCover: { kind: "fallback", url: "/assets/covers/fallback.svg", alternativeText: "Portada oficial no disponible", attribution: null },
      genres: [{ genreId: "rpg", name: "Rol" }, { genreId: "adventure", name: "Aventura" }],
      releaseSummary: {
        platforms: [
          { platformId: "ps4-id", name: "PlayStation 4" },
          { platformId, name },
          { platformId: "10000000-0000-4000-8000-000000000003", name: "Windows PC" },
        ],
        totalPlatforms: 4,
        earliestKnownYear: 2015,
        latestKnownYear: 2023,
      },
      platformRelease: {
        platform: { platformId, name },
        region: { regionId: "world", name: "Mundial" },
        releaseDate: releaseDates[position % releaseDates.length],
        status: "released",
      },
      score: source === "igdb_players"
        ? {
          source: "igdb", kind: "player", value, scale: 10, count,
          attribution: { label: "Jugadores IGDB", sourceUrl: `https://www.igdb.com/games/game-${position}` },
          observedAt: "2026-10-20T08:00:00Z",
        }
        : { source: "gameometro", kind: "community", value: Math.max(1, value - 1), scale: 10, count },
    });
  }
  return { source, items, page: { number: page, size, totalItems: TOTAL, totalPages: Math.ceil(TOTAL / size) } };
}

async function stubRanking(page: Page) {
  const requested: URL[] = [];
  await page.route("**/api/v1/session", (route) => route.fulfill({ json: { authenticated: false } }));
  await page.route("**/api/v1/platforms", (route) => route.fulfill({ json: { items: directory } }));
  await page.route("**/api/v1/game-rankings?*", (route) => {
    const url = new URL(route.request().url());
    requested.push(url);
    const source = (url.searchParams.get("source") ?? "igdb_players") as Source;
    return route.fulfill({
      json: rankingPage(
        source,
        url.searchParams.get("platformIds") ?? PS5,
        Number(url.searchParams.get("page")),
        Number(url.searchParams.get("pageSize")),
      ),
    });
  });
  return requested;
}

test.beforeEach(async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
});

test("Plataformas leads to a platform's TOPómetro and a direct load or refresh renders it", async ({ page }) => {
  await page.setViewportSize({ width: 1320, height: 900 });
  const requested = await stubRanking(page);
  await page.goto("/plataformas");
  const card = page.getByRole("list", { name: "Plataformas destacadas" }).getByRole("article").nth(1);
  await card.getByRole("link", { name: "TOPómetro" }).click();

  await expect(page).toHaveURL(/\/plataformas\/playstation-5\/topometro$/);
  await expect(page.getByRole("heading", { level: 1, name: "TOPómetro de PlayStation 5" })).toBeVisible();
  await expect(page.getByRole("list", { name: "Podio" }).locator(":scope > li")).toHaveCount(3);
  expect(requested.at(-1)?.searchParams.getAll("platformIds")).toEqual([PS5]);

  await page.reload();
  await expect(page.getByRole("list", { name: "Podio" }).locator(":scope > li")).toHaveCount(3);
  await expect(page.getByRole("navigation", { name: "Principal" }).getByRole("link", { name: "Plataformas" }))
    .toHaveAttribute("aria-current", "true");
});

for (const width of [320, 390, 834, 1320]) {
  test(`the platform's opening, podium and ledger reflow without overflow at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: width >= 1024 ? 900 : 844 });
    await stubRanking(page);
    await page.goto("/plataformas/playstation-5/topometro");

    const podium = page.getByRole("list", { name: "Podio" });
    await expect(podium.locator(":scope > li")).toHaveCount(3);
    await expect(page.getByRole("list", { name: "Clasificación desde el cuarto puesto" }).locator(":scope > li"))
      .toHaveCount(17);
    expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);

    // The plate answers the lockup beneath it, inside the page, and never overflows it.
    const lockup = await page.locator(".topometro-head-platform .topometro-logo").boundingBox();
    const plate = await page.locator(".topometro-platform").boundingBox();
    if (lockup === null || plate === null) throw new Error("The platform opening lost its lockup or plate");
    expect(plate.y).toBeGreaterThanOrEqual(lockup.y + lockup.height - 1);
    expect(plate.x + plate.width).toBeLessThanOrEqual(width);
    // Controls keep comfortable targets inside the viewport.
    const sources = page.getByRole("navigation", { name: "Puntuaciones del TOPómetro" });
    for (const control of [
      sources.getByRole("link", { name: "Jugadores IGDB" }),
      sources.getByRole("link", { name: "Comunidad Gameómetro" }),
      page.getByRole("button", { name: "Cambiar plataforma" }),
    ]) {
      const box = await control.boundingBox();
      expect(box?.height ?? 0).toBeGreaterThanOrEqual(38);
      expect((box?.x ?? -1) >= 0 && (box?.x ?? 0) + (box?.width ?? 0) <= width).toBe(true);
    }
    // Each game is dated on the platform; the platform is never repeated on each place.
    await expect(podium.locator(".platform-marks")).toHaveCount(0);
    await expect(page.locator(".ranking-list .platform-marks")).toHaveCount(0);
    // Second place came out on the platform in a known year only; third without a known date.
    await expect(podium.locator(':scope > li[value="2"] .podium-year [aria-hidden="true"]')).toHaveText("2020");
    await expect(podium.locator(':scope > li[value="3"] .podium-year [aria-hidden="true"]')).toHaveText("Por confirmar");

    expect((await analyzeAccessibility(page)).violations).toEqual([]);
    await page.screenshot({ path: testInfo.outputPath(`platform-topometro-${width}.png`), fullPage: true });
  });
}

test("keeps the platform across sources, further places and a game page's way back", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const requested = await stubRanking(page);
  await page.route("**/api/v1/games/game-22", (route) => route.fulfill({ status: 404, json: { code: "GAME_NOT_FOUND" } }));
  await page.goto("/plataformas/playstation-5/topometro");
  await expect(page.getByRole("list", { name: "Podio" }).locator(":scope > li")).toHaveCount(3);

  await page.getByRole("link", { name: "Comunidad Gameómetro" }).click();
  await expect(page).toHaveURL(/\/plataformas\/playstation-5\/topometro\?source=gameometro$/);
  await expect(page.getByRole("link", { name: "Comunidad Gameómetro" })).toHaveAttribute("aria-current", "true");

  await page.getByRole("button", { name: "Cargar más juegos" }).click();
  // The batch it brought receives focus at its first game.
  await expect(page.getByRole("link", { name: /^Puesto 21: / })).toBeFocused();
  const target = page.getByRole("link", { name: "Puesto 22: Juego 22" });
  expect(requested.map((url) => [url.searchParams.get("source"), url.searchParams.get("page"), url.searchParams.getAll("platformIds")]))
    .toEqual([["igdb_players", "1", [PS5]], ["gameometro_community", "1", [PS5]], ["gameometro_community", "2", [PS5]]]);

  await target.scrollIntoViewIfNeeded();
  const before = await page.evaluate(() => scrollY);
  await target.click();
  await expect(page).toHaveURL(/\/games\/game-22\/game-22$/);
  await page.getByRole("link", { name: "Volver al TOPómetro" }).click();
  await expect(page).toHaveURL(/\/plataformas\/playstation-5\/topometro\?source=gameometro$/);
  await expect(page.getByRole("link", { name: "Puesto 22: Juego 22" })).toBeVisible();
  expect(Math.abs((await page.evaluate(() => scrollY)) - before)).toBeLessThan(80);
});

test("switches platform keeping the source, from the featured four to an Otras platform", async ({ page }) => {
  await page.setViewportSize({ width: 834, height: 1112 });
  const requested = await stubRanking(page);
  await page.goto("/plataformas/playstation-5/topometro?source=gameometro");
  await page.getByRole("button", { name: "Cambiar plataforma" }).click();
  await page.getByRole("link", { name: "PlayStation 4" }).click();

  await expect(page).toHaveURL(/\/plataformas\/ps4-id\/topometro\?source=gameometro$/);
  await expect(page.getByRole("heading", { level: 1, name: "TOPómetro de PlayStation 4" })).toBeVisible();
  await expect(page.getByRole("list", { name: "Podio" }).locator(":scope > li")).toHaveCount(3);
  expect(requested.at(-1)?.searchParams.getAll("platformIds")).toEqual(["ps4-id"]);
  expect((await analyzeAccessibility(page)).violations).toEqual([]);
});

test("reduced motion keeps the plate still and forced colours keep its rule", async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce", forcedColors: "active" });
  await page.setViewportSize({ width: 834, height: 1112 });
  await stubRanking(page);
  await page.goto("/plataformas/meta-quest-3-id/topometro");
  await expect(page.getByRole("heading", { level: 1, name: "TOPómetro de Meta Quest 3" })).toBeVisible();
  await expect(page.getByRole("list", { name: "Podio" })).toBeVisible();
  expect(await page.evaluate(() => document.getAnimations().length)).toBe(0);
  await expect(page.locator(".topometro-platform")).toHaveCSS("border-top-style", "solid");
});
