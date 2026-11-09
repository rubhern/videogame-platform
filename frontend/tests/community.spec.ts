import { expect, test } from "@playwright/test";

import { analyzeAccessibility } from "./fixtures/accessibility";

for (const width of [1320, 834, 390, 320]) {
  test(`About and contact reflow and remain accessible at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.route("**/api/v1/session", route => route.fulfill({ json: { authenticated: false } }));
    await page.route("**/api/v1/feedback", route => route.fulfill({ status: 204 }));

    await page.goto("/sobre-gameometro");
    await expect(page.getByRole("heading", { name: "Sobre Gameómetro", level: 1 })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    expect((await analyzeAccessibility(page)).violations).toEqual([]);

    const contact = page.getByRole("link", { name: "Ir a contacto y sugerencias" });
    await contact.focus();
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(/\/contacto$/);
    await expect(page.getByRole("heading", { name: "Contacto y sugerencias", level: 1 })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    expect((await analyzeAccessibility(page)).violations).toEqual([]);

    const submit = page.getByRole("button", { name: "Enviar mensaje" });
    await submit.focus();
    await page.keyboard.press("Enter");
    await expect(page.getByLabel("Motivo")).toBeFocused();
    await expect(page.getByRole("alert")).toContainText("Elige un motivo");
    expect((await analyzeAccessibility(page)).violations).toEqual([]);

    await page.getByLabel("Motivo").selectOption("bug");
    await page.getByLabel("Mensaje").fill("Falla al buscar un juego.");
    await submit.focus();
    await page.keyboard.press("Enter");
    await expect(page.getByText(/ha aceptado tu mensaje/)).toBeVisible();
    await expect(page.getByLabel("Mensaje")).toHaveValue("");
  });
}
