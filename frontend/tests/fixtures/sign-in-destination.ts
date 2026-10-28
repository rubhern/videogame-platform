import { expect, type Page } from "@playwright/test";

/**
 * Finish the return to the landing page, the home (#382), through the real first-run choice,
 * including accounts used on another device.
 */
export async function reachHomeAfterSignIn(page: Page, applicationOrigin: string) {
  await expect(page).toHaveURL(url => url.origin === applicationOrigin &&
    ["/", "/bienvenida"].includes(url.pathname));
  if (new URL(page.url()).pathname === "/bienvenida") {
    await page.getByRole("button", { name: "Ahora no", exact: true }).click();
  }
  await expect(page).toHaveURL(`${applicationOrigin}/`);
}
