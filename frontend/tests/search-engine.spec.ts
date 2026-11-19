import { expect, test } from "@playwright/test";

// What a crawler reads before and after JavaScript (#537), on the packaged application with the
// seed catalogue. The browser gate configures no indexing origin, like every non-production
// deployment.
const GAME = "30000000-0000-4000-8000-000000000001";
const SLUG = "death-stranding-2-on-the-beach";
const TITLE = "Death Stranding 2: On the Beach · Gameómetro";

function head(html: string) {
  return html.slice(html.indexOf("<!-- page-metadata:start -->"), html.indexOf("<!-- page-metadata:end -->"));
}

test("a game page states its title, description and canonical URL without JavaScript", async ({ request, baseURL }) => {
  const response = await request.get(`/games/${GAME}`);
  expect(response.status()).toBe(200);
  const tags = head(await response.text());
  expect(tags).toContain(`<title>${TITLE}</title>`);
  // The seed holds no summary, so the description rule names the page's real content instead of
  // the stored "no summary yet" notice.
  expect(tags).toContain(
    '<meta name="description" content="Lanzamientos por plataforma de Death Stranding 2: On the Beach y puntuaciones de la comunidad de Gameómetro." />',
  );
  expect(tags).toContain(`<link rel="canonical" href="${baseURL}/games/${GAME}/${SLUG}" />`);
  expect(tags).not.toContain("noindex");
});

test("missing, personal and search pages are never offered for indexing", async ({ request }) => {
  const missingGame = await request.get("/games/30000000-0000-4000-8000-0000000000ff/missing");
  expect(missingGame.status()).toBe(404);
  expect(head(await missingGame.text())).toContain('<meta name="robots" content="noindex" />');
  const missingPlatform = await request.get("/plataformas/no-such-platform/catalogo");
  expect(missingPlatform.status()).toBe(404);
  for (const path of ["/mis-puntuaciones", "/mi-perfil", "/bienvenida", "/search?q=zelda"]) {
    const response = await request.get(path);
    expect(response.status()).toBe(200);
    const tags = head(await response.text());
    expect(tags).toContain('<meta name="robots" content="noindex" />');
    expect(tags).not.toContain("canonical");
  }
});

test("a deployment without an indexing origin disallows every path and has no sitemap", async ({ request }) => {
  const robots = await request.get("/robots.txt");
  expect(robots.status()).toBe(200);
  expect(await robots.text()).toBe("User-agent: *\nDisallow: /\n");
  expect((await request.get("/sitemap.xml")).status()).toBe(404);
});

test("after JavaScript the game page keeps the server's title and adds its structured data", async ({ page, baseURL }) => {
  await page.route("**/api/v1/session", (route) => route.fulfill({ json: { authenticated: false } }));
  await page.goto(`/games/${GAME}/${SLUG}`);
  await expect(page.getByRole("heading", { level: 1, name: "Death Stranding 2: On the Beach" })).toBeVisible();
  await expect(page).toHaveTitle(TITLE);
  await expect(page.locator('link[rel="canonical"]')).toHaveAttribute("href", `${baseURL}/games/${GAME}/${SLUG}`);
  const json = await page.locator('script[type="application/ld+json"]').textContent();
  const data: Record<string, unknown> = JSON.parse(json ?? "{}");
  expect(data).toMatchObject({
    "@type": "VideoGame",
    name: "Death Stranding 2: On the Beach",
    url: `${baseURL}/games/${GAME}/${SLUG}`,
  });
  // The seed's notice is no description, and no rating is stated without community votes.
  expect(data).not.toHaveProperty("description");
});

test("after JavaScript the public pages keep the titles the server wrote", async ({ page }) => {
  await page.route("**/api/v1/session", (route) => route.fulfill({ json: { authenticated: false } }));
  for (const [path, title] of [
    ["/lanzamientos", "Lanzamientos · Gameómetro"],
    ["/lanzamientos/calendario", "Calendario de lanzamientos · Gameómetro"],
    ["/privacidad", "Política de privacidad · Gameómetro"],
    ["/topometro", "TOPómetro · Gameómetro"],
  ] as const) {
    await page.goto(path);
    await expect(page.locator("h1").first()).toBeVisible();
    await expect(page).toHaveTitle(title);
  }
});
