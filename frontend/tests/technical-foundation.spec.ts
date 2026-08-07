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
  await page.getByRole("link", { name: "Volver al inicio" }).press("Enter");
  await expect(page).toHaveURL("http://127.0.0.1:4173/");
});
