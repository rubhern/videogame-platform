import { expect, test, type Locator } from "@playwright/test";
import { analyzeAccessibility } from "./fixtures/accessibility";
import { featuredReleases, releasePage } from "./fixtures/releases";
import type { components } from "../src/shared/api/generated/schema";

type Scores = components["schemas"]["DiscoveryScores"];
// Deterministic contract fixtures exercise states missing from the real local catalogue.
const both = {
  community: { status: "available", mean: 9, count: 1 },
  external: { source: "igdb", kind: "player", value: 8.8, scale: 10, count: 1,
    attribution: { label: "Jugadores IGDB", sourceUrl: "https://www.igdb.com/games/original" },
    observedAt: "2020-01-01T00:00:00Z" },
} satisfies Scores;
const states: Scores[] = [both, { community: both.community },
  { community: { status: "unavailable", reasonCode: "RATING_STATISTICS_READ_FAILED" }, external: both.external },
  { community: { status: "available", mean: null, count: 0 } },
  { ...both, community: { status: "available", mean: 6.5, count: 123456 } },
];

async function expectCompactReflow(scores: Locator) {
  const geometry = await scores.evaluate(list => {
    const bounds = list.getBoundingClientRect();
    return {
      width: bounds.width,
      gap: Number.parseFloat(getComputedStyle(list).columnGap),
      sources: Array.from(list.children, source => {
        const rect = source.getBoundingClientRect();
        const contentWidth = Array.from(source.querySelectorAll(
          ".discovery-score-mark, .discovery-score-reading, .discovery-score-count > [aria-hidden='true']",
        ), part => part.getBoundingClientRect().width).reduce((sum, width) => sum + width, 0)
          + 2 * Number.parseFloat(getComputedStyle(source).columnGap);
        return { x: rect.x - bounds.x, y: rect.y, width: rect.width, contentWidth, bottom: rect.bottom,
          fits: source.scrollWidth <= source.clientWidth };
      }),
    };
  });
  for (const source of geometry.sources) {
    expect(source.x).toBeGreaterThanOrEqual(0);
    expect(source.x + source.width).toBeLessThanOrEqual(geometry.width + 1);
    expect(source.width).toBeLessThanOrEqual(source.contentWidth + 1);
    expect(source.fits).toBe(true);
  }
  const [first, second] = geometry.sources;
  if (first !== undefined && second !== undefined) {
    if (first.contentWidth + second.contentWidth + geometry.gap <= geometry.width) {
      expect(Math.abs(first.y - second.y)).toBeLessThanOrEqual(1);
      expect(second.x).toBeGreaterThanOrEqual(first.width + geometry.gap - 1);
    } else {
      expect(second.y).toBeGreaterThanOrEqual(first.bottom);
      expect(second.x).toBe(0);
    }
  }
}

test("featured cards fit two three-digit counts and abbreviate thousands without reducing logos or text", async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.route("https://images.igdb.com/**", route => route.fulfill({ status: 404 }));
  await page.route("**/api/v1/session", route => route.fulfill({ json: { authenticated: false } }));
  const response = featuredReleases();
  const pairs = [[345, 584], [999, 999], [3548, 9876], [1000, 10000], [1, 31]] as const;
  await page.route("**/api/v1/featured-releases*", route => route.fulfill({ json: {
    ...response, items: response.items.map((item, index) => {
      const [communityCount, externalCount] = pairs[Math.max(0, index - 1)] ?? [1, 1];
      return { ...item, scores: { ...both,
        community: { status: "available", mean: index === 2 ? 10 : 2, count: communityCount },
        external: { ...both.external, value: index === 2 ? 10 : 7.3, count: externalCount },
      } };
    }),
  } }));
  for (const width of [320, 390, 834, 1320]) {
    await page.setViewportSize({ width, height: 1050 });
    await page.goto("/?granularity=month");
    await page.evaluate(() => document.fonts.ready);
    const cards = page.locator(".featured-card");
    await expect(cards).toHaveCount(5);
    for (const scores of await cards.locator(".discovery-scores").all()) {
      await expect(scores).toBeVisible();
      const positions = await scores.getByRole("listitem").evaluateAll(sources =>
        sources.map(source => source.getBoundingClientRect().y));
      expect(positions).toHaveLength(2);
      expect(positions[0]).toBe(positions[1]);
      await expectCompactReflow(scores);
      for (const source of await scores.getByRole("listitem").all()) {
        expect(await source.locator(".discovery-score-mark").evaluate(mark => ({
          width: getComputedStyle(mark).width, height: getComputedStyle(mark).height,
        }))).toEqual({ width: "32px", height: "26px" });
        expect(await source.locator(".discovery-score-reading").evaluate(reading => getComputedStyle(reading).fontSize))
          .toBe("14px");
        expect(await source.locator(".discovery-score-count").evaluate(count => getComputedStyle(count).fontSize))
          .toBe("12px");
      }
    }
    const threeDigits = cards.nth(0).getByRole("list", { name: /^Puntuaciones de / });
    await expect(threeDigits.getByText("345 valoraciones", { exact: true })).toBeAttached();
    await expect(threeDigits.getByText("584 valoraciones", { exact: true })).toBeAttached();
    const thousands = cards.nth(2).getByRole("list", { name: /^Puntuaciones de / });
    await expect(thousands.getByText("(+3K)", { exact: true })).toBeVisible();
    await expect(thousands.getByText("(+9K)", { exact: true })).toBeVisible();
    await expect(thousands.getByText("3.548 valoraciones", { exact: true })).toBeAttached();
    await expect(thousands.getByText("9.876 valoraciones", { exact: true })).toBeAttached();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.locator(".featured-others").screenshot({ path: `test-results/featured-score-row-${width}.png` });
  }
});

for (const width of [320, 390, 834, 1320]) {
  test(`discovery scores reflow, source attribution and accessibility at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1050 });
    // Fixture CDN identifiers are not real media; keep this layout check independent of the network.
    await page.route("https://images.igdb.com/**", route => route.fulfill({ status: 404 }));
    await page.route("**/api/v1/session", route => route.fulfill({ json: { authenticated: false } }));
    await page.route("**/api/v1/featured-releases*", route => {
      const response = featuredReleases();
      return route.fulfill({ json: { ...response, items: response.items.map((item, index) => ({ ...item,
        scores: states[index % states.length],
      })) } });
    });
    await page.route("**/api/v1/releases*", route => route.fulfill({ json: {
      ...releasePage(), items: states.map((scores, index) => ({ ...releasePage().items[0],
        gameId: `30000000-0000-4000-8000-${String(950 + index).padStart(12, "0")}`, scores,
      })),
      page: { number: 1, size: 6, totalItems: states.length, totalPages: 1 },
    } }));
    await page.goto("/?granularity=month");
    await page.evaluate(() => document.fonts.ready);
    const hero = page.locator(".featured-hero");
    const ratings = hero.getByRole("list", { name: /^Puntuaciones de / });
    await expect(ratings).toBeVisible();
    await expect(ratings.getByRole("listitem")).toHaveCount(2);
    await expect(ratings.getByLabel("9,0 de 10")).toBeVisible();
    await expect(ratings.getByLabel("8,8 de 10")).toBeVisible();
    await expect(ratings.getByText("1 valoración", { exact: true })).toHaveCount(2);
    const attribution = ratings.getByRole("link", { name: /^Jugadores IGDB:/ });
    await expect(attribution).toHaveAttribute("href", "https://www.igdb.com/games/original");
    expect(await attribution.evaluate(link => link.parentElement?.closest("a") === null)).toBe(true);
    await attribution.focus();
    await expect(attribution).toBeFocused();
    expect(await attribution.evaluate(link => getComputedStyle(link).outlineStyle)).not.toBe("none");
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
    // Placement is verified by the browser, including narrow reflow.
    const ratingsBox = await ratings.boundingBox();
    if (ratingsBox === null) throw new Error("Scores have no visible box");
    const sourceTop = ratingsBox.y;
    const metadataBottom = await hero.locator(".featured-hero-meta").evaluate(el => el.getBoundingClientRect().bottom);
    expect(sourceTop).toBeGreaterThan(metadataBottom);
    const featuredCards = page.locator(".featured-card");
    await expect(featuredCards.nth(0).locator(".discovery-score")).toHaveCount(1);
    await expect(featuredCards.nth(1).locator(".discovery-score")).toHaveCount(1);
    await expect(featuredCards.nth(2).locator(".discovery-scores")).toHaveCount(0);
    const featuredScores = featuredCards.nth(4).getByRole("list", { name: /^Puntuaciones de / });
    await expect(featuredScores.getByRole("listitem")).toHaveCount(2);
    for (const scores of await page.locator(".featured-card .discovery-scores").all()) {
      await expectCompactReflow(scores);
    }
    await page.screenshot({ path: `test-results/discovery-scores-fixture-${width}.png`, fullPage: true });
    await page.goto("/?view=upcoming");
    const compact = page.locator(".release-card .discovery-scores").first();
    await expect(compact).toBeVisible();
    const card = page.locator(".release-card").first();
    // The card can still be rising in: compare geometry from the same frame.
    const { titleBottom, platformTop, scoreBox } = await card.evaluate(el => {
      const title = el.querySelector(".card-title");
      const platform = el.querySelector(".card-release");
      const scores = el.querySelector(".discovery-scores");
      if (!title || !platform || !scores) throw new Error("Release card content is missing");
      const box = scores.getBoundingClientRect();
      return {
        titleBottom: title.getBoundingClientRect().bottom,
        platformTop: platform.getBoundingClientRect().top,
        scoreBox: { y: box.y, height: box.height },
      };
    });
    expect(scoreBox.y).toBeGreaterThanOrEqual(titleBottom);
    expect(scoreBox.y + scoreBox.height).toBeLessThanOrEqual(platformTop);
    await expect(compact.getByText("1 valoración", { exact: true })).toHaveCount(2);
    await expect(page.locator(".release-card").nth(3).locator(".discovery-scores")).toHaveCount(0);
    for (const scores of await page.locator(".release-card .discovery-scores").all()) {
      await expectCompactReflow(scores);
    }
    await expect(page.getByText("123.456 valoraciones", { exact: true })).toHaveCount(1);
    await page.screenshot({ path: `test-results/discovery-scores-upcoming-fixture-${width}.png`, fullPage: true });
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.emulateMedia({ forcedColors: "active", reducedMotion: "reduce" });
    await expect(compact.getByRole("link", { name: /^Jugadores IGDB:/ })).toBeVisible();
  });
}
