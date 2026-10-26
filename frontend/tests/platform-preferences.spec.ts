import { expect, test, type Page, type Request } from "@playwright/test";

import { analyzeAccessibility } from "./fixtures/accessibility";

/**
 * Preferred platforms (#295) in the real browser against the packaged application.
 *
 * <p>The public reads — the selectable platforms, Destacados and the explorer — come from the
 * packaged catalogue with its development seed. Only the BFF session and the personal preference
 * resource are scripted at the network boundary, as `my-ratings.spec.ts` does, so the journey runs
 * without Keycloak. This spec owns what a component test cannot prove: the first-run offer reached
 * through the real router, keyboard choice of tiles, reflow at the reference widths, the control's
 * place in each screen's controls and automated accessibility evidence.
 */

const PS5 = { platformId: "10000000-0000-4000-8000-000000000001", name: "PlayStation 5" };
const WIDTHS = [1320, 834, 390, 320] as const;

class ScriptedAccount {
  preferences: { configured: boolean; platforms: { platformId: string; name: string }[] };
  readonly commands: unknown[] = [];
  readonly discoveryReads: URL[] = [];

  constructor(
    private readonly page: Page,
    preferences: ScriptedAccount["preferences"],
  ) {
    this.preferences = preferences;
  }

  async install() {
    await this.page.route("**/api/v1/session", (route) =>
      route.fulfill({ json: { authenticated: true, csrfToken: "browser-csrf" } }),
    );
    await this.page.route("**/api/v1/me/platform-preferences", async (route) => {
      const request = route.request();
      if (request.method() === "PUT") {
        expect(request.headers()["x-csrf-token"]).toBe("browser-csrf");
        const body = request.postDataJSON() as { platformIds: string[] };
        this.commands.push(body);
        this.preferences = {
          configured: true,
          platforms: body.platformIds.includes(PS5.platformId) ? [PS5] : [],
        };
      }
      await route.fulfill({ json: this.preferences, headers: { "Cache-Control": "no-store" } });
    });
    this.page.on("request", (request: Request) => {
      const url = new URL(request.url());
      if (url.pathname === "/api/v1/releases" || url.pathname === "/api/v1/featured-releases") {
        this.discoveryReads.push(url);
      }
    });
  }
}

async function expectNoPageOverflow(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
}

test.describe("first-run platform choice", () => {
  test("is offered on the way into discovery, chosen by keyboard and returns to the destination", async ({ page }) => {
    const account = new ScriptedAccount(page, { configured: false, platforms: [] });
    await account.install();

    await page.goto("/lanzamientos?sort=hype");

    await expect(page).toHaveURL(/\/bienvenida\?returnTo=%2Flanzamientos%3Fsort%3Dhype$/);
    await expect(page.getByRole("heading", { level: 1, name: "¿A qué juegas?" })).toBeVisible();
    const tile = page.getByRole("checkbox", { name: "PlayStation 5" });
    await tile.focus();
    await page.keyboard.press("Space");
    await expect(tile).toBeChecked();
    await expect(page.getByText("1 plataforma elegida")).toBeVisible();

    await page.getByRole("button", { name: "Guardar y continuar" }).click();

    await expect(page).toHaveURL(/\/lanzamientos\?sort=hype$/);
    expect(account.commands).toEqual([{ platformIds: [PS5.platformId] }]);
    await expect
      .poll(() => account.discoveryReads.at(-1)?.searchParams.getAll("platformIds"))
      .toEqual([PS5.platformId]);
  });

  for (const width of WIDTHS) {
    test(`reflows its tiles accessibly at ${width}px`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 });
      await new ScriptedAccount(page, { configured: false, platforms: [] }).install();

      await page.goto("/bienvenida");

      await expect(page.getByRole("group", { name: "Tus plataformas" })).toBeVisible();
      await page.getByRole("checkbox", { name: "PlayStation 5" }).check();
      await expectNoPageOverflow(page);
      // Selection is a shape too: the tick shows beside the ice light.
      const tick = page.locator(".platform-tile:has(input:checked) .platform-tile-check");
      await expect(tick).toBeVisible();
      expect((await analyzeAccessibility(page)).violations).toEqual([]);
    });
  }

  test("is skipped once and never offered again in that document", async ({ page }) => {
    const account = new ScriptedAccount(page, { configured: false, platforms: [] });
    await account.install();

    await page.goto("/");
    await expect(page).toHaveURL(/\/bienvenida$/);
    await page.getByRole("button", { name: "Ahora no" }).click();

    await expect(page).toHaveURL(/\/lanzamientos\/destacados$/);
    expect(account.commands).toEqual([{ platformIds: [] }]);
    await page.getByRole("button", { name: "Lanzamientos", exact: true }).click();
    await page.getByRole("navigation", { name: "Principal" }).getByRole("link", { name: "Explorar", exact: true }).click();
    await expect(page).toHaveURL(/\/lanzamientos$/);
    await expect(page.getByRole("heading", { level: 1, name: "Explorar lanzamientos" })).toBeVisible();
  });
});

test.describe("Mis plataformas in Mi cuenta", () => {
  for (const width of [1320, 390] as const) {
    test(`applies or sets aside the preferences for both screens at ${width}px`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 });
      const account = new ScriptedAccount(page, { configured: true, platforms: [PS5] });
      await account.install();

      await page.goto("/?granularity=month");
      await page.getByRole("button", { name: "Mi cuenta" }).click();
      const check = page.getByRole("checkbox", { name: "Filtrar por mis plataformas" });
      await expect(check).toBeChecked();
      await expect(page.getByRole("link", { name: "Mis plataformas", exact: true })).toBeVisible();
      await expectNoPageOverflow(page);
      expect((await analyzeAccessibility(page)).violations).toEqual([]);
      expect(account.discoveryReads.at(-1)?.searchParams.getAll("platformIds")).toEqual([PS5.platformId]);

      await check.click();
      await expect(check).not.toBeChecked();
      await expect
        .poll(() => account.discoveryReads.at(-1)?.searchParams.has("platformIds"))
        .toBe(false);
      expect(account.commands).toEqual([]);

      // The same document-wide choice holds in the explorer.
      await page.keyboard.press("Escape");
      await page.getByRole("button", { name: "Lanzamientos", exact: true }).click();
    await page.getByRole("navigation", { name: "Principal" }).getByRole("link", { name: "Explorar", exact: true }).click();
      await expect(page).toHaveURL(/\/lanzamientos$/);
      await expect
        .poll(() => account.discoveryReads.at(-1)?.pathname)
        .toBe("/api/v1/releases");
      expect(account.discoveryReads.at(-1)?.searchParams.has("platformIds")).toBe(false);
      await expectNoPageOverflow(page);
    });
  }

  test("is managed apart from discovery in Mis plataformas", async ({ page }) => {
    const account = new ScriptedAccount(page, { configured: true, platforms: [PS5] });
    await account.install();

    await page.goto("/");
    await page.getByRole("button", { name: "Mi cuenta" }).click();
    await page.getByRole("link", { name: "Mis plataformas", exact: true }).click();

    await expect(page.getByRole("heading", { level: 1, name: "Mis plataformas" })).toBeVisible();
    await expect(page.getByRole("checkbox", { name: "PlayStation 5" })).toBeChecked();
    await page.getByRole("button", { name: "Quitar todas" }).click();
    await page.getByRole("button", { name: "Guardar cambios" }).click();
    await expect(page.getByText("Selección guardada")).toBeVisible();
    expect(account.commands).toEqual([{ platformIds: [] }]);
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
  });
});
