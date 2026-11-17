import { expect, test, type Page } from "@playwright/test";

import { analyzeAccessibility } from "./fixtures/accessibility";

type Source = "igdb_players" | "gameometro_community";

const TITLES = [
  "The Legend of Zelda: Tears of the Kingdom",
  "Hades",
  "Persona 5 Royal",
  "Metaphor: ReFantazio",
  "Outer Wilds",
];
const TOTAL = 45;

/** A fixture ranking: 45 games, long titles, one-vote and thousands-of-votes samples. */
function rankingPage(source: Source, page: number, size: number) {
  const first = (page - 1) * size + 1;
  const items = [];
  for (let position = first; position < first + size && position <= TOTAL; position++) {
    const value = Math.round((9.8 - position * 0.12) * 10) / 10;
    const count = position % 4 === 1 ? 1 : 1234 * position;
    items.push({
      position,
      gameId: `game-${position}`,
      slug: `game-${position}`,
      canonicalTitle: `${TITLES[position % TITLES.length]} ${position}`,
      primaryCover: {
        kind: "fallback",
        url: "/assets/covers/fallback.svg",
        alternativeText: "Portada oficial no disponible",
        attribution: null,
      },
      genres: [{ genreId: "rpg", name: "Rol" }, { genreId: "adventure", name: "Aventura" }],
      releaseSummary: {
        platforms: [
          { platformId: "10000000-0000-4000-8000-000000000001", name: "PlayStation 5" },
          { platformId: "10000000-0000-4000-8000-000000000003", name: "Windows PC" },
          { platformId: "10000000-0000-4000-8000-000000000004", name: "Xbox Series X|S" },
        ],
        totalPlatforms: 5,
        earliestKnownYear: 2017,
        latestKnownYear: 2023,
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
  const requested: string[] = [];
  await page.route("**/api/v1/session", route => route.fulfill({ json: { authenticated: false } }));
  await page.route("**/api/v1/game-rankings?*", route => {
    const params = new URL(route.request().url()).searchParams;
    const source = (params.get("source") ?? "igdb_players") as Source;
    requested.push(`${source}:${params.get("page")}`);
    return route.fulfill({ json: rankingPage(source, Number(params.get("page")), Number(params.get("pageSize"))) });
  });
  return requested;
}

for (const width of [320, 390, 834, 1320]) {
  test(`podium, ledger and header reflow without overflow at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 900 });
    await stubRanking(page);
    await page.goto("/topometro");

    await expect(page).toHaveTitle("TOPómetro · Gameómetro");
    const podium = page.getByRole("list", { name: "Podio" });
    await expect(podium.locator(":scope > li")).toHaveCount(3);
    const boxes = await podium.locator(":scope > li").evaluateAll(items =>
      items.map(item => item.getBoundingClientRect()).map(({ x, width: w }) => ({ x, w })));
    const [first, second, third] = boxes;
    if (!first || !second || !third) throw new Error("The podium lost a place");
    // The winner stands between its followers and is the widest place.
    expect(second.x).toBeLessThan(first.x);
    expect(first.x).toBeLessThan(third.x);
    expect(first.w).toBeGreaterThanOrEqual(second.w);
    expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
    // Nothing inside a podium place spills beyond it.
    const spills = await podium.locator(":scope > li").evaluateAll(items => items.flatMap(item => {
      const box = item.getBoundingClientRect();
      return [...item.querySelectorAll(".ranking-reading, .podium-title, .platform-marks")]
        .map(child => child.getBoundingClientRect())
        .filter(child => child.left < box.left - 1 || child.right > box.right + 1);
    }));
    expect(spills).toEqual([]);
    // Phones name the current section inside the Menú sheet (#358).
    if (width < 720) await page.getByRole("button", { name: "Menú" }).click();
    await expect(page.getByRole("navigation", { name: "Principal" }).getByRole("link", { name: "TOPómetro" }))
      .toHaveAttribute("aria-current", "page");
    if (width < 720) await page.keyboard.press("Escape");
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
    await page.screenshot({ path: testInfo.outputPath(`topometro-${width}.png`), fullPage: true });
  });
}

test("loads further places only on request, with keyboard focus on the first new game", async ({ page }) => {
  await page.setViewportSize({ width: 1320, height: 900 });
  const requested = await stubRanking(page);
  await page.goto("/topometro");

  const ledger = page.getByRole("list", { name: "Clasificación desde el cuarto puesto" });
  await expect(ledger.locator(":scope > li")).toHaveCount(17);
  const more = page.getByRole("button", { name: "Cargar más juegos" });
  // Reaching the end of the list never reads a page by itself.
  await more.scrollIntoViewIfNeeded();
  await page.mouse.wheel(0, 4000);
  await expect(more).toContainText("Puestos 21–40");
  expect(requested).toEqual(["igdb_players:1"]);

  await more.focus();
  await page.keyboard.press("Enter");
  await expect(ledger.locator(":scope > li")).toHaveCount(37);
  await expect(page.getByRole("link", { name: "Puesto 21: Hades 21" })).toBeFocused();
  await expect(page.getByRole("status").filter({ hasText: "Mostrando" })).toHaveText("Mostrando 40 de 45 juegos");
  await expect(more).toContainText("Puestos 41–45");

  await more.click();
  await expect(ledger.locator(":scope > li")).toHaveCount(42);
  await expect(page.getByText("Has llegado al final del TOPómetro")).toBeVisible();
  await expect(more).toHaveCount(0);
  expect(requested).toEqual(["igdb_players:1", "igdb_players:2", "igdb_players:3"]);
});

test("switches source through the URL, restarts at the podium and keeps history", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const requested = await stubRanking(page);
  await page.goto("/topometro");
  await expect(page.getByRole("list", { name: "Podio" })).toBeVisible();

  await page.getByRole("link", { name: "Comunidad Gameómetro" }).click();
  await expect(page).toHaveURL(/\/topometro\?source=gameometro$/);
  await expect(page.getByRole("link", { name: "Comunidad Gameómetro" })).toHaveAttribute("aria-current", "true");
  await expect(page.getByRole("list", { name: "Podio" }).getByText("Ardiendo").first()).toBeVisible();
  await expect(page.getByRole("list", { name: "Podio" }).getByRole("link", { name: /Jugadores IGDB/ })).toHaveCount(0);
  await page.goBack();
  await expect(page).toHaveURL(/\/topometro$/);
  await expect(page.getByRole("link", { name: "Jugadores IGDB", exact: true })).toHaveAttribute("aria-current", "true");
  expect(requested).toEqual(["igdb_players:1", "gameometro_community:1"]);
});

test("returns from a game page to the same loaded places and reading position", async ({ page }) => {
  await page.setViewportSize({ width: 1320, height: 900 });
  await stubRanking(page);
  await page.route("**/api/v1/games/game-30", route => route.fulfill({ status: 404, json: { code: "GAME_NOT_FOUND" } }));
  await page.goto("/topometro");
  await page.getByRole("button", { name: "Cargar más juegos" }).click();
  const target = page.getByRole("link", { name: /^Puesto 30: / });
  await target.scrollIntoViewIfNeeded();
  const before = await page.evaluate(() => scrollY);
  await target.click();
  await expect(page).toHaveURL(/\/games\/game-30\/game-30$/);
  await page.getByRole("link", { name: "Volver al TOPómetro" }).click();
  await expect(page).toHaveURL(/\/topometro$/);
  await expect(page.getByRole("link", { name: /^Puesto 30: / })).toBeVisible();
  expect(Math.abs((await page.evaluate(() => scrollY)) - before)).toBeLessThan(80);
});

test("reduced motion keeps the podium still and forced colours keep the places", async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce", forcedColors: "active" });
  await page.setViewportSize({ width: 834, height: 1112 });
  await stubRanking(page);
  await page.goto("/topometro");
  await expect(page.getByRole("list", { name: "Podio" })).toBeVisible();
  expect(await page.evaluate(() => document.getAnimations().length)).toBe(0);
  await expect(page.locator(".podium-numeral").first()).toBeVisible();
  await expect(page.locator(".ranking-podium .podium-step").first()).toHaveCSS("border-top-style", "solid");
});
