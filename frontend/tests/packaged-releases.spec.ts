import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

import type { ReleasePage } from "../src/features/releases/releases-api";

const igdbHosts = new Set(["api.igdb.com", "igdb.com", "images.igdb.com", "www.igdb.com"]);

test("the packaged releases shell reads PostgreSQL through the same-origin API", async ({
  page,
  request,
}) => {
  const providerRequests: string[] = [];
  page.on("request", (browserRequest) => {
    const url = new URL(browserRequest.url());
    if (igdbHosts.has(url.hostname) || url.pathname.toLowerCase().includes("igdb")) {
      providerRequests.push(browserRequest.url());
    }
  });

  const releasesResponsePromise = page.waitForResponse((response) => {
    const url = new URL(response.url());
    return url.pathname === "/api/v1/releases" && url.searchParams.get("view") === "recent";
  });

  await page.goto("/");
  const releasesResponse = await releasesResponsePromise;
  expect(releasesResponse.status()).toBe(200);
  expect(releasesResponse.request().resourceType()).toBe("fetch");
  expect(releasesResponse.url()).toContain("page=1&pageSize=6");

  const releasePage = (await releasesResponse.json()) as ReleasePage;
  expect(releasePage.items.map((item) => item.canonicalTitle)).toContain("Pragmata");

  await expect(
    page.getByRole("region", { name: "Lanzamientos recientes" }),
  ).toBeVisible();
  await expect(page.getByRole("heading", { level: 2, name: "Pragmata" })).toBeVisible();
  await expect(page.getByText("2.º trimestre de 2026")).toBeVisible();

  const accessibilityScanResults = await new AxeBuilder({ page }).analyze();
  expect(accessibilityScanResults.violations).toEqual([]);

  await page.keyboard.press("Tab");
  const gameLink = page.getByRole("link", { name: "Ver Pragmata" });
  await expect(gameLink).toBeFocused();
  await gameLink.press("Enter");
  await expect(page).toHaveURL(/\/games\/pragmata$/);
  await expect(
    page.getByRole("heading", { level: 1, name: "Detalle de juego todavía no disponible" }),
  ).toBeVisible();
  await expect(page.getByRole("main")).toBeFocused();

  for (const serverOwnedPath of ["/api", "/auth", "/actuator"]) {
    const response = await request.get(serverOwnedPath);
    expect(response.headers()["content-type"] ?? "").not.toContain("text/html");
    expect(await response.text()).not.toContain('<div id="root"></div>');
  }

  expect(providerRequests).toEqual([]);
});
