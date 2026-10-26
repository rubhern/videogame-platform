import { expect, test, type Page } from "@playwright/test";

import { analyzeAccessibility } from "./fixtures/accessibility";
import { gameDetailsFixture } from "../src/test/game-details-fixture";

const gamePath =
  "/games/30000000-0000-4000-8000-000000000005/resident-evil-requiem";

const reading = (page: Page) => page.getByRole("button", { name: /^Tu puntuación/ });

test("public game details follow a real search result through the packaged API and PostgreSQL", async ({
  page,
}) => {
  const external: string[] = [];
  page.on("request", (request) => {
    if (new URL(request.url()).hostname.includes("igdb"))
      external.push(request.url());
  });
  await page.goto("/search?q=resident+evil");
  const link = page.getByRole("link", { name: "Resident Evil Requiem", exact: true });
  await expect(link).toBeVisible();
  await link.focus();
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(new RegExp(gamePath + "$"));
  await expect(
    page.getByRole("heading", { level: 1, name: "Resident Evil Requiem" }),
  ).toBeVisible();
  await expect(page.getByRole("main")).toBeFocused();
  // Eligibility is expressed by the enabled personal reading, not a separate block.
  await expect(reading(page)).toBeEnabled();
  await expect(page.getByText("Disponible para puntuar")).toHaveCount(0);
  await expect(page.getByText("Sin puntuación todavía")).toBeVisible();
  await expect(page.getByRole("region", { name: "Fechas y plataformas" })).toBeVisible();
  expect((await analyzeAccessibility(page)).violations).toEqual([]);
  await page.reload();
  await expect(page.getByRole("heading", { level: 1 })).toHaveText(
    "Resident Evil Requiem",
  );
  await page.goBack();
  await expect(
    page.getByRole("link", { name: "Resident Evil Requiem", exact: true }),
  ).toBeVisible();
  expect(external).toEqual([]);
});

for (const state of [
  "released",
  "informed",
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
    if (state === "informed") {
      game.developers = [{ companyId: "dev", name: "A Studio With A Remarkably Long Company Name" }];
      game.publishers = [{ companyId: "pub", name: "Publisher" }];
      game.genres = [
        { genreId: "g1", name: "Hack and slash/Beat 'em up" },
        { genreId: "g2", name: "Role-playing (RPG)" },
      ];
      game.gameModes = [{ gameModeId: "m1", name: "Massively Multiplayer Online (MMO)" }];
    }
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
          ? "Los juegos todavía no están disponibles"
          : state === "loading"
            ? "Detalle del juego"
            : state === "error"
              ? "No se pudo cargar el juego"
              : game.canonicalTitle,
    );
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
    expect(
      await page.evaluate(
        () =>
          document.documentElement.scrollWidth <=
          document.documentElement.clientWidth,
      ),
    ).toBe(true);
    await page.getByRole("link", { name: "Volver a Destacados" }).focus();
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(/\/$/);
  });
}

const longSummary =
  "A long sourced summary in the provider's own language. ".repeat(14) +
  "\n\nIt keeps going so that the compact frame has to offer the whole text on request.";

for (const width of [320, 390, 760, 834, 1024, 1320]) {
  test(`detail composition, calendar and compact rating at ${width}px`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width, height: 1000 });
    const game = gameDetailsFixture();
    const original = game.releases[0];
    if (!original) throw new Error("Fixture needs a release");
    original.stage = "full_release";
    game.summary = {
      kind: "sourced",
      text: longSummary,
      language: "en",
      provenance: { sourceKind: "external_provider", sourceName: "IGDB", sourceEntityType: "game_summary" },
    };
    game.developers = [{ companyId: "capcom", name: "Capcom" }];
    game.publishers = [{ companyId: "capcom", name: "Capcom" }];
    game.genres = [
      { genreId: "adventure", name: "Adventure" },
      { genreId: "shooter", name: "Shooter" },
    ];
    game.gameModes = [{ gameModeId: "single", name: "Single player" }];
    game.releases.push(
      {
        ...original,
        releaseId: "pc-eu",
        stage: "unknown",
        platform: { platformId: "pc", name: "Windows PC" },
        releaseDate: { precision: "quarter", value: "2027-Q2" },
        status: "scheduled",
      },
      {
        ...original,
        releaseId: "pc-world",
        stage: "early_access",
        platform: { platformId: "pc", name: "Windows PC" },
        region: { regionId: "world", name: "Mundial" },
        releaseDate: { precision: "unknown", value: null },
        reviewStatus: "required",
      },
      // A cancelled record of the same platform and region follows the presented one (#212).
      {
        ...original,
        releaseId: "pc-world-cancelled",
        stage: "alpha",
        platform: { platformId: "pc", name: "Windows PC" },
        region: { regionId: "world", name: "Mundial" },
        releaseDate: { precision: "day", value: "2026-05-01" },
        status: "cancelled",
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
      platforms: [],
    };
    await page.route("**/api/v1/games/*", (route) =>
      route.fulfill({ json: game }),
    );
    await page.goto(gamePath);
    await expect(page.getByText("Acceso anticipado")).toBeVisible();
    // A full release is the normal case, so its stage is never named (#341).
    await expect(page.getByText("Lanzamiento completo")).toHaveCount(0);
    await page.evaluate(() => document.fonts.ready);

    // A known special stage reads as quieter secondary text, on the line beneath its date on
    // phones and beside it on a wide ledger.
    const staged = page.locator(".game-release-group:has(.game-release-stage)").first();
    const dateLayout = await staged.evaluate((group) => {
      const date = group.querySelector(".game-release-date");
      const stage = group.querySelector(".game-release-stage dd");
      if (!date || !stage) throw new Error("Release date and stage missing");
      return {
        dateBottom: date.getBoundingClientRect().bottom,
        stageTop: stage.getBoundingClientRect().top,
        dateSize: Number.parseFloat(getComputedStyle(date).fontSize),
        stageSize: Number.parseFloat(getComputedStyle(stage).fontSize),
      };
    });
    if (width < 760) expect(dateLayout.stageTop).toBeGreaterThanOrEqual(dateLayout.dateBottom - 1);
    expect(dateLayout.stageSize).toBeLessThan(dateLayout.dateSize);

    // Every platform and region reads at once; the further record waits, whole and unmerged,
    // behind a keyboard-operable disclosure.
    const calendar = page.getByRole("region", { name: "Fechas y plataformas" });
    await expect(page.getByRole("radio")).toHaveCount(0);
    await expect(calendar.getByText("2.º trimestre de 2027")).toBeVisible();
    await expect(calendar.getByText("Fecha por confirmar")).toBeVisible();
    await expect(calendar.getByText("Acceso anticipado")).toBeVisible();
    await expect(calendar.getByText("1 de mayo de 2026")).toBeHidden();
    await calendar.locator("summary").focus();
    await page.keyboard.press("Enter");
    await expect(calendar.getByText("1 de mayo de 2026")).toBeVisible();
    await expect(calendar.getByText("Cancelado", { exact: true })).toBeVisible();
    await expect(calendar.getByText("Alfa")).toBeVisible();
    await expect(page.getByLabel("Puntuación media: 8,5 de 10")).toBeVisible();
    await expect(page.getByRole("table")).toHaveCount(0);

    // The editorial summary stays compact under the title and opens whole on request.
    const summaryText = page.locator(".game-summary-text");
    const collapsed = await summaryText.boundingBox();
    const more = page.getByRole("button", { name: "Leer más" });
    await expect(more).toHaveAttribute("aria-expanded", "false");
    await more.focus();
    await page.keyboard.press("Enter");
    await expect(page.getByRole("button", { name: "Mostrar menos" })).toHaveAttribute(
      "aria-expanded",
      "true",
    );
    const expanded = await summaryText.boundingBox();
    expect(expanded?.height ?? 0).toBeGreaterThan((collapsed?.height ?? 0) + 40);
    await page.getByRole("button", { name: "Mostrar menos" }).click();

    // The compact rating opens from the keyboard, overlays what follows without moving it, and
    // returns focus to the reading on Escape.
    const following = page.locator(width >= 760 ? ".game-summary" : ".game-information");
    // Document coordinates: focusing the reading may scroll it into view without moving anything.
    const place = () =>
      following.evaluate((element) => {
        const box = element.getBoundingClientRect();
        return { x: box.x + window.scrollX, y: box.y + window.scrollY, width: box.width, height: box.height };
      });
    const before = await place();
    await reading(page).focus();
    await page.keyboard.press("Enter");
    const panel = page.getByRole("dialog", { name: /^Tu puntuación de / });
    await expect(panel).toBeVisible();
    await expect(page.getByRole("button", { name: "1", exact: true })).toBeFocused();
    expect(await place()).toEqual(before);
    const box = await panel.boundingBox();
    if (!box) throw new Error("The keypad panel must be visible");
    expect(box.x).toBeGreaterThanOrEqual(0);
    expect(box.x + box.width).toBeLessThanOrEqual(width);
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
    await page.keyboard.press("Escape");
    await expect(panel).toBeHidden();
    await expect(reading(page)).toBeFocused();

    expect((await analyzeAccessibility(page)).violations).toEqual([]);
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBe(true);

    // Compare the editorial hierarchy and the two levels inside one information card.
    await page.evaluate(() => window.scrollTo(0, 0));
    const cover = await page.locator(".game-artwork").boundingBox();
    const title = await page.getByRole("heading", { level: 1 }).boundingBox();
    const score = await page.getByRole("region", { name: "Puntuación de la comunidad" }).boundingBox();
    const personal = await reading(page).boundingBox();
    const summary = await page.getByRole("region", { name: "Resumen" }).boundingBox();
    const information = await page.getByRole("region", { name: "Información del juego" }).boundingBox();
    const dates = await calendar.boundingBox();
    if (!cover || !title || !score || !personal || !summary || !information || !dates)
      throw new Error("Detail composition must be visible");
    const metadata = await page.locator(".game-facts").boundingBox();
    if (!metadata) throw new Error("Known metadata must be visible");
    expect(dates.x).toBeGreaterThanOrEqual(information.x);
    expect(dates.x + dates.width).toBeLessThanOrEqual(information.x + information.width + 1);
    expect(dates.y + dates.height).toBeLessThan(information.y + information.height + 1);
    if (width >= 1024) {
      // The spec sheet sets the facts beside the release ledger.
      expect(dates.x).toBeGreaterThan(metadata.x + metadata.width);
    } else {
      expect(dates.y).toBeGreaterThan(metadata.y + metadata.height);
    }
    await expect(page.getByRole("region", { name: "Información del juego" })
      .getByRole("heading", { level: 3, name: "Fechas y plataformas" })).toBeVisible();
    for (const role of ["Desarrollador", "Publisher"]) {
      await expect(page.locator(".game-fact").filter({ has: page.locator("dt", { hasText: role }) })
        .locator("dd")).toHaveText("Capcom");
    }
    if (width >= 760) {
      // The cover stands beside the title and the readings' row, which follows the title.
      expect(title.x).toBeGreaterThan(cover.x + cover.width);
      expect(score.x).toBeGreaterThan(cover.x + cover.width);
      expect(score.y).toBeGreaterThan(title.y + title.height);
      // The visitor's reading follows the community's: beside it on the desktop, else beneath.
      if (width >= 1024) expect(personal.x).toBeGreaterThan(score.x + score.width);
      else expect(personal.y).toBeGreaterThan(score.y + score.height);
      expect(summary.y).toBeGreaterThan(Math.max(score.y + score.height, personal.y + personal.height));
      expect(information.y).toBeGreaterThan(Math.max(summary.y + summary.height, cover.y + cover.height));
    }
    if (width >= 1024) {
      // The summary keeps the editorial column beside the cover.
      expect(summary.x).toBeGreaterThan(cover.x + cover.width);
    } else if (width >= 760) {
      // Tablets give the summary the full width below the cover.
      expect(summary.y).toBeGreaterThan(cover.y + cover.height);
    } else {
      // Phones start with the identity, then cover, scores, summary and structured facts.
      expect(cover.y).toBeGreaterThan(title.y + title.height);
      expect(score.y).toBeGreaterThan(cover.y + cover.height);
      expect(personal.y).toBeGreaterThan(score.y + score.height);
      expect(summary.y).toBeGreaterThan(personal.y + personal.height);
      expect(information.y).toBeGreaterThan(summary.y + summary.height);
    }
    // Editorial text has no card surface and keeps its accessible heading and source language.
    expect(await page.locator(".game-summary").evaluate((element) => ({
      border: getComputedStyle(element).borderTopWidth,
      background: getComputedStyle(element).backgroundColor,
    }))).toEqual({ border: "0px", background: "rgba(0, 0, 0, 0)" });
    // The nested calendar uses a divider, with no separate card surface or individual row cards.
    expect(await calendar.evaluate((element) => ({
      background: getComputedStyle(element).backgroundColor,
      radius: getComputedStyle(element).borderRadius,
    }))).toEqual({ background: "rgba(0, 0, 0, 0)", radius: "0px" });
    await expect(summaryText).toHaveAttribute("lang", "en");
    // Opening the narrower identity column still gives every keypad value a usable target.
    await reading(page).click();
    const targets = await panel.locator(".rating-option").evaluateAll((buttons) =>
      buttons.map((button) => ({ width: button.getBoundingClientRect().width, height: button.getBoundingClientRect().height })),
    );
    expect(targets).toHaveLength(10);
    for (const target of targets) {
      expect(target.width).toBeGreaterThanOrEqual(44);
      expect(target.height).toBeGreaterThanOrEqual(44);
    }
    await page.keyboard.press("Escape");
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({
      path: testInfo.outputPath(`details-${width}.png`),
      fullPage: true,
    });
    await page.emulateMedia({
      forcedColors: "active",
      reducedMotion: "reduce",
    });
    await reading(page).focus();
    await page.keyboard.press("Space");
    await expect(panel).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(reading(page)).toBeFocused();
  });
}

for (const width of [320, 390, 760, 834, 1024, 1320]) {
  test(`community, IGDB players and personal readings at ${width}px`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width, height: 1000 });
    const game = gameDetailsFixture();
    game.ratingStatistics = {
      status: "available",
      count: 1,
      mean: 7,
      distribution: { "1": 0, "2": 0, "3": 0, "4": 0, "5": 0, "6": 0, "7": 1, "8": 0, "9": 0, "10": 0 },
      platforms: [],
    };
    game.externalScore = {
      source: "igdb",
      kind: "player",
      value: 7.8,
      scale: 10,
      count: 12345,
      attribution: { label: "Jugadores IGDB", sourceUrl: "https://www.igdb.com/games/resident-evil-requiem" },
      observedAt: "2026-08-01T10:00:00Z",
    };
    await page.route("**/api/v1/games/*", (route) => route.fulfill({ json: game }));
    await page.goto(gamePath);
    const community = page.getByRole("region", { name: "Puntuación de la comunidad" });
    const igdb = page.getByRole("region", { name: "Jugadores IGDB" });
    await expect(igdb.getByLabel("Nota media: 7,8 de 10")).toBeVisible();
    await expect(igdb.getByText(/^Basada en/)).toHaveText("Basada en 12.345 valoraciones");
    await expect(
      igdb.getByRole("link", { name: "Jugadores IGDB: fuente de la puntuación de Resident Evil Requiem" }),
    ).toHaveAttribute("href", "https://www.igdb.com/games/resident-evil-requiem");
    await page.evaluate(() => document.fonts.ready);

    const cover = await page.locator(".game-artwork").boundingBox();
    const score = await community.boundingBox();
    const external = await igdb.boundingBox();
    const personal = await reading(page).boundingBox();
    const summary = await page.getByRole("region", { name: "Resumen" }).boundingBox();
    if (!cover || !score || !external || !personal || !summary)
      throw new Error("The readings must be visible");
    // The community's mean is the product's reading and leads; IGDB's supports it, smaller.
    const sizes = await page.locator(".game-scores .game-reading-value").evaluateAll((nodes) =>
      nodes.map((node) => Number.parseFloat(getComputedStyle(node).fontSize)),
    );
    expect(sizes).toHaveLength(2);
    expect(sizes[0] ?? 0).toBeGreaterThan((sizes[1] ?? 0) * 1.5);
    if (width >= 1024) {
      // Beside the cover the community's reading leads the row; IGDB's and the visitor's own
      // follow in the supporting column beside it, aligned with its top.
      expect(score.x).toBeGreaterThan(cover.x + cover.width);
      expect(external.x).toBeGreaterThan(score.x + score.width);
      expect(Math.abs(external.y - score.y)).toBeLessThanOrEqual(2);
      expect(Math.abs(personal.x - external.x)).toBeLessThanOrEqual(1);
      expect(personal.y).toBeGreaterThan(external.y + external.height);
      expect(summary.y).toBeGreaterThan(Math.max(score.y + score.height, personal.y + personal.height));
    } else {
      // Below the desktop the two sources read side by side, the community's first, with their
      // labels and counts on shared rows; the visitor's own reading follows beneath both.
      if (width >= 760) expect(score.x).toBeGreaterThan(cover.x + cover.width);
      expect(external.x).toBeGreaterThan(score.x + score.width - 1);
      expect(Math.abs(external.y - score.y)).toBeLessThanOrEqual(1);
      const counts = await page.locator(".game-scores .game-reading-description").evaluateAll((nodes) =>
        nodes.map((node) => Math.round(node.getBoundingClientRect().top)),
      );
      expect(new Set(counts).size).toBe(1);
      expect(personal.y).toBeGreaterThan(Math.max(score.y + score.height, external.y + external.height));
      expect(personal.width).toBeGreaterThan(score.width + external.width - 2);
      expect(summary.y).toBeGreaterThan(personal.y + personal.height);
    }

    // The keypad still opens over what follows from the reading below the IGDB card.
    await reading(page).focus();
    await page.keyboard.press("Enter");
    const panel = page.getByRole("dialog", { name: /^Tu puntuación de / });
    await expect(panel).toBeVisible();
    const box = await panel.boundingBox();
    if (!box) throw new Error("The keypad panel must be visible");
    expect(box.x).toBeGreaterThanOrEqual(0);
    expect(box.x + box.width).toBeLessThanOrEqual(width);
    await page.keyboard.press("Escape");
    await expect(reading(page)).toBeFocused();

    expect((await analyzeAccessibility(page)).violations).toEqual([]);
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    ).toBe(true);
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({ path: testInfo.outputPath(`readings-${width}.png`), fullPage: true });
    // Forced colours keep the white mark on its own dark ground, still a visible link.
    await page.emulateMedia({ forcedColors: "active", reducedMotion: "reduce" });
    const mark = igdb.getByRole("link");
    await expect(mark).toBeVisible();
    expect(await mark.evaluate((link) => getComputedStyle(link).backgroundColor)).toBe("rgb(0, 0, 0)");
  });
}

for (const width of [320, 390, 834, 1024, 1320]) {
  test(`long title, short summary and multiple company roles at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 });
    const game = gameDetailsFixture();
    game.canonicalTitle = "The Legend of an Extraordinary Journey: A Very Long Canonical Game Name With Its Complete Edition";
    game.summary = {
      kind: "sourced",
      text: "A short summary, kept in its original language.",
      language: "en",
      provenance: { sourceKind: "external_provider", sourceName: "IGDB", sourceEntityType: "game_summary" },
    };
    game.developers = [
      { companyId: "a", name: "A Development Studio With A Very Long Name" },
      { companyId: "b", name: "Another Development Studio With A Very Long Name" },
      { companyId: "c", name: "A Third Development Studio With A Very Long Name" },
    ];
    game.publishers = [
      { companyId: "d", name: "A Distribution Company With A Very Long Name" },
      { companyId: "e", name: "Another Distribution Company With A Very Long Name" },
    ];
    await page.route("**/api/v1/games/*", route => route.fulfill({ json: game }));
    await page.goto(gamePath);
    await expect(page.getByRole("heading", { level: 1 })).toHaveText(game.canonicalTitle);
    const information = page.getByRole("region", { name: "Información del juego" });
    const calendar = page.getByRole("region", { name: "Fechas y plataformas" });
    await expect(information.getByText("Desarrollador", { exact: true })).toBeVisible();
    await expect(information.getByText("Publisher", { exact: true })).toBeVisible();
    await expect(information.getByText("Géneros", { exact: true })).toHaveCount(0);
    await expect(information.getByText("Modos de juego", { exact: true })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Leer más" })).toHaveCount(0);
    await expect(calendar.locator(".game-release-group")).toHaveCount(1);
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    const metadata = await information.locator(".game-facts").boundingBox();
    const dates = await calendar.boundingBox();
    if (!metadata || !dates) throw new Error("Structured facts must be visible");
    // The release ledger follows the facts: beside them on the desktop spec sheet, else below.
    if (width >= 1024) expect(dates.x).toBeGreaterThan(metadata.x + metadata.width);
    else expect(dates.y).toBeGreaterThan(metadata.y + metadata.height);
    expect(await page.getByRole("heading", { level: 1 }).evaluate(element =>
      Number.parseFloat(getComputedStyle(element).fontSize))).toBeLessThanOrEqual(40);
  });
}

// #325: platforms sharing a whole context read as one calendar line; a distinct status keeps its own.
for (const width of [320, 390, 1320]) {
  test(`grouped calendar and centred cover at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 });
    const game = gameDetailsFixture();
    const [original] = game.releases;
    if (!original) throw new Error("Fixture needs a release");
    const shared = {
      ...original,
      stage: "full_release",
      region: { regionId: "worldwide", name: "Mundial" },
      releaseDate: { precision: "day", value: "2026-09-24" },
      verificationLevel: "provider_only",
    } as const;
    game.releases = [
      { ...shared, releaseId: "ps5", platform: { platformId: "playstation-5", name: "PlayStation 5" } },
      { ...shared, releaseId: "mac", platform: { platformId: "mac", name: "Mac" } },
      { ...shared, releaseId: "pc", platform: { platformId: "pc", name: "Windows PC" } },
      { ...shared, releaseId: "xbox", platform: { platformId: "xbox-series-x-s", name: "Xbox Series X|S" } },
      { ...shared, releaseId: "switch", platform: { platformId: "nintendo-switch", name: "Nintendo Switch" }, status: "cancelled" },
    ];
    await page.route("**/api/v1/games/*", (route) => route.fulfill({ json: game }));
    await page.goto(gamePath);
    const calendar = page.getByRole("region", { name: "Fechas y plataformas" });
    await expect(calendar.locator(".game-release-group")).toHaveCount(2);
    const first = calendar.locator(".game-release-group").first();
    await expect(first.getByText("24 de septiembre de 2026")).toHaveCount(1);
    await expect(first.locator(".game-release-platforms li")).toHaveText([
      "PlayStation 5",
      "Mac",
      "Windows PC",
      "Xbox Series X|S",
    ]);
    await expect(calendar.locator(".game-release-group").nth(1)).toContainText("Cancelado");
    await page.evaluate(() => document.fonts.ready);
    // Platforms fill as many columns as fit: two side by side on a 390px phone, one at 320px.
    const tops = await first.locator(".game-release-platforms li").evaluateAll((items) =>
      items.map((item) => Math.round(item.getBoundingClientRect().top)),
    );
    if (width === 320) expect(new Set(tops).size).toBe(4);
    else expect(tops[0]).toBe(tops[1]);
    if (width < 760) {
      // The portrait cover stands centred in the column.
      const cover = await page.locator(".game-artwork").boundingBox();
      const column = await page.locator(".game-detail-layout").boundingBox();
      if (!cover || !column) throw new Error("Cover and column must render");
      expect(Math.abs(cover.x + cover.width / 2 - (column.x + column.width / 2))).toBeLessThanOrEqual(1);
    }
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  });
}

// Enlarged text stacks whole reading groups and spec lines instead of shrinking or clipping them.
test("readings and spec sheet reflow under 150% text at 390px", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 1000 });
  await page.addInitScript(() =>
    document.addEventListener("DOMContentLoaded", () => {
      document.documentElement.style.fontSize = "150%";
    }),
  );
  const game = gameDetailsFixture();
  game.developers = [{ companyId: "dev", name: "Insomniac Games" }];
  game.externalScore = {
    source: "igdb",
    kind: "player",
    value: 7.5,
    scale: 10,
    count: 43,
    attribution: { label: "Jugadores IGDB", sourceUrl: "https://www.igdb.com/games/resident-evil-requiem" },
    observedAt: "2026-08-01T10:00:00Z",
  };
  await page.route("**/api/v1/games/*", (route) => route.fulfill({ json: game }));
  await page.goto(gamePath);
  const community = await page.getByRole("region", { name: "Puntuación de la comunidad" }).boundingBox();
  const external = await page.getByRole("region", { name: "Jugadores IGDB" }).boundingBox();
  if (!community || !external) throw new Error("Both readings must render");
  expect(external.y).toBeGreaterThanOrEqual(community.y + community.height);
  const fact = page.locator(".game-fact").filter({ hasText: "Desarrollador" });
  const term = await fact.locator("dt").boundingBox();
  const value = await fact.locator("dd").boundingBox();
  if (!term || !value) throw new Error("The developer fact must render");
  expect(value.y).toBeGreaterThanOrEqual(term.y + term.height - 1);
  // The shared header's own reflow is reviewed separately; the page's content stays in view.
  const clipped = await page.locator("main").evaluate((main) =>
    [...main.querySelectorAll("*")].filter((element) => {
      const box = element.getBoundingClientRect();
      return box.width > 0 && box.right > innerWidth + 0.5 && !element.closest(".stage-light");
    }).length,
  );
  expect(clipped).toBe(0);
});

// #209: nothing decorative animates perpetually beneath the page, which would redraw every
// blurred surface each frame on high-density phones, and the fixed grain overscans the viewport
// so Android's moving URL bar never uncovers an edge. The stage is lit by the cover and holds still.
for (const width of [320, 390, 834, 1320]) {
  test(`decorative layers stay bounded at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 800 });
    await page.route("**/api/v1/games/*", (route) =>
      route.fulfill({ json: gameDetailsFixture() }),
    );
    await page.goto(gamePath);
    await expect(page.getByRole("heading", { level: 1 })).toHaveText(
      "Resident Evil Requiem",
    );
    // Motion on the stage and the ambient field; small product markers are not measured here.
    const perpetual = () =>
      page.evaluate(
        () =>
          document.getAnimations().filter((animation) => {
            const target =
              animation.effect instanceof KeyframeEffect ? animation.effect.target : null;
            return (
              (target?.closest(".stage-light") || target?.classList.contains("app-frame")) &&
              (animation.timeline instanceof ScrollTimeline ||
                animation.effect?.getTiming().iterations === Infinity)
            );
          }).length,
      );
    expect(await perpetual()).toBe(0);
    const ambient = await page.evaluate(() => {
      const frame = document.querySelector(".app-frame");
      if (!frame) throw new Error("App frame missing");
      return {
        background: getComputedStyle(frame).backgroundImage,
        overlay: getComputedStyle(frame, "::after").content,
      };
    });
    expect(ambient.background).toContain("data:image/svg+xml");
    expect(ambient.overlay).toBe("none");
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= document.documentElement.clientWidth,
      ),
    ).toBe(true);
    await page.emulateMedia({ reducedMotion: "reduce" });
    expect(await perpetual()).toBe(0);
  });
}
