import { expect, test } from "@playwright/test";

import { analyzeAccessibility } from "./fixtures/accessibility";

const documents = [
  ["privacidad", "Política de privacidad"],
  ["condiciones", "Condiciones de uso"],
  ["aviso-legal", "Aviso legal"],
  ["cookies", "Cookies y almacenamiento del navegador"],
] as const;

for (const width of [1320, 320]) {
  test(`anonymous legal pages and footer at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.route("**/api/v1/session", route => route.fulfill({ json: { authenticated: false } }));
    for (const [path, title] of documents) {
      await page.goto(`/${path}`);
      await expect(page.getByRole("heading", { level: 1, name: title })).toBeVisible();
      const article = page.getByRole("article");
      await expect(article).not.toContainText(/Texto aprobado por el titular|Internal editorial|Plantilla administrativa|Status:|Borrador/);
      await expect(page.getByRole("checkbox")).toHaveCount(0);
      const footer = page.getByRole("contentinfo");
      for (const [destination] of documents) {
        await expect(footer.locator(`a[href="/${destination}"]`)).toHaveCount(1);
      }
      await expect(footer.getByRole("link", { name: "Contacto", exact: true }))
        .toHaveAttribute("href", "mailto:hola@gameometro.com");
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
      expect((await analyzeAccessibility(page)).violations).toEqual([]);
    }
    // Internal navigation retains the shared shell, keyboard focus and browser history.
    const privacy = page.getByRole("contentinfo").getByRole("link", { name: "Privacidad", exact: true });
    await privacy.focus();
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(/\/privacidad$/);
    await expect(page.getByRole("heading", { name: "Política de privacidad", exact: true })).toBeVisible();
    await page.goBack();
    await expect(page.getByRole("heading", { name: "Cookies y almacenamiento del navegador", exact: true })).toBeVisible();
  });
}
