import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page, type Route } from "@playwright/test";

import { gameDetailsFixture } from "../src/test/game-details-fixture";

/**
 * Inline personal-rating journeys against a scripted same-origin BFF.
 *
 * <p>The packaged browser gate runs without Keycloak, so these journeys script `/api/v1/session`,
 * the conditional rating contract and the `/auth/rating-intent` return flow at the network
 * boundary. The real Keycloak journey lives in `rating-boundary.spec.ts`.
 */

const gameId = "30000000-0000-4000-8000-000000000005";
const gamePath = `/games/${gameId}/resident-evil-requiem`;
const ratingPath = `/api/v1/me/ratings/${gameId}`;

type Rating = {
  gameId: string;
  value: number;
  createdAt: string;
  updatedAt: string;
  entityTag: string;
};

function statistics(mean: number | null, count: number) {
  return {
    status: "available",
    mean,
    count,
    distribution: {
      "1": 0, "2": 0, "3": 0, "4": 0, "5": 0, "6": 0, "7": 0, "8": 0, "9": 0, "10": 0,
    },
  };
}

function problem(route: Route, status: number, code: string) {
  return route.fulfill({
    status,
    contentType: "application/problem+json",
    body: JSON.stringify({ code, correlationId: "corr-browser" }),
  });
}

/** A scripted BFF holding one user's rating and the session state. */
class ScriptedBackend {
  authenticated = false;
  rating: Rating | null = null;
  version = 0;
  pendingIntent: number | null = null;
  readonly commands: { method: string; headers: Record<string, string> }[] = [];
  putOverride: ((route: Route) => Promise<void>) | null = null;

  constructor(private readonly page: Page) {}

  private persist(value: number): Rating {
    this.version += 1;
    return {
      gameId,
      value,
      createdAt: this.rating?.createdAt ?? "2026-08-13T10:00:00Z",
      updatedAt: "2026-08-13T10:00:00Z",
      entityTag: `"rating-version-${this.version}"`,
    };
  }

  async install() {
    await this.page.route("**/api/v1/games/*", (route) =>
      route.fulfill({ json: gameDetailsFixture() }),
    );
    await this.page.route("**/api/v1/session", (route) =>
      route.fulfill({
        json: this.authenticated
          ? { authenticated: true, csrfToken: "browser-csrf" }
          : { authenticated: false },
      }),
    );
    await this.page.route(/\/auth\/rating-intent\/start\?/, (route) => {
      // The scripted identity provider signs the visitor in and returns to the same game.
      const value = new URL(route.request().url()).searchParams.get("value");
      this.authenticated = true;
      this.pendingIntent = Number(value);
      return route.fulfill({
        status: 302,
        headers: { Location: `${gamePath}?rating-intent=resumed` },
      });
    });
    await this.page.route(/\/auth\/rating-intent$/, (route) => {
      const value = this.pendingIntent;
      this.pendingIntent = null;
      return value === null
        ? route.fulfill({ status: 404 })
        : route.fulfill({
            json: { gameId, slug: "resident-evil-requiem", value },
          });
    });
    await this.page.route(`**${ratingPath}`, async (route) => {
      const request = route.request();
      const method = request.method();
      if (method === "GET") {
        return this.rating
          ? route.fulfill({
              json: this.rating,
              headers: { ETag: this.rating.entityTag },
            })
          : problem(route, 404, "RATING_NOT_FOUND");
      }
      this.commands.push({ method, headers: await request.allHeaders() });
      const headers = await request.allHeaders();
      if (headers["x-csrf-token"] !== "browser-csrf") {
        return problem(route, 403, "CSRF_VALIDATION_FAILED");
      }
      if (method === "PUT" && this.putOverride) return this.putOverride(route);
      const ifMatch = headers["if-match"];
      if (method === "PUT") {
        const body = request.postDataJSON() as { value: number };
        if (headers["if-none-match"] === "*") {
          if (this.rating) return problem(route, 412, "RATING_ALREADY_EXISTS");
          this.rating = this.persist(body.value);
          return route.fulfill({
            status: 201,
            json: {
              personalRating: this.rating,
              ratingStatistics: statistics(body.value, 1),
            },
          });
        }
        if (!this.rating || ifMatch !== this.rating.entityTag) {
          return problem(route, 412, "RATING_WRITE_CONFLICT");
        }
        this.rating = this.persist(body.value);
        return route.fulfill({
          json: {
            personalRating: this.rating,
            ratingStatistics: statistics(body.value, 1),
          },
        });
      }
      if (!this.rating || ifMatch !== this.rating.entityTag) {
        return problem(route, 412, "RATING_WRITE_CONFLICT");
      }
      this.rating = null;
      return route.fulfill({
        json: { personalRating: null, ratingStatistics: statistics(null, 0) },
      });
    });
  }
}

const note = (page: Page, value: number) =>
  page.getByRole("button", { name: String(value), exact: true });

test("anonymous press, authentication return, automatic create, update and delete stay in game-page context", async ({
  page,
}) => {
  const backend = new ScriptedBackend(page);
  await backend.install();
  await page.goto(gamePath);

  const community = page.getByRole("region", {
    name: "Puntuaciones de la comunidad",
  });
  await expect(community.getByText("Sin nota todavía")).toBeVisible();
  await expect(page.getByRole("button", { name: /Puntuar|Actualizar/ })).toHaveCount(0);

  // Arrow keys only move focus inside the labelled scale; Enter on a value starts authentication.
  await note(page, 1).focus();
  await page.keyboard.press("ArrowRight");
  await page.keyboard.press("ArrowRight");
  await expect(note(page, 3)).toBeFocused();
  await expect(page).toHaveURL(new RegExp(`${gamePath}$`));
  await note(page, 8).click();

  // Back on the same game: the chosen value is persisted once, automatically.
  await expect(page.getByRole("status").filter({ hasText: "Puntuación guardada: 8/10." })).toHaveCount(1);
  await expect(page).toHaveURL(new RegExp(`${gamePath}$`));
  await expect(note(page, 8)).toHaveAttribute("aria-pressed", "true");
  await expect(page.getByText("Mi cuenta")).toBeVisible();
  await expect(community.getByLabel("Nota media: 8,0 de 10")).toBeVisible();
  await expect(community.getByText("1 puntuación")).toBeVisible();
  expect(backend.commands).toHaveLength(1);
  expect(backend.commands[0]?.headers["if-none-match"]).toBe("*");
  expect(backend.commands[0]?.headers["if-match"]).toBeUndefined();
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);

  // A reload resumes nothing (single-use context) and keeps the persisted rating.
  await page.reload();
  await expect(note(page, 8)).toHaveAttribute("aria-pressed", "true");
  expect(backend.commands).toHaveLength(1);

  // Pressing another value updates with the current strong ETag.
  await note(page, 9).click();
  await expect(page.getByRole("status").filter({ hasText: "Puntuación guardada: 9/10." })).toHaveCount(1);
  expect(backend.commands[1]?.headers["if-match"]).toBe('"rating-version-1"');
  await expect(community.getByLabel("Nota media: 9,0 de 10")).toBeVisible();

  // Delete with the ETag returned by the update; focus stays inside the scale.
  await page.getByRole("button", { name: "Eliminar puntuación" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Puntuación eliminada." })).toHaveCount(1);
  expect(backend.commands[2]?.method).toBe("DELETE");
  expect(backend.commands[2]?.headers["if-match"]).toBe('"rating-version-2"');
  await expect(community.getByText("Sin nota todavía")).toBeVisible();
  await expect(note(page, 1)).toBeFocused();
  await expect(page.getByRole("button", { pressed: true })).toHaveCount(0);
  expect(backend.commands).toHaveLength(3);
});

test("a stale ETag conflict shows the winning state and never retries the command", async ({
  page,
}) => {
  const backend = new ScriptedBackend(page);
  backend.authenticated = true;
  backend.version = 1;
  backend.rating = {
    gameId,
    value: 7,
    createdAt: "2026-08-13T10:00:00Z",
    updatedAt: "2026-08-13T10:00:00Z",
    entityTag: '"rating-version-1"',
  };
  await backend.install();
  await page.goto(gamePath);
  await expect(note(page, 7)).toHaveAttribute("aria-pressed", "true");

  // Another session wins before this one submits.
  backend.rating = { ...backend.rating, value: 5, entityTag: '"rating-version-2"' };
  await note(page, 9).click();

  await expect(page.getByRole("alert")).toContainText(
    "Tu nota cambió desde otra sesión",
  );
  await expect(note(page, 5)).toHaveAttribute("aria-pressed", "true");
  expect(backend.commands).toHaveLength(1);
  expect(backend.commands[0]?.headers["if-match"]).toBe('"rating-version-1"');
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
});

test("rejected and ambiguous commands preserve the previous valid state", async ({
  page,
}) => {
  const backend = new ScriptedBackend(page);
  backend.authenticated = true;
  backend.version = 1;
  backend.rating = {
    gameId,
    value: 7,
    createdAt: "2026-08-13T10:00:00Z",
    updatedAt: "2026-08-13T10:00:00Z",
    entityTag: '"rating-version-1"',
  };
  await backend.install();
  await page.goto(gamePath);
  await expect(note(page, 7)).toHaveAttribute("aria-pressed", "true");

  // Eligibility rejection.
  backend.putOverride = (route) => problem(route, 422, "RATING_NOT_ELIGIBLE");
  await note(page, 2).click();
  await expect(page.getByRole("alert")).toContainText("ya no admite nuevas puntuaciones");
  await expect(note(page, 7)).toHaveAttribute("aria-pressed", "true");

  // Ambiguous transport failure: no automatic retry, explicit re-read on request.
  backend.putOverride = (route) => route.abort("connectionreset");
  await note(page, 3).click();
  const alert = page.getByRole("alert");
  await expect(alert).toContainText("No sabemos si el cambio se aplicó");
  await expect(note(page, 7)).toHaveAttribute("aria-pressed", "true");
  expect(backend.commands).toHaveLength(2);
  await alert.getByRole("button", { name: "Comprobar mi nota" }).click();
  await expect(page.getByRole("alert")).toHaveCount(0);
  await expect(note(page, 7)).toHaveAttribute("aria-pressed", "true");
  expect(backend.commands).toHaveLength(2);
});

test("an ineligible game keeps the scale disabled and stays accessible", async ({
  page,
}) => {
  const game = gameDetailsFixture();
  game.ratingEligibility = {
    eligible: false,
    reason: "RELEASE_NOT_OCCURRED",
    evaluatedOn: "2026-08-13",
  };
  await page.route("**/api/v1/games/*", (route) => route.fulfill({ json: game }));
  await page.route("**/api/v1/session", (route) =>
    route.fulfill({ json: { authenticated: false } }),
  );
  await page.goto(gamePath);
  await expect(note(page, 8)).toBeDisabled();
  await expect(page.getByText("el lanzamiento aún no ha ocurrido")).toBeVisible();
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
});
