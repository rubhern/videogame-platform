import { expect, test, type Page } from "@playwright/test";
import { analyzeAccessibility } from "./fixtures/accessibility";

type Setting = {
  key: string; group: string; kind: "integer" | "decimal"; value: number; defaultValue: number;
  minimum: number; maximum: number; origin: string; changedAt?: string;
};

function settings(horizon: number): Setting[] {
  const integer = (key: string, group: string, value: number, maximum: number, minimum = 0): Setting => ({
    key, group, kind: "integer", value, defaultValue: value, minimum, maximum, origin: "default",
  });
  const decimal = (key: string, value: number, maximum: number): Setting => ({
    key, group: "attention", kind: "decimal", value, defaultValue: value, minimum: 0, maximum, origin: "default",
  });
  return [
    integer("featured.weeks-back", "featured", 16, 104),
    integer("featured.weeks-forward", "featured", 16, 104),
    integer("featured.months-back", "featured", 10, 36),
    integer("featured.months-forward", "featured", 5, 24),
    {
      ...integer("upcoming-highlights.horizon-months", "upcoming_highlights", horizon, 24, 1), defaultValue: 6,
      ...(horizon === 6 ? {} : { origin: "operator", changedAt: "2026-11-12T10:00:00Z" }),
    },
    decimal("attention.hypes-weight", 1, 10),
    decimal("attention.want-to-play-weight", 1, 10),
    decimal("attention.visits-weight", 1, 10),
    decimal("attention.editorial-weight", 0.5, 1),
    decimal("attention.inherited-share", 0.5, 1),
    decimal("attention.re-release-share", 0.5, 1),
    integer("rankings.igdb-minimum-ratings", "rankings", 50, 100000, 1),
    integer("rankings.community-minimum-ratings", "rankings", 1, 100000, 1),
    integer("calendar.minimum-hypes", "calendar", 10, 1000000, 1),
    integer("calendar.day-preview-size", "calendar", 3, 10, 1),
    integer("calendar.low-interest-hypes", "calendar", 10, 10000000, 1),
    integer("calendar.moderate-interest-hypes", "calendar", 30, 10000000, 1),
    integer("calendar.high-interest-hypes", "calendar", 100, 10000000, 1),
    integer("calendar.very-high-interest-hypes", "calendar", 150, 10000000, 1),
    { ...integer("synchronization.near-term.past-days", "synchronization", 28, 366), origin: "deployment" },
    { ...integer("synchronization.near-term.future-days", "synchronization", 28, 366), origin: "deployment" },
    { ...integer("synchronization.upcoming.past-days", "synchronization", 0, 366), origin: "deployment" },
    { ...integer("synchronization.upcoming.future-days", "synchronization", 180, 730), origin: "deployment" },
  ];
}

function overview(horizon: number) {
  return {
    revision: horizon === 6 ? 0 : 1,
    settings: settings(horizon),
    coverage: {
      evaluatedOn: "2026-11-12", schedulingEnabled: true, acquisitionForwardDays: 180, acquisitionEnd: "2027-05-11",
      featuredLastMonth: "2027-04", featuredBeyondAcquisition: false,
      upcomingHorizonEnd: horizon === 6 ? "2027-05-11" : "2027-07-11", upcomingBeyondAcquisition: horizon !== 6,
    },
    recentChanges: horizon === 6 ? [] : [{
      revision: 1, key: "upcoming-highlights.horizon-months", previousValue: 6, newValue: horizon,
      restoredDefault: false, changedAt: "2026-11-12T10:00:00Z",
    }],
  };
}

async function install(page: Page) {
  const saved: unknown[] = [];
  await page.route("**/api/v1/session", (route) => route.fulfill({ json: {
    authenticated: true, csrfToken: "csrf", capabilities: ["catalogue_administration"],
  } }));
  await page.route("**/api/v1/admin/catalogue/synchronization", (route) => route.fulfill({ json: { recentRuns: [] } }));
  await page.route("**/api/v1/admin/catalogue/product-settings", async (route) => {
    if (route.request().method() === "PUT") {
      saved.push(route.request().postDataJSON());
      await route.fulfill({ json: overview(8) });
      return;
    }
    await route.fulfill({ json: overview(saved.length > 0 ? 8 : 6) });
  });
  return saved;
}

for (const width of [1320, 834, 390, 320]) {
  test(`product settings reflow without page overflow at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await install(page);
    await page.goto("/administracion/catalogo?tab=ajustes");
    await expect(page.getByLabel("Horizonte")).toHaveValue("6");
    await expect(page.getByRole("heading", { level: 2, name: "Sincronización programada" })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth))
      .toBeLessThanOrEqual(0);
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
    await page.screenshot({ path: `test-results/product-settings-${width}.png`, fullPage: true });
  });
}

test("an operator changes the upcoming horizon by keyboard and reads the coverage warning", async ({ page }) => {
  const saved = await install(page);
  await page.goto("/administracion/catalogo?tab=ajustes");

  const horizon = page.getByLabel("Horizonte");
  await horizon.focus();
  await page.keyboard.press("ControlOrMeta+A");
  await page.keyboard.type("25");
  await expect(horizon).toHaveAttribute("aria-invalid", "true");
  await expect(page.getByText("Debe estar entre 1 y 24.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Guardar cambios" })).toBeDisabled();

  await page.keyboard.press("ControlOrMeta+A");
  await page.keyboard.type("8");
  await expect(page.getByText("1 cambio pendiente.")).toBeVisible();
  await page.getByRole("button", { name: "Guardar cambios" }).focus();
  await page.keyboard.press("Enter");

  await expect(page.locator(".admin-request-feedback")).toBeFocused();
  await expect(page.getByText(/Cambios guardados/)).toBeVisible();
  expect(saved).toEqual([{ revision: 0, changes: [{ key: "upcoming-highlights.horizon-months", value: 8 }] }]);
  await expect(page.getByText("Más allá de la sincronización")).toBeVisible();
  await expect(page.getByRole("button", { name: "Restaurar por defecto" })).toBeVisible();
  await expect(page.getByRole("region", { name: "Cambios recientes" }).getByText("6 → 8")).toBeVisible();
  expect((await analyzeAccessibility(page)).violations).toEqual([]);
});
