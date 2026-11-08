import { expect, test, type Page } from "@playwright/test";

import { analyzeAccessibility } from "./fixtures/accessibility";

const SWITCH_2 = "10000000-0000-4000-8000-000000000002";
const directory = [
  { platformId: SWITCH_2, name: "Nintendo Switch 2" },
  { platformId: "ps4-id", name: "PlayStation 4" },
  { platformId: "android-id", name: "Android" },
];

const states = ["released", "scheduled", "announced", "delayed"] as const;

function game(index: number) {
  const state = states[index % states.length] ?? "released";
  const releaseDate =
    state === "announced"
      ? { precision: "unknown", value: null }
      : state === "scheduled"
        ? { precision: "quarter", value: "2027-Q1" }
        : { precision: "day", value: `2026-0${(index % 9) + 1}-1${index % 9}` };
  return {
    gameId: `game-${index}`,
    slug: `game-${index}`,
    // Long titles prove the two-line clamp; short ones the alignment of the row.
    canonicalTitle: index % 5 === 0 ? `Una aventura con un título muy largo número ${index}` : `Juego ${index}`,
    primaryCover: { kind: "fallback", url: "/assets/covers/fallback.svg", alternativeText: "Portada oficial no disponible", attribution: null },
    genres: index % 3 === 0 ? [] : [{ genreId: "adventure", name: "Aventura" }, { genreId: "role-playing-rpg", name: "Rol (RPG)" }],
    platformRelease: {
      platform: { platformId: SWITCH_2, name: "Nintendo Switch 2" },
      region: { regionId: "world", name: index % 4 === 1 ? "Europa" : "Mundial" },
      releaseDate,
      status: state,
    },
    scores: index % 2 === 0
      ? {
          community: { status: "available", mean: 8.4, count: 12 },
          external: {
            source: "igdb", kind: "player", value: 7.9, scale: 10, count: 2261,
            attribution: { label: "Jugadores IGDB", sourceUrl: `https://www.igdb.com/games/game-${index}` },
            observedAt: "2026-10-20T08:00:00Z",
          },
        }
      : { community: { status: "available", mean: null, count: 0 } },
  };
}

const genres = [
  "Arcade", "Aventura", "Carreras", "Deportes", "Disparos", "Estrategia", "Indie", "Lucha", "Plataformas",
  "Puzles", "Rol (RPG)", "Simulación",
].map((name) => ({ genreId: name.toLowerCase().replace(/[^a-z]+/g, "-").replace(/^-|-$/g, ""), name }));

async function stubCatalogue(page: Page, total = 60) {
  const requested: URL[] = [];
  await page.route("**/api/v1/session", (route) => route.fulfill({ json: { authenticated: false } }));
  await page.route("**/api/v1/platforms", (route) => route.fulfill({ json: { items: directory } }));
  await page.route("**/api/v1/platform-games?*", (route) => {
    const url = new URL(route.request().url());
    requested.push(url);
    const number = Number(url.searchParams.get("page") ?? "1");
    const size = Number(url.searchParams.get("pageSize") ?? "24");
    const start = (number - 1) * size;
    const items = Array.from({ length: Math.max(0, Math.min(size, total - start)) }, (_, offset) => game(start + offset));
    return route.fulfill({
      json: {
        platforms: [{ platformId: SWITCH_2, name: "Nintendo Switch 2" }],
        availableFilters: { genres, earliestYear: 2025, latestYear: 2027 },
        items,
        page: { number, size, totalItems: total, totalPages: Math.ceil(total / size) },
      },
    });
  });
  return requested;
}

const grid = (page: Page) => page.getByRole("list", { name: "Juegos de Nintendo Switch 2" });

test.beforeEach(async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
});

test("Plataformas leads to a platform's catalogue and a direct load or refresh renders it", async ({ page }) => {
  await page.setViewportSize({ width: 1320, height: 900 });
  await stubCatalogue(page);
  await page.goto("/plataformas");
  const card = page.getByRole("list", { name: "Plataformas destacadas" }).getByRole("article").first();
  await card.getByRole("link", { name: "Explorar catálogo", exact: true }).click();
  await expect(page).toHaveURL(/\/plataformas\/nintendo-switch-2\/catalogo$/);
  await expect(page.getByRole("heading", { level: 1, name: "Explorar catálogo de Nintendo Switch 2" })).toBeVisible();
  await expect(grid(page).getByRole("article")).toHaveCount(24);

  await page.reload();
  await expect(grid(page).getByRole("article")).toHaveCount(24);
  await expect(page.getByRole("navigation", { name: "Principal" }).getByRole("button", { name: "Plataformas" }))
    .toHaveAttribute("aria-current", "true");
});

for (const [width, columns] of [[320, 2], [360, 2], [390, 2], [834, 4], [1320, 6]] as const) {
  test(`reflows the opening, controls and cover grid without overflow at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: width >= 1024 ? 900 : 844 });
    await stubCatalogue(page);
    await page.goto("/plataformas/nintendo-switch-2/catalogo?genreIds=aventura&fromYear=2026");
    await expect(grid(page).getByRole("article")).toHaveCount(24);

    expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBe(0);
    const tops = await grid(page).locator(":scope > li").evaluateAll((cells) =>
      cells.slice(0, 8).map((cell) => Math.round(cell.getBoundingClientRect().top)));
    expect(tops.filter((top) => top === tops[0])).toHaveLength(columns);

    // Every control keeps a comfortable target and the first covers start within the first screen.
    for (const control of [
      page.getByRole("link", { name: "Ya lanzados" }),
      page.getByRole("searchbox", { name: "Buscar por título en Nintendo Switch 2" }),
      page.getByRole("combobox", { name: /Ordenar por/ }),
      page.getByRole("button", { name: /^Filtros/ }),
      page.getByRole("button", { name: "Cambiar plataforma" }),
    ]) {
      const box = await control.boundingBox();
      expect(box?.height ?? 0).toBeGreaterThanOrEqual(38);
      expect((box?.x ?? -1) >= 0 && (box?.x ?? 0) + (box?.width ?? 0) <= width).toBe(true);
    }
    const firstCover = await grid(page).locator(".cover-figure").first().boundingBox();
    expect(firstCover?.y ?? Infinity).toBeLessThan(width >= 1024 ? 480 : 620);

    // Cards in one row share their title row, whatever the title's length.
    const titleTops = await grid(page).locator(".card-title").evaluateAll((titles, count) =>
      titles.slice(0, count).map((title) => Math.round(title.getBoundingClientRect().top)), columns);
    expect(new Set(titleTops).size).toBe(1);

    const results = await analyzeAccessibility(page);
    expect(results.violations).toEqual([]);
  });
}

test("works by keyboard: state, search, filters and pages live in the URL", async ({ page }) => {
  await page.setViewportSize({ width: 1320, height: 900 });
  const requested = await stubCatalogue(page);
  await page.goto("/plataformas/nintendo-switch-2/catalogo");
  await expect(grid(page).getByRole("article")).toHaveCount(24);

  await page.getByRole("link", { name: "Próximos", exact: true }).focus();
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/\?releaseState=upcoming$/);
  await expect.poll(() => requested.at(-1)?.searchParams.get("sort")).toBe("release_date_asc");

  const search = page.getByRole("searchbox", { name: "Buscar por título en Nintendo Switch 2" });
  await search.focus();
  await page.keyboard.type("zelda");
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/\?q=zelda&releaseState=upcoming$/);

  const filters = page.getByRole("button", { name: /^Filtros/ });
  await filters.focus();
  await page.keyboard.press("Enter");
  await expect(filters).toHaveAttribute("aria-expanded", "true");
  const aventura = page.getByRole("region", { name: "Filtros avanzados" }).getByRole("button", { name: "Aventura" });
  await aventura.focus();
  await expect(aventura).toBeFocused();
  await page.keyboard.press("Space");
  await expect(page).toHaveURL(/genreIds=aventura/);
  await expect(aventura).toHaveAttribute("aria-pressed", "true");
  await expect(page.getByRole("button", { name: "Filtros, 1 activo" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Quitar el género Aventura" })).toBeVisible();

  await page.getByRole("button", { name: /^Ver \d+ juegos$/ }).click();
  await expect(filters).toHaveAttribute("aria-expanded", "false");

  await page.getByRole("link", { name: /Página siguiente/ }).click();
  await expect(page).toHaveURL(/page=2/);
  await expect(page.getByRole("heading", { level: 2, name: "Juegos de Nintendo Switch 2" })).toBeFocused();
  await page.goBack();
  await expect(page).not.toHaveURL(/page=2/);
});

test("switches platform keeping the selection and Escape closes the list", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await stubCatalogue(page);
  await page.goto("/plataformas/nintendo-switch-2/catalogo?releaseState=released&q=mario");
  const trigger = page.getByRole("button", { name: "Cambiar plataforma" });
  await trigger.click();
  await expect(trigger).toHaveAttribute("aria-expanded", "true");
  await expect(page.getByRole("link", { name: "Nintendo Switch 2" }).last()).toHaveAttribute("aria-current", "page");
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBe(0);
  await page.keyboard.press("Escape");
  await expect(trigger).toHaveAttribute("aria-expanded", "false");
  await expect(trigger).toBeFocused();

  await trigger.click();
  await page.getByRole("link", { name: "PlayStation 4" }).click();
  await expect(page).toHaveURL(/\/plataformas\/ps4-id\/catalogo\?q=mario&releaseState=released$/);
});

test("returns from a game page to the same selection and reading position", async ({ page }) => {
  await page.setViewportSize({ width: 1320, height: 900 });
  await stubCatalogue(page);
  await page.route("**/api/v1/games/game-31", (route) => route.fulfill({ status: 404, json: { code: "GAME_NOT_FOUND" } }));
  await page.goto("/plataformas/nintendo-switch-2/catalogo?page=2&genreIds=aventura");
  const target = grid(page).getByRole("link", { name: "Juego 31", exact: true });
  await target.scrollIntoViewIfNeeded();
  const before = await page.evaluate(() => scrollY);
  await target.click();
  await expect(page).toHaveURL(/\/games\/game-31\/game-31$/);
  await page.getByRole("link", { name: "Volver al catálogo" }).click();
  await expect(page).toHaveURL(/\/plataformas\/nintendo-switch-2\/catalogo\?page=2&genreIds=aventura$/);
  await expect(grid(page).getByRole("link", { name: "Juego 31", exact: true })).toBeVisible();
  expect(Math.abs((await page.evaluate(() => scrollY)) - before)).toBeLessThan(80);
});

test("reduced motion keeps the page still and forced colours keep the choices", async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce", forcedColors: "active" });
  await page.setViewportSize({ width: 834, height: 1112 });
  await stubCatalogue(page);
  await page.goto("/plataformas/nintendo-switch-2/catalogo?releaseState=upcoming");
  await expect(grid(page).getByRole("article")).toHaveCount(24);
  expect(await page.evaluate(() => document.getAnimations().length)).toBe(0);
  await expect(page.getByRole("link", { name: "Próximos", exact: true })).toHaveCSS("outline-style", "solid");
});
