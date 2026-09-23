import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";

import type { GameSearchPage } from "../src/features/search/game-search-api";
import { gameDetailsFixture } from "../src/test/game-details-fixture";
import { releasePage } from "./fixtures/releases";

const game = gameDetailsFixture();

function suggestion(gameId: string, slug: string, title: string): GameSearchPage["items"][number] {
  return {
    gameId,
    slug,
    canonicalTitle: title,
    primaryCover: {
      kind: "fallback",
      url: "/assets/covers/fallback.svg",
      alternativeText: `Portada no disponible de ${title}`,
      attribution: null,
    },
    releaseContext: [
      {
        platform: { platformId: "10000000-0000-4000-8000-000000000003", name: "Windows PC" },
        region: { regionId: "20000000-0000-4000-8000-000000000002", name: "Europe" },
        releaseDate: { precision: "day", value: "2026-02-27" },
        status: "released",
        freshnessStatus: "fresh",
      },
      {
        platform: { platformId: "10000000-0000-4000-8000-000000000001", name: "PlayStation 5" },
        region: { regionId: "20000000-0000-4000-8000-000000000002", name: "Europe" },
        releaseDate: { precision: "year", value: "2024" },
        status: "released",
        freshnessStatus: "fresh",
      },
    ],
    releaseSummary: {
      platforms: [
        { platformId: "10000000-0000-4000-8000-000000000001", name: "PlayStation 5" },
        { platformId: "10000000-0000-4000-8000-000000000003", name: "Windows PC" },
      ],
      totalPlatforms: 2,
      earliestKnownYear: 2024,
      latestKnownYear: 2026,
    },
  };
}

const suggestions: GameSearchPage = {
  items: [
    { ...suggestion(game.gameId, game.slug, game.canonicalTitle), matchedAlias: "RE Requiem" },
    suggestion("30000000-0000-4000-8000-000000000010", "resident-evil-village", "Resident Evil Village"),
  ],
  page: { number: 1, size: 5, totalItems: 2, totalPages: 1 },
};

async function scriptCatalogue(page: Page) {
  await page.route("**/api/v1/session", (route) => route.fulfill({ json: { authenticated: false } }));
  await page.route("**/api/v1/releases?*", (route) => route.fulfill({ json: releasePage() }));
  await page.route("**/api/v1/games?*", (route) => route.fulfill({ json: suggestions }));
  await page.route(`**/api/v1/games/${game.gameId}`, (route) => route.fulfill({ json: game }));
}

for (const width of [1320, 390]) {
  test(`typeahead opens a game from the keyboard at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await scriptCatalogue(page);
    await page.goto("/");

    if (width < 620) {
      await page.getByRole("button", { name: "Buscar juegos" }).click();
    }
    const search = page.getByRole("combobox", { name: "Buscar en el catálogo" });
    await search.fill("resident");

    const listbox = page.getByRole("listbox", { name: "Sugerencias de juegos" });
    await expect(listbox.getByRole("option")).toHaveCount(2);
    // Intersection accounts for ancestor clipping, which a bounding box alone would miss.
    await expect(listbox.getByRole("option").last()).toBeInViewport({ ratio: 1 });
    await expect(page.getByRole("button", { name: "Ver todos los resultados para «resident»" })).toBeInViewport({ ratio: 1 });
    await expect(search).toBeFocused();
    // A dot separates neighbouring platform icons; none trails the last one.
    const separators = await listbox.getByRole("option").first()
      .locator(".search-suggestion-platforms > *")
      .evaluateAll((icons) => icons.map((icon) => getComputedStyle(icon, "::after").content));
    expect(separators).toEqual(['""', "none"]);
    expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);

    // The popup hangs directly under the search control at exactly its width; on phones it
    // spans the search dialog's row so the compact rows keep their room.
    const control = await (width < 620
      ? page.getByRole("dialog", { name: "Buscar juegos" }).locator(".mobile-search-dialog-content")
      : page.getByRole("search").filter({ has: search })
    ).boundingBox();
    const popup = await page.locator(".search-suggestions").filter({ has: listbox }).boundingBox();
    expect(Math.abs((popup?.x ?? 0) - (control?.x ?? 0))).toBeLessThanOrEqual(1);
    expect(Math.abs((popup?.width ?? 0) - (control?.width ?? 0))).toBeLessThanOrEqual(1);
    expect((popup?.y ?? 0) - ((control?.y ?? 0) + (control?.height ?? 0))).toBeGreaterThanOrEqual(0);

    expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);

    // Escape closes only the popup, even inside the phone search dialog.
    await page.keyboard.press("Escape");
    await expect(listbox).toHaveCount(0);
    await expect(search).toBeFocused();
    await expect(search).toHaveValue("resident");

    await page.keyboard.press("ArrowDown");
    await expect(listbox).toBeVisible();
    await page.keyboard.press("ArrowDown");
    await expect(listbox.getByRole("option").first()).toHaveAttribute("aria-selected", "true");
    await page.keyboard.press("Enter");

    await expect(page).toHaveURL(new RegExp(`/games/${game.gameId}/${game.slug}$`));
    await expect(page.getByRole("heading", { level: 1, name: game.canonicalTitle })).toBeVisible();
  });
}
