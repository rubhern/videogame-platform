import { expect, test, type Locator, type Page } from "@playwright/test";

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
  return {
    source,
    minimumRatings: source === "igdb_players" ? 50 : 1,
    items,
    page: { number: page, size, totalItems: TOTAL, totalPages: Math.ceil(TOTAL / size) },
  };
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
      return [...item.querySelectorAll(".ranking-reading, .podium-title, .platform-marks, .quick-rate-stamp")]
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

/** A signed-in player who has rated the second game; saves answer as the API would (#574). */
async function stubPlayer(page: Page) {
  const saved = new Map<string, number>([["game-2", 7]]);
  const personal = (gameId: string, value: number) => ({
    gameId, mode: "direct", value, contribution: value, platforms: [],
    createdAt: "2026-11-01T10:00:00Z", updatedAt: "2026-11-01T10:00:00Z", entityTag: `"${gameId}-${value}"`,
  });
  await stubRanking(page);
  await page.route("**/api/v1/session", route => route.fulfill({ json: { authenticated: true, csrfToken: "csrf" } }));
  await page.route("**/api/v1/me/ratings?*", route => {
    const ids = new URL(route.request().url()).searchParams.getAll("gameIds");
    const items = ids.filter(gameId => saved.has(gameId)).map(gameId => ({
      game: {
        gameId, slug: gameId, canonicalTitle: gameId,
        primaryCover: { kind: "fallback", url: "/assets/covers/fallback.svg", alternativeText: "Portada", attribution: null },
      },
      personalRating: personal(gameId, saved.get(gameId) ?? 1),
      genres: [],
      ratingSummary: { status: "unavailable" },
    }));
    return route.fulfill({ json: { items, page: { number: 1, size: ids.length, totalItems: items.length, totalPages: 1 } } });
  });
  await page.route("**/api/v1/me/ratings/*", async route => {
    const gameId = new URL(route.request().url()).pathname.split("/").at(-1) ?? "";
    const { value } = route.request().postDataJSON() as { value: number };
    saved.set(gameId, value);
    return route.fulfill({
      json: {
        personalRating: personal(gameId, value),
        ratingStatistics: { status: "available", mean: value, count: 1, distribution: {}, platforms: [] },
      },
    });
  });
}

/** The dialog fits the screen and the page never scrolls sideways; phones rest it on the bottom edge. */
async function expectFits(page: Page, dialog: Locator, width: number) {
  const box = await dialog.boundingBox();
  if (box === null) throw new Error("The dialog has no box");
  expect(box.x).toBeGreaterThanOrEqual(0);
  expect(box.x + box.width).toBeLessThanOrEqual(width + 0.5);
  if (width < 620) expect(Math.round(box.y + box.height)).toBe(800);
  expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
  return box;
}

for (const width of [320, 390, 1320]) {
  test(`scores a game in one press from its stamp at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 800 });
    // Measure the settled card, not its entrance.
    await page.emulateMedia({ reducedMotion: "reduce" });
    await stubPlayer(page);
    await page.goto("/topometro?source=gameometro");

    await expect(page.getByText("Juegos ya lanzados con al menos 1 puntuación de la comunidad.")).toBeVisible();
    await expect(page.getByRole("button", { name: /Tu puntuación de Persona 5 Royal 2: .*7\/10/ })).toBeVisible();
    const stamp = page.getByRole("button", { name: /^Puntuar Hades 1$/ });
    await stamp.click();

    const card = page.getByRole("dialog", { name: "Hades 1" });
    await expect(card).toBeVisible();
    const box = await expectFits(page, card, width);
    if (width >= 620) {
      // Wide up the card stands beside the stamp that opened it, on the same line.
      const anchor = await stamp.boundingBox();
      if (anchor === null) throw new Error("The stamp has no box");
      expect(box.x + box.width).toBeLessThanOrEqual(anchor.x);
      expect(box.y).toBeLessThan(anchor.y + anchor.height / 2);
      expect(box.y + box.height).toBeGreaterThan(anchor.y + anchor.height / 2);
    }
    await expect(card.getByRole("button", { name: "1", exact: true })).toBeFocused();
    expect((await analyzeAccessibility(page)).violations).toEqual([]);

    // One press saves and closes: the stamp holds the score and takes focus back.
    await card.getByRole("button", { name: "9", exact: true }).click();
    await expect(page.getByRole("dialog")).toHaveCount(0);
    await expect(page.getByRole("button", { name: /Tu puntuación de Hades 1: .*9\/10/ })).toBeFocused();
  });

  test(`rates a run of games from the list without leaving it at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 800 });
    await page.emulateMedia({ reducedMotion: "reduce" });
    await stubPlayer(page);
    await page.goto("/topometro?source=gameometro");

    await expect(page.getByRole("button", { name: /Tu puntuación de Persona 5 Royal 2: .*7\/10/ })).toBeVisible();
    await page.getByRole("button", { name: "Puntuar varios" }).click();

    const sheet = page.getByRole("dialog", { name: "Hades 1" });
    await expect(sheet).toBeVisible();
    await expectFits(page, sheet, width);
    await expect(sheet.getByRole("button", { name: "1", exact: true })).toBeFocused();
    expect((await analyzeAccessibility(page)).violations).toEqual([]);

    // One press saves and the sheet stays on the game, its new score in place.
    await sheet.getByRole("button", { name: "9", exact: true }).click();
    await expect(sheet.getByRole("button", { name: "9", exact: true })).toHaveAttribute("aria-pressed", "true");
    await expect(sheet.getByRole("status")).toHaveText("");

    await sheet.getByRole("button", { name: /^Siguiente/ }).click();
    await expect(page.getByRole("dialog", { name: "Persona 5 Royal 2" })).toBeVisible();

    await page.keyboard.press("Escape");
    await expect(page.getByRole("dialog")).toHaveCount(0);
    await expect(page.getByRole("button", { name: /Tu puntuación de Persona 5 Royal 2: .*7\/10/ })).toBeFocused();
    await expect(page.getByRole("button", { name: /Tu puntuación de Hades 1: .*9\/10/ })).toBeVisible();
  });
}
