import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

test("the technical shell is navigable and has no detectable accessibility violations", async ({
  page,
}) => {
  await page.goto("/");

  await expect(
    page.getByRole("heading", {
      level: 1,
      name: "El frontend ya puede crecer por slices verticales.",
    }),
  ).toBeVisible();

  const accessibilityScanResults = await new AxeBuilder({ page }).analyze();
  expect(accessibilityScanResults.violations).toEqual([]);

  await page.goto("/not-implemented");
  const notFoundAccessibilityScanResults = await new AxeBuilder({ page }).analyze();
  expect(notFoundAccessibilityScanResults.violations).toEqual([]);

  await page.getByRole("link", { name: "Volver al inicio" }).press("Enter");
  await expect(page).toHaveURL("http://127.0.0.1:4173/");
  await expect(page.getByRole("main")).toBeFocused();
});

const responsiveViewports = [
  { name: "minimum", width: 320, height: 568 },
  { name: "phone", width: 390, height: 844 },
  { name: "tablet", width: 768, height: 1024 },
  { name: "desktop", width: 1280, height: 800 },
] as const;

for (const viewport of responsiveViewports) {
  test(`the technical shell fits the ${viewport.name} viewport`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.goto("/");

    await expect(
      page.getByRole("heading", {
        level: 1,
        name: "El frontend ya puede crecer por slices verticales.",
      }),
    ).toBeVisible();

    const hasHorizontalOverflow = await page.evaluate(
      () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
    );
    expect(hasHorizontalOverflow).toBe(false);
  });
}
