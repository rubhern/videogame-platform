import { readFile } from "node:fs/promises";
import { expect, test, type Page } from "@playwright/test";
import type { components } from "../src/shared/api/generated/schema";
import { analyzeAccessibility } from "./fixtures/accessibility";

type Game = components["schemas"]["PublicProfileGame"];

const alias = "luna-despues-de-jugar";
const attribution = { label: "IGDB", sourceUrl: "https://www.igdb.com/games/example" };
const palettes = [["#1b2a6b", "#ff7a3d"], ["#0d4d3a", "#e9f06b"], ["#5a0f2e", "#ffb1c8"], ["#2b1a4f", "#7ad7ff"]];

function game(index: number, title: string, score?: number): Game {
  return {
    gameId: `30000000-0000-4000-8000-00000000000${index}`, slug: `juego-${index}`, canonicalTitle: title,
    primaryCover: {
      kind: "provider", url: `https://images.igdb.com/igdb/image/upload/t_cover_big_2x/cover${index}.svg`,
      alternativeText: `Portada de ${title}`, attribution,
    },
    ...(score === undefined ? {} : { score }),
  };
}

/** A stand-in provider cover, served with the CORS header the IGDB CDN sends. */
function cover(index: number) {
  const [deep, light] = palettes[index % palettes.length] ?? ["#000", "#fff"];
  return `<svg xmlns="http://www.w3.org/2000/svg" width="528" height="748"><defs><linearGradient id="g" x2="0.6" y2="1">`
    + `<stop offset="0" stop-color="${light}"/><stop offset="0.5" stop-color="${deep}"/><stop offset="1" stop-color="#050507"/>`
    + `</linearGradient></defs><rect width="528" height="748" fill="url(#g)"/></svg>`;
}

const rated = [game(1, "Aurora Rift: Crónicas del Horizonte Perdido", 10), game(2, "Verdant", 9.5), game(3, "Crimson Tide", 7)];
const wished = [game(4, "Neon Halo"), game(1, "Aurora Rift: Crónicas del Horizonte Perdido")];

async function stubProfile(page: Page) {
  const state = { published: true, reads: [] as string[] };
  await page.route("**/api/v1/session", (route) => route.fulfill({ json: { authenticated: true, csrfToken: "browser-proof" } }));
  await page.route("**/api/v1/me/profile", (route) => route.fulfill({
    json: { email: "private@example.test", deletionAvailable: true },
  }));
  await page.route("**/api/v1/me/public-profile", (route) => route.fulfill({
    json: { alias, published: true, shareRatings: true, shareWishlist: true, revision: crypto.randomUUID() },
  }));
  await page.route("**/api/v1/profiles/**", (route) => {
    const url = new URL(route.request().url());
    state.reads.push(url.pathname);
    if (!state.published) return route.fulfill({ status: 404, json: { code: "PUBLIC_PROFILE_NOT_FOUND" } });
    if (url.pathname.endsWith("/statistics")) {
      return route.fulfill({ json: {
        ratings: { total: 31, mean: 7.4, lowest: 3, highest: 10, distribution: [0, 0, 1, 2, 3, 4, 8, 7, 4, 2] },
        wishlistCount: 12,
      } });
    }
    if (url.pathname.endsWith("/sections/ratings") || url.pathname.endsWith("/sections/wishlist")) {
      const items = url.pathname.endsWith("/ratings") ? rated : wished;
      return route.fulfill({ json: { items, page: { number: 1, size: 6, totalItems: items.length, totalPages: 1 } } });
    }
    return route.fulfill({ json: { alias, sections: ["ratings", "wishlist"] } });
  });
  await page.route("https://images.igdb.com/**", (route) => {
    const index = Number(/cover(\d+)/.exec(route.request().url())?.[1] ?? 0);
    return route.fulfill({ body: cover(index), contentType: "image/svg+xml", headers: { "Access-Control-Allow-Origin": "*" } });
  });
  await page.addInitScript(() => {
    const choice = JSON.stringify({ version: 1, choice: "denied", decidedAt: new Date().toISOString() });
    localStorage.setItem("gameometro_analytics_consent", choice);
    localStorage.setItem("gameometro_visit_ranking_consent", choice);
  });
  return state;
}

/** The painted canvas: its size, whether it can be exported, and how many distinct colours it holds. */
async function paintedCanvas(page: Page) {
  await expect(page.locator(".share-card-preview[data-ready]")).toBeVisible();
  return page.locator(".share-card-canvas").evaluate((canvas: HTMLCanvasElement) => {
    const context = canvas.getContext("2d");
    const pixels = context?.getImageData(0, 0, canvas.width, canvas.height).data ?? new Uint8ClampedArray();
    const colours = new Set<number>();
    for (let index = 0; index < pixels.length; index += 4 * 997) {
      colours.add(((pixels[index] ?? 0) << 16) | ((pixels[index + 1] ?? 0) << 8) | (pixels[index + 2] ?? 0));
    }
    return { width: canvas.width, height: canvas.height, colours: colours.size, png: canvas.toDataURL("image/png") };
  });
}

/** The dimensions a PNG file declares in its header. */
async function pngSize(path: string) {
  const bytes = await readFile(path);
  expect(bytes.subarray(1, 4).toString("ascii")).toBe("PNG");
  return { width: bytes.readUInt32BE(16), height: bytes.readUInt32BE(20) };
}

test("previews, explains and downloads the public profile as a post or a story", async ({ page }) => {
  const state = await stubProfile(page);
  await page.addInitScript(() => {
    Object.defineProperty(navigator, "share", { value: undefined });
    Object.defineProperty(navigator, "canShare", { value: undefined });
  });
  await page.setViewportSize({ width: 390, height: 900 });
  await page.goto("/mi-perfil");
  const sharing = page.getByRole("region", { name: "Perfil público" });
  const toggle = sharing.getByRole("button", { name: "Crear imagen para compartir" });
  await expect(toggle).toBeVisible();
  expect(state.reads).toEqual([]);

  await toggle.focus();
  await page.keyboard.press("Enter");
  await expect(toggle).toHaveAttribute("aria-expanded", "true");
  const post = await paintedCanvas(page);
  expect(post).toMatchObject({ width: 1080, height: 1350 });
  expect(post.colours).toBeGreaterThan(40);
  await expect(page.getByRole("img", { name: /^Perfil de @luna-despues-de-jugar en Gameómetro, en formato publicación\./ }))
    .toBeVisible();
  const recipients = sharing.getByText(/Quien la reciba podrá conservarla y reenviarla/);
  await expect(recipients).toBeHidden();
  await sharing.getByText("Más info sobre la imagen").click();
  await expect(recipients).toBeVisible();
  await expect(sharing.getByRole("button", { name: "Compartir imagen" })).toHaveCount(0);
  expect(await page.locator(".share-card-canvas").getAttribute("aria-label")).not.toContain("private@example.test");
  expect(state.reads.every((path) => path.startsWith(`/api/v1/profiles/${alias}`))).toBe(true);

  for (const width of [320, 390, 834, 1320]) {
    await page.setViewportSize({ width, height: 900 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    expect((await analyzeAccessibility(page)).violations).toEqual([]);
    await sharing.screenshot({ path: test.info().outputPath(`share-studio-${width}.png`) });
  }

  const [postFile] = await Promise.all([
    page.waitForEvent("download"),
    sharing.getByRole("button", { name: "Descargar imagen" }).click(),
  ]);
  expect(postFile.suggestedFilename()).toBe(`gameometro-${alias}-publicacion.png`);
  expect(await pngSize(await postFile.path())).toEqual({ width: 1080, height: 1350 });
  await expect(sharing.getByRole("status").filter({ hasText: "Imagen descargada como" })).toBeVisible();
  await postFile.saveAs(test.info().outputPath("share-image-post.png"));

  await sharing.getByRole("radio", { name: /Historia/ }).check();
  await sharing.getByRole("checkbox", { name: "Mis deseados", exact: true }).uncheck();
  await expect(page.getByRole("img", { name: /en formato historia\..*Así puntúo/ })).toBeVisible();
  const story = await paintedCanvas(page);
  expect(story).toMatchObject({ width: 1080, height: 1920 });
  const [storyFile] = await Promise.all([
    page.waitForEvent("download"),
    sharing.getByRole("button", { name: "Descargar imagen" }).click(),
  ]);
  expect(storyFile.suggestedFilename()).toBe(`gameometro-${alias}-historia.png`);
  expect(await pngSize(await storyFile.path())).toEqual({ width: 1080, height: 1920 });
  await storyFile.saveAs(test.info().outputPath("share-image-story.png"));
});

test("hands a PNG and the profile link to the native share sheet", async ({ page }) => {
  await stubProfile(page);
  await page.addInitScript(() => {
    const shared: { files: { name: string; type: string; size: number }[]; text: string }[] = [];
    Object.assign(window, { sharedProfileImages: shared });
    Object.defineProperty(navigator, "canShare", { value: (data: ShareData) => (data.files?.length ?? 0) > 0 });
    Object.defineProperty(navigator, "share", {
      value: (data: ShareData) => {
        shared.push({
          files: (data.files ?? []).map((file) => ({ name: file.name, type: file.type, size: file.size })),
          text: data.text ?? "",
        });
        return Promise.resolve();
      },
    });
  });
  await page.goto("/mi-perfil");
  const sharing = page.getByRole("region", { name: "Perfil público" });
  await sharing.getByRole("button", { name: "Crear imagen para compartir" }).click();
  await paintedCanvas(page);

  await sharing.getByRole("button", { name: "Compartir imagen" }).click();
  await expect(sharing.getByText("Imagen enviada a la aplicación que elegiste.")).toBeVisible();
  const shared = await page.evaluate(() => (window as unknown as {
    sharedProfileImages: { files: { name: string; type: string; size: number }[]; text: string }[];
  }).sharedProfileImages);
  expect(shared).toHaveLength(1);
  expect(shared[0]?.files).toEqual([
    { name: `gameometro-${alias}-publicacion.png`, type: "image/png", size: expect.any(Number) as number },
  ]);
  expect(shared[0]?.files[0]?.size).toBeGreaterThan(10_000);
  expect(shared[0]?.text).toBe(`¡Mira mi perfil de jugador en Gameómetro!\n${new URL(`/u/${alias}`, page.url()).href}`);
});

test("creates no image once the profile is withdrawn after its preview", async ({ page }) => {
  const state = await stubProfile(page);
  await page.addInitScript(() => {
    Object.defineProperty(navigator, "share", { value: undefined });
    Object.defineProperty(navigator, "canShare", { value: undefined });
  });
  let downloads = 0;
  page.on("download", () => { downloads += 1; });
  await page.goto("/mi-perfil");
  const sharing = page.getByRole("region", { name: "Perfil público" });
  await sharing.getByRole("button", { name: "Crear imagen para compartir" }).click();
  await paintedCanvas(page);

  state.published = false;
  await sharing.getByRole("button", { name: "Descargar imagen" }).click();

  await expect(sharing.getByText("Tu perfil no es público ahora mismo, así que no se puede crear ninguna imagen de él."))
    .toBeVisible();
  await expect(page.locator(".share-card-canvas")).toHaveCount(0);
  await expect(sharing.getByRole("button", { name: "Descargar imagen" })).toHaveCount(0);
  expect(downloads).toBe(0);
  expect((await analyzeAccessibility(page)).violations).toEqual([]);
});
