import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";
import { gameDetailsFixture } from "../src/test/game-details-fixture";

const gamePath =
  "/games/30000000-0000-4000-8000-000000000005/resident-evil-requiem";

test("public game details follow a real search result through the packaged API and PostgreSQL", async ({
  page,
}) => {
  const external: string[] = [];
  page.on("request", (request) => {
    if (new URL(request.url()).hostname.includes("igdb"))
      external.push(request.url());
  });
  await page.goto("/search?q=resident+evil");
  const link = page.getByRole("link", { name: "Ver Resident Evil Requiem" });
  await expect(link).toBeVisible();
  await link.focus();
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(new RegExp(gamePath + "$"));
  await expect(
    page.getByRole("heading", { level: 1, name: "Resident Evil Requiem" }),
  ).toBeVisible();
  await expect(page.getByRole("main")).toBeFocused();
  // Eligibility is expressed by the enabled inline control, not a separate block.
  await expect(page.getByRole("button", { name: "8", exact: true })).toBeEnabled();
  await expect(page.getByText("Disponible para puntuar")).toHaveCount(0);
  await expect(page.getByText("Sin nota todavía")).toBeVisible();
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  await page.reload();
  await expect(page.getByRole("heading", { level: 1 })).toHaveText(
    "Resident Evil Requiem",
  );
  await page.goBack();
  await expect(
    page.getByRole("link", { name: "Ver Resident Evil Requiem" }),
  ).toBeVisible();
  expect(external).toEqual([]);
});

for (const state of [
  "released",
  "upcoming",
  "uncertain",
  "degraded",
  "missing",
  "not-ready",
  "no-releases",
  "review",
  "error",
  "loading",
] as const) {
  test(`game details: accessible ${state} state at 320px`, async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 740 });
    const game = gameDetailsFixture();
    if (state === "no-releases") {
      game.releases = [];
      game.ratingEligibility = {
        ...game.ratingEligibility,
        eligible: false,
        reason: "NO_COMMERCIAL_RELEASE",
      };
    }
    if (state === "review") {
      game.releases = game.releases.map((r) => ({
        ...r,
        reviewStatus: "required",
        freshnessStatus: "stale",
      }));
      game.ratingEligibility = {
        ...game.ratingEligibility,
        eligible: false,
        reason: "RELEASE_REVIEW_REQUIRED",
      };
    }
    if (state === "upcoming" || state === "uncertain") {
      game.ratingEligibility = {
        eligible: false,
        reason:
          state === "upcoming"
            ? "RELEASE_NOT_OCCURRED"
            : "RELEASE_DATE_UNCERTAIN",
        evaluatedOn: "2026-08-13",
      };
      game.releases = game.releases.map((release) => ({
        ...release,
        releaseDate:
          state === "upcoming"
            ? { precision: "year", value: "2027" }
            : { precision: "unknown", value: null },
        status: "scheduled",
        freshnessStatus: "stale",
      }));
    }
    if (state === "degraded")
      game.ratingStatistics = {
        status: "unavailable",
        reasonCode: "RATING_STATISTICS_READ_FAILED",
      };
    await page.route("**/api/v1/games/*", async (route) => {
      if (state === "loading") return;
      if (state === "error")
        return route.fulfill({ status: 500, json: { code: "INTERNAL_ERROR" } });
      if (state === "missing" || state === "not-ready") {
        await route.fulfill({
          status: state === "missing" ? 404 : 503,
          contentType: "application/problem+json",
          body: JSON.stringify({
            code:
              state === "missing" ? "GAME_NOT_FOUND" : "CATALOGUE_NOT_READY",
          }),
        });
      } else await route.fulfill({ json: game });
    });
    await page.goto(gamePath);
    await expect(page.getByRole("heading", { level: 1 })).toHaveText(
      state === "missing"
        ? "Juego no encontrado"
        : state === "not-ready"
          ? "El catálogo todavía no está disponible"
          : state === "loading"
            ? "Detalle del juego"
            : state === "error"
              ? "No se pudo cargar el juego"
              : game.canonicalTitle,
    );
    expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
    expect(
      await page.evaluate(
        () =>
          document.documentElement.scrollWidth <=
          document.documentElement.clientWidth,
      ),
    ).toBe(true);
    await page.getByRole("link", { name: "Volver a lanzamientos" }).focus();
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(/\/$/);
  });
}

for (const width of [390, 834, 1320]) {
  test(`detail composition and keyboard context selection at ${width}px`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width, height: 1000 });
    const game = gameDetailsFixture();
    const original = game.releases[0];
    if (!original) throw new Error("Fixture needs a release");
    game.releases.push(
      {
        ...original,
        releaseId: "pc-eu",
        platform: { platformId: "pc", name: "Windows PC" },
        releaseDate: { precision: "quarter", value: "2027-Q2" },
        status: "scheduled",
      },
      {
        ...original,
        releaseId: "pc-world",
        platform: { platformId: "pc", name: "Windows PC" },
        region: { regionId: "world", name: "Worldwide" },
        releaseDate: { precision: "unknown", value: null },
        freshnessStatus: "stale",
        reviewStatus: "required",
      },
    );
    game.ratingStatistics = {
      status: "available",
      count: 2,
      mean: 8.5,
      distribution: {
        "1": 0,
        "2": 0,
        "3": 0,
        "4": 0,
        "5": 0,
        "6": 0,
        "7": 0,
        "8": 1,
        "9": 1,
        "10": 0,
      },
    };
    await page.route("**/api/v1/games/*", (route) =>
      route.fulfill({ json: game }),
    );
    await page.goto(gamePath);
    const ps5 = page.getByRole("radio", { name: "PlayStation 5" });
    await ps5.focus();
    await page.keyboard.press("ArrowRight");
    await expect(page.getByRole("radio", { name: "Windows PC" })).toBeChecked();
    await expect(page.getByText("2.º trimestre de 2027")).toBeVisible();
    await page.getByRole("radio", { name: "Europa" }).focus();
    await page.keyboard.press("ArrowRight");
    await expect(page.getByRole("radio", { name: "Mundial" })).toBeChecked();
    await expect(page.getByText("Fecha por confirmar")).toBeVisible();
    await expect(page).toHaveURL(/platformId=pc&regionId=world$/);
    await expect(page.getByLabel("Nota media: 8,5 de 10")).toBeVisible();
    await expect(page.getByRole("table")).toHaveCount(0);
    await expect(page.getByText("Lanzamientos y evidencia")).toHaveCount(0);
    await page.evaluate(() => document.fonts.ready);
    expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBe(true);
    if (width === 1320) {
      const cover = await page.locator(".game-artwork").boundingBox();
      const title = await page.getByRole("heading", { level: 1 }).boundingBox();
      const score = await page.locator(".game-community-score").boundingBox();
      if (!cover || !title || !score)
        throw new Error("Detail composition must be visible");
      expect(cover.width).toBeGreaterThan(350);
      expect(cover.x).toBeLessThan(title.x);
      expect(score.y).toBeGreaterThan(cover.y + cover.height);
    }
    if (width === 390) {
      const selectors = await page.locator(".game-context-selectors").boundingBox();
      const cover = await page.locator(".game-artwork").boundingBox();
      if (!selectors || !cover) throw new Error("Mobile context must be visible");
      expect(selectors.y).toBeLessThan(cover.y);
    }
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({
      path: testInfo.outputPath(`details-${width}.png`),
      fullPage: true,
    });
    await page.reload();
    await expect(page.getByRole("radio", { name: "Mundial" })).toBeChecked();
    await page.goBack();
    await expect(page.getByRole("radio", { name: "Europa" })).toBeChecked();
    await expect(page.getByText("2.º trimestre de 2027")).toBeVisible();
    await page.emulateMedia({
      forcedColors: "active",
      reducedMotion: "reduce",
    });
    await page.getByRole("radio", { name: "Mundial" }).focus();
    await page.keyboard.press("Space");
    await expect(page.getByRole("radio", { name: "Mundial" })).toBeChecked();
  });
}
