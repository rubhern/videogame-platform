import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

const gamePath =
  "/games/30000000-0000-4000-8000-000000000005/resident-evil-requiem";

const username = process.env.OIDC_TEST_USERNAME;
const password = process.env.OIDC_TEST_PASSWORD;

test("anonymous browsing exposes no login entry point but offers the rating boundary", async ({
  page,
}) => {
  await page.goto(gamePath);
  await expect(
    page.getByRole("heading", { level: 1, name: "Resident Evil Requiem" }),
  ).toBeVisible();

  // No general account/login entry point while anonymous.
  await expect(page.getByText("Mi cuenta")).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "Cerrar sesión" }),
  ).toHaveCount(0);

  // The minimal, accessible rating entry point is present.
  await expect(page.getByLabel("Tu puntuación (1-10)")).toBeVisible();
  await expect(page.getByRole("button", { name: "Puntuar" })).toBeVisible();
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
});

test.describe("real Keycloak rating boundary journey", () => {
  test.skip(
    !username || !password,
    "The real Keycloak compatibility topology is not active.",
  );

  test("authenticates from the rating boundary and resumes the same game and value", async ({
    context,
    page,
  }) => {
    await page.goto(gamePath);
    await page.getByLabel("Tu puntuación (1-10)").selectOption("8");
    await page.getByRole("button", { name: "Puntuar" }).click();

    // Authentication is hosted by Keycloak, not a product login page.
    await expect(page).toHaveURL(
      /\/realms\/videogame-platform\/protocol\/openid-connect\/auth/,
    );
    await page.getByLabel("Username").fill(username ?? "");
    await page.locator("#password").fill(password ?? "");
    await page.getByRole("button", { name: "Sign In" }).click();

    // Back on the same game with the selected value recovered as pending, non-persisted state.
    await expect(page).toHaveURL(new RegExp(gamePath));
    await expect(
      page.getByText(/Tu puntuación seleccionada es 8\/10/),
    ).toBeVisible();
    await expect(page.getByText(/pendiente, todavía sin guardar/)).toBeVisible();

    // The header now reflects the authenticated session.
    await expect(page.getByText("Mi cuenta")).toBeVisible();

    // A reload cannot resume the same selection again (single-use return context).
    await page.reload();
    await expect(
      page.getByText(/Tu puntuación seleccionada/),
    ).toHaveCount(0);

    // Logout returns the header to the anonymous state and clears the session cookie.
    await page.getByRole("button", { name: "Cerrar sesión" }).click();
    await expect(page.getByText("Mi cuenta")).toHaveCount(0);
    await expect(await context.cookies("http://application:8080")).toEqual([]);
  });

  test("a new visitor self-registers through Keycloak and resumes the same game and value", async ({
    page,
  }) => {
    await page.goto(gamePath);
    await page.getByLabel("Tu puntuación (1-10)").selectOption("7");
    await page.getByRole("button", { name: "Puntuar" }).click();

    // Keycloak hosts registration; the product exposes no registration page.
    await expect(page).toHaveURL(
      /\/realms\/videogame-platform\/protocol\/openid-connect\/auth/,
    );
    await page.getByRole("link", { name: "Register" }).click();

    const unique = `player-${Date.now()}`;
    await page.locator("#firstName").fill("New");
    await page.locator("#lastName").fill("Visitor");
    await page.locator("#email").fill(`${unique}@localhost.invalid`);
    await page.locator("#username").fill(unique);
    await page.locator("#password").fill("Str0ng-Passw0rd!");
    await page.locator("#password-confirm").fill("Str0ng-Passw0rd!");
    await page.getByRole("button", { name: "Register" }).click();

    // First-time registration completes authentication and resumes the pending context.
    await expect(page).toHaveURL(new RegExp(gamePath));
    await expect(
      page.getByText(/Tu puntuación seleccionada es 7\/10/),
    ).toBeVisible();
    await expect(page.getByText("Mi cuenta")).toBeVisible();
  });
});
