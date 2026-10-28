import { expect, test, type Page } from "@playwright/test";

import { analyzeAccessibility } from "./fixtures/accessibility";
import { reachHomeAfterSignIn } from "./fixtures/sign-in-destination";

const PS5 = { platformId: "10000000-0000-4000-8000-000000000001", name: "PlayStation 5" };
const PC = { platformId: "10000000-0000-4000-8000-000000000003", name: "Windows PC" };
const XBOX = { platformId: "10000000-0000-4000-8000-000000000004", name: "Xbox Series X|S" };
const WORLD = { regionId: "20000000-0000-4000-8000-000000000001", name: "Mundial" };
const LEAD = "Castlevania: Belmont's Curse of the Endless Night";

const fallbackCover = {
  kind: "fallback",
  url: "/assets/covers/fallback.svg",
  alternativeText: "Portada oficial no disponible",
  attribution: null,
};

function release(gameId: string, releaseDate: { precision: string; value: string }, review = false) {
  return {
    releaseId: `release-${gameId}`,
    stage: "full_release",
    gameId,
    platform: PS5,
    region: WORLD,
    releaseDate,
    status: "scheduled",
    provenance: { sourceKind: "external_provider", sourceName: "IGDB", sourceEntityType: "release_date" },
    verificationLevel: "provider_only",
    reviewStatus: review ? "required" : "not_required",
  };
}

/** A week with long titles, a subtitle, a month-precision release and a pending review. */
function featuredItem(id: string, title: string, day: string, extra: Record<string, unknown> = {}) {
  return {
    gameId: id,
    slug: id,
    canonicalTitle: title,
    primaryCover: fallbackCover,
    featuredImage: {
      kind: "fallback",
      presentation: "fill",
      url: "/assets/featured/fallback.svg",
      compactUrl: "/assets/featured/fallback.svg",
      alternativeText: `Imagen destacada no disponible de ${title}`,
      attribution: null,
    },
    releases: [{ ...release(id, { precision: "day", value: day }), platform: PS5 }, { ...release(`${id}-pc`, { precision: "day", value: day }), platform: PC }],
    genres: [{ genreId: "adventure", name: "Aventura" }, { genreId: "hack-and-slash-beat-em-up", name: "Hack and slash" }],
    ...extra,
  };
}

const featured = {
  granularity: "week",
  week: "2026-W44",
  evaluatedOn: "2026-10-28",
  window: { from: "2026-10-26", to: "2026-11-01" },
  navigation: { previous: "2026-W43", next: "2026-W45" },
  selection: { status: "ranked", popularityFreshness: "fresh", popularityObservedAt: "2026-10-27T05:00:00Z" },
  items: [
    featuredItem("lead", LEAD, "2026-10-29", {
      scores: {
        community: { status: "available", mean: 8.1, count: 3 },
        external: { source: "igdb", kind: "player", value: 8.7, scale: 10, count: 1234,
          attribution: { label: "Jugadores IGDB", sourceUrl: "https://www.igdb.com/games/lead" }, observedAt: "2026-10-20T08:00:00Z" },
      },
    }),
    featuredItem("wolf", "The Wolf Among Us Remastered: The Complete Collection Edition", "2026-10-29"),
    featuredItem("queen", "Queen's Domain", "2026-10-30"),
    featuredItem("lizards", "Terrible Lizards", "2026-10-29"),
    featuredItem("nun", "Remothered: Red Nun's Legacy", "2026-10-27"),
  ],
};

const calendar = (month: string) => ({
  month,
  evaluatedOn: "2026-10-28",
  window: month === "2026-10" ? { from: "2026-10-01", to: "2026-10-31" } : { from: "2026-11-01", to: "2026-11-30" },
  navigation: month === "2026-10"
    ? { first: "2026-10", last: "2027-03", next: "2026-11" }
    : { first: "2026-10", last: "2027-03", previous: "2026-10", next: "2026-12" },
  activeFilters: { platformIds: [], regionIds: [] },
  availableFilters: { platforms: [PS5], regions: [WORLD] },
  days: month === "2026-10"
    ? [
      { date: "2026-10-28", gameCount: 1, totalHype: 10 },
      { date: "2026-10-29", gameCount: 5, totalHype: 284 },
      { date: "2026-10-30", gameCount: 1, totalHype: 13 },
    ]
    : [
      { date: "2026-11-03", gameCount: 2, totalHype: 54 },
      { date: "2026-11-05", gameCount: 2, totalHype: 89 },
      { date: "2026-11-10", gameCount: 1, totalHype: 10 },
      { date: "2026-11-12", gameCount: 2, totalHype: 160 },
    ],
});

function dayReleases(day: string, size: number) {
  const titles = ["Phantom Blade Zero", "The Wolf Among Us Remastered", "Atmosfar", "Terrible Lizards"];
  return {
    evaluatedOn: "2026-10-28",
    window: { from: day, to: day },
    activeFilters: { platformIds: [], regionIds: [] },
    availableFilters: { platforms: [PS5, PC, XBOX], regions: [WORLD] },
    items: titles.slice(0, size).map((title, index) => ({
      gameId: `${day}-${index}`,
      slug: `${day}-${index}`,
      canonicalTitle: title,
      primaryCover: fallbackCover,
      releases: [PS5, PC, XBOX].map((platform) => ({ ...release(`${day}-${index}-${platform.name}`, { precision: "day", value: day }), platform })),
    })),
    page: { number: 1, size, totalItems: 52, totalPages: 13 },
  };
}

function ranking(source: string) {
  const titles = ["The Witcher 3: Wild Hunt", "The Legend of Zelda: Breath of the Wild", "Hollow Knight: Silksong"];
  return {
    source,
    items: titles.map((title, index) => ({
      position: index + 1,
      gameId: `ranked-${index + 1}`,
      slug: `ranked-${index + 1}`,
      canonicalTitle: title,
      primaryCover: fallbackCover,
      genres: [{ genreId: "rpg", name: "Rol" }],
      releaseSummary: { platforms: [PS5, PC, XBOX], totalPlatforms: 6, earliestKnownYear: 2015, latestKnownYear: 2026 },
      score: source === "igdb_players"
        ? { source: "igdb", kind: "player", value: 9.4 - index / 10, scale: 10, count: 5494 - index * 1000,
          attribution: { label: "Jugadores IGDB", sourceUrl: `https://www.igdb.com/games/ranked-${index + 1}` }, observedAt: "2026-10-20T08:00:00Z" }
        : { source: "gameometro", kind: "community", value: 10 - index, scale: 10, count: 1 },
    })),
    page: { number: 1, size: 20, totalItems: 3, totalPages: 1 },
  };
}

const directory = [PS5, PC, XBOX, { platformId: "10000000-0000-4000-8000-000000000002", name: "Nintendo Switch 2" },
  { platformId: "ps4-id", name: "PlayStation 4" }, { platformId: "android-id", name: "Android" }];

/** Every read the home composes, from fixtures; one can be replaced to fail. */
async function stubHome(page: Page, options: { session?: object; failFeatured?: boolean } = {}) {
  const requests: string[] = [];
  page.on("request", (request) => {
    const url = new URL(request.url());
    if (url.pathname.startsWith("/api/")) requests.push(url.pathname + url.search);
  });
  await page.route("**/api/v1/session", (route) => route.fulfill({ json: options.session ?? { authenticated: false } }));
  await page.route("**/api/v1/me/platform-preferences", (route) => route.fulfill({ json: { configured: true, platforms: [] } }));
  await page.route("**/api/v1/featured-releases*", (route) =>
    options.failFeatured
      ? route.fulfill({ status: 500, json: { code: "INTERNAL_ERROR", correlationId: "home-featured" } })
      : route.fulfill({ json: featured }));
  await page.route("**/api/v1/release-calendar*", (route) =>
    route.fulfill({ json: calendar(new URL(route.request().url()).searchParams.get("month") ?? "2026-10") }));
  await page.route("**/api/v1/releases?*", (route) => {
    const params = new URL(route.request().url()).searchParams;
    return route.fulfill({ json: dayReleases(params.get("from") ?? "", Number(params.get("pageSize"))) });
  });
  await page.route("**/api/v1/game-rankings?*", (route) =>
    route.fulfill({ json: ranking(new URL(route.request().url()).searchParams.get("source") ?? "igdb_players") }));
  await page.route("**/api/v1/platforms", (route) => route.fulfill({ json: { items: directory } }));
  return requests;
}

const pageFits = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth);

/** No element inside a preview is wider than the page's own column (clipping would hide it). */
const clipped = (page: Page) => page.evaluate(() => [...document.querySelectorAll(".home-page section, .home-page article")]
  .filter((element) => element.getBoundingClientRect().right > innerWidth + 0.5 || element.getBoundingClientRect().left < -0.5)
  .filter((element) => !element.closest(".home-days, .home-day-games, .home-feature-visual, .home-feature-light"))
  .map((element) => element.className || element.tagName));

for (const width of [320, 390, 834, 1320]) {
  test(`the home composes every preview without page overflow at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 900 });
    await page.emulateMedia({ reducedMotion: "reduce" });
    await stubHome(page);
    await page.goto("/");

    await expect(page.getByRole("heading", { level: 1, name: "Descubre tu próximo juego" })).toBeVisible();
    const lead = page.getByRole("article", { name: LEAD });
    await expect(lead.getByRole("heading", { level: 2 })).toHaveAccessibleName(LEAD);
    for (const region of ["Más destacados de la semana", "Calendario de destacados", "TOPómetro", "Explora por plataforma", "Tu criterio. Tu historia."]) {
      await expect(page.getByRole("region", { name: region })).toBeAttached();
    }
    await expect(page.getByRole("list", { name: "Podio de Jugadores IGDB" }).getByRole("article")).toHaveCount(3);
    await expect(page.getByRole("list", { name: /Lanzamientos destacados del/ }).getByRole("article")).toHaveCount(4);

    // The featured release and its actions open in the first view; the next section follows.
    // The release's two ways out sit inside the hero, in the first view.
    await expect(lead.getByRole("link", { name: `Ver ficha de ${LEAD}` })).toBeInViewport();
    await expect(lead.getByRole("link", { name: "Ver destacados" })).toBeInViewport();
    // Destacados's temporal G frames the release (the phone seal below 620px).
    await expect(lead.locator(width < 620 ? ".featured-dial-emblem" : ".featured-dial-g")).toBeVisible();
    expect(await pageFits(page)).toBe(true);
    expect(await clipped(page)).toEqual([]);
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
    await page.screenshot({ path: testInfo.outputPath(`home-${width}.png`), fullPage: true });
  });
}

test("a cold load keeps the home's own loading surface while its reads are pending", async ({ page }) => {
  await page.setViewportSize({ width: 1320, height: 900 });
  await stubHome(page);
  await page.route("**/api/v1/featured-releases*", () => undefined);
  await page.goto("/");

  await expect(page.getByRole("status")).toHaveText("Cargando Gameómetro…");
  await expect(page.getByRole("heading", { level: 1, name: "Descubre tu próximo juego" })).toBeVisible();
  // Never Destacados's surface, and no preview presents data it has not read.
  await expect(page.getByRole("heading", { name: "Destacados de la semana", level: 1 })).toHaveCount(0);
  await expect(page.getByRole("article")).toHaveCount(0);
});

test("sign-in destination waits through the home loading surface and first-run welcome", async ({ page }) => {
  await stubHome(page, { session: { authenticated: true, csrfToken: "csrf", capabilities: [] } });
  let configured = false;
  const preferencesReady = Promise.withResolvers<undefined>();
  await page.route("**/api/v1/me/platform-preferences", async (route) => {
    if (route.request().method() === "PUT") configured = true;
    await preferencesReady.promise;
    await route.fulfill({ json: { configured, platforms: [] } });
  });
  await page.goto("/");

  await expect(page.getByRole("status")).toHaveText("Cargando Gameómetro…");
  let entered = false;
  const destination = reachHomeAfterSignIn(page, new URL(page.url()).origin).then(() => { entered = true; });
  try {
    // Keep the first read pending through a rendered loading frame: its title cannot finish sign-in.
    await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
    expect(entered).toBe(false);
  } finally {
    preferencesReady.resolve(undefined);
  }
  await destination;

  expect(configured).toBe(true);
  await expect(page.getByRole("region", { name: "Tu criterio. Tu historia." })).toBeVisible();
  // A later sign-in on a configured account returns directly, without a welcome choice.
  await page.reload();
  await reachHomeAfterSignIn(page, new URL(page.url()).origin);
});

test("keyboard reaches the featured release, chooses a calendar day and switches the TOPómetro source", async ({ page }) => {
  await page.setViewportSize({ width: 1320, height: 900 });
  await page.emulateMedia({ reducedMotion: "reduce" });
  const requests = await stubHome(page);
  await page.goto("/");

  const primary = page.getByRole("link", { name: `Ver ficha de ${LEAD}` });
  await primary.focus();
  await expect(primary).toHaveCSS("outline-style", "solid");

  const calendar = page.getByRole("region", { name: "Calendario de destacados" });
  await expect(calendar.getByRole("button", { pressed: true })).toHaveAccessibleName(/jueves, 29 de octubre/);
  const november = calendar.getByRole("button", { name: /jueves, 12 de noviembre/ });
  await november.focus();
  await expect(november).toHaveCSS("outline-style", "solid");
  await page.keyboard.press("Enter");
  await expect(november).toHaveAttribute("aria-pressed", "true");
  await expect(calendar.getByRole("heading", { name: "Jueves, 12 de noviembre" })).toBeVisible();
  expect(requests).toContain("/api/v1/releases?from=2026-11-12&to=2026-11-12&sort=hype&page=1&pageSize=2");

  const topometro = page.getByRole("region", { name: "TOPómetro" });
  await topometro.getByRole("button", { name: "Comunidad Gameómetro" }).focus();
  await page.keyboard.press("Space");
  await expect(topometro.getByRole("list", { name: "Podio de Comunidad Gameómetro" })).toBeVisible();
  await expect(topometro.getByText("1 puntuación").first()).toBeVisible();

  // A game opened from the home names the home as its way back.
  await topometro.getByRole("link", { name: "Puesto 1: The Witcher 3: Wild Hunt" }).focus();
  await expect(page.locator(".home-rank").first()).toBeVisible();
});

test("nothing moves under reduced motion and only one entrance runs otherwise", async ({ page }) => {
  await page.setViewportSize({ width: 1320, height: 900 });
  await stubHome(page);
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.goto("/");
  await expect(page.getByRole("article", { name: LEAD })).toBeVisible();
  expect(await page.evaluate(() => document.getAnimations().length)).toBe(0);

  await page.emulateMedia({ reducedMotion: "no-preference" });
  await page.reload();
  await expect(page.getByRole("article", { name: LEAD })).toBeVisible();
  // The entrance is finite: no decorative layer animates perpetually (#209).
  const perpetual = await page.evaluate(() => document.getAnimations()
    .filter((animation) => !(animation.effect?.target instanceof Element && animation.effect.target.classList.contains("skeleton")))
    .filter((animation) => animation.effect?.getComputedTiming().iterations === Infinity).length);
  expect(perpetual).toBe(0);
});

test("one failed preview leaves the rest of the home usable, also in forced colours", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.emulateMedia({ reducedMotion: "reduce" });
  await stubHome(page, { failFeatured: true });
  await page.goto("/");

  const alert = page.getByRole("alert");
  await expect(alert).toContainText("Destacados se ha tomado un respiro");
  await expect(alert).toContainText("home-featured");
  await expect(page.getByRole("list", { name: "Podio de Jugadores IGDB" })).toBeVisible();
  await expect(page.getByRole("region", { name: "Calendario de destacados" }).getByRole("button", { pressed: true })).toBeVisible();
  // As in the other journeys, measure normal-palette contrast before testing forced colours.
  expect((await analyzeAccessibility(page)).violations).toEqual([]);

  await page.emulateMedia({ reducedMotion: "reduce", forcedColors: "active" });
  await expect(page.getByRole("region", { name: "Calendario de destacados" }).getByRole("button", { pressed: true }))
    .toHaveCSS("outline-style", "solid");
  expect(await pageFits(page)).toBe(true);
  expect(await clipped(page)).toEqual([]);
});

test("a signed-in player gets their own pages and no sign-in invitation", async ({ page }) => {
  await page.setViewportSize({ width: 1320, height: 900 });
  await page.emulateMedia({ reducedMotion: "reduce" });
  const requests = await stubHome(page, { session: { authenticated: true, csrfToken: "csrf", capabilities: [] } });
  await page.goto("/");

  const personal = page.getByRole("region", { name: "Tu criterio. Tu historia." });
  await expect(personal.getByRole("link", { name: /Mis puntuaciones/ })).toHaveAttribute("href", "/mis-puntuaciones");
  await expect(personal.getByRole("link", { name: /Tus estadísticas/ })).toHaveAttribute("href", "/mis-puntuaciones/estadisticas");
  await expect(personal.getByRole("link", { name: "Crear cuenta" })).toHaveCount(0);
  expect(requests.filter((path) => path.startsWith("/api/v1/me/") && !path.startsWith("/api/v1/me/platform-preferences"))).toEqual([]);
  expect((await analyzeAccessibility(page)).violations).toEqual([]);
});
