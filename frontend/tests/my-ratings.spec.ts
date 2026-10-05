import { expect, test, type Locator, type Page, type Route } from "@playwright/test";

import { analyzeAccessibility } from "./fixtures/accessibility";

/**
 * `Mis puntuaciones` maintenance against a scripted same-origin BFF.
 *
 * <p>Like `inline-rating.spec.ts`, this runs without Keycloak: it scripts `/api/v1/session`, the
 * personal collection and the conditional rating commands at the network boundary. The real
 * Keycloak and ETag-conflict journey lives in `rating-boundary.spec.ts`. This spec owns what a
 * component test cannot prove: the editor opens over the row without moving it, at every
 * reference width, and works by touch.
 */

type Rating = { gameId: string; value: number; createdAt: string; updatedAt: string; entityTag: string };

const titles = [
  "Élite Dangerous",
  "The Legend of Zelda: Tears of the Kingdom — Edición Coleccionista",
  "Hades II",
];

function problem(route: Route, status: number, code: string) {
  return route.fulfill({
    status,
    contentType: "application/problem+json",
    body: JSON.stringify({ code, correlationId: "corr-browser" }),
  });
}

/** A scripted BFF holding one user's ratings, newest first. */
class ScriptedCollection {
  readonly ratings = titles.map((title, index) => ({
    title,
    rating: {
      gameId: `30000000-0000-4000-8000-00000000000${index + 1}`,
      value: [7, 3, 10][index] ?? 5,
      createdAt: "2026-08-13T10:00:00Z",
      updatedAt: "2026-08-13T10:00:00Z",
      entityTag: '"version-1"',
    } as Rating,
  }));
  readonly listReads: URL[] = [];
  readonly commands: { method: string; ifMatch: string | undefined }[] = [];

  constructor(private readonly page: Page) {}

  async install() {
    await this.page.route("**/api/v1/session", (route) =>
      route.fulfill({ json: { authenticated: true, csrfToken: "browser-csrf" } }),
    );
    await this.page.route(/\/api\/v1\/me\/ratings(\?.*)?$/, (route) => {
      const url = new URL(route.request().url());
      this.listReads.push(url);
      const items = this.ratings.map(({ title, rating }) => ({
        game: {
          gameId: rating.gameId,
          slug: title.toLowerCase().replace(/[^a-z0-9]+/g, "-"),
          canonicalTitle: title,
          genres: [{ genreId: "action", name: "Acción" }, { genreId: "rpg", name: "Rol" }],
          primaryCover: {
            kind: "fallback",
            url: "/assets/covers/fallback.svg",
            alternativeText: "Portada no disponible",
            attribution: null,
          },
        },
        personalRating: rating,
        ratingSummary: { status: "available", mean: 8.2, count: 1247 },
      }));
      return route.fulfill({
        json: {
          items,
          page: { number: 1, size: Number(url.searchParams.get("pageSize")), totalItems: items.length, totalPages: 1 },
        },
      });
    });
    await this.page.route(/\/api\/v1\/me\/ratings\/[^/?]+$/, async (route) => {
      const request = route.request();
      const headers = await request.allHeaders();
      this.commands.push({ method: request.method(), ifMatch: headers["if-match"] });
      const gameId = new URL(request.url()).pathname.split("/").at(-1);
      const index = this.ratings.findIndex(({ rating }) => rating.gameId === gameId);
      const entry = this.ratings[index];
      if (headers["x-csrf-token"] !== "browser-csrf") return problem(route, 403, "CSRF_VALIDATION_FAILED");
      if (!entry || headers["if-match"] !== entry.rating.entityTag) return problem(route, 412, "RATING_WRITE_CONFLICT");
      const statistics = { status: "available", mean: 8, count: 1,
        distribution: { "1": 0, "2": 0, "3": 0, "4": 0, "5": 0, "6": 0, "7": 0, "8": 1, "9": 0, "10": 0 } };
      if (request.method() === "DELETE") {
        this.ratings.splice(index, 1);
        return route.fulfill({ json: { personalRating: null, ratingStatistics: statistics } });
      }
      const { value } = request.postDataJSON() as { value: number };
      entry.rating = { ...entry.rating, value, updatedAt: "2026-08-14T10:00:00Z", entityTag: '"version-2"' };
      return route.fulfill({ json: { personalRating: entry.rating, ratingStatistics: statistics } });
    });
  }
}

const card = (page: Page, title: string) => page.getByRole("article", { name: title });
const score = (row: Locator) => row.getByRole("button", { name: /^Tu puntuación/ });

/** Where the row's fixed parts sit: the editor must leave all of them untouched. */
async function geometry(page: Page) {
  const first = card(page, titles[0] ?? "");
  const boxes = await Promise.all([
    first.boundingBox(),
    first.getByRole("heading").boundingBox(),
    first.locator(".cover-figure").boundingBox(),
    score(first).boundingBox(),
    card(page, titles[1] ?? "").boundingBox(),
  ]);
  return boxes.map((box) => box && [box.x, box.y, box.width, box.height].map(Math.round));
}

test.describe("Mis puntuaciones", () => {
  test.use({ colorScheme: "dark" });

  test("the score opens its editor over the row without moving it, at every reference width", async ({
    page,
  }, testInfo) => {
    await page.emulateMedia({ reducedMotion: "reduce" });
    const backend = new ScriptedCollection(page);
    await backend.install();
    await page.goto("/mis-puntuaciones");
    await expect(score(card(page, titles[0] ?? ""))).toBeVisible();
    expect(backend.listReads.map((url) => url.searchParams.get("pageSize"))).toEqual(["10"]);
    await expect(page.getByRole("button", { name: "Actualizar resultados" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Editar puntuación" })).toHaveCount(0);

    for (const width of [1320, 834, 390, 320]) {
      await page.setViewportSize({ width, height: 900 });
      const first = card(page, titles[0] ?? "");
      await expect(first.getByLabel("Géneros: Acción · Rol")).toBeVisible();
      await expect(first.getByLabel("Comunidad")).toContainText("8,2/10");
      await expect(first.getByLabel("Comunidad").getByRole("button")).toHaveCount(0);
      await expect(first.getByText("Ver ficha →")).toHaveCount(0);
      const title = first.getByRole("link", { name: titles[0], exact: true });
      await title.focus();
      await page.keyboard.press("Shift+Tab");
      await page.keyboard.press("Tab");
      await expect(title).toBeFocused();
      await expect(title).toHaveCSS("outline-style", "solid");
      await page.keyboard.press("Tab");
      await expect(score(first)).toBeFocused();
      const before = await geometry(page);
      await score(first).focus();
      await page.keyboard.press("Enter");
      const editor = first.getByRole("dialog", { name: `Tu puntuación de ${titles[0]}` });
      await expect(editor).toBeVisible();
      await expect(editor.getByRole("button", { name: "7", exact: true })).toBeFocused();
      expect(await geometry(page)).toEqual(before);
      // The panel stays on screen and the page never scrolls sideways.
      const panel = await editor.boundingBox();
      expect(panel?.x).toBeGreaterThanOrEqual(0);
      expect((panel?.x ?? 0) + (panel?.width ?? 0)).toBeLessThanOrEqual(width);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      // Every keypad value keeps a 44px target.
      for (const box of await Promise.all((await editor.locator(".rating-option").all()).map((option) => option.boundingBox()))) {
        expect(Math.min(box?.width ?? 0, box?.height ?? 0)).toBeGreaterThanOrEqual(44);
      }
      await editor.getByRole("button", { name: "9", exact: true }).click();
      await editor.getByRole("button", { name: "Eliminar puntuación" }).click();
      expect((await analyzeAccessibility(page)).violations).toEqual([]);
      await page.screenshot({ path: testInfo.outputPath(`my-ratings-editor-${width}.png`), fullPage: true });
      await page.keyboard.press("Escape");
      await expect(editor).toHaveCount(0);
      await expect(score(first)).toBeFocused();
    }
    expect(backend.commands).toHaveLength(0);
  });

  test.describe("on a touch phone", () => {
    test.use({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true });

    test("taps maintain a rating and the list re-reads itself after each command", async ({ page }) => {
      const backend = new ScriptedCollection(page);
      await backend.install();
      await page.goto("/mis-puntuaciones");
      const first = card(page, titles[0] ?? "");
      await score(first).tap();
      const editor = first.getByRole("dialog");
      await editor.getByRole("button", { name: "4", exact: true }).tap();
      await expect(editor).toHaveAttribute("data-thermal", "cold");
      await expect(editor.getByText("Nueva nota")).toBeVisible();
      await editor.getByRole("button", { name: "Guardar nota" }).tap();
      await expect(score(first)).toHaveAccessibleName(/^Tu puntuación Frío 4\/10/);
      await expect(editor).toHaveCount(0);
      expect(backend.commands).toEqual([{ method: "PUT", ifMatch: '"version-1"' }]);
      await expect.poll(() => backend.listReads.length).toBe(2);

      const second = card(page, titles[1] ?? "");
      await score(second).tap();
      await second.getByRole("button", { name: "Eliminar puntuación" }).tap();
      expect(backend.commands).toHaveLength(1);
      await second.getByRole("button", { name: "Sí, eliminar" }).tap();
      await expect(second).toHaveCount(0);
      expect(backend.commands[1]).toEqual({ method: "DELETE", ifMatch: '"version-1"' });
      await expect.poll(() => backend.listReads.length).toBe(3);
      await expect(page.getByRole("heading", { name: "Resultados de mis puntuaciones" })).toBeFocused();
    });
  });
});
