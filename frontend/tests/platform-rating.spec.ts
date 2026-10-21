import { expect, test, type Page } from "@playwright/test";

import { analyzeAccessibility } from "./fixtures/accessibility";
import { gameDetailsFixture } from "../src/test/game-details-fixture";

/**
 * Rating by platform (#359) against a scripted same-origin BFF: the modal sheet from the compact
 * reading, its keyboard path and focus return, and the community's per-platform readings, at a
 * desktop and a phone width. Component tests own the command semantics; this spec owns what only
 * a browser shows: the top layer, layout, reflow and automated accessibility.
 */

const gameId = "30000000-0000-4000-8000-000000000005";
const gamePath = `/games/${gameId}/resident-evil-requiem`;
const ps5 = { platformId: "10000000-0000-4000-8000-000000000001", name: "PlayStation 5" };
const switch2 = { platformId: "10000000-0000-4000-8000-000000000002", name: "Nintendo Switch 2" };

function game(platforms: { platform: typeof ps5; mean: number; count: number }[]) {
  const details = gameDetailsFixture();
  const [first] = details.releases;
  if (first === undefined) throw new Error("The fixture has a release");
  details.releases = [
    { ...first, platform: ps5 },
    { ...first, releaseId: "release-two", platform: switch2 },
  ];
  details.ratingStatistics = {
    status: "available",
    mean: 8.8,
    count: 2,
    distribution: { "1": 0, "2": 0, "3": 0, "4": 0, "5": 0, "6": 0, "7": 0, "8": 0, "9": 2, "10": 0 },
    platforms,
  };
  return details;
}

const readings = [
  { platform: ps5, mean: 9, count: 1 },
  { platform: switch2, mean: 8, count: 1 },
];

/** Signed in, without a rating yet; the command is recorded and answered as the API would. */
async function install(page: Page, details = game(readings)) {
  const commands: unknown[] = [];
  await page.route("**/api/v1/session", (route) =>
    route.fulfill({ json: { authenticated: true, csrfToken: "browser-csrf" } }),
  );
  await page.route("**/api/v1/games/*", (route) => route.fulfill({ json: details }));
  await page.route(`**/api/v1/me/ratings/${gameId}`, async (route) => {
    const request = route.request();
    if (request.method() === "GET") {
      return route.fulfill({
        status: 404,
        contentType: "application/problem+json",
        body: JSON.stringify({ code: "RATING_NOT_FOUND" }),
      });
    }
    commands.push(request.postDataJSON());
    return route.fulfill({
      status: 201,
      headers: { ETag: '"rating-version-1"' },
      json: {
        personalRating: {
          gameId,
          mode: "platforms",
          value: null,
          contribution: 9,
          platforms: [{ platform: ps5, value: 9 }],
          createdAt: "2026-08-13T10:00:00Z",
          updatedAt: "2026-08-13T10:00:00Z",
          entityTag: '"rating-version-1"',
        },
        ratingStatistics: details.ratingStatistics,
      },
    });
  });
  return commands;
}

const overflow = (page: Page) =>
  page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);

for (const { name, width, height } of [
  { name: "desktop", width: 1320, height: 900 },
  { name: "tablet", width: 834, height: 900 },
  { name: "phone", width: 390, height: 844 },
  { name: "small phone", width: 320, height: 740 },
]) {
  test(`${name}: rates by platform from the keyboard in a modal sheet and returns focus`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height });
    const commands = await install(page);
    await page.goto(gamePath);
    const reading = page.getByRole("button", { name: /^Tu puntuación/ });
    await expect(reading).toHaveAccessibleName(/sin puntuación/);

    await reading.focus();
    await page.keyboard.press("Enter");
    await page.getByRole("button", { name: "Puntuar por plataforma" }).focus();
    await page.keyboard.press("Enter");

    const sheet = page.getByRole("dialog", { name: "Puntuar por plataforma" });
    await expect(sheet).toBeVisible();
    // The anchored panel gives way to the modal sheet, which opens on the first platform.
    await expect(page.getByRole("dialog", { name: /^Tu puntuación de / })).toHaveCount(0);
    await expect(sheet.getByRole("checkbox", { name: "PlayStation 5" })).toBeFocused();
    // Measure the settled sheet, after its short entrance.
    await page.evaluate(() =>
      Promise.all(document.getAnimations().map((animation) => animation.finished.catch(() => undefined))),
    );
    expect(await overflow(page)).toBeLessThanOrEqual(0);
    const box = await sheet.boundingBox();
    expect(box?.height ?? Infinity).toBeLessThanOrEqual(height);
    if (width < 620) expect(box?.width).toBe(width);

    await page.keyboard.press("Space");
    await page.keyboard.press("Tab");
    await expect(sheet.getByRole("button", { name: "Puntuar PlayStation 5" })).toBeFocused();
    await page.keyboard.press("Enter");
    const scale = sheet.getByRole("group", { name: "Nota en PlayStation 5 del 1 al 10" });
    await scale.getByRole("button", { name: "1", exact: true }).focus();
    // Arrows browse without choosing; Enter chooses and hands focus back to the platform.
    for (let step = 0; step < 8; step += 1) await page.keyboard.press("ArrowRight");
    await expect(scale.getByRole("button", { name: "9", exact: true })).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(
      sheet.getByRole("button", { name: "Nota en PlayStation 5: 9 de 10. Cambiar" }),
    ).toBeFocused();
    await expect(sheet.getByText("9/10")).toBeVisible();

    const scan = await analyzeAccessibility(page);
    expect(scan.violations).toEqual([]);

    await sheet.getByRole("button", { name: "Guardar", exact: true }).click();
    await expect(sheet).toHaveCount(0);
    expect(commands).toEqual([{ platforms: [{ platformId: ps5.platformId, value: 9 }] }]);
    await expect(reading).toBeFocused();
    await expect(reading).toHaveAccessibleName(/9\/10, nota de 1 plataforma/);
  });

  test(`${name}: keeps the community's platform readings secondary and the page compact`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height });
    await install(page, game([]));
    await page.goto(gamePath);
    const scores = page.locator(".game-scores");
    await expect(page.getByText(/^Basada en/)).toBeVisible();
    const without = (await scores.boundingBox())?.height ?? 0;

    await page.unrouteAll({ behavior: "ignoreErrors" });
    await install(page);
    await page.reload();
    const toggle = page.getByRole("button", { name: "Por plataforma" });
    await expect(toggle).toBeVisible();
    const withReadings = (await scores.boundingBox())?.height ?? 0;
    // One quiet line at most: the initial page stays as compact as before.
    expect(withReadings - without).toBeLessThanOrEqual(40);

    await toggle.click();
    const panel = page.getByRole("dialog", { name: /^Puntuación de la comunidad por plataforma/ });
    await expect(panel).toBeFocused();
    await expect(panel.getByRole("listitem")).toHaveCount(2);
    await expect(panel).toContainText("cuenta una sola vez");
    await page.evaluate(() =>
      Promise.all(document.getAnimations().map((animation) => animation.finished.catch(() => undefined))),
    );
    const panelBox = await panel.boundingBox();
    expect(panelBox).not.toBeNull();
    if (panelBox === null) throw new Error("The community panel must have a visible box");
    expect(panelBox.x).toBeGreaterThanOrEqual(16);
    expect(panelBox.x + panelBox.width).toBeLessThanOrEqual(width - 16);
    expect(await overflow(page)).toBeLessThanOrEqual(0);
    const scan = await analyzeAccessibility(page);
    expect(scan.violations).toEqual([]);

    await page.keyboard.press("Escape");
    await expect(panel).toHaveCount(0);
    await expect(toggle).toBeFocused();
  });
}
