import { expect, test, type Page } from "@playwright/test";

import { analyzeAccessibility } from "./fixtures/accessibility";

const PS5 = "10000000-0000-4000-8000-000000000001";
const directory = [
  { platformId: PS5, name: "PlayStation 5" },
  { platformId: "ps4-id", name: "PlayStation 4" },
  { platformId: "meta-quest-3-id", name: "Meta Quest 3" },
];

const releaseDates = [
  { precision: "day", value: "2027-02-12" },
  { precision: "day", value: "2026-10-28" },
  { precision: "quarter", value: "2027-Q1" },
  { precision: "month", value: "2026-11" },
] as const;

function fallbackArt(title: string) {
  return {
    kind: "fallback",
    presentation: "fill",
    url: "/assets/featured/fallback.svg",
    compactUrl: "/assets/featured/fallback.svg",
    alternativeText: `Imagen destacada no disponible de ${title}`,
    attribution: null,
  };
}

/** A platform's horizon: up to ten games, long titles, every date precision that qualifies. */
function horizon(platformId: string, count: number) {
  const name = directory.find((platform) => platform.platformId === platformId)?.name ?? "PlayStation 5";
  const items = [];
  for (let position = 1; position <= count; position++) {
    const title = position % 4 === 2
      ? `Una aventura esperada con un título muy largo para esta plataforma ${position}`
      : `Juego esperado ${position}`;
    items.push({
      position,
      gameId: `game-${position}`,
      slug: `game-${position}`,
      canonicalTitle: title,
      primaryCover: { kind: "fallback", url: "/assets/covers/fallback.svg", alternativeText: "Portada oficial no disponible", attribution: null },
      featuredImage: fallbackArt(title),
      genres: [{ genreId: "rpg", name: "Rol" }, { genreId: "adventure", name: "Aventura" }],
      platformRelease: {
        platform: { platformId, name },
        region: { regionId: "world", name: "Mundial" },
        releaseDate: releaseDates[position % releaseDates.length],
        status: "scheduled",
      },
    });
  }
  return {
    platforms: [{ platformId, name }],
    evaluatedOn: "2026-10-27",
    window: { from: "2026-10-27", to: "2027-04-26" },
    selection: count === 0 ? { status: "no_qualifying_releases" } : { status: "ranked", popularityFreshness: "fresh", popularityObservedAt: "2026-10-26T05:00:00Z" },
    items,
  };
}

async function stubHighlights(page: Page, count = 10) {
  const requested: URL[] = [];
  await page.route("**/api/v1/session", (route) => route.fulfill({ json: { authenticated: false } }));
  await page.route("**/api/v1/platforms", (route) => route.fulfill({ json: { items: directory } }));
  await page.route("**/api/v1/platform-upcoming-highlights?*", (route) => {
    const url = new URL(route.request().url());
    requested.push(url);
    return route.fulfill({ json: horizon(url.searchParams.get("platformIds") ?? PS5, count) });
  });
  return requested;
}

const ranking = (page: Page, name = "PlayStation 5") =>
  page.getByRole("list", { name: `Los más esperados en ${name}` });

test.beforeEach(async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
});

test("Plataformas leads to a platform's Próximos destacados and a reload renders it", async ({ page }) => {
  await page.setViewportSize({ width: 1320, height: 900 });
  const requested = await stubHighlights(page);
  await page.goto("/plataformas");
  const card = page.getByRole("list", { name: "Plataformas destacadas" }).getByRole("article").nth(1);
  await card.getByRole("link", { name: "Próximos destacados" }).click();

  await expect(page).toHaveURL(/\/plataformas\/playstation-5\/proximos$/);
  await expect(page.getByRole("heading", { level: 1, name: "Próximos destacados de PlayStation 5" })).toBeVisible();
  await expect(ranking(page).locator(":scope > li")).toHaveCount(10);
  expect(requested.at(-1)?.searchParams.getAll("platformIds")).toEqual([PS5]);

  await page.reload();
  await expect(ranking(page).locator(":scope > li")).toHaveCount(10);
  await expect(page.getByRole("navigation", { name: "Principal" }).getByRole("button", { name: "Plataformas" }))
    .toHaveAttribute("aria-current", "true");
});

for (const width of [320, 390, 834, 1320]) {
  test(`the opening, horizon and ranking reflow without overflow at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: width >= 1024 ? 900 : 844 });
    await stubHighlights(page);
    await page.goto("/plataformas/playstation-5/proximos");

    const list = ranking(page);
    await expect(list.locator(":scope > li")).toHaveCount(10);
    expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);

    // The horizon reads its window and lands only the exact days as needles; a month or quarter
    // spans its period and is never pinned to a day.
    await expect(page.locator(".upcoming-window-range")).toHaveText("27 oct 2026 – 26 abr 2027");
    await expect(page.locator(".upcoming-horizon-needle")).toHaveCount(5);
    await expect(page.locator(".upcoming-horizon-span")).toHaveCount(5);
    const rail = await page.locator(".upcoming-horizon-rail").boundingBox();
    expect((rail?.x ?? -1) >= 0 && (rail?.x ?? 0) + (rail?.width ?? 0) <= width).toBe(true);

    // Every place keeps one target: its title, whose pointer target covers the place.
    for (const entry of await list.locator(":scope > li").all()) {
      await expect(entry.getByRole("link")).toHaveCount(1);
      const box = await entry.boundingBox();
      expect(box?.height ?? 0).toBeGreaterThanOrEqual(44);
      expect((box?.x ?? -1) >= 0 && (box?.x ?? 0) + (box?.width ?? 0) <= width).toBe(true);
    }
    const cta = page.getByRole("link", { name: "Ver todos los próximos juegos" });
    await expect(cta).toHaveAttribute("href", "/plataformas/playstation-5/catalogo?releaseState=upcoming");
    expect((await cta.boundingBox())?.height ?? 0).toBeGreaterThanOrEqual(44);
    await expect(page.getByRole("button", { name: "Cambiar plataforma" })).toBeVisible();

    expect((await analyzeAccessibility(page)).violations).toEqual([]);
    await page.screenshot({ path: testInfo.outputPath(`platform-upcoming-${width}.png`), fullPage: true });
  });
}

test("keyboard focus lifts the game's arrival on the horizon and returns from its page", async ({ page }) => {
  await page.setViewportSize({ width: 1320, height: 900 });
  await stubHighlights(page);
  await page.route("**/api/v1/games/game-2", (route) => route.fulfill({ status: 404, json: { code: "GAME_NOT_FOUND" } }));
  await page.goto("/plataformas/playstation-5/proximos");
  await expect(ranking(page).locator(":scope > li")).toHaveCount(10);

  const second = page.getByRole("link", { name: /^Una aventura esperada con un título muy largo para esta plataforma 2$/ });
  await second.focus();
  await expect(second).toBeFocused();
  await expect(page.locator(".upcoming-horizon-scale")).toHaveAttribute("data-active", "2");
  // The second place is known only to its quarter, so its span lifts, never a day's needle.
  await expect(page.locator(".upcoming-horizon-span[data-active]")).toHaveCount(1);
  await expect(page.locator(".upcoming-horizon-needle[data-active]")).toHaveCount(0);

  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/\/games\/game-2\/game-2$/);
  await page.getByRole("link", { name: "Volver a Próximos destacados" }).click();
  await expect(page).toHaveURL(/\/plataformas\/playstation-5\/proximos$/);
  await expect(ranking(page).locator(":scope > li")).toHaveCount(10);
});

test("fewer games show fewer places and an empty horizon offers the platform's upcoming games", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await stubHighlights(page, 3);
  await page.goto("/plataformas/playstation-5/proximos");
  await expect(ranking(page).locator(":scope > li")).toHaveCount(3);

  await page.unrouteAll({ behavior: "ignoreErrors" });
  await stubHighlights(page, 0);
  await page.getByRole("button", { name: "Cambiar plataforma" }).click();
  await page.getByRole("link", { name: "PlayStation 4" }).click();
  await expect(page).toHaveURL(/\/plataformas\/ps4-id\/proximos$/);
  await expect(page.getByRole("heading", {
    level: 2,
    name: "No conocemos próximos lanzamientos de PlayStation 4 con fecha en los próximos seis meses",
  })).toBeVisible();
  await expect(page.locator(".upcoming-horizon-scale")).toHaveCount(0);
  await expect(page.getByRole("link", { name: "Ver todos los próximos juegos" }))
    .toHaveAttribute("href", "/plataformas/ps4-id/catalogo?releaseState=upcoming");
  expect((await analyzeAccessibility(page)).violations).toEqual([]);
});

test("reduced motion keeps everything still and forced colours keep the numerals and the horizon", async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce", forcedColors: "active" });
  await page.setViewportSize({ width: 834, height: 1112 });
  await stubHighlights(page);
  await page.goto("/plataformas/meta-quest-3-id/proximos");
  await expect(page.getByRole("heading", { level: 1, name: "Próximos destacados de Meta Quest 3" })).toBeVisible();
  await expect(ranking(page, "Meta Quest 3").locator(":scope > li")).toHaveCount(10);
  expect(await page.evaluate(() => document.getAnimations().length)).toBe(0);
  await expect(page.locator(".upcoming-rank").first()).toHaveCSS("-webkit-text-stroke-width", "0px");
  await expect(page.locator(".upcoming-horizon-rail")).toBeVisible();
});

for (const width of [320, 390, 834, 1320]) {
  test(`platform shortcuts and three-way navigation preserve context and history at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 900 });
    await stubHighlights(page);
    // These existing pages still expose navigation during an unavailable read.
    await page.route("**/api/v1/platform-games?*", route => route.fulfill({ status: 503, json: { code: "CATALOGUE_NOT_READY" } }));
    await page.route("**/api/v1/game-rankings?*", route => route.fulfill({ status: 503, json: { code: "CATALOGUE_NOT_READY" } }));
    await page.goto("/plataformas");
    if (width < 720) await page.getByRole("button", { name: "Menú", exact: true }).click();
    else await page.getByRole("button", { name: "Plataformas", exact: true }).click();
    const main = page.getByRole("navigation", { name: "Principal", exact: true });
    await expect(main.getByRole("link", { name: "Nintendo Switch 2", exact: true })).toHaveAttribute("href", "/plataformas/nintendo-switch-2/catalogo");
    await expect(main.getByRole("link", { name: "Xbox Series X|S", exact: true })).toHaveAttribute("href", "/plataformas/xbox-series-x-s/catalogo");
    await expect(main.getByRole("link", { name: "Windows PC", exact: true })).toHaveAttribute("href", "/plataformas/windows-pc/catalogo");
    await expect(main.getByRole("link", { name: "Ver todas las plataformas" })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBe(0);
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
    await page.screenshot({ path: testInfo.outputPath(`platform-header-${width}.png`), fullPage: false });
    await main.getByRole("link", { name: "PlayStation 5", exact: true }).click();
    await expect(page).toHaveURL(/\/plataformas\/playstation-5\/catalogo$/);
    const nav = page.getByRole("navigation", { name: "Navegación de PlayStation 5", exact: true });
    await expect(nav.getByRole("link", { name: "Catálogo", exact: true })).toHaveAttribute("aria-current", "page");
    await nav.getByRole("link", { name: "TOPómetro", exact: true }).click();
    await expect(page).toHaveURL(/\/plataformas\/playstation-5\/topometro$/);
    await expect(nav.getByRole("link", { name: "TOPómetro", exact: true })).toHaveAttribute("aria-current", "page");
    await nav.getByRole("link", { name: "Próximos destacados", exact: true }).click();
    await expect(ranking(page).locator(":scope > li")).toHaveCount(10);
    await expect(nav.getByRole("link", { name: "Próximos destacados", exact: true })).toHaveAttribute("aria-current", "page");
    await expect(page.getByRole("navigation", { name: "Ruta de navegación" })).toHaveCount(0);
    await expect(page.getByText(/Ordenados por el Hype/)).toHaveCount(0);
    for (const link of await nav.getByRole("link").all()) expect((await link.boundingBox())?.height ?? 0).toBeGreaterThanOrEqual(44);
    await page.reload();
    await expect(nav.getByRole("link", { name: "Próximos destacados", exact: true })).toHaveAttribute("aria-current", "page");
    await page.goBack();
    await expect(nav.getByRole("link", { name: "TOPómetro", exact: true })).toHaveAttribute("aria-current", "page");
    await page.goForward();
    await expect(nav.getByRole("link", { name: "Próximos destacados", exact: true })).toHaveAttribute("aria-current", "page");
    await page.getByRole("button", { name: "Cambiar plataforma" }).click();
    await page.getByRole("link", { name: "Meta Quest 3", exact: true }).click();
    const otherNav = page.getByRole("navigation", { name: "Navegación de Meta Quest 3", exact: true });
    await otherNav.getByRole("link", { name: "Catálogo", exact: true }).click();
    await expect(page).toHaveURL(/\/plataformas\/meta-quest-3-id\/catalogo$/);
  });
}
