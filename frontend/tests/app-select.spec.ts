import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

import { pragmata, releasePage } from "./fixtures/releases";

test("release filters open from the full trigger and show tinted platform icons", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.route("**/api/v1/releases?*", (route) => route.fulfill({ json: releasePage({
    availableFilters: {
      platforms: [
        { platformId: "nintendo-switch-2", name: "Nintendo Switch 2" },
        { platformId: "playstation-5", name: "PlayStation 5" },
        { platformId: "windows-pc", name: "Windows PC" },
        { platformId: "xbox-series", name: "Xbox Series X|S" },
      ],
      regions: [{ regionId: "worldwide", name: "Worldwide" }],
    },
  }) }));

  await page.goto("/");
  const trigger = page.getByRole("combobox", { name: /^Plataforma:/ });
  await expect(trigger).toBeVisible();
  const box = await trigger.boundingBox();
  expect(box).not.toBeNull();
  if (!box) return;
  await page.mouse.click(box.x + box.width - 7, box.y + box.height / 2);
  await expect(trigger).toHaveAttribute("aria-expanded", "true");

  const list = page.getByRole("listbox", { name: "Plataforma:" });
  await expect(list.getByRole("option")).toHaveCount(5);
  for (const [name, mark] of [
    ["Nintendo Switch 2", "nintendo-switch"],
    ["PlayStation 5", "playstation"],
    ["Windows PC", "windows"],
    ["Xbox Series X|S", "xbox"],
  ] as const) {
    const icon = list.getByRole("option", { name }).locator(`.app-select-icon-${mark}`);
    await expect(icon).toBeVisible();
    expect(await icon.evaluate((element) => getComputedStyle(element).maskImage)).toContain(`${mark}.png`);
  }
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  await list.getByRole("option", { name: "Windows PC" }).click();
  await expect(page).toHaveURL(/platformId=windows-pc/);
  await expect(trigger).toContainText("Windows PC");
  expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
});

test("personal rating dropdowns use the same keyboard and visual treatment", async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 844 });
  await page.route("**/api/v1/session", (route) => route.fulfill({ json: { authenticated: true, csrfToken: "test-csrf" } }));
  await page.route("**/api/v1/me/ratings**", (route) => route.fulfill({ json: {
    items: [{
      game: {
        gameId: pragmata.gameId,
        slug: pragmata.slug,
        canonicalTitle: pragmata.canonicalTitle,
        primaryCover: pragmata.primaryCover,
      },
      personalRating: {
        gameId: pragmata.gameId,
        value: 7,
        createdAt: "2026-08-13T10:00:00Z",
        updatedAt: "2026-08-13T10:00:00Z",
        entityTag: '"version-1"',
      },
    }],
    page: { number: 1, size: 20, totalItems: 1, totalPages: 1 },
  } }));

  await page.goto("/mis-puntuaciones");
  const sort = page.getByRole("combobox", { name: /^Ordenar por/ });
  await expect(sort).toBeVisible();
  await sort.focus();
  await page.keyboard.press("ArrowDown");
  await expect(page.getByRole("listbox", { name: "Ordenar por" })).toBeVisible();
  await page.keyboard.press("Escape");
  await page.getByRole("button", { name: "Editar puntuación" }).click();
  const score = page.getByRole("combobox", { name: /^Nueva puntuación/ });
  await expect(score).toBeFocused();
  await score.click();
  await expect(page.getByRole("option", { name: "9/10" })).toBeVisible();
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
});
