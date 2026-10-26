import { expect, type Page } from "@playwright/test";

/** Finish discovery entry through the real first-run choice, including accounts used on another device. */
export async function reachFeaturedAfterSignIn(page: Page, applicationOrigin: string) {
  await expect(page).toHaveURL(url => url.origin === applicationOrigin &&
    ["/lanzamientos/destacados", "/bienvenida"].includes(url.pathname));
  if (new URL(page.url()).pathname === "/bienvenida") {
    await page.getByRole("button", { name: "Ahora no", exact: true }).click();
  }
  await expect(page).toHaveURL(`${applicationOrigin}/lanzamientos/destacados`);
}
