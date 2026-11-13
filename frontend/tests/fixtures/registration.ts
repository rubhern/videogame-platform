import { expect, type APIRequestContext, type Page } from "@playwright/test";

/**
 * Self-registration under the production identity policy, which the shared realm imports too:
 * the form asks only for the email and the acceptance of the terms of use, Keycloak sends a
 * verification link, and the password is chosen after following it. The link is read from the
 * Mailpit sink of the identity topology (MAILPIT_API_ORIGIN).
 */
export async function registerWithVerifiedEmail(
  page: Page,
  request: APIRequestContext,
  email: string,
  password: string,
) {
  const mailpitOrigin = process.env.MAILPIT_API_ORIGIN ?? "";
  expect(mailpitOrigin, "self-registration needs the identity topology's Mailpit").not.toBe("");
  await expect(page.locator('#kc-register-form input[type="checkbox"]')).toHaveCount(1);
  await expect(page.locator("#firstName, #lastName, #password")).toHaveCount(0);
  await page.locator("#email").fill(email);
  await page.locator('input[name="termsAccepted"]').check();
  await page.locator("#kc-register-form [type=submit]").click();
  await expect(page.locator("body")).toContainText("Verifica tu email");

  const query = { query: `to:"${email}"` };
  await expect
    .poll(async () => ((await (await request.get(`${mailpitOrigin}/api/v1/search`, { params: query })).json()).messages ?? []).length)
    .toBeGreaterThanOrEqual(1);
  const [newest] = (await (await request.get(`${mailpitOrigin}/api/v1/search`, { params: query })).json())
    .messages as { ID: string }[];
  const message = (await (await request.get(`${mailpitOrigin}/api/v1/message/${newest?.ID}`)).json()) as { Text: string };
  const link = /https?:\/\/\S+\/login-actions\/action-token\?\S+/.exec(message.Text)?.[0] ?? "";
  expect(link, "the verification email carries a native action link").not.toBe("");

  const response = await page.goto(link);
  expect(response?.status(), "the emailed verification action is admitted").toBe(200);
  await expect(page.locator("#password-new")).toBeVisible();
  await page.locator("#password-new").fill(password);
  await page.locator("#password-confirm").fill(password);
  await page.locator("#kc-passwd-update-form [type=submit]").click();
}
