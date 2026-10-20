import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";
import { analyzeAccessibility } from "./fixtures/accessibility";
import { emptyRatingInsightsFixture, ratingInsightsFixture } from "../src/test/fixtures/rating-insights";

const path="/mis-puntuaciones/estadisticas";
async function session(page: Page) {
  await page.route("**/api/v1/session", route => route.fulfill({ json: { authenticated:true,csrfToken:"insights-browser-session",capabilities:[] } }));
}
async function accessible(page: Page) {
  await page.evaluate(() => document.fonts.ready);
  expect(await page.evaluate(() => document.documentElement.scrollWidth-window.innerWidth)).toBeLessThanOrEqual(0);
  expect((await analyzeAccessibility(page)).violations).toEqual([]);
}

test("personal score atlas reflows at every reference width and keeps keyboard / forced-colour readings", async ({page},testInfo) => {
  test.slow();
  await session(page);
  await page.emulateMedia({ reducedMotion:"reduce" });
  let reads=0;
  await page.route("**/api/v1/me/rating-insights",route => {reads++;return route.fulfill({json:ratingInsightsFixture()});});
  await page.goto(path);
  await expect(page.getByRole("heading",{name:"Así puntúas"})).toBeVisible();
  for(const width of [1320,834,390,320]) {
    await page.setViewportSize({width,height:900});
    await accessible(page);
    await page.screenshot({path:testInfo.outputPath(`personal-statistics-${width}.png`),fullPage:true});
  }
  expect(reads).toBe(1);
  const nine=page.getByRole("button",{name:/^9 de 10: 18 juegos/});
  await nine.focus();
  await page.keyboard.press("Enter");
  await expect(nine).toHaveAttribute("aria-pressed","true");
  await expect(page.getByText("9 de 10 · Ardiendo · 18 juegos · 21,4 % de tu colección")).toBeVisible();
  const best=page.getByRole("button",{name:"Mejor puntuados"});
  await best.focus();
  await page.keyboard.press("Space");
  await expect(best).toHaveAttribute("aria-pressed","true");
  await expect(page.getByRole("region",{name:"Tu mapa de géneros"}).getByRole("listitem").first()).toContainText("Rol");
  const months=page.getByText("Ver meses y recuentos");
  await months.focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("table")).toBeVisible();
  await page.emulateMedia({forcedColors:"active",reducedMotion:"reduce"});
  // axe reads authored palette values in forced colours, even for the shared shell's native
  // system-colour text. Normal-mode contrast is scanned above at all four widths. Here check
  // actual system-colour mapping and retain every structural axe rule.
  expect((await new AxeBuilder({page}).disableRules(["color-contrast"]).analyze()).violations).toEqual([]);
  await expect(page.locator(".insights-bin-fill").first()).toHaveCSS("background-color","rgb(0, 0, 0)");
  await expect(page.locator(".insights-bin-fill > span").first()).toHaveCSS("color","rgb(0, 0, 0)");
  await page.screenshot({path:testInfo.outputPath("personal-statistics-forced-colours.png"),fullPage:true});
  expect(await page.evaluate(() => document.getAnimations().filter(animation => animation.playState==="running").length)).toBe(0);
});

test("empty, sparse, failed and loading states never fill charts with pretend data",async ({page},testInfo) => {
  await session(page);
  await page.setViewportSize({width:390,height:844});
  await page.route("**/api/v1/me/rating-insights",route => route.fulfill({json:emptyRatingInsightsFixture()}));
  await page.goto(path);
  await expect(page.getByRole("heading",{name:"Tu historia empieza con una puntuación"})).toBeVisible();
  await expect(page.getByRole("button",{name:/de 10:/})).toHaveCount(0);
  await accessible(page);
  await page.screenshot({path:testInfo.outputPath("personal-statistics-empty.png"),fullPage:true});
  const source=ratingInsightsFixture().highest;
  const extreme=source ? {...source,tiedGames:1,game:{...source.game,primaryCover:{kind:"fallback",url:"/assets/covers/fallback.svg",alternativeText:"Portada no disponible"}}} : null;
  await page.route("**/api/v1/me/rating-insights",route => route.fulfill({json:{...emptyRatingInsightsFixture(),total:1,mean:10,distribution:[0,0,0,0,0,0,0,0,0,1],highest:extreme,lowest:extreme,gamesWithoutGenres:1}}));
  await page.reload();
  await expect(page.getByText(/Con menos de cinco juegos/)).toBeVisible();
  await page.getByRole("button",{name:"Mejor puntuados"}).click();
  await expect(page.getByText(/Aún no hay géneros con tres juegos/)).toBeVisible();
  await accessible(page);
  await page.screenshot({path:testInfo.outputPath("personal-statistics-small-sample.png"),fullPage:true});
  await page.route("**/api/v1/me/rating-insights",route => route.fulfill({status:500,json:{code:"PERSONAL_RATINGS_READ_FAILED",correlationId:"browser-reference"}}));
  await page.reload();
  await expect(page.getByRole("heading",{name:"No se pudieron cargar tus estadísticas"})).toBeVisible();
  await accessible(page);
  await page.screenshot({path:testInfo.outputPath("personal-statistics-error.png"),fullPage:true});
  let resolve: (()=>void) | undefined;
  await page.route("**/api/v1/me/rating-insights",async route => {await new Promise<void>(done => {resolve=done;});await route.fulfill({json:ratingInsightsFixture()});});
  await page.reload();
  await expect(page.getByRole("status").filter({hasText:"Leyendo tus puntuaciones"})).toBeVisible();
  await expect(page.locator(".insights-loading button")).toHaveCount(0);
  await page.screenshot({path:testInfo.outputPath("personal-statistics-loading.png"),fullPage:true});
  resolve?.();
  await expect(page.getByRole("heading",{name:"Así puntúas"})).toBeVisible();
});

test("large collections keep exact accessible counts without overflowing narrow charts", async ({page}) => {
  await session(page);
  await page.setViewportSize({width:320,height:900});
  await page.emulateMedia({reducedMotion:"reduce"});
  const original=ratingInsightsFixture();
  const factor=10000;
  const data={...original,total:original.total*factor,
    distribution:original.distribution.map(count=>count*factor),
    mostRatedGenres:original.mostRatedGenres.map(genre=>({...genre,count:genre.count*factor})),
    bestRatedGenres:original.bestRatedGenres.map(genre=>({...genre,count:genre.count*factor})),
    gamesWithoutGenres:original.gamesWithoutGenres*factor,
    activity:original.activity.map(month=>({...month,count:month.count*factor})),
    olderRatings:original.olderRatings*factor,
  };
  await page.route("**/api/v1/me/rating-insights",route=>route.fulfill({json:data}));
  await page.goto(path);
  await expect(page.getByRole("button",{name:/^9 de 10: 180.000 juegos/})).toBeVisible();
  await accessible(page);
});
