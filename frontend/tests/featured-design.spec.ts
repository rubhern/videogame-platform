import { expect, test, type Page } from "@playwright/test";

import { analyzeAccessibility } from "./fixtures/accessibility";
import { coverMedia, featuredItem, featuredReleases } from "./fixtures/releases";

type FeaturedReleases = ReturnType<typeof featuredReleases>;

const SUMMARY = "Explora un mundo abierto y descubre las historias de sus habitantes. ".repeat(40);

const LEAD = "Una aventura extraordinariamente larga: más allá del horizonte";

async function expectAccessibleLayout(page: Page) {
  await page.evaluate(() => document.fonts.ready);
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth),
  ).toBeLessThanOrEqual(0);
  expect((await analyzeAccessibility(page)).violations).toEqual([]);
}

/** Generated stand-ins for fixture CDN URLs: landscape art, a portrait cover, a transparent logo. */
function fixtureImage(id: string): string {
  if (id.startsWith("lo")) {
    return (
      '<svg xmlns="http://www.w3.org/2000/svg" width="900" height="320" viewBox="0 0 900 320">' +
      '<text x="450" y="210" font-family="serif" font-size="160" text-anchor="middle" fill="#f4e7c8">LOGO</text></svg>'
    );
  }
  const [width, height] = id.startsWith("co") ? [600, 800] : [1920, 1080];
  return (
    `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}">` +
    '<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#3b2a6b"/>' +
    '<stop offset="1" stop-color="#c2410c"/></linearGradient></defs><rect width="100%" height="100%" fill="url(#g)"/>' +
    `<circle cx="${width * 0.3}" cy="${height * 0.45}" r="${height * 0.25}" fill="#fde68a" opacity=".55"/></svg>`
  );
}

/** Serves the featured releases of whichever month the page asks for, and their images. */
async function scriptFeatured(
  page: Page,
  missingImages: ReadonlySet<string> = new Set(),
  adjust: (month: FeaturedReleases) => FeaturedReleases = (month) => month,
) {
  await page.route("https://images.igdb.com/**", (route) => {
    const id = new URL(route.request().url()).pathname.split("/").pop()?.split(".")[0] ?? "";
    return missingImages.has(id)
      ? route.fulfill({ status: 404 })
      : route.fulfill({ contentType: "image/svg+xml", body: fixtureImage(id) });
  });
  await page.route("**/api/v1/session", (route) => route.fulfill({ json: { authenticated: false } }));
  await page.route("**/api/v1/featured-releases*", (route) => {
    const month = new URL(route.request().url()).searchParams.get("month") ?? "2026-08";
    const [year, number] = month.split("-");
    // Like the API, a month outside the fixtures' current year (2026) fails filter validation.
    if (year !== "2026") {
      return route.fulfill({
        status: 422,
        contentType: "application/problem+json",
        json: {
          type: "urn:videogame-platform:problem:filter_invalid",
          title: "Release filter is invalid",
          status: 422,
          code: "FILTER_INVALID",
          category: "validation",
          correlationId: "correlation-month",
          violations: [{ pointer: "/query/month", message: "Use a supported month." }],
        },
      });
    }
    const last = new Date(Date.UTC(Number(year), Number(number), 0)).getUTCDate();
    return route.fulfill({
      json: adjust(
        featuredReleases({ month, window: { from: `${month}-01`, to: `${month}-${last}` } }),
      ),
    });
  });
}

// Controlled responses isolate the composition; the packaged spec owns the real API journey.
for (const width of [320, 390, 834, 1320]) {
  test(`featured releases layout, contrast and keyboard at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await scriptFeatured(page, new Set(), (month) => ({ ...month, items: month.items.map((item, index) => ({
      ...item,
      genres: [{ genreId: "adventure", name: "Aventura" }, { genreId: "rpg", name: "Rol (RPG)" }],
      ...(index === 0 ? { summary: { kind: "sourced", text: SUMMARY, language: "es",
        provenance: { sourceKind: "external_provider", sourceName: "IGDB", sourceEntityType: "game" },
        translation: { kind: "machine_translation", sourceText: "Explore a world.", sourceLanguage: "en", current: true },
      } } : {}),
    })) }));
    await page.goto("/");

    const hero = page.getByRole("article", { name: LEAD });
    await expect(hero).toBeVisible();
    await expect(page.getByRole("heading", { level: 1, name: "Lanzamientos del mes" })).toBeVisible();
    await expect(hero.getByText("Lanzamiento del mes")).toBeVisible();
    const others = page.getByRole("region", { name: "Otros lanzamientos destacados" });
    await expect(others.getByRole("article")).toHaveCount(5);
    // An unrecognized platform keeps its name beside the generic mark; known ones are marks.
    // Phones keep the compact marks instead, and every name stays announced.
    const platforms = hero.getByRole("list", { name: "Plataformas" });
    await expect(platforms).toContainText("Plataforma recién adquirida");
    if (width >= 620) {
      await expect(hero.getByText("Plataforma recién adquirida")).toBeVisible();
    } else {
      await expect(hero.locator(".featured-platform-more")).toHaveText("+2");
      // Date, platforms and region share one row at 390px and degrade to two whole-group rows
      // at 320px; no group ever breaks inside.
      const rows = await hero
        .locator(".featured-hero-meta > li")
        .evaluateAll((groups) =>
          groups.map((group) => {
            const box = group.getBoundingClientRect();
            return { middle: box.top + box.height / 2, height: box.height };
          }),
        );
      expect(rows.every((row) => row.height <= 24)).toBe(true);
      const [date, marks, region] = rows;
      expect(Math.abs((marks?.middle ?? 0) - (date?.middle ?? 99))).toBeLessThan(2);
      if (width === 390) {
        expect(Math.abs((region?.middle ?? 0) - (date?.middle ?? 99))).toBeLessThan(2);
      } else {
        expect(region?.middle ?? 0).toBeGreaterThan((date?.middle ?? 0) + 10);
      }
    }
    // The whole hero is one native link; metadata introduces no controls.
    await expect(hero.getByRole("link", { name: /Explorar lanzamientos/ })).toHaveCount(0);
    await expectAccessibleLayout(page);

    // Supporting metadata stays comfortably readable on its glass.
    const sizes = await page
      .locator(".featured-note, .featured-chip, .featured-card-release, .card-date-badge, .featured-badge")
      .evaluateAll((elements) => elements.map((element) => Number.parseFloat(getComputedStyle(element).fontSize)));
    expect(Math.min(...sizes)).toBeGreaterThanOrEqual(11);

    const card = await hero.boundingBox();
    const visual = await hero.locator(".featured-hero-visual").boundingBox();
    const body = await hero.locator(".featured-hero-body").boundingBox();
    if (width >= 620) {
      // From tablet the copy takes the hero's left, where key art keeps its logo space, and the
      // art spans the rest to the hero's right edge.
      expect(body?.x ?? 1).toBeLessThan((card?.x ?? 0) + (card?.width ?? 0) * 0.1);
      expect((visual?.x ?? 0) + (visual?.width ?? 0)).toBeGreaterThanOrEqual(
        (card?.x ?? 0) + (card?.width ?? 0) - 1,
      );
    } else {
      // Phones stack the art over the copy; the badge and wordmark settle over its faded edge.
      expect(body?.y ?? 0).toBeGreaterThan(visual?.y ?? 0);
      expect(body?.y ?? 0).toBeLessThan((visual?.y ?? 0) + (visual?.height ?? 0));
    }

    // Provider logo availability never changes the product-owned canonical hero heading.
    const title = hero.getByRole("heading", { level: 2, name: LEAD });
    await expect(title).toHaveText(LEAD);
    await expect(title.locator("img")).toHaveCount(0);
    await expect(hero.locator(".featured-art-image")).toHaveCSS("object-fit", "cover");
    // The image fades in behind the copy without a hard panel boundary.
    if (width >= 620) {
      expect(visual?.x ?? 0).toBeLessThan((body?.x ?? 0) + (body?.width ?? 0));
    }
    await expect(hero.locator(".featured-art")).toHaveAttribute("data-art-kind", "artwork");
    // Every featured frame is landscape, and each card presents its own media path.
    expect(
      await others.locator(".featured-art").evaluateAll((frames) =>
        frames.map((frame) => frame.getAttribute("data-art-kind")),
      ),
    ).toEqual(["artwork", "screenshot", "cover", "fallback", "artwork"]);
    for (const frame of await others.locator(".featured-card-media").all()) {
      const box = await frame.boundingBox();
      expect((box?.width ?? 0) / (box?.height ?? 1)).toBeGreaterThan(1.5);
    }
    // Only the screenshot, which carries no title of its own, gets the logo over it.
    await expect(others.locator(".featured-card-logo")).toHaveCount(1);
    if (width === 1320) {
      // As in the reference, the hero and the whole row open within the first desktop view.
      await expect(others.getByRole("heading", { level: 3 }).last()).toBeInViewport({ ratio: 1 });
    }
    // A cover shown whole keeps its own proportions: it is never stretched or cropped.
    const cover = others.locator('[data-art-kind="cover"] img');
    await cover.scrollIntoViewIfNeeded();
    await expect
      .poll(() => cover.evaluate((image: HTMLImageElement) => image.complete && image.naturalWidth > 0))
      .toBe(true);
    const [shown, natural] = await cover.evaluate((image: HTMLImageElement) => {
      const box = image.getBoundingClientRect();
      return [box.width / box.height, image.naturalWidth / image.naturalHeight];
    });
    expect(Math.abs((shown ?? 0) - (natural ?? 1))).toBeLessThan(0.02);


    const action = hero.getByRole("link", { name: LEAD, exact: true });
    await expect(action).toHaveCount(1);
    await expect(action.locator("a, button, input, select, summary, [tabindex]")).toHaveCount(0);
    const summary = hero.locator(".featured-hero-summary");
    await expect(summary).toHaveText(SUMMARY);
    await expect(summary).toHaveAttribute("lang", "es");
    await expect(summary).toHaveCSS("-webkit-line-clamp", width < 620 ? "3" : "2");
    const textHeight = await summary.evaluate((element) => ({
      shown: element.clientHeight, full: element.scrollHeight,
      line: Number.parseFloat(getComputedStyle(element).lineHeight),
    }));
    expect(textHeight.full).toBeGreaterThan(textHeight.shown);
    expect(Math.abs(textHeight.shown - textHeight.line * (width < 620 ? 3 : 2))).toBeLessThan(2);
    await expect(hero.getByRole("list", { name: "Géneros" })).toHaveText("AventuraRol (RPG)");
    await expect(others.locator(".featured-card-genres")).toHaveCount(5);
    await expect(others.locator(".featured-hero-summary")).toHaveCount(0);
    await expect(hero.getByText("Publicado", { exact: true })).toHaveCount(0);
    await expect(hero.getByText("Lanzamiento completo", { exact: true })).toHaveCount(0);
    await expect(hero.getByText("Ver ficha", { exact: true })).toHaveCount(0);
    // Tab reaches the frame as a single keyboard stop; its visible focus outlines that frame.
    const previous = page.getByRole("link", { name: "Mes siguiente: septiembre de 2026" });
    await previous.focus();
    await page.keyboard.press("Tab");
    await expect(action).toBeFocused();
    await expect(action).toHaveCSS("outline-style", "solid");
    await expect(action).toHaveCSS("outline-width", "2px");
    await page.screenshot({ path: test.info().outputPath(`featured-${width}.png`), fullPage: true });
    if (width === 390 || width === 1320) {
      const destination = await action.getAttribute("href");
      await page.keyboard.press("Enter");
      await expect(page).toHaveURL(new RegExp(`${destination}$`));
      await page.goBack();
      await expect(action).toBeVisible();
      // Clicking the image side, away from all text, navigates through the same link.
      const box = await action.boundingBox();
      if (!box) throw new Error("Featured link has no frame");
      await action.click({ position: { x: box.width - 18, y: 18 } });
      await expect(page).toHaveURL(new RegExp(`${destination}$`));
      await page.goBack();
    }

    const next = page.getByRole("link", { name: "Mes siguiente: septiembre de 2026" });
    await next.focus();
    await page.keyboard.press("Shift+Tab");
    await page.keyboard.press("Tab");
    await expect(next).toBeFocused();
    await expect(next).toHaveCSS("outline-style", "solid");
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(/\/\?month=2026-09$/);
    await expect(
      page.getByRole("navigation", { name: "Mes de la selección" }).getByText("Septiembre 2026"),
    ).toBeVisible();
    await expect(page.getByRole("status")).toHaveText("Lanzamientos destacados de septiembre de 2026");
    await expect(page.getByRole("link", { name: "Destacados", exact: true })).toHaveAttribute(
      "aria-current",
      "page",
    );
  });
}

test("a provider image that cannot load steps down to the product fallback", async ({ page }) => {
  await page.setViewportSize({ width: 1320, height: 900 });
  await scriptFeatured(page, new Set(["arlead", "lolead", "scyotei"]));
  await page.goto("/");

  const hero = page.getByRole("article", { name: LEAD });
  await expect(hero.locator(".featured-art")).toHaveAttribute("data-art-kind", "fallback");
  // The canonical hero title survives every media fallback.
  await expect(hero.getByRole("heading", { level: 2, name: LEAD })).toHaveText(LEAD);
  const others = page.getByRole("region", { name: "Otros lanzamientos destacados" });
  await expect(
    others.getByRole("article").filter({ hasText: "Ghost of Yōtei" }).locator(".featured-art"),
  ).toHaveAttribute("data-art-kind", "fallback");
  await expectAccessibleLayout(page);
});

test("month steps stop at January and December of the current year", async ({ page }) => {
  await page.setViewportSize({ width: 1320, height: 900 });
  await scriptFeatured(page);
  await page.goto("/?month=2026-01");

  const months = page.getByRole("navigation", { name: "Mes de la selección" });
  await expect(months.getByText("Enero 2026")).toBeVisible();
  const previous = months.getByRole("link", { name: "Mes anterior" });
  await expect(previous).toHaveAttribute("aria-disabled", "true");
  // A disabled step is dimmed, inert and outside the tab order; the enabled one still focuses.
  await expect(previous).toHaveCSS("opacity", "0.35");
  expect(await previous.evaluate((element) => (element as HTMLElement).tabIndex)).toBe(-1);
  const next = months.getByRole("link", { name: "Mes siguiente: febrero de 2026" });
  await next.focus();
  await expect(next).toHaveCSS("outline-style", "solid");
  await expectAccessibleLayout(page);

  await page.goto("/?month=2026-12");
  await expect(months.getByText("Diciembre 2026")).toBeVisible();
  await expect(months.getByRole("link", { name: "Mes siguiente" })).toHaveAttribute(
    "aria-disabled",
    "true",
  );
  await months.getByRole("link", { name: "Mes anterior: noviembre de 2026" }).click();
  await expect(page).toHaveURL(/\/\?month=2026-11$/);
  await expect(months.getByRole("link", { name: "Mes siguiente: diciembre de 2026" })).toBeVisible();

  // A month of another year typed into the URL returns to the current month, never its content.
  for (const month of ["2019-03", "2027-01"]) {
    await page.goto(`/?month=${month}`);
    await expect(page).toHaveURL(/\/$/);
    await expect(months.getByText("Agosto 2026")).toBeVisible();
    await expect(page.getByText(month.slice(0, 4), { exact: false })).toHaveCount(0);
  }
});

test("a hero without landscape media presents the designed fallback, never its cover", async ({ page }) => {
  await page.setViewportSize({ width: 1320, height: 900 });
  await scriptFeatured(page, new Set(), (month) => ({
    ...month,
    items: [
      featuredItem(1, "Fable", { precision: "day", value: "2026-08-28" }, undefined, coverMedia("cofable", "Fable")),
      ...month.items.slice(1),
    ],
  }));
  await page.goto("/");

  const hero = page.getByRole("article", { name: "Fable" });
  await expect(hero.locator(".featured-art")).toHaveAttribute("data-art-kind", "fallback");
  await expect(hero.locator('[data-art-kind="cover"]')).toHaveCount(0);
  await expect(hero.getByRole("heading", { level: 2, name: "Fable" })).toHaveText("Fable");
  await expectAccessibleLayout(page);
});

for (const state of ["loading", "unranked", "empty", "error"] as const) {
  test(`${state} featured state uses accessible surfaces and recovery`, async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    let retry = false;
    await page.route("**/api/v1/session", (route) => route.fulfill({ json: { authenticated: false } }));
    await page.route("**/api/v1/featured-releases*", async (route) => {
      if (state === "loading") return; // Kept pending until the page closes, without timing sleeps.
      if (retry) return route.fulfill({ json: featuredReleases() });
      if (state === "unranked" || state === "empty") {
        return route.fulfill({
          json: featuredReleases({
            selection: {
              status: state === "unranked" ? "popularity_unavailable" : "no_qualifying_releases",
            },
            items: [],
          }),
        });
      }
      return route.fulfill({
        status: 500,
        json: {
          type: "about:blank", title: "Internal error", status: 500, detail: "Internal error",
          instance: "urn:videogame-platform:problem-instance:visual-check-reference",
          code: "INTERNAL_ERROR", category: "technical", correlationId: "visual-check-reference",
        },
      });
    });
    await page.goto("/");

    if (state === "loading") {
      await expect(page.getByRole("status")).toHaveText("Cargando lanzamientos destacados…");
      await expect(page.getByRole("article")).toHaveCount(0);
    } else if (state === "unranked") {
      await expect(
        page.getByRole("heading", { name: "Aún no hay lanzamientos destacados en agosto de 2026" }),
      ).toBeVisible();
    } else if (state === "empty") {
      await expect(page.getByRole("heading", { name: "No hay lanzamientos en agosto de 2026" })).toBeVisible();
      await expect(page.getByRole("link", { name: "Ver lanzamientos recientes" })).toHaveAttribute(
        "href",
        "/?view=recent&weeks=1",
      );
    } else {
      await expect(page.getByRole("alert")).toContainText("visual-check-reference");
    }
    await expectAccessibleLayout(page);
    if (state === "error") {
      retry = true;
      await page.getByRole("button", { name: "Reintentar" }).click();
      await expect(page.getByRole("article", { name: LEAD })).toBeVisible();
    }
  });
}

for (const forcedColors of ["none", "active"] as const) {
  test(`featured link and summary preserve focus under reduced motion and forced colours ${forcedColors}`, async ({ page }) => {
    await page.emulateMedia({ reducedMotion: "reduce" });
    await page.setViewportSize({ width: 390, height: 844 });
    await scriptFeatured(page, new Set(), (month) => ({ ...month, items: month.items.map((item, index) => index === 0 ? {
      ...item, summary: { kind: "editorial", text: SUMMARY, language: "es" },
    } : item) }));
    await page.goto("/");
    const link = page.getByRole("article", { name: LEAD }).getByRole("link", { name: LEAD, exact: true });
    await expect(link).toBeVisible();
    // As in the packaged journeys, measure normal-palette contrast before testing forced colours.
    await expectAccessibleLayout(page);
    await page.emulateMedia({ reducedMotion: "reduce", forcedColors });
    await page.getByRole("link", { name: "Mes siguiente: septiembre de 2026" }).focus();
    await page.keyboard.press("Tab");
    await expect(link).toBeFocused();
    await expect(link).toHaveCSS("outline-style", "solid");
    await expect(link.locator(".featured-hero-summary")).toHaveCSS("-webkit-line-clamp", "3");
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: test.info().outputPath(`featured-forced-${forcedColors}.png`), fullPage: true });
  });
}
