import { expect, test, type Locator, type Page } from "@playwright/test";
import { cdn, featuredWeekReleases, pragmata, releasePage } from "./fixtures/releases";
import { gameDetailsFixture } from "../src/test/game-details-fixture";

function scrollCover(index: number) {
  return {
    kind: "provider" as const,
    url: cdn("t_cover_big_2x", `scroll-cover-${index}`),
    alternativeText: `Portada del juego ${index}`,
    attribution: { label: "IGDB", sourceUrl: "https://www.igdb.com/games/scroll-fixture" },
  };
}

async function fixtures(page: Page) {
  await page.route("**/api/v1/session", route => route.fulfill({ json: { authenticated: false } }));
  // Keep the masked artwork and blurred stage active: aborting these requests only
  // exercises the fallback and misses the phone compositor path.
  await page.route("https://images.igdb.com/**", route => route.fulfill({
    contentType: "image/svg+xml",
    body: '<svg xmlns="http://www.w3.org/2000/svg" width="1920" height="1080"><defs><linearGradient id="art"><stop stop-color="#274c76"/><stop offset="1" stop-color="#a74518"/></linearGradient></defs><rect width="1920" height="1080" fill="url(#art)"/><circle cx="1400" cy="480" r="350" fill="#f4b261"/></svg>',
  }));
  await page.route("**/api/v1/featured-releases?*", route => route.fulfill({ json: featuredWeekReleases() }));
  await page.route("**/api/v1/releases?*", route => {
    const number = Number(new URL(route.request().url()).searchParams.get("page") ?? 1);
    return route.fulfill({ json: releasePage({
      items: Array.from({ length: 12 }, (_, i) => ({ ...pragmata, gameId: `30000000-0000-4000-8000-${String(i).padStart(12, "0")}`, canonicalTitle: `Juego ${i}`, primaryCover: scrollCover(i) })),
      page: { number, size: 12, totalItems: 24, totalPages: 2 },
    }) });
  });
  await page.route("**/api/v1/games?*", route => route.fulfill({ json: {
    items: Array.from({ length: 12 }, (_, i) => ({ ...pragmata, gameId: `30000000-0000-4000-8000-${String(i).padStart(12, "0")}`, canonicalTitle: `Juego ${i}`, primaryCover: scrollCover(i), releaseContext: [], releaseSummary: { platforms: [], totalPlatforms: 0 } })),
    page: { number: Number(new URL(route.request().url()).searchParams.get("page") ?? 1), size: 12, totalItems: 24, totalPages: 2 },
  } }));
  await page.route("**/api/v1/games/*", route => route.fulfill({ json: gameDetailsFixture() }));
  await page.route("**/api/v1/me/ratings/*", route => route.fulfill({
    status: 404, json: { code: "RATING_NOT_FOUND" },
  }));
}

async function at(page: Page, y: number) {
  await expect.poll(() => page.evaluate(() => scrollY)).toBeCloseTo(y, -1);
}

/** Let finite entrance animations end; one cancelled by a rerender has nothing left to wait for. */
async function settleAnimations(page: Page) {
  await page.evaluate(async () => {
    await document.fonts.ready;
    await Promise.all(document.getAnimations().filter(animation =>
      animation.effect?.getTiming().iterations !== Infinity && animation.timeline instanceof DocumentTimeline,
    ).map(animation => animation.finished.catch(() => undefined)));
  });
}

async function openFromReadingPosition(page: Page, link: Locator) {
  // An animating link fails Playwright's stability check, and each retry scrolls it
  // with another alignment. Settle first so the reader's position survives the click,
  // and still capture it at navigation in case Playwright moves it. The destination
  // mounts after its route read, so wait for the link before collecting animations.
  await expect(link).toBeVisible();
  await settleAnimations(page);
  await link.scrollIntoViewIfNeeded();
  const position = page.evaluate(() => new Promise<number>(resolve => {
    window.addEventListener("click", () => resolve(scrollY), { once: true, capture: true });
  }));
  await link.click();
  return position;
}

/** Inspect painted text without scrolling a locator into view or changing its styles. */
async function paintedTextPixels(page: Page, locator: Locator) {
  const box = await locator.boundingBox();
  if (!box) throw new Error("Expected content has no layout box");
  expect(box.y).toBeGreaterThanOrEqual(0);
  const pixels = await page.screenshot({ clip: box, animations: "allow" });
  return page.evaluate(async base64 => {
    const image = new Image();
    image.src = `data:image/png;base64,${base64}`;
    await image.decode();
    const canvas = document.createElement("canvas");
    canvas.width = image.naturalWidth;
    canvas.height = image.naturalHeight;
    const context = canvas.getContext("2d");
    if (!context) throw new Error("Cannot inspect painted pixels");
    context.drawImage(image, 0, 0);
    const { data } = context.getImageData(0, 0, canvas.width, canvas.height);
    let count = 0;
    for (let pixel = 0; pixel < data.length; pixel += 4) {
      if ((data[pixel] ?? 0) > 130 && (data[pixel + 1] ?? 0) > 130 && (data[pixel + 2] ?? 0) > 130) count += 1;
    }
    return count;
  }, pixels.toString("base64"));
}

for (const width of [390, 412, 1320]) {
  test.describe(`${width}px`, () => {
    test.use({ viewport: { width, height: width === 412 ? 915 : 740 }, hasTouch: width < 620, isMobile: width < 620, deviceScaleFactor: width === 412 ? 3.5 : 1 });
    for (const path of ["/?view=recent&pageSize=12", "/?view=upcoming&pageSize=12", "/search?q=juego&pageSize=12"]) {
      test(`PUSH and history preserve reading position from ${path}`, async ({ page }) => {
        await fixtures(page);
        await page.goto(path);
        const link = page.getByRole("link", { name: "Juego 11", exact: true });
        const before = await openFromReadingPosition(page, link);
        expect(before).toBeGreaterThan(100);
        await expect(page.getByRole("heading", { level: 1, name: "Resident Evil Requiem" })).toBeVisible();
        await at(page, 0);
        await page.evaluate(() => scrollTo(0, 300));
        await at(page, 300);
        await page.goBack();
        await expect(link).toBeVisible();
        await at(page, before);
        await page.goForward();
        await at(page, 300);
      });
    }
    test("pagination starts a result page and POP restores its entry", async ({ page }) => {
      await fixtures(page);
      await page.goto("/?view=recent&pageSize=12");
      const next = page.getByRole("link", { name: "Página siguiente" });
      const before = await openFromReadingPosition(page, next);
      await expect(page).toHaveURL(/page=2/);
      await at(page, 0);
      await page.goBack();
      await at(page, before);
    });
    test("history waits for a freshly loaded personal collection", async ({ page }) => {
      await fixtures(page);
      await page.route("**/api/v1/session", route => route.fulfill({ json: { authenticated: true, csrfToken: "test-csrf" } }));
      let releaseRead: (() => void) | undefined;
      let reads = 0;
      await page.route("**/api/v1/me/ratings?*", async route => {
        if (++reads > 1) await new Promise<void>(resolve => { releaseRead = resolve; });
        await route.fulfill({ json: {
          items: Array.from({ length: 10 }, (_, i) => ({
            game: { ...pragmata, gameId: `30000000-0000-4000-8000-${String(i).padStart(12, "0")}`, canonicalTitle: `Nota ${i}` },
            personalRating: { gameId: pragmata.gameId, value: 7, createdAt: "2026-08-13T10:00:00Z", updatedAt: "2026-08-13T10:00:00Z", entityTag: '"version-1"' },
            ratingSummary: { status: "available", mean: 7, count: 1 },
          })),
          page: { number: 1, size: 10, totalItems: 10, totalPages: 1 },
        } });
      });
      await page.goto("/mis-puntuaciones");
      const link = page.getByRole("link", { name: "Nota 9", exact: true });
      const before = await openFromReadingPosition(page, link);
      await expect(page.getByRole("heading", { level: 1, name: "Resident Evil Requiem" })).toBeVisible();
      await page.goBack();
      await expect.poll(() => Boolean(releaseRead)).toBe(true);
      releaseRead?.();
      await expect(link).toBeVisible();
      await at(page, before);
    });
    test("release filters preserve context and a new section starts at the top", async ({ page }) => {
      await fixtures(page);
      await page.goto("/?view=recent&pageSize=12");
      await expect(page.getByRole("link", { name: "Juego 0", exact: true })).toBeVisible();
      const filter = page.getByRole("combobox", { name: /^Plataforma:/ });
      await filter.focus();
      await page.keyboard.press("ArrowDown");
      await page.keyboard.press("End");
      await expect(page.getByRole("option", { name: "Windows PC" })).toBeInViewport();
      const before = await page.evaluate(() => scrollY);
      await page.keyboard.press("Enter");
      await expect(page).toHaveURL(/platformIds=windows-pc/);
      await at(page, before);
      await page.keyboard.press("Escape");
      await page.getByRole("link", { name: "Próximos", exact: true }).click();
      await expect(page).toHaveURL(/view=upcoming/);
      await at(page, 0);
      await expect(page.getByRole("main")).toBeFocused();
    });

    test("refresh keeps no offset in browser storage and a data-dependent deep anchor restores after async loading", async ({ page }) => {
      await fixtures(page);
      const path = "/games/30000000-0000-4000-8000-000000000005/resident-evil-requiem";
      await page.goto(path);
      await expect(page.getByRole("heading", { level: 1, name: "Resident Evil Requiem" })).toBeVisible();
      await page.evaluate(() => scrollTo(0, 300));
      await at(page, 300);
      let resolveRead: (() => void) | undefined;
      const read = new Promise<void>(resolve => { resolveRead = resolve; });
      let requested = false;
      await page.route("**/api/v1/games/*", async route => {
        requested = true;
        await read;
        await route.fulfill({ json: gameDetailsFixture() });
      });
      await page.reload({ waitUntil: "domcontentloaded" });
      await expect.poll(() => requested).toBe(true);
      resolveRead?.();
      await expect(page.getByRole("heading", { level: 1, name: "Resident Evil Requiem" })).toBeVisible();
      // Offsets live in memory only: the privacy baseline keeps Web Storage empty, so a
      // reloaded document starts at the top.
      await at(page, 0);
      expect(await page.evaluate(() => ({ local: localStorage.length, session: sessionStorage.length })))
        .toEqual({ local: 0, session: 0 });
      await page.unroute("**/api/v1/games/*");
      await page.route("**/api/v1/games/*", route => route.fulfill({ json: gameDetailsFixture() }));
      await page.goto(`${path}#statistics-title`);
      await expect(page.locator("#statistics-title")).toBeInViewport();
      const position = await page.evaluate(() => Math.min(scrollY + 160, document.documentElement.scrollHeight - innerHeight));
      await page.mouse.wheel(0, 160);
      await at(page, position);
      await page.evaluate(() => document.fonts.ready);
      await at(page, position);
    });

    test("late artwork cannot override the reader's scroll", async ({ page }) => {
      await fixtures(page);
      let finishArtwork: (() => void) | undefined;
      const artwork = new Promise<void>(resolve => { finishArtwork = resolve; });
      await page.route("**/scroll-cover.svg", async route => {
        await artwork;
        await route.fulfill({ contentType: "image/svg+xml", body: '<svg xmlns="http://www.w3.org/2000/svg" width="900" height="1600"><rect width="900" height="1600" fill="navy"/></svg>' });
      });
      await page.route("**/api/v1/games/*", route => {
        const game = gameDetailsFixture();
        game.primaryCover.url = "/scroll-cover.svg";
        return route.fulfill({ json: game });
      });
      await page.goto("/games/30000000-0000-4000-8000-000000000005/resident-evil-requiem", { waitUntil: "domcontentloaded" });
      await expect(page.getByRole("heading", { level: 1, name: "Resident Evil Requiem" })).toBeVisible();
      await page.evaluate(() => scrollTo(0, 300));
      await at(page, 300);
      finishArtwork?.();
      await expect.poll(() => page.locator(".catalogue-cover").evaluate(image => (image as HTMLImageElement).complete)).toBe(true);
      await at(page, 300);
    });

    test("Featured title and selectors are painted on their first upward reentry", async ({ page }) => {
      await fixtures(page);
      await page.goto("/");
      const selector = page.getByRole("navigation", { name: "Periodo del TOPómetro" });
      await expect(selector).toBeVisible();
      const title = page.getByRole("heading", { name: "TOPómetro de la semana", exact: true });
      await expect(title).toBeVisible();
      // The heading of the loaded week can mount after the selector and starts its own fade-in,
      // so collect animations only once both are present.
      await settleAnimations(page);
      // Capture the viewport directly: Locator.screenshot can scroll the element and
      // repaint a missing layer, hiding the direction-dependent defect being tested.
      const paintedText = () => Promise.all([title, selector].map(locator => paintedTextPixels(page, locator)));
      await expect.poll(async () => Math.min(...await paintedText())).toBeGreaterThan(30);
      const before = await paintedText();
      const touch = width < 620 ? await page.context().newCDPSession(page) : null;
      const scroll = async (distance: number) => {
        if (touch) {
          const x = width / 2;
          await touch.send("Input.dispatchTouchEvent", { type: "touchStart", touchPoints: [{ x, y: 180 }] });
          for (const y of [220, 300, 400, 520, 650]) {
            await touch.send("Input.dispatchTouchEvent", { type: "touchMove", touchPoints: [{ x, y }] });
          }
          await touch.send("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] });
        } else await page.mouse.wheel(0, distance);
      };
      for (let pass = 0; pass < 3; pass += 1) {
        // Touch gestures use the compositor's real touch scrolling; a DOM event would not scroll.
        if (touch) await page.evaluate(() => scrollTo(0, document.documentElement.scrollHeight));
        else await scroll(10000);
        await expect.poll(() => page.evaluate(() => scrollY + innerHeight >= document.documentElement.scrollHeight - 2)).toBe(true);
        if (touch) {
          for (let step = 0; step < 15 && await page.evaluate(() => scrollY) > 0; step += 1) {
            await scroll(-400);
          }
        } else {
          const distance = await page.evaluate(() => scrollY - 24);
          await scroll(-distance);
          await at(page, 24);
        }
        if (touch) await at(page, 0);
        await expect(selector).toBeInViewport();
        await expect(selector.getByRole("link", { name: "Semana", exact: true })).toBeVisible();
        await expect(selector.getByRole("link", { name: "Mes", exact: true })).toBeVisible();
        await expect(title).toBeInViewport();
        const after = await paintedText();
        // Both title and controls must actually paint; DOM visibility alone misses a
        // raster layer dropped on Android. Allow antialiasing/grain differences.
        for (const [index, count] of after.entries()) {
          expect(count).toBeGreaterThan((before[index] ?? 0) * 0.85);
        }
      }
      await page.screenshot({ path: test.info().outputPath("first-upward.png") });
      await touch?.detach();
    });
  });
}


test("a pending search keeps one result status and announces navigation separately", async ({ page }) => {
  await page.setViewportSize({ width: 1320, height: 740 });
  await fixtures(page);
  await page.goto("/search?q=juego&pageSize=12");
  await expect(page.getByRole("link", { name: "Juego 0", exact: true })).toBeVisible();
  let finishRead: (() => void) | undefined;
  const pending = new Promise<void>(resolve => { finishRead = resolve; });
  let requested = false;
  await page.route("**/api/v1/games?*", async route => {
    const params = new URL(route.request().url()).searchParams;
    if (params.get("q") !== "otro" || params.get("pageSize") === "5") return route.fallback();
    requested = true;
    await pending;
    return route.fallback();
  });
  const search = page.getByRole("combobox", { name: "Buscar juegos" });
  await search.fill("otro");
  await search.press("Enter");
  await expect.poll(() => requested).toBe(true);
  await expect(page.getByRole("main")).toHaveAttribute("aria-busy", "true");
  await expect(page.getByRole("status")).toHaveCount(1);
  await expect(page.locator(".app-frame > [aria-live=polite]")).toHaveText("Cargando página…");
  finishRead?.();
  await expect(page).toHaveURL(/q=otro/);
  await expect(page.getByRole("main")).toHaveAttribute("aria-busy", "false");
  await expect(page.getByRole("status")).toHaveCount(1);
  await expect(page.getByRole("heading", { level: 2, name: "Resultados" })).toBeFocused();
});

test.describe("shared shell on a high-density touch screen", () => {
  test.use({ viewport: { width: 412, height: 915 }, isMobile: true, hasTouch: true, deviceScaleFactor: 3.5 });
  for (const path of ["/", "/?view=recent&pageSize=12", "/?view=upcoming&pageSize=12", "/search?q=juego&pageSize=12"]) {
    test(`header and page opening repaint after deep scroll and toolbar resize: ${path}`, async ({ page }) => {
      await fixtures(page);
      await page.goto(path);
      const header = page.getByRole("banner");
      const title = page.getByRole("heading", { level: 1 });
      const controls = path === "/"
        ? page.getByRole("navigation", { name: "Periodo del TOPómetro" })
        : path.startsWith("/?view=") ? page.locator(".releases-controls") : null;
      await expect(title).toBeVisible();
      if (path.startsWith("/?view=")) {
        // Owner-selected plain canvas: provider covers must not create a backdrop.
        await expect(page.locator(".stage-light")).toHaveCount(0);
        await expect(page.locator(".catalogue-cover").first()).toBeVisible();
      } else if (path.startsWith("/search")) {
        await expect(page.locator(".stage-light-source")).toHaveCount(3);
      }
      await settleAnimations(page);
      const targets = controls ? [header, title, controls] : [header, title];
      const before = await Promise.all(targets.map(target => paintedTextPixels(page, target)));
      expect(Math.min(...before)).toBeGreaterThan(30);
      // Decoration must not create a fixed translucent surface over the scrolling app.
      expect(await page.locator(".app-frame").evaluate(frame =>
        getComputedStyle(frame, "::after").position,
      )).not.toBe("fixed");
      const touch = await page.context().newCDPSession(page);
      const swipe = async (direction: "down" | "up") => {
        const ys = direction === "up" ? [180, 250, 330, 410, 490, 570, 650] : [650, 570, 490, 410, 330, 250, 180];
        await touch.send("Input.dispatchTouchEvent", { type: "touchStart", touchPoints: [{ x: 206, y: ys[0] ?? 180 }] });
        for (const y of ys.slice(1)) {
          await touch.send("Input.dispatchTouchEvent", { type: "touchMove", touchPoints: [{ x: 206, y }] });
        }
        await touch.send("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] });
      };
      for (let pass = 0; pass < 2; pass += 1) {
        for (let step = 0; step < 30; step += 1) {
          if (await page.evaluate(() => scrollY + innerHeight >= document.documentElement.scrollHeight - 2)) break;
          await swipe("down");
        }
        await expect.poll(() => page.evaluate(() => scrollY + innerHeight >= document.documentElement.scrollHeight - 2)).toBe(true);
        // Model the viewport change when Chrome's address bar returns. This is still
        // desktop Chromium emulation, not verification of the physical Android GPU.
        await page.setViewportSize({ width: 412, height: pass === 0 ? 800 : 915 });
        for (let step = 0; step < 30 && await page.evaluate(() => scrollY) > 0; step += 1) await swipe("up");
        await at(page, 0);
        for (const [index, target] of targets.entries()) {
          await expect(target).toBeInViewport();
          expect(await paintedTextPixels(page, target)).toBeGreaterThan((before[index] ?? 0) * 0.85);
        }
      }
      await page.screenshot({ path: test.info().outputPath("shared-header-first-return.png") });
      await touch.detach();
    });
  }
});
