import { expect, test, type Page } from "@playwright/test";

import { gameDetailsFixture } from "../src/test/game-details-fixture";
import { analyzeAccessibility } from "./fixtures/accessibility";

const game = gameDetailsFixture();
const gamePath = `/games/${game.gameId}/${game.slug}`;
const ranked = {
  gameId: game.gameId,
  slug: game.slug,
  title: game.canonicalTitle,
  primaryCover: game.primaryCover,
  count: 12,
  ratingEligibility: game.ratingEligibility,
};
const rankedGames = [ranked, ...Array.from({ length: 9 }, (_, index) => ({
  ...ranked,
  gameId: `game-${index + 2}`,
  slug: `game-${index + 2}`,
  title: index === 0
    ? "El juego con un nombre que necesita varias líneas en una pantalla pequeña"
    : `Juego ${index + 2}`,
  count: 11 - index,
}))];

async function stub(page: Page) {
  await page.route("**/api/v1/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/v1/session") return route.fulfill({ json: { authenticated: false } });
    if (path === "/api/v1/web-analytics") return route.fulfill({ json: {} });
    if (path.endsWith("/home-rankings/votes")) return route.fulfill({ json: { items: rankedGames } });
    if (path.endsWith("/home-rankings/visits")) return route.fulfill({ json: { items: rankedGames } });
    if (path === `/api/v1/games/${game.gameId}`) return route.fulfill({ json: game });
    return route.fulfill({ status: 503, json: { code: "CATALOGUE_NOT_READY" } });
  });
}

for (const width of [320, 390, 834, 1320]) {
  test(`both weekly rankings reflow at ${width}px and open the rating without navigating`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 });
    await stub(page);
    await page.goto("/");
    await page.getByRole("button", { name: "Rechazar todo" }).click();
    const section = page.getByRole("region", { name: "Pulso de la semana" });
    const votes = section.getByRole("region", { name: "Más votados" });
    const visits = section.getByRole("region", { name: "Más visitados" });
    await expect(votes.getByText("12 votos")).toBeVisible();
    await expect(visits.getByText("12 visitas")).toBeVisible();
    if (width < 896) {
      await expect(votes.getByRole("listitem")).toHaveCount(3);
      await votes.getByRole("button", { name: "Ver los 10 · Más votados" }).click();
      await expect(visits.getByRole("listitem")).toHaveCount(3);
    }
    await expect(votes.getByRole("listitem")).toHaveCount(10);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    if (width !== 320) await section.screenshot({ path: info.outputPath(`home-rankings-${width}.png`) });
    if (width === 320) expect((await analyzeAccessibility(page)).violations).toEqual([]);

    const stamp = votes.getByRole("button", { name: `Puntuar ${game.canonicalTitle}` });
    await stamp.focus();
    await page.keyboard.press("Enter");
    await expect(page.getByRole("dialog", { name: game.canonicalTitle })).toBeVisible();
    await expect(page).toHaveURL(/\/$/);
    await page.keyboard.press("Escape");
    await expect(stamp).toBeFocused();
  });
}

test("only the separate ranking choice records visible game arrivals, without credentials", async ({ page, baseURL }) => {
  const visits: import("@playwright/test").Request[] = [];
  await stub(page);
  await page.route("**/api/v1/game-detail-visits", (route) => {
    visits.push(route.request());
    return route.fulfill({ status: 204 });
  });
  await page.context().addCookies([{ name: "test-session", value: "ignore", url: baseURL }]);
  await page.addInitScript(() => {
    // Match Chromium on an HTTP origin other than loopback, as used by packaged CI.
    Object.defineProperty(window.crypto, "randomUUID", { value: undefined });
    localStorage.setItem("gameometro_analytics_consent", JSON.stringify({
      version: 1, choice: "granted", decidedAt: new Date().toISOString(),
    }));
  });
  await page.goto(gamePath);
  await expect(page.getByRole("heading", { name: game.canonicalTitle, level: 1 })).toBeVisible();
  expect(visits).toHaveLength(0);
  await page.getByText("Personalizar", { exact: true }).click();
  await page.getByRole("checkbox", { name: /Ranking de visitas/ }).check();
  await page.getByRole("button", { name: "Guardar preferencias" }).click();
  await expect.poll(() => visits.length).toBe(1);
  expect(visits[0]?.postDataJSON()).toEqual({
    gameId: game.gameId,
    visitId: expect.stringMatching(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/),
  });
  expect(await visits[0]?.headerValue("cookie")).toBeNull();

  await page.getByRole("button", { name: "Configurar cookies" }).click();
  await page.getByRole("checkbox", { name: /Ranking de visitas/ }).uncheck();
  await page.getByRole("button", { name: "Guardar preferencias" }).click();
  await page.reload();
  await expect(page.getByRole("heading", { name: game.canonicalTitle, level: 1 })).toBeVisible();
  expect(visits).toHaveLength(1);
});

test("rating from Most visited updates the same game's control in both lists", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await stub(page);
  await page.route("**/api/v1/session", (route) => route.fulfill({ json: { authenticated: true, csrfToken: "csrf", capabilities: [] } }));
  await page.route("**/api/v1/me/ratings?**", (route) => route.fulfill({ json: { items: [] } }));
  let attempts = 0;
  await page.route(`**/api/v1/me/ratings/${game.gameId}`, async (route) => {
    attempts++;
    expect(route.request().method()).toBe("PUT");
    expect(route.request().postDataJSON()).toEqual({ value: 8 });
    expect(await route.request().headerValue("if-none-match")).toBe("*");
    if (attempts === 1) return route.fulfill({ status: 503, json: { code: "SERVICE_OVERLOADED" } });
    return route.fulfill({
      status: 201,
      json: {
        personalRating: {
          gameId: game.gameId,
          mode: "direct",
          value: 8,
          contribution: 8,
          platforms: [],
          createdAt: "2026-11-19T10:00:00Z",
          updatedAt: "2026-11-19T10:00:00Z",
          entityTag: '"version"',
        },
      },
    });
  });
  await page.goto("/");
  await page.getByRole("button", { name: "Rechazar todo" }).click();
  const visits = page.getByRole("region", { name: "Más visitados" });
  await visits.getByRole("button", { name: `Puntuar ${game.canonicalTitle}` }).click();
  const rating = page.getByRole("dialog", { name: game.canonicalTitle });
  const eight = rating.getByRole("button", { name: "8", exact: true });
  await eight.click();
  await expect(rating.getByRole("alert")).toBeVisible();
  await eight.click();
  await expect(rating).toHaveCount(0);
  const rated = page.getByRole("button", { name: /Tu puntuación de .*8\/10\. Cambiar/ });
  await expect(rated).toHaveCount(2);
  await expect(visits.getByRole("button", { name: /Tu puntuación de .*8\/10\. Cambiar/ })).toBeFocused();
  await expect(page).toHaveURL(/\/$/);
});

test("a signed-out rating resumes on the home page after sign-in", async ({ page }) => {
  await stub(page);
  let signedIn = false;
  await page.route("**/api/v1/session", (route) => route.fulfill({
    json: signedIn ? { authenticated: true, csrfToken: "csrf", capabilities: [] } : { authenticated: false },
  }));
  await page.route("**/auth/rating-intent/start?**", async (route) => {
    const params = new URL(route.request().url()).searchParams;
    expect(params.get("returnTo")).toBe("/");
    expect(params.get("gameId")).toBe(game.gameId);
    expect(params.get("value")).toBe("8");
    signedIn = true;
    return route.fulfill({ status: 302, headers: { Location: "/?rating-intent=resumed" } });
  });
  await page.route("**/auth/rating-intent", (route) => route.fulfill({ json: { gameId: game.gameId, value: 8 } }));
  await page.route("**/api/v1/me/ratings?**", (route) => route.fulfill({ json: { items: [] } }));
  await page.route(`**/api/v1/me/ratings/${game.gameId}`, async (route) => {
    if (route.request().method() === "GET") return route.fulfill({ status: 404, json: { code: "RATING_NOT_FOUND" } });
    expect(route.request().postDataJSON()).toEqual({ value: 8 });
    return route.fulfill({
      status: 201,
      json: {
        personalRating: {
          gameId: game.gameId,
          mode: "direct",
          value: 8,
          contribution: 8,
          platforms: [],
          createdAt: "2026-11-19T10:00:00Z",
          updatedAt: "2026-11-19T10:00:00Z",
          entityTag: '"version"',
        },
      },
    });
  });
  await page.goto("/");
  await page.getByRole("button", { name: "Rechazar todo" }).click();
  const votes = page.getByRole("region", { name: "Más votados" });
  await votes.getByRole("button", { name: `Puntuar ${game.canonicalTitle}` }).click();
  await page.getByRole("dialog", { name: game.canonicalTitle }).getByRole("button", { name: "8", exact: true }).click();
  await expect(page.getByText(/Puntuación guardada:.*8\/10/)).toBeVisible();
  await expect(page).toHaveURL(/\/$/);
});
