import { expect, type Page } from "@playwright/test";

/**
 * Finish the return to the landing page, the home (#382), through the real first-run choice,
 * including accounts used on another device. `/` is a real page now, so wait until it or the
 * welcome it may redirect to has rendered before deciding. Both `/` and its heading appear in
 * the loading surface too; the personal region only exists once the home loader has completed.
 */
export async function reachHomeAfterSignIn(page: Page, applicationOrigin: string) {
  const home = page.getByRole("region", { name: "Tu criterio. Tu historia.", exact: true });
  const welcome = page.getByRole("button", { name: "Ahora no", exact: true });
  await expect(home.or(welcome)).toBeVisible();
  if (await welcome.isVisible()) await welcome.click();
  await expect(home).toBeVisible();
  await expect(page).toHaveURL(`${applicationOrigin}/`);
}
