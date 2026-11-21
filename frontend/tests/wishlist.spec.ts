import { expect, test } from "@playwright/test";
import { analyzeAccessibility } from "./fixtures/accessibility";

const game = {
  gameId: "30000000-0000-4000-8000-000000000005",
  canonicalTitle: "Resident Evil Requiem",
  slug: "resident-evil-requiem",
  primaryCover: {
    kind: "fallback", url: "/assets/covers/fallback.svg",
    alternativeText: "Portada oficial no disponible", attribution: null,
  },
  timing: "released",
  releaseDate: { precision: "day", value: "2026-02-27" },
};

test("saves from a game, opens the private list and removes by keyboard", async ({ page }) => {
  let saved = false;
  await page.route("**/api/v1/session", (route) => route.fulfill({
    json: { authenticated: true, csrfToken: "browser-proof" },
  }));
  await page.route("**/api/v1/me/wishlist/status?*", (route) =>
    route.fulfill({ json: { gameIds: saved ? [game.gameId] : [] } }));
  await page.route(`**/api/v1/me/wishlist/games/${game.gameId}`, async (route) => {
    expect(route.request().headers()["x-csrf-token"]).toBe("browser-proof");
    expect(["PUT", "DELETE"]).toContain(route.request().method());
    saved = route.request().method() === "PUT";
    await route.fulfill({ status: 204 });
  });
  await page.route("**/api/v1/me/wishlist?*", (route) => route.fulfill({
    json: {
      items: saved ? [game] : [],
      page: { number: 1, size: 10, totalItems: saved ? 1 : 0, totalPages: saved ? 1 : 0 },
    },
  }));

  await page.goto(`/games/${game.gameId}/${game.slug}`);
  const heading = page.getByRole("heading", { level: 1, name: game.canonicalTitle, exact: true });
  const wish = heading.getByRole("button", { name: `La quiero: ${game.canonicalTitle}` });
  await expect(wish).toHaveClass(/wishlist-button-hero/);
  await expect(wish).toHaveText("");
  await expect(wish).toHaveAttribute("aria-pressed", "false");
  await wish.focus();
  await page.keyboard.press("Space");
  await expect(wish).toHaveAttribute("aria-pressed", "true");
  await page.getByRole("button", { name: "Mi cuenta" }).click();
  const menuWishlist = page.getByRole("link", { name: "Mis deseados", exact: true });
  const profileIcon = page.getByRole("link", { name: "Mi perfil", exact: true }).locator("svg");
  const accountColor = await profileIcon.evaluate((icon) => getComputedStyle(icon).color);
  await expect(menuWishlist.locator(".wishlist-mark")).toHaveCSS("color", accountColor);
  await menuWishlist.click();
  await expect(page).toHaveURL(/\/mis-deseados$/);
  await expect(page.getByRole("link", { name: game.canonicalTitle, exact: true })).toBeVisible();

  for (const width of [1320, 834, 390, 320]) {
    await page.setViewportSize({ width, height: 900 });
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
    await page.screenshot({ path: test.info().outputPath(`wishlist-${width}.png`), fullPage: true });
  }
  await page.emulateMedia({ forcedColors: "active" });
  const remove = page.getByRole("button", { name: `Quitar de mis deseados: ${game.canonicalTitle}` });
  await remove.focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("heading", { name: "Aún no has guardado juegos" })).toBeVisible();
  await expect(page.getByText("0 juegos", { exact: true })).toBeFocused();
});

test("keeps wishlist filtering private from calendar counts to the explorer day", async ({ page }) => {
  await page.route("**/api/v1/session", (route) => route.fulfill({
    json: { authenticated: true, csrfToken: "browser-proof" },
  }));
  await page.route("**/api/v1/me/platform-preferences", (route) => route.fulfill({
    json: { configured: true, platforms: [] },
  }));
  await page.route("**/api/v1/me/wishlist/status?*", (route) =>
    route.fulfill({ json: { gameIds: new URL(route.request().url()).searchParams.getAll("gameIds") } }));

  const forwarded = [
    ["/me/wishlist/release-calendar", "/release-calendar"],
    ["/me/wishlist/releases", "/releases"],
  ] as const;
  for (const [privatePath, publicPath] of forwarded) {
    await page.route(`**/api/v1${privatePath}?*`, async (route) => {
      const source = new URL(route.request().url());
      const reply = await page.request.get(`/api/v1${publicPath}${source.search}`);
      await route.fulfill({ status: reply.status(), json: await reply.json() });
    });
  }
  const isPrivate = (response: { url(): string }, resource: string) =>
    new URL(response.url()).pathname === `/api/v1/me/wishlist/${resource}`;

  await page.goto("/lanzamientos/calendario?month=2026-09&day=2026-09-25");
  const picked = page.waitForResponse((response) => isPrivate(response, "release-calendar"));
  const filter = page.getByRole("checkbox", { name: "Solo mis deseados" });
  await filter.click();
  await expect(filter).toBeChecked();
  expect((await picked).ok()).toBe(true);
  await expect(page).toHaveURL(/wishedOnly=true/);
  const day = page.getByRole("link", { name: /Ver todos los lanzamientos del día/ });
  await expect(day).toHaveAttribute("href", /wishedOnly=true/);
  const explorer = page.waitForResponse((response) => isPrivate(response, "releases")
    && new URL(response.url()).searchParams.get("pageSize") === "12");
  await day.click();
  expect((await explorer).ok()).toBe(true);
  await expect(page.getByRole("checkbox", { name: "Solo mis deseados" })).toBeChecked();
  await expect(page.getByRole("list", { name: "Explorar lanzamientos", exact: true })).toBeVisible();
  await page.unrouteAll({ behavior: "wait" });
});


test("drags wishlist games, reorders by keyboard and keeps the first three highlighted", async ({ page }) => {
  let items = [
    game,
    { ...game, gameId: "silksong", slug: "silksong", canonicalTitle: "Hollow Knight: Silksong" },
    { ...game, gameId: "sunken-city", slug: "sunken-city", canonicalTitle: "A Long Journey Through The Sunken City",
      timing: "upcoming", releaseDate: { precision: "month", value: "2027-04" } },
    { ...game, gameId: "night", slug: "night", canonicalTitle: "Keeper of the Night", timing: "undated",
      releaseDate: { precision: "unknown", value: null } },
  ];
  await page.route("**/api/v1/session", (route) => route.fulfill({
    json: { authenticated: true, csrfToken: "browser-proof" },
  }));
  await page.route("**/api/v1/me/wishlist?*", (route) => route.fulfill({
    json: { items, page: { number: 1, size: 10, totalItems: 4, totalPages: 1 } },
  }));
  await page.route("**/api/v1/me/wishlist/games/*/position", async (route) => {
    expect(route.request().method()).toBe("PUT");
    expect(route.request().headers()["x-csrf-token"]).toBe("browser-proof");
    const id = new URL(route.request().url()).pathname.split("/").at(-2);
    const { anchorGameId, placement } = route.request().postDataJSON() as {
      anchorGameId: string; placement: "before" | "after";
    };
    const selected = items.find((item) => item.gameId === id);
    if (!selected) throw Error("Missing wish in fixture");
    items = items.filter((wish) => wish !== selected);
    const anchor = items.findIndex((item) => item.gameId === anchorGameId);
    expect(anchor).toBeGreaterThanOrEqual(0);
    items.splice(anchor + (placement === "after" ? 1 : 0), 0, selected);
    await route.fulfill({ status: 204 });
  });

  await page.goto("/mis-deseados");
  const list = page.getByRole("list", { name: "Juegos deseados" });
  const top = list.getByRole("heading").first();
  await expect(list.locator("[data-prominent=true]")).toHaveCount(3);
  const moveFrom = page.getByRole("button", { name: "Mover Keeper of the Night" });
  const moveTo = page.getByRole("heading", { name: "Resident Evil Requiem" });
  await moveFrom.scrollIntoViewIfNeeded();
  const from = await moveFrom.boundingBox();
  const to = await moveTo.boundingBox();
  if (!from || !to) throw Error("Missing drag source or destination");
  await page.mouse.move(from.x + from.width / 2, from.y + from.height / 2);
  await page.mouse.down();
  await page.mouse.move(to.x + to.width / 2, to.y + to.height / 2, { steps: 12 });
  await expect(list.locator("[data-wishlist-drop=before]")).toContainText("Resident Evil Requiem");
  await page.mouse.up();
  await expect(top).toHaveText("Keeper of the Night");

  const keyboard = page.getByRole("button", { name: "Mover Hollow Knight: Silksong" });
  await keyboard.focus();
  await page.keyboard.press("Space");
  await page.keyboard.press("ArrowUp");
  await page.keyboard.press("ArrowUp");
  await page.keyboard.press("Space");
  await expect(top).toHaveText("Hollow Knight: Silksong");
  await expect(keyboard).toBeFocused();

  await moveFrom.click();
  await keyboard.click();
  await expect(top).toHaveText("Keeper of the Night");
  await page.reload();
  await expect(top).toHaveText("Keeper of the Night");
  for (const width of [1320, 834, 390, 320]) {
    await page.setViewportSize({ width, height: 900 });
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth - innerWidth),
      { message: `No horizontal overflow at ${width}px` }).toBeLessThanOrEqual(0);
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
    await page.screenshot({ path: test.info().outputPath(`wishlist-order-${width}.png`), fullPage: true });
  }

  await page.setViewportSize({ width: 390, height: 900 });
  const touchSource = await keyboard.boundingBox();
  const touchTarget = await top.boundingBox();
  if (!touchSource || !touchTarget) throw Error("Missing touch source or destination");
  const start = { x: touchSource.x + touchSource.width / 2, y: touchSource.y + touchSource.height / 2 };
  const end = { x: touchTarget.x + touchTarget.width / 2, y: touchTarget.y + touchTarget.height / 2 };
  const cdp = await page.context().newCDPSession(page);
  await cdp.send("Emulation.setTouchEmulationEnabled", { enabled: true, maxTouchPoints: 1 });
  await cdp.send("Input.dispatchTouchEvent", { type: "touchStart", touchPoints: [start] });
  for (let step = 1; step <= 6; step++) {
    await cdp.send("Input.dispatchTouchEvent", { type: "touchMove", touchPoints: [
      { x: start.x + (end.x - start.x) * step / 6, y: start.y + (end.y - start.y) * step / 6 },
    ] });
  }
  await cdp.send("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] });
  await expect(top).toHaveText("Hollow Knight: Silksong");
});


test("appends ten wishes at a time and can drag a newly loaded wish to the top", async ({ page }) => {
  let items = Array.from({ length: 13 }, (_, index) => ({
    ...game, gameId: `wish-${index + 1}`, slug: `wish-${index + 1}`, canonicalTitle: `Juego ${index + 1}`,
  }));
  await page.route("**/api/v1/session", (route) => route.fulfill({
    json: { authenticated: true, csrfToken: "browser-proof" },
  }));
  const requestedPages: number[] = [];
  let failNext = true;
  await page.route("**/api/v1/me/wishlist?*", (route) => {
    const params = new URL(route.request().url()).searchParams;
    const number = Number(params.get("page"));
    expect(params.get("pageSize")).toBe("10");
    requestedPages.push(number);
    if (number === 2 && failNext) {
      failNext = false;
      return route.fulfill({ status: 503, json: { code: "SERVICE_OVERLOADED" } });
    }
    return route.fulfill({ json: {
      items: items.slice((number - 1) * 10, number * 10),
      page: { number, size: 10, totalItems: 13, totalPages: 2 },
    } });
  });
  await page.route("**/api/v1/me/wishlist/games/wish-13/position", async (route) => {
    expect(route.request().postDataJSON()).toEqual({ anchorGameId: "wish-1", placement: "before" });
    const last = items.at(-1);
    if (!last) throw Error("Missing wish");
    items = [last, ...items.slice(0, -1)];
    await route.fulfill({ status: 204 });
  });

  await page.goto("/mis-deseados");
  const list = page.getByRole("list", { name: "Juegos deseados" });
  const more = page.getByRole("button", { name: "Mostrar más", exact: true });
  await expect(list.getByRole("listitem")).toHaveCount(10);
  expect(requestedPages).toEqual([1]);
  await more.click();
  await expect(page.getByRole("alert")).toContainText("No se pudieron cargar más juegos.");
  await expect(list.getByRole("listitem")).toHaveCount(10);
  await more.click();
  await expect(list.getByRole("listitem")).toHaveCount(13);
  await expect(page.getByRole("button", { name: "Mover Juego 11", exact: true })).toBeFocused();
  await expect(more).toHaveCount(0);
  expect(requestedPages).toEqual([1, 2, 2]);
  await expect(page).toHaveURL(/\/mis-deseados$/);

  const source = page.getByRole("button", { name: "Mover Juego 13", exact: true });
  await source.scrollIntoViewIfNeeded();
  const from = await source.boundingBox();
  if (!from) throw Error("Missing source");
  await page.mouse.move(from.x + from.width / 2, from.y + from.height / 2);
  await page.mouse.down();
  await page.mouse.move(from.x + from.width / 2, 24, { steps: 12 });
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(0);

  const top = list.getByRole("heading").first();
  const to = await top.boundingBox();
  if (!to) throw Error("Missing target");
  await page.mouse.move(to.x + to.width / 2, to.y + to.height / 2);
  await expect(list.locator("[data-wishlist-drop=before]")).toContainText("Juego 1");
  await page.mouse.up();
  await expect(top).toHaveText("Juego 13");
  await expect(list.getByRole("listitem")).toHaveCount(13);
  await expect(list.locator("[data-prominent=true]")).toHaveCount(3);
});
