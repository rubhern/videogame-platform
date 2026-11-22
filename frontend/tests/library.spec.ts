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
};

test("sets a game's state on its page, offers rating and lists it in Mis juegos", async ({ page }) => {
  let state: string | null = null;
  let changedAt = "2026-08-13T10:00:00Z";
  await page.route("**/api/v1/session", (route) => route.fulfill({
    json: { authenticated: true, csrfToken: "browser-proof" },
  }));
  await page.route("**/api/v1/me/wishlist/status?*", (route) => route.fulfill({
    json: { gameIds: state === "wished" ? [game.gameId] : [] },
  }));
  await page.route("**/api/v1/me/library/states?*", (route) => route.fulfill({
    json: { entries: state === null ? [] : [{ gameId: game.gameId, state }] },
  }));
  await page.route(`**/api/v1/me/library/games/${game.gameId}`, async (route) => {
    expect(route.request().headers()["x-csrf-token"]).toBe("browser-proof");
    state = route.request().method() === "DELETE" ? null : (route.request().postDataJSON() as { state: string }).state;
    changedAt = "2026-08-13T10:05:00Z";
    await route.fulfill({ status: 204 });
  });
  await page.route("**/api/v1/me/library?*", (route) => {
    const filter = new URL(route.request().url()).searchParams.get("state");
    const items = state !== null && (filter === null || filter === state)
      ? [{ ...game, state, stateChangedAt: changedAt, released: true }] : [];
    const counts = { total: state === null ? 0 : 1, wished: 0, backlog: 0, playing: 0, completed: 0, abandoned: 0 };
    if (state !== null) counts[state as keyof typeof counts] = 1;
    return route.fulfill({
      json: { items, counts, page: { number: 1, size: 20, totalItems: items.length, totalPages: items.length } },
    });
  });

  await page.goto(`/games/${game.gameId}/${game.slug}`);
  // The state sits under the cover: one press marks Jugando, the caret opens every state.
  const quick = page.getByRole("button", { name: `Jugando: marcar ${game.canonicalTitle}` });
  const cover = page.locator(".game-artwork .cover-figure");
  await expect(quick).toBeVisible();
  const [coverBox, quickBox] = await Promise.all([cover.boundingBox(), quick.boundingBox()]);
  expect(quickBox?.y ?? 0).toBeGreaterThan((coverBox?.y ?? 0) + (coverBox?.height ?? 0) - 1);
  await page.locator(".game-artwork").screenshot({ path: test.info().outputPath("state-quick-1320.png") });
  await quick.click();
  const current = page.getByRole("button", { name: `Jugando: cambiar el estado de ${game.canonicalTitle}` });
  await expect(current).toBeFocused();
  await page.keyboard.press("Enter");
  const dialog = page.getByRole("dialog", { name: "Elige el estado de este juego" });
  await expect(dialog.getByRole("button", { name: "Jugando", pressed: true })).toBeFocused();
  for (const width of [1320, 390]) {
    await page.setViewportSize({ width, height: 900 });
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
    await page.screenshot({ path: test.info().outputPath(`state-dialog-${width}.png`) });
  }
  await dialog.getByRole("button", { name: "Completado" }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByRole("button", { name: `Completado: cambiar el estado de ${game.canonicalTitle}` })).toBeFocused();
  await expect(page.getByRole("status").filter({ hasText: `${game.canonicalTitle}: Completado.` })).toBeAttached();
  // Finishing offers the rating; it never rates the game.
  const offer = page.getByRole("button", { name: "¿Qué te ha parecido? Puntúalo" });
  await offer.click();
  await expect(page.locator("#tu-puntuacion")).toBeFocused();
  for (const width of [1320, 834, 390, 320]) {
    await page.setViewportSize({ width, height: 900 });
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
    await page.locator(".game-artwork").screenshot({ path: test.info().outputPath(`state-button-${width}.png`) });
  }
  await page.setViewportSize({ width: 1320, height: 900 });

  await page.getByRole("button", { name: "Mi cuenta" }).click();
  await page.getByRole("link", { name: "Mis juegos", exact: true }).click();
  await expect(page).toHaveURL(/\/mis-juegos$/);
  const filters = page.getByRole("navigation", { name: "Filtrar por estado" });
  await filters.getByRole("link", { name: /^Completado/ }).click();
  await expect(page).toHaveURL(/\/mis-juegos\?estado=completado$/);
  await expect(filters.getByRole("link", { name: /^Completado/ })).toHaveAttribute("aria-current", "page");
  await expect(page.getByRole("link", { name: game.canonicalTitle, exact: true })).toBeVisible();

  for (const width of [1320, 834, 390, 320]) {
    await page.setViewportSize({ width, height: 900 });
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
    await page.screenshot({ path: test.info().outputPath(`library-${width}.png`), fullPage: true });
  }
  await page.emulateMedia({ forcedColors: "active" });
  const chip = page.getByRole("button", { name: `Completado: cambiar el estado de ${game.canonicalTitle}` });
  await chip.click();
  await page.getByRole("dialog", { name: "Elige el estado de este juego" }).getByRole("button", { name: "Quitar estado" }).click();
  await expect(page.getByRole("heading", { name: "Aún no tienes juegos con estado" })).toBeVisible();
  await expect(page.getByText("0 juegos", { exact: true })).toBeFocused();
});

test("offers only Deseado on the page of a game that has not come out", async ({ page }) => {
  const upcoming = { gameId: "30000000-0000-4000-8000-00000000000c", slug: "marvels-wolverine", title: "Marvel's Wolverine" };
  let state: string | null = null;
  await page.route("**/api/v1/session", (route) => route.fulfill({
    json: { authenticated: true, csrfToken: "browser-proof" },
  }));
  await page.route("**/api/v1/me/wishlist/status?*", (route) => route.fulfill({
    json: { gameIds: state === "wished" ? [upcoming.gameId] : [] },
  }));
  await page.route("**/api/v1/me/library/states?*", (route) => route.fulfill({
    json: { entries: state === null ? [] : [{ gameId: upcoming.gameId, state }] },
  }));
  await page.route(`**/api/v1/me/library/games/${upcoming.gameId}`, async (route) => {
    state = route.request().method() === "DELETE" ? null : (route.request().postDataJSON() as { state: string }).state;
    await route.fulfill({ status: 204 });
  });

  await page.goto(`/games/${upcoming.gameId}/${upcoming.slug}`);
  const quick = page.getByRole("button", { name: `Deseado: marcar ${upcoming.title}` });
  await expect(quick).toBeVisible();
  await expect(page.getByRole("button", { name: `Elegir el estado de ${upcoming.title}` })).toHaveCount(0);
  await page.locator(".game-artwork").screenshot({ path: test.info().outputPath("state-unreleased-1320.png") });
  await quick.click();
  await expect.poll(() => state).toBe("wished");
  await page.getByRole("button", { name: `Deseado: cambiar el estado de ${upcoming.title}` }).click();
  const dialog = page.getByRole("dialog", { name: "Elige el estado de este juego" });
  await expect(dialog.getByRole("button", { name: "Deseado", pressed: true })).toBeFocused();
  await expect(dialog.getByRole("button", { name: "Jugando" })).toHaveCount(0);
  await expect(dialog.getByText("Podrás marcar otros estados cuando el juego salga.")).toBeVisible();
  for (const width of [1320, 390]) {
    await page.setViewportSize({ width, height: 900 });
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
    await page.screenshot({ path: test.info().outputPath(`state-unreleased-dialog-${width}.png`) });
  }
});
