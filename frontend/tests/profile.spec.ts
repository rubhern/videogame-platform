import { expect, test } from "@playwright/test";
import { analyzeAccessibility } from "./fixtures/accessibility";

for (const width of [1320, 834, 390, 320]) {
  test(`private profile and deliberate deletion at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    let commands = 0;
    let authenticated = true;
    let deletionAvailable = false;
    await page.route("**/api/v1/session", route => route.fulfill({ json: { authenticated, csrfToken: "browser-proof" } }));
    await page.route("**/api/v1/me/profile", route => route.fulfill({ json: { email: "private@example.test", deletionAvailable } }));
    await page.route("**/api/v1/me/data-export", route => route.fulfill({ contentType: "application/json", headers: { "Content-Disposition": 'attachment; filename="gameometro-datos.json"' }, body: JSON.stringify({ format: "gameometro-personal-data-export", version: 1, ratings: [] }) }));
    await page.route("**/api/v1/me/account-deletion", async route => {
      commands++;
      authenticated = false;
      expect(route.request().postDataJSON()).toEqual({ confirmation: "ELIMINAR" });
      expect(route.request().headers()["x-csrf-token"]).toBe("browser-proof");
      await route.fulfill({ status: 202, json: { state: "pending" } });
    });
    await page.route("**/api/v1/account-deletion", route => route.fulfill({ json: { state: "pending" } }));
    await page.goto("/mi-perfil");
    await expect(page.getByRole("heading", { name: "Mi perfil", exact: true })).toBeVisible();
    await expect(page.getByText("private@example.test")).toBeVisible();
    await expect(page.getByText("Nombre", { exact: true })).toHaveCount(0);
    const unavailable = page.getByRole("button", { name: "Eliminar mi cuenta", exact: true });
    await expect(unavailable).toBeVisible();
    await expect(unavailable).toBeDisabled();
    await expect(unavailable).toHaveAccessibleDescription(/El borrado automático no está disponible ahora/);
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
    deletionAvailable = true;
    await page.reload();
    await expect(page.getByText("private@example.test")).toBeVisible();
    await expect(unavailable).toBeEnabled();
    // Navigate away like a hosted identity page, then return through browser history.
    await page.route("**/auth/profile/security", route => route.fulfill({ contentType: "text/html; charset=utf-8", body: "<html lang='es'><meta charset='utf-8'><title>Contraseña</title><h1>Cambiar contraseña</h1></html>" }));
    await page.getByRole("link", { name: "Cambiar contraseña", exact: true }).click();
    await expect(page.getByRole("heading", { name: "Cambiar contraseña" })).toBeVisible();
    await page.goBack();
    await expect(page.getByText("private@example.test")).toBeVisible();
    const download = page.waitForEvent("download");
    await page.getByRole("button", { name: "Exportar mis datos" }).click();
    expect((await download).suggestedFilename()).toBe("gameometro-datos.json");
    const trigger = page.getByRole("button", { name: "Eliminar mi cuenta", exact: true });
    await trigger.click();
    await expect(page.getByRole("button", { name: "Conservar mi cuenta" })).toBeFocused();
    await page.keyboard.press("Escape"); await expect(trigger).toBeFocused(); expect(commands).toBe(0);
    await trigger.click();
    await page.getByLabel("Escribe ELIMINAR para confirmar").fill("ELIMINAR");
    const a11y = await analyzeAccessibility(page);
    expect(a11y.violations).toEqual([]);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await page.getByRole("button", { name: "Sí, solicitar el borrado" }).click();
    await expect(page).toHaveURL("/");
    await expect(page.getByText("Comprobando el resultado…")).toHaveCount(0);
    await expect(page.getByText("Solicitud recibida", { exact: true })).toHaveCount(0);
    expect(commands).toBe(1);
    await expect(page.getByRole("link", { name: "Iniciar sesión", exact: true }).first()).toBeVisible();
    await expect(page.getByText("private@example.test")).toHaveCount(0);
  });
}
