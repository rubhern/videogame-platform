import { expect, test } from "@playwright/test";

test("browser startup identifies the artifact once and client navigation does not repeat it", async ({
  page,
  request,
}) => {
  let version = process.env.EXPECTED_APPLICATION_VERSION;
  let revision = process.env.EXPECTED_SOURCE_REVISION;
  if (!version || !revision) {
    // The existing management endpoint is reached by the test runner on the
    // private test network; the browser never receives management access.
    const response = await request.get(
      process.env.PLAYWRIGHT_MANAGEMENT_URL ?? "http://application:8081/actuator/info",
    );
    expect(response.ok()).toBe(true);
    const info: { build: { version: string; sourceRevision: string } } = await response.json();
    version = info.build.version;
    revision = info.build.sourceRevision;
  }
  const displayRevision = /^[0-9a-f]{40}$/.test(revision) ? revision.slice(0, 12) : revision;
  const expectedMessage = `Gameómetro ${version} — revision ${displayRevision}`;
  const messages: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "info" && message.text().startsWith("Gameómetro ")) {
      messages.push(message.text());
    }
  });
  await page.route("**/api/v1/session", (route) => route.fulfill({ json: { authenticated: false } }));
  await page.goto("/?view=recent");
  await expect(page.locator("h1")).toBeVisible();
  expect(messages).toEqual([expectedMessage]);

  await page.getByRole("link", { name: "Próximos", exact: true }).first().click();
  await expect(page).toHaveURL(/view=upcoming/);
  expect(messages).toEqual([expectedMessage]);
  await page.reload();
  await expect(page.locator("h1")).toBeVisible();
  expect(messages).toEqual([expectedMessage, expectedMessage]);
});
