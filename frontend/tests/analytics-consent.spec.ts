import { expect, test, type Page } from "@playwright/test";

import { analyzeAccessibility } from "./fixtures/accessibility";

const GOOGLE = /google-analytics\.com|googletagmanager\.com|analytics\.google\.com|doubleclick\.net/;

/** Every request the page attempts towards a Google measurement host. */
function googleRequests(page: Page): string[] {
  const seen: string[] = [];
  page.on("request", (request) => {
    if (GOOGLE.test(request.url())) seen.push(request.url());
  });
  return seen;
}

async function stubDeployment(page: Page, measurementId: string | null) {
  await page.route("**/api/v1/session", (route) => route.fulfill({ json: { authenticated: false } }));
  await page.route("**/api/v1/web-analytics", (route) =>
    route.fulfill({ json: measurementId === null ? {} : { googleAnalytics: { measurementId } } }),
  );
}

const banner = (page: Page) => page.getByRole("region", { name: "¿Nos ayudas a mejorar Gameómetro?" });

test("a deployment without a measurement ID asks nothing and contacts no Google host", async ({ page }) => {
  const requests = googleRequests(page);
  await stubDeployment(page, null);
  await page.goto("/cookies");
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
  await expect(banner(page)).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Configurar cookies" })).toHaveCount(0);
  expect(requests).toEqual([]);
  expect((await page.context().cookies()).filter((cookie) => cookie.name.startsWith("_ga"))).toEqual([]);
});

for (const width of [1320, 320]) {
  test(`the consent notice at ${width}px loads nothing until accepted and nothing after rejection`, async ({ page }) => {
    const requests = googleRequests(page);
    await page.setViewportSize({ width, height: 900 });
    await stubDeployment(page, "G-TEST1234");
    await page.goto("/cookies");

    const notice = banner(page);
    await expect(notice).toBeVisible();
    const reject = notice.getByRole("button", { name: "Rechazar" });
    const accept = notice.getByRole("button", { name: "Aceptar" });
    // Equal prominence: the same size and style for both choices.
    const [rejectBox, acceptBox] = await Promise.all([reject.boundingBox(), accept.boundingBox()]);
    expect(rejectBox?.height).toBe(acceptBox?.height);
    expect(rejectBox?.width).toBe(acceptBox?.width);
    expect(await reject.getAttribute("class")).toBe(await accept.getAttribute("class"));
    // The notice stays in the page flow: the page heading remains visible beneath it.
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    expect((await analyzeAccessibility(page)).violations).toEqual([]);

    // Browsing while undecided loads nothing either.
    await page.getByRole("contentinfo").getByRole("link", { name: "Privacidad", exact: true }).click();
    await expect(page.getByRole("heading", { name: "Política de privacidad", exact: true })).toBeVisible();
    expect(requests).toEqual([]);

    await reject.click();
    await expect(notice).toHaveCount(0);
    await expect(page.locator("#main-content")).toBeFocused();
    await page.reload();
    await expect(page.getByRole("heading", { name: "Política de privacidad", exact: true })).toBeVisible();
    await expect(banner(page)).toHaveCount(0);
    expect(requests).toEqual([]);
    expect((await page.context().cookies()).filter((cookie) => cookie.name.startsWith("_ga"))).toEqual([]);

    // The choice can be reviewed at any time from the footer, by keyboard.
    const settings = page.getByRole("contentinfo").getByRole("button", { name: "Configurar cookies" });
    await settings.focus();
    await page.keyboard.press("Enter");
    await expect(banner(page)).toContainText("Ahora mismo: rechazadas.");
    await expect(page.getByText("¿Nos ayudas a mejorar Gameómetro?")).toBeFocused();
    expect(requests).toEqual([]);
  });
}
