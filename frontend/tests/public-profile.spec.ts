import { expect, test } from "@playwright/test";
import type { components } from "../src/shared/api/generated/schema";
import { analyzeAccessibility } from "./fixtures/accessibility";

type Settings = components["schemas"]["PublicProfileSettings"];
type Command = components["schemas"]["PublicProfileSettingsCommand"];

const alias = "luna-despues-de-jugar";
const firstGame = {
  gameId: "30000000-0000-4000-8000-000000000001",
  slug: "juego-de-ejemplo",
  canonicalTitle: "Juego de ejemplo",
  primaryCover: {
    kind: "fallback", url: "/assets/covers/fallback.svg", alternativeText: "Portada no disponible",
  },
  score: 8.5,
};
const secondGame = { ...firstGame, gameId: "30000000-0000-4000-8000-000000000002", canonicalTitle: "Otro juego" };

test("requires a section to publish, reports alias errors and withdraws sharing", async ({ page }) => {
  let settings: Settings = { published: false, shareRatings: false, shareWishlist: false };
  const writes: Command[] = [];
  const publicReads: string[] = [];
  const missing = { status: 404, json: { code: "PUBLIC_PROFILE_NOT_FOUND" } };
  await page.route("**/api/v1/session", route => route.fulfill({
    json: { authenticated: true, csrfToken: "browser-proof" },
  }));
  await page.route("**/api/v1/me/profile", route => route.fulfill({
    json: { email: "private@example.test", deletionAvailable: true },
  }));
  await page.route("**/api/v1/me/public-profile", route => {
    if (route.request().method() === "PUT") {
      expect(route.request().headers()["x-csrf-token"]).toBe("browser-proof");
      const command = route.request().postDataJSON() as Command;
      writes.push(command);
      if (settings.alias && command.alias.toLowerCase() !== settings.alias) {
        return route.fulfill({ status: 409, json: { code: "PUBLIC_ALIAS_CHANGE_TOO_SOON" } });
      }
      settings = { ...command, alias: command.alias.toLowerCase(), revision: crypto.randomUUID() };
    }
    return route.fulfill({ json: settings });
  });
  await page.route("**/api/v1/profiles/**", route => {
    const { pathname, searchParams } = new URL(route.request().url());
    publicReads.push(pathname);
    if (!settings.published) return route.fulfill(missing);
    if (pathname.endsWith("/sections/wishlist")) return route.fulfill(missing);
    if (pathname.endsWith("/sections/ratings")) {
      const number = Number(searchParams.get("page"));
      return route.fulfill({ json: {
        items: [number === 1 ? firstGame : secondGame],
        page: { number, size: 12, totalItems: 13, totalPages: 2 },
      } });
    }
    return route.fulfill({ json: { alias, sections: ["ratings"] } });
  });
  const sharedMessages: string[] = [];
  await page.exposeFunction("captureProfileShare", (message: string) => {
    sharedMessages.push(message);
  });
  // The packaged test runs over HTTP on a Docker hostname, which disables the clipboard API.
  await page.addInitScript(() => {
    const browser = window as Window & { captureProfileShare: (message: string) => Promise<void> };
    Object.defineProperty(navigator, "share", { value: undefined });
    Object.defineProperty(navigator, "clipboard", { value: { writeText: browser.captureProfileShare } });
  });

  await page.goto("/mi-perfil");
  const sharing = page.getByRole("region", { name: "Perfil público" });
  const publication = sharing.getByRole("checkbox", { name: "Publicar mi perfil" });
  const ratings = sharing.getByRole("checkbox", { name: "Mostrar mis puntuaciones" });
  const wishlist = sharing.getByRole("checkbox", { name: "Mostrar mis deseados" });
  const save = sharing.getByRole("button", { name: "Guardar", exact: true });
  const aliasInput = sharing.getByRole("textbox", { name: "Alias" });
  const [privacy] = await Promise.all([
    page.waitForEvent("popup"),
    sharing.getByRole("link", { name: /Cómo usamos estos datos/ }).click(),
  ]);
  await expect(privacy).toHaveURL(/\/privacidad$/);
  await expect(page).toHaveURL(/\/mi-perfil$/);
  await privacy.close();
  await page.bringToFront();

  await expect(publication).not.toBeChecked();
  await expect(ratings).toBeDisabled();
  await expect(wishlist).toBeDisabled();
  await expect(sharing.getByRole("button", { name: "Así te ven los demás" })).toHaveCount(0);
  await aliasInput.fill(alias.toUpperCase());
  await expect(save).toBeDisabled();
  await publication.check();
  await expect(ratings).toBeEnabled();
  await expect(save).toBeDisabled();
  await ratings.check();
  await wishlist.check();
  await expect(save).toBeEnabled();
  await publication.uncheck();
  for (const section of [ratings, wishlist]) {
    await expect(section).toBeDisabled();
    await expect(section).not.toBeChecked();
  }
  await expect(save).toBeDisabled();
  expect(writes).toHaveLength(0);
  expect(publicReads).toHaveLength(0);

  await page.setViewportSize({ width: 320, height: 900 });
  expect((await analyzeAccessibility(page)).violations).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await publication.check();
  await expect(save).toBeDisabled();
  await ratings.check();
  await save.click();
  await expect(sharing.getByRole("status").filter({ hasText: "Ajustes de publicación guardados" })).toBeVisible();
  expect(writes[0]).toEqual({
    alias: alias.toUpperCase(), published: true, shareRatings: true, shareWishlist: false,
  });

  await sharing.getByRole("button", { name: "Compartir perfil" }).click();
  await expect(sharing.getByText("Mensaje y enlace copiados.")).toBeVisible();
  expect(sharedMessages).toEqual([
    `¡Mira mi perfil en Gameómetro!\n${new URL(`/u/${alias}`, page.url()).href}`,
  ]);

  await aliasInput.fill("otro-alias");
  await save.click();
  const aliasError = sharing.locator("#public-player-alias + [role=alert]");
  await expect(aliasError).toHaveText("Puedes cambiar el alias pasados 30 días desde que lo elegiste.");
  await expect(aliasError).toHaveClass(/profile-feedback-error/);
  await expect(aliasInput).toHaveAttribute("aria-invalid", "true");
  await expect(aliasInput).toHaveAttribute("aria-describedby", /public-player-alias-error/);
  expect((await analyzeAccessibility(page)).violations).toEqual([]);
  await sharing.getByRole("link", { name: `Ver @${alias}` }).click();
  await expect(page.getByRole("heading", { name: `@${alias}` })).toBeVisible();
  await expect(page.getByText("private@example.test")).toHaveCount(0);
  await expect(page.getByRole("heading", { name: "Deseados", exact: true })).toHaveCount(0);
  await expect(page.getByRole("tab", { name: "Puntuaciones", selected: true })).toBeVisible();
  await expect(page.getByRole("tab", { name: "Deseados" })).toHaveCount(0);
  await expect(page.locator(".public-profile-reading strong")).toContainText("8,5");
  await expect(page.getByRole("button", { name: /Cambiar o eliminar|Puntuar/ })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Compartir perfil" })).toHaveCount(0);
  await page.getByRole("button", { name: "Siguiente" }).focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("heading", { name: "Puntuaciones", exact: true })).toBeFocused();
  await expect(page.getByRole("link", { name: secondGame.canonicalTitle })).toBeVisible();

  for (const width of [320, 1320]) {
    await page.setViewportSize({ width, height: 900 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
    await page.screenshot({ path: test.info().outputPath(`public-profile-${width}.png`) });
  }

  await page.getByRole("link", { name: "Denunciar perfil" }).click();
  await expect(page.getByRole("textbox", { name: "Mensaje" })).toHaveValue(
    `Quiero denunciar el perfil @${alias}. Motivo: `,
  );
  await page.goto("/mi-perfil");
  await sharing.getByRole("button", { name: "Hacer privado", exact: true }).click();
  await expect(sharing.getByText("Tu perfil es privado.")).toBeVisible();
  await expect(ratings).not.toBeChecked();
  await expect(wishlist).not.toBeChecked();
  expect(writes.at(-1)).toMatchObject({ published: false, shareRatings: false, shareWishlist: false });
  await page.goto(`/u/${alias}`);
  await expect(page.getByRole("heading", { name: "Página no encontrada" })).toBeVisible();
});

test("separates both shared sections into keyboard tabs and only loads the active panel", async ({ page }) => {
  const reads: string[] = [];
  await page.route("**/api/v1/session", route => route.fulfill({ json: { authenticated: false } }));
  await page.route(`**/api/v1/profiles/${alias}`, route => route.fulfill({
    json: { alias, sections: ["ratings", "wishlist"] },
  }));
  const sorts: Array<string | null> = [];
  await page.route(`**/api/v1/profiles/${alias}/statistics`, route => {
    reads.push("statistics");
    return route.fulfill({ json: {
      ratings: { total: 3, mean: 6.5, highest: 8.5, lowest: 4, distribution: [0, 0, 0, 1, 0, 0, 1, 0, 1, 0] },
      wishlistCount: 1,
    } });
  });
  await page.route(`**/api/v1/profiles/${alias}/sections/**`, route => {
    const url = new URL(route.request().url());
    const section = url.pathname.endsWith("/wishlist") ? "wishlist" : "ratings";
    reads.push(section);
    if (section === "ratings") sorts.push(url.searchParams.get("sort"));
    const withoutScore = {
      gameId: secondGame.gameId, slug: secondGame.slug, canonicalTitle: "Juego deseado",
      primaryCover: secondGame.primaryCover,
    };
    const rated = [
      firstGame,
      { ...secondGame, canonicalTitle: "Un título muy largo para comprobar cómo refluye una puntuación en el móvil", score: 4 },
      { ...firstGame, gameId: "30000000-0000-4000-8000-000000000003", canonicalTitle: "Tercer juego", score: 7 },
    ];
    const items = section === "ratings" ? rated : [withoutScore];
    return route.fulfill({ json: {
      items,
      page: { number: 1, size: 12, totalItems: items.length, totalPages: 1 },
    } });
  });

  await page.addInitScript(() => {
    const choice = JSON.stringify({ version: 1, choice: "denied", decidedAt: new Date().toISOString() });
    localStorage.setItem("gameometro_analytics_consent", choice);
    localStorage.setItem("gameometro_visit_ranking_consent", choice);
  });
  await page.goto(`/u/${alias}`);
  const header = page.locator(".public-profile-identity");
  await expect(header.getByRole("link", { name: "Denunciar perfil" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Compartir perfil" })).toHaveCount(0);
  const tabs = page.getByRole("tablist", { name: "Contenido compartido" });
  const ratingsTab = tabs.getByRole("tab", { name: "Puntuaciones" });
  const wishlistTab = tabs.getByRole("tab", { name: "Deseados" });
  const ratings = page.getByRole("tabpanel", { name: "Puntuaciones" });
  const wishlist = page.getByRole("tabpanel", { name: "Deseados" });
  await expect(ratings).toBeVisible();
  await expect(ratings.getByRole("heading", { name: firstGame.canonicalTitle })).toBeVisible();
  await expect(ratings.getByText("Ardiendo")).toBeVisible();
  await expect(ratings.getByText("Frío")).toBeVisible();
  expect([...new Set(reads)]).toEqual(["ratings"]);

  for (const width of [320, 390, 834, 1320]) {
    await page.setViewportSize({ width, height: 900 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
    await page.screenshot({ path: test.info().outputPath(`ratings-ledger-${width}.png`) });
  }

  await ratingsTab.focus();
  await page.keyboard.press("ArrowRight");
  await expect(wishlistTab).toBeFocused();
  await expect(ratingsTab).toHaveAttribute("aria-selected", "true");
  expect([...new Set(reads)]).toEqual(["ratings"]);
  await page.keyboard.press("Enter");
  await expect(wishlistTab).toHaveAttribute("aria-selected", "true");
  await expect(ratings).toHaveCount(0);
  await expect(wishlist.getByRole("heading", { name: "Juego deseado" })).toBeVisible();
  await expect(wishlist.locator(".game-meter")).toHaveCount(0);
  expect([...new Set(reads)]).toEqual(["ratings", "wishlist"]);

  await page.keyboard.press("Home");
  await expect(ratingsTab).toBeFocused();
  await page.keyboard.press("Space");
  await expect(ratingsTab).toHaveAttribute("aria-selected", "true");
  await expect(ratings.getByText("Ardiendo")).toBeVisible();
  const order = ratings.getByRole("combobox", { name: /Ordenar puntuaciones/ });
  await order.click();
  await page.getByRole("option", { name: "Nota más baja", exact: true }).click();
  await expect.poll(() => sorts.at(-1)).toBe("score");
  await order.click();
  await page.getByRole("option", { name: "Lanzamientos recientes", exact: true }).click();
  await expect.poll(() => sorts.at(-1)).toBe("releaseDate");
  await order.click();
  await page.getByRole("option", { name: "Mayor interés", exact: true }).click();
  await expect.poll(() => sorts.at(-1)).toBe("interest");
  await expect(ratings.getByText("Según las visitas registradas por IGDB en el catálogo.")).toBeVisible();

  await tabs.getByRole("tab", { name: "Estadísticas" }).click();
  const statistics = page.getByRole("tabpanel", { name: "Estadísticas" });
  await expect(statistics.getByRole("heading", { name: "El perfil en cifras" })).toBeVisible();
  await expect(statistics.getByText("Puntuación media", { exact: true })).toBeVisible();
  await expect(statistics.getByRole("heading", { name: "Distribución de las notas" })).toBeVisible();
  expect([...new Set(reads)]).toEqual(["ratings", "wishlist", "statistics"]);
  for (const width of [320, 1320]) {
    await page.setViewportSize({ width, height: 900 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
    await statistics.screenshot({ path: test.info().outputPath(`public-statistics-${width}.png`) });
  }
});
