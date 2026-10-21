import { expect, test, type Page } from "@playwright/test";

import { analyzeAccessibility } from "./fixtures/accessibility";
import { pragmata, pragmataRelease, releasePage } from "./fixtures/releases";
import { gameDetailsFixture } from "../src/test/game-details-fixture";

/**
 * The Gameómetro identity across the screens migrated in #318, against controlled responses: the
 * window's scale and its needles on the release windows, the canonical title as the wordmark on
 * the game page, the thermal ledger of Mis puntuaciones, and the absence of the former glass,
 * aurora and serif language. Layout and journeys stay with the screens' own specs.
 */

/** Surfaces and text of the former visual language that the migrated screens no longer use. */
async function expectNoLegacyLanguage(page: Page) {
  const legacy = await page.evaluate(() =>
    [...document.querySelectorAll("main *")].flatMap((element) => {
      const style = getComputedStyle(element);
      const found: string[] = [];
      if (style.backdropFilter !== "none" && style.backdropFilter !== "") found.push("glass");
      if (style.fontFamily.includes("Instrument Serif")) found.push("serif");
      return found.map((kind) => `${kind}: ${element.className}`);
    }),
  );
  expect(legacy).toEqual([]);
}

function release(day: number, index: number) {
  const gameId = `30000000-0000-4000-8000-${String(index + 1).padStart(12, "0")}`;
  return {
    ...pragmata,
    gameId,
    slug: `juego-${index + 1}`,
    canonicalTitle: `Juego ${index + 1}`,
    releases: [
      {
        ...pragmataRelease,
        gameId,
        releaseId: `40000000-0000-4000-8000-${String(index + 1).padStart(12, "0")}`,
        releaseDate: { precision: "day" as const, value: `2026-08-${String(day).padStart(2, "0")}` },
      },
    ],
  };
}

for (const width of [390, 1320]) {
  test(`the release explorer's day bar marks the page's release days at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.emulateMedia({ reducedMotion: "reduce" });
    await page.route("**/api/v1/releases?*", (route) =>
      route.fulfill({
        json: releasePage({
          items: [release(12, 0), release(12, 1), release(11, 2)],
          page: { number: 1, size: 12, totalItems: 3, totalPages: 1 },
        }),
      }),
    );
    await page.goto("/lanzamientos");
    await expect(page.getByRole("heading", { level: 1, name: "Explorar lanzamientos" })).toBeVisible();
    await expect(page.locator(".release-period")).toContainText("10–16 ago 2026");

    const scale = page.locator(".day-bar");
    await expect(scale.locator(".window-scale-rail")).toHaveAttribute("aria-hidden", "true");
    // One division per day, the days gone by the API's date lit, one needle per release day.
    await expect(scale.locator(".window-scale-tick")).toHaveCount(7);
    await expect(scale.locator(".window-scale-tick-elapsed")).toHaveCount(4);
    await expect(scale.locator(".window-scale-needle")).toHaveCount(2);
    await expect(scale.locator(".day-bar-day-today small")).toHaveText("hoy");

    // Each card states its own date under the fire needle; the full wording stays for assistive
    // technology in the release line.
    const third = page.getByRole("article").filter({ hasText: "Juego 3" });
    await expect(third.locator(".card-date")).toHaveText("11 ago 2026");
    await expect(third.getByText("11 de agosto de 2026")).toBeAttached();

    // Pointing at a release, or focusing its title, lifts its needle above the others.
    await third.getByRole("link", { name: "Juego 3" }).focus();
    await expect(scale).toHaveClass(/window-scale-pointing/);
    await expect(scale.locator(".window-scale-needle-active")).toHaveCount(1);
    const fire = await scale
      .locator(".window-scale-needle-active")
      .evaluate((needle) => getComputedStyle(needle, "::after").borderTopColor);
    expect(fire).toBe("rgb(255, 106, 61)");
    await page.keyboard.press("Tab");
    await page.keyboard.press("Tab");
    await expect(scale).not.toHaveClass(/window-scale-pointing/);

    await expectNoLegacyLanguage(page);
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  });
}

test("a future range's day bar lights no day and picks one from the keyboard", async ({ page }) => {
  await page.setViewportSize({ width: 834, height: 900 });
  await page.route("**/api/v1/releases?*", (route) => {
    const query = new URL(route.request().url()).searchParams;
    return route.fulfill({
      json: releasePage({
        window: { from: query.get("from") ?? "", to: query.get("to") ?? "" },
        items: [release(20, 0)],
      }),
    });
  });
  await page.goto("/lanzamientos?from=2026-08-14&to=2026-08-27");
  const scale = page.locator(".day-bar");
  await expect(scale.locator(".window-scale-tick")).toHaveCount(14);
  await expect(scale.locator(".window-scale-tick-elapsed")).toHaveCount(0);
  await expect(scale.locator(".window-scale-needle")).toHaveCount(1);

  // One tab stop: the first explored day. Arrows move between days without exploring.
  const days = page.getByRole("toolbar", { name: "Elegir un día del periodo" });
  const first = days.getByRole("button", { name: /^viernes, 14 de agosto de 2026/ });
  await expect(first).toHaveAttribute("tabindex", "0");
  await first.focus();
  await expect(first).toHaveCSS("outline-style", "solid");
  await page.keyboard.press("ArrowRight");
  await expect(days.getByRole("button", { name: /^sábado, 15 de agosto de 2026/ })).toBeFocused();
  await expect(page).toHaveURL(/from=2026-08-14&to=2026-08-27/);
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/from=2026-08-15&to=2026-08-15/);
  await expect(days.getByRole("button", { name: /^sábado, 15 de agosto de 2026/ })).toHaveAttribute(
    "aria-pressed",
    "true",
  );
  expect((await analyzeAccessibility(page)).violations).toEqual([]);
});

for (const width of [390, 1320]) {
  test(`the game page sets the canonical title as the wordmark at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.emulateMedia({ reducedMotion: "reduce" });
    const game = gameDetailsFixture();
    game.canonicalTitle = "Resident Evil Requiem: Edición de prueba";
    await page.route("**/api/v1/games/*", (route) => route.fulfill({ json: game }));
    await page.goto("/games/30000000-0000-4000-8000-000000000005/resident-evil-requiem");

    // The heading keeps the canonical title exactly; the lockup sets the name in capitals and
    // the subtitle the colon introduces on its own line.
    const title = page.getByRole("heading", { level: 1 });
    await expect(title).toHaveText("Resident Evil Requiem: Edición de prueba");
    await expect(title.locator(".game-wordmark-main")).toHaveCSS("text-transform", "uppercase");
    const [main, sub] = await Promise.all([
      title.locator(".game-wordmark-main").boundingBox(),
      title.locator(".game-wordmark-sub").boundingBox(),
    ]);
    expect(sub?.y ?? 0).toBeGreaterThanOrEqual((main?.y ?? 0) + (main?.height ?? 0) - 1);

    // The readings are an instrument row, not boxed panels.
    const community = page.getByRole("region", { name: "Puntuación de la comunidad" });
    await expect(community).toHaveCSS("border-top-width", "0px");
    await expect(community).toHaveCSS("background-color", "rgba(0, 0, 0, 0)");

    await expectNoLegacyLanguage(page);
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  });
}

test("each line of Mis puntuaciones takes its own score's temperature at its edge", async ({ page }) => {
  await page.setViewportSize({ width: 1320, height: 900 });
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.route("**/api/v1/session", (route) =>
    route.fulfill({ json: { authenticated: true, csrfToken: "identity-csrf" } }),
  );
  await page.route(/\/api\/v1\/me\/ratings(\?.*)?$/, (route) =>
    route.fulfill({
      json: {
        items: [
          [1, "Congelado"],
          [9, "Ardiendo"],
        ].map(([value], index) => {
          const gameId = `30000000-0000-4000-8000-00000000000${index + 1}`;
          return {
            game: {
              gameId,
              slug: `juego-${index + 1}`,
              canonicalTitle: `Juego ${index + 1}`,
              primaryCover: {
                kind: "fallback",
                url: "/assets/covers/fallback.svg",
                alternativeText: "Portada no disponible",
                attribution: null,
              },
            },
            personalRating: {
              gameId,
              mode: "direct",
              value,
              contribution: value,
              platforms: [],
              createdAt: "2026-08-13T10:00:00Z",
              updatedAt: "2026-08-13T10:00:00Z",
              entityTag: '"v1"',
            },
          };
        }),
        page: { number: 1, size: 10, totalItems: 2, totalPages: 1 },
      },
    }),
  );
  await page.goto("/mis-puntuaciones");
  const lines = page.locator(".my-rating-card");
  await expect(lines).toHaveCount(2);
  const edges = await lines.evaluateAll((items) =>
    items.map((item) => getComputedStyle(item, "::before").backgroundColor),
  );
  // Freeze for 1 and burn for 9, from the thermal roles; the number stays beside them.
  expect(edges).toEqual(["rgb(223, 244, 255)", "rgb(255, 91, 61)"]);
  await expect(page.getByRole("button", { name: /^Tu puntuación Congelado 1\/10/ })).toBeVisible();
  await expectNoLegacyLanguage(page);
  expect((await analyzeAccessibility(page)).violations).toEqual([]);
});
