import { expect, test, type Page } from "@playwright/test";

import { analyzeAccessibility } from "./fixtures/accessibility";
import { openReleaseControls, pragmata, pragmataRelease, releasePage } from "./fixtures/releases";
import { gameDetailsFixture } from "../src/test/game-details-fixture";

async function expectAccessibleLayout(page: Page) {
  await page.evaluate(() => document.fonts.ready);
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth),
  ).toBeLessThanOrEqual(0);
  expect((await analyzeAccessibility(page)).violations).toEqual([]);
}

async function expectComfortableCatalogueMetadata(page: Page) {
  const sizes = await page
    .locator(
      ".card-date, .card-region, .platform-mark-more, .card-genres, .badge, .day-bar-numeral",
    )
    .evaluateAll((elements) => elements.map((element) => Number.parseFloat(getComputedStyle(element).fontSize)));
  expect(Math.min(...sizes)).toBeGreaterThanOrEqual(11);
}

// Controlled HTTP responses isolate visual/keyboard states; packaged tests own the real API journey.
for (const width of [320, 390, 834, 1320]) {
  test(`catalogue layout, contrast and keyboard navigation at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.route("**/api/v1/releases?*", async (route) => {
      const query = new URL(route.request().url()).searchParams;
      await route.fulfill({ json: releasePage({
        window: query.has("from")
          ? { from: query.get("from") ?? "", to: query.get("to") ?? "" }
          : { from: "2026-08-10", to: "2026-08-16" },
        items: [{ ...pragmata, genres: [{ genreId: "action", name: "Acción" }, { genreId: "rpg", name: "Rol" }] }, {
          genres: [{ genreId: "long", name: "Un género de nombre extraordinariamente largo" }],
          ...pragmata,
          gameId: "30000000-0000-4000-8000-000000000007",
          slug: "long-title",
          canonicalTitle: "Una aventura extraordinariamente larga: más allá del horizonte",
          releases: [{ ...pragmataRelease, gameId: "30000000-0000-4000-8000-000000000007", releaseId: "40000000-0000-4000-8000-000000000007", reviewStatus: "required", releaseDate: { precision: "unknown", value: null } }],
        }],
        page: { number: Number(query.get("page") ?? 1), size: 6, totalItems: 8, totalPages: 2 },
      }) });
    });
    await page.goto("/lanzamientos?pageSize=6");
    await expect(page.getByRole("link", { name: "Pragmata", exact: true })).toBeVisible();
    await expect(page.locator(".stage-light")).toHaveCount(0);
    await expect(page.locator(".releases-footer")).toHaveCSS("border-top-width", "0px");
    // The day bar: one division and one day button per day of the API's range. Its rail is
    // decorative; the range is stated in words beside the title.
    await expect(page.locator(".window-scale-rail")).toHaveAttribute("aria-hidden", "true");
    await expect(page.locator(".window-scale-tick")).toHaveCount(7);
    await expect(
      page.getByRole("toolbar", { name: "Elegir un día del periodo" }).getByRole("button"),
    ).toHaveCount(7);
    await expect(page.locator(".release-period")).toContainText("10–16 ago 2026");
    await expect(page.locator(".result-count")).toHaveText("8 juegos · Página 1 de 2");
    if (width < 620) {
      // Phones fold the controls under one toggle that states what they hold.
      await expect(page.getByRole("button", { name: /^Filtros y orden/ })).toHaveAttribute("aria-expanded", "false");
      await expect(page.getByLabel("Desde")).toBeHidden();
    }
    await expectAccessibleLayout(page);
    await expectComfortableCatalogueMetadata(page);
    const releaseCards = page.locator(".release-card");
    await expect(releaseCards.first().getByLabel("Géneros: Acción · Rol")).toBeVisible();
    await expect(releaseCards.locator(".card-title").last()).toHaveCSS("-webkit-line-clamp", "2");
    const metadata = await releaseCards.evaluateAll(cards => cards.map(card => {
      const title = card.querySelector(".card-title");
      const genre = card.querySelector(".card-genres");
      const platform = card.querySelector(".card-release");
      if (!title || !genre || !platform) throw new Error("Expected release metadata");
      return { titleHeight: title.getBoundingClientRect().height, titleLines: title.getBoundingClientRect().height / parseFloat(getComputedStyle(title).lineHeight), genreHeight: genre.getBoundingClientRect().height, platformHeight: platform.getBoundingClientRect().height, contextY: platform.getBoundingClientRect().y };
    }));
    expect(metadata[0]?.titleHeight).toBe(metadata[1]?.titleHeight);
    expect(metadata[1]?.titleLines).toBeLessThanOrEqual(2.01);
    expect(metadata[0]?.contextY).toBe(metadata[1]?.contextY);
    expect(metadata[1]?.genreHeight).toBeLessThanOrEqual(18);
    expect(metadata[1]?.platformHeight).toBeLessThanOrEqual(22);
    await expect(page.locator(".stale-banner")).toHaveCount(0);
    await expect(page.getByText("Datos locales desactualizados")).toHaveCount(0);
    await expect(page.getByText("Información pendiente de revisión")).toBeVisible();
    await expect(page.locator(".catalogue-card .cover-caption")).toHaveCount(0);
    await expect(page.locator(".catalogue-card").getByText(/^Fuente:/)).toHaveCount(0);
    await expect(page.locator(".catalogue-card").getByText("Ver ficha →")).toHaveCount(0);
    await page.keyboard.press("Tab");
    await expect(page.getByRole("link", { name: "Saltar al contenido" })).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(page.getByRole("main")).toBeFocused();
    const next = page.getByRole("link", { name: "Página siguiente" });
    await next.focus();
    await expect(next).toHaveCSS("outline-style", "solid");
    await page.keyboard.press("Enter");
    await expect(page.getByRole("heading", { name: "Resultados", exact: true })).toBeFocused();
    await expect(page).toHaveURL(/page=2/);
    await expect(page.getByRole("link", { name: "Página siguiente" })).toHaveCount(0);
    await openReleaseControls(page);
    await page.getByRole("combobox", { name: /^Plataforma:/ }).click();
    await page.getByRole("option", { name: "Windows PC" }).click();
    await expect(page).toHaveURL(/platformIds=windows-pc/);
    await expect(page).not.toHaveURL(/page=2/);
    await page.getByRole("link", { name: "Quitar filtros" }).first().click();
    await expectAccessibleLayout(page);
    await page.getByLabel("Desde").fill("2026-07-17");
    await page.getByLabel("Hasta").fill("2026-08-13");
    await page.getByLabel("Hasta").press("Enter");
    await expect(page).toHaveURL(/from=2026-07-17&to=2026-08-13/);
    await expect(page).not.toHaveURL(/page=2/);
    await expect(page.locator(".release-period")).toContainText(
      "Del 17 de julio de 2026 al 13 de agosto de 2026",
    );
    await expect(
      page.getByRole("toolbar", { name: "Elegir un día del periodo" }).getByRole("button"),
    ).toHaveCount(28);
    await expectAccessibleLayout(page);
    if (width < 620) {
      const logo = await page.getByRole("link", { name: "Gameómetro · Inicio" }).boundingBox();
      const primary = page.getByRole("navigation", { name: "Principal" });
      const recent = await primary.getByRole("button", { name: "Lanzamientos", exact: true }).boundingBox();
      const search = await page.getByRole("button", { name: "Buscar juegos" }).boundingBox();
      expect(logo?.y).toBe(search?.y);
      // The two release entries share the identity's row from 360px; narrower phones give
      // them a full-width row of their own under the identity and both icons.
      if (width >= 360) {
        expect(logo?.y).toBe(recent?.y);
      } else {
        expect(recent?.y ?? 0).toBeGreaterThan((logo?.y ?? 0) + (logo?.height ?? 0));
      }
      await page.getByRole("button", { name: "Buscar juegos" }).click();
      await expect(page.getByRole("dialog", { name: "Buscar juegos" })).toBeVisible();
      await expect(page.getByRole("combobox", { name: "Buscar juegos" })).toBeFocused();
      await page.keyboard.press("Escape");
      await expect(page.getByRole("dialog", { name: "Buscar juegos" })).not.toBeVisible();
      await expect(page.getByRole("button", { name: "Buscar juegos" })).toBeFocused();
      await page.keyboard.press("/");
      await expect(page.getByRole("combobox", { name: "Buscar juegos" })).toBeFocused();
    }
    const catalogueSearch = page.getByRole("combobox", { name: "Buscar juegos" });
    await catalogueSearch.fill("Pragmata");
    await catalogueSearch.press("Enter");
    if (width >= 620) await expect(page.getByRole("combobox", { name: "Buscar juegos" })).toBeVisible();
    await expectAccessibleLayout(page);
  });
}

for (const range of ["", "?from=2026-08-14&to=2026-11-13"] as const) {
test(`the explorer${range === "" ? "" : " over a future range"} defaults to two rows of six games on desktop`, async ({ page }) => {
  await page.setViewportSize({ width: 1320, height: 950 });
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.route("**/api/v1/releases?*", async (route) => {
    expect(new URL(route.request().url()).searchParams.get("pageSize")).toBe("12");
    await route.fulfill({ json: releasePage({
      window: range === ""
        ? { from: "2026-08-10", to: "2026-08-16" }
        : { from: "2026-08-14", to: "2026-11-13" },
      items: Array.from({ length: 12 }, (_, index) => ({
        ...pragmata,
        gameId: `30000000-0000-4000-8000-${String(index + 1).padStart(12, "0")}`,
        canonicalTitle: `Juego ${index + 1}`,
        releases: [
          {
            ...pragmataRelease,
            releaseId: `40000000-0000-4000-8000-${String(index + 1).padStart(12, "0")}`,
            stage: "unknown",
          },
        ],
      })),
      page: { number: 1, size: 12, totalItems: 12, totalPages: 1 },
    }) });
  });

  await page.goto(`/lanzamientos${range}`);
  await expect(page.locator(".release-grid > li")).toHaveCount(12);
  expect(await page.locator(".release-grid").evaluate((grid) =>
    getComputedStyle(grid).gridTemplateColumns.split(" ").length,
  )).toBe(6);
  const rows = await page.locator(".release-grid > li").evaluateAll((items) =>
    items.map((item) => item.getBoundingClientRect().top),
  );
  expect(new Set(rows).size).toBe(2);
  await expect(page.getByRole("combobox", { name: /^Plataforma:/ })).toBeVisible();
  await expect(page.getByRole("combobox", { name: /^Región:/ })).toBeVisible();
  await expect(page.locator(".release-period")).toBeVisible();
  await expectAccessibleLayout(page);

  await page.getByRole("combobox", { name: /^Plataforma:/ }).focus();
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/platformIds=windows-pc/);
  await expect(page.getByRole("combobox", { name: /^Plataforma:/ })).toContainText("Windows PC");
});
}

test("a future range keeps its compact layout and keyboard filters on a phone", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.route("**/api/v1/releases?*", async (route) => {
    await route.fulfill({ json: releasePage({
      window: { from: "2026-08-14", to: "2026-11-13" },
      items: [{
        ...pragmata,
        releases: [
          {
            ...pragmataRelease,
            releaseDate: { precision: "day", value: "2026-09-25" },
            status: "announced",
          },
        ],
      }],
    }) });
  });

  await page.goto("/lanzamientos?from=2026-08-14&to=2026-11-13");
  await expect(page.getByRole("heading", { level: 1, name: "Explorar lanzamientos" })).toBeVisible();
  await openReleaseControls(page);
  await expect(page.locator(".release-period")).toContainText("14 ago – 13 nov 2026");
  const title = await page.getByRole("heading", { level: 1 }).boundingBox();
  const period = await page.locator(".release-period").boundingBox();
  const platform = await page.getByRole("combobox", { name: /^Plataforma:/ }).boundingBox();
  const region = await page.getByRole("combobox", { name: /^Región:/ }).boundingBox();
  // The period readout follows the title, beside it or on the next line, never above it.
  expect(period?.y ?? 0).toBeGreaterThanOrEqual(title?.y ?? 0);
  expect(platform?.y).toBe(region?.y);
  // Both dates share a row; a frame wider than the phone scrolls inside the bar, never the page.
  const from = await page.getByLabel("Desde").boundingBox();
  const to = await page.getByLabel("Hasta").boundingBox();
  expect(from?.y).toBe(to?.y);
  const days = page.getByRole("toolbar", { name: "Elegir un día del periodo" }).getByRole("button");
  await expect(days).toHaveCount(92);
  expect((await days.first().boundingBox())?.width ?? 0).toBeGreaterThanOrEqual(28);
  await expectAccessibleLayout(page);
  await page.getByRole("combobox", { name: /^Región:/ }).focus();
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/regionIds=worldwide/);
  await expect(page.getByRole("combobox", { name: /^Región:/ })).toContainText("Mundial");
});

for (const width of [320, 834, 1320]) {
  test(`approximate-date opt-in fits and stays keyboard-operable at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.emulateMedia({ reducedMotion: "reduce" });
    await page.route("**/api/v1/releases?*", async (route) => {
      const approximate =
        new URL(route.request().url()).searchParams.get("includeApproximateDates") === "true";
      await route.fulfill({ json: releasePage({
        items: approximate
          ? [{
              ...pragmata,
              releases: [{
                ...pragmataRelease,
                releaseDate: { precision: "quarter", value: "2026-Q4" },
                status: "scheduled",
              }],
            }]
          : [],
        page: approximate
          ? { number: 1, size: 12, totalItems: 1, totalPages: 1 }
          : { number: 1, size: 12, totalItems: 0, totalPages: 0 },
      }) });
    });

    await page.goto("/lanzamientos");
    await openReleaseControls(page);
    const optIn = page.getByRole("checkbox", { name: "Incluir fechas aproximadas" });
    await expect(optIn).toBeVisible();
    await expect(optIn).not.toBeChecked();
    await expect(page.getByText(/No tenemos lanzamientos para estos filtros/)).toBeVisible();
    await expectAccessibleLayout(page);

    const range = await page.locator(".release-range").boundingBox();
    const option = await page.locator(".release-approximate-option").boundingBox();
    const toolbar = await page.locator(".releases-toolbar").boundingBox();
    expect(option?.height ?? 0).toBeGreaterThanOrEqual(44);
    if (width >= 834) {
      // From tablet width the opt-in stays on the range's row.
      expect(Math.abs((range?.y ?? 0) + (range?.height ?? 0) / 2
        - (option?.y ?? 0) - (option?.height ?? 0) / 2)).toBeLessThan(2);
    } else {
      // Phones wrap it below the dates instead of squeezing them.
      expect(option?.y ?? 0).toBeGreaterThanOrEqual((range?.y ?? 0) + (range?.height ?? 0));
    }
    // The order and the facets always follow the range and its precision.
    expect(toolbar?.y ?? 0).toBeGreaterThanOrEqual((option?.y ?? 0) + (option?.height ?? 0));

    await optIn.focus();
    await expect(page.locator(".release-approximate-option")).toHaveCSS("outline-style", "solid");
    await page.keyboard.press("Space");
    await expect(page).toHaveURL(/\/lanzamientos\?includeApproximateDates=true/);
    await expect(optIn).toBeChecked();
    await expect(page.locator(".card-date")).toHaveText("T4 2026");
    await expect(page.getByText("4.º trimestre de 2026")).toBeAttached();
    await expectAccessibleLayout(page);
  });
}

test("phone search closes cleanly when the layout widens", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.route("**/api/v1/releases?*", (route) => route.fulfill({ json: releasePage() }));
  await page.goto("/lanzamientos");
  await page.getByRole("button", { name: "Buscar juegos" }).click();
  await expect(page.getByRole("dialog", { name: "Buscar juegos" })).toBeVisible();

  await page.setViewportSize({ width: 834, height: 844 });
  await expect(page.getByRole("dialog", { name: "Buscar juegos" })).not.toBeVisible();
  await expect(page.getByRole("combobox", { name: "Buscar juegos" })).toBeFocused();
});

for (const state of ["loading", "empty", "not-ready", "error"] as const) {
  test(`${state} uses accessible catalogue surfaces and recovery`, async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    let retry = false;
    await page.route("**/api/v1/releases?*", async (route) => {
      if (state === "loading") return; // Kept pending until the page is closed, without timing sleeps.
      if (retry || state === "empty") {
        return route.fulfill({ json: releasePage({ items: [], page: { number: 1, size: 12, totalItems: 0, totalPages: 0 } }) });
      }
      return route.fulfill({ status: 503, json: {
        type: "about:blank", title: "Unavailable", status: 503,
        code: state === "not-ready" ? "CATALOGUE_NOT_READY" : "INTERNAL_ERROR",
        category: "dependency", correlationId: "visual-check-reference",
      } });
    });
  await page.goto("/lanzamientos?platformIds=windows-pc");
    if (state === "loading") {
      await expect(page.getByRole("status")).toHaveText("Cargando lanzamientos…");
      await expect(page.getByRole("article")).toHaveCount(0);
      await page.emulateMedia({ reducedMotion: "reduce" });
    } else if (state === "empty") {
      await expect(page.getByRole("heading", { name: "Sin lanzamientos para esta selección" })).toBeVisible();
      await openReleaseControls(page);
      await expect(page.getByRole("link", { name: "Quitar filtros" })).toHaveCount(2);
    } else if (state === "not-ready") {
      await expect(page.locator(".notice-info")).toHaveAttribute("role", "status");
    } else {
      await expect(page.getByRole("alert")).toBeVisible();
    }
    await expectAccessibleLayout(page);
    if (state === "not-ready" || state === "error") {
      retry = true;
      await page.getByRole("button", { name: "Reintentar" }).click();
      await expect(page.getByRole("heading", { name: "Sin lanzamientos para esta selección" })).toBeVisible();
    }
  });
}

// Long approved notices must retain the same controls, semantics and phone reflow.
for (const width of [320, 390, 834, 1320]) {
  test(`approved copy preserves state meaning and reflows at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.emulateMedia({ reducedMotion: "reduce" });
    let authenticated = false;
    let eligible = false;
    let featuredCode = "INTERNAL_ERROR";
    await page.route("**/api/v1/session", (route) => route.fulfill({ json: authenticated
      ? { authenticated: true, csrfToken: "copy-fixture", capabilities: [] }
      : { authenticated: false } }));
    await page.route("**/api/v1/featured-releases*", (route) => route.fulfill({ status: 503, json: { code: featuredCode } }));
    await page.route("**/api/v1/releases?*", (route) => route.fulfill({ json: releasePage({
      items: [], page: { number: 1, size: 12, totalItems: 0, totalPages: 0 },
    }) }));
    const empty = { items: [], page: { number: 1, size: 10, totalItems: 0, totalPages: 0 } };
    await page.route("**/api/v1/games?*", (route) => route.fulfill({ json: empty }));
    await page.route("**/api/v1/me/ratings?*", (route) => route.fulfill({ json: empty }));
    const game = gameDetailsFixture();
    await page.route("**/api/v1/games/*", (route) => route.fulfill({ json: {
      ...game, ratingEligibility: { ...game.ratingEligibility, eligible,
        reason: eligible ? "ELIGIBLE_RELEASE_FOUND" : "NO_COMMERCIAL_RELEASE" },
    } }));

    await page.goto("/");
    await expect(page.getByRole("heading", { name: "Ups, parece que Destacados se han tomado unas vacaciones. Prueba de nuevo más tarde" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Reintentar" })).toBeEnabled();
    await expectAccessibleLayout(page);
    await page.screenshot({ path: test.info().outputPath(`copy-error-${width}.png`) });

    featuredCode = "CATALOGUE_NOT_READY";
    await page.reload();
    await expect(page.getByText("Aún no tenemos datos válidos de juegos, pero tranquilo que llegarán")).toBeVisible();
    await expect(page.getByText("Puedes usar la búsqueda de juegos")).toBeVisible();

    await page.goto("/lanzamientos");
    await expect(page.getByText("No tenemos lanzamientos para estos filtros, pero afinando la búsqueda seguro que los encontramos")).toBeVisible();
    await openReleaseControls(page);
    await expect(page.getByRole("checkbox", { name: "Incluir fechas aproximadas" })).not.toBeChecked();
    await expectAccessibleLayout(page);

    const query = 'Juego <sintético> & "Luz"';
    await page.goto(`/search?${new URLSearchParams({ q: query })}`);
    await expect(page.getByText(`No tenemos nada que coincida con «${query}», al menos por ahora porque Gameómetro no deja de crecer`)).toBeVisible();
    await expectAccessibleLayout(page);

    await page.goto(`/games/${game.gameId}/${game.slug}`);
    const community = page.getByRole("region", { name: "Puntuación de la comunidad" });
    await expect(community.getByText("Aún no se puede puntuar")).toBeVisible();
    await expect(page.getByRole("button", { name: "Tu puntuación: sin puntuación. No disponible" }))
      .toHaveAccessibleDescription("Puntuación no disponible: Aún no tenemos un lanzamiento");
    await expect(community.getByText("Sé el primero en puntuar este juego")).toHaveCount(0);
    eligible = true;
    await page.reload();
    await expect(community.getByText("Sé el primero en puntuar este juego")).toBeVisible();
    await expectAccessibleLayout(page);

    authenticated = true;
    await page.goto("/mis-puntuaciones");
    await expect(page.getByRole("heading", { name: "No has puntuado nada aún. Un Gameómetro muere en el mundo cada vez que tú decides no puntuar" })).toBeVisible();
    await expectAccessibleLayout(page);
    await page.screenshot({ path: test.info().outputPath(`copy-empty-${width}.png`) });
  });
}
