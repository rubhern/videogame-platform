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
    await page.getByRole("region",{name:"Dónde has jugado"}).screenshot({path:testInfo.outputPath(`platform-distribution-${width}.png`)});
  }
  expect(reads).toBe(1);
  // The needle reads the exact mean on the columns' own scale: 7,2 falls inside column 7, right of its centre.
  const centre=async (name: RegExp) => { const box=await page.getByRole("button",{name}).boundingBox(); return box ? box.x+box.width/2 : NaN; };
  const needle=await page.locator(".insights-mean-needle").boundingBox();
  const [seven,eight]=[await centre(/^7 de 10:/),await centre(/^8 de 10:/)];
  expect(needle?.x).toBeGreaterThan(seven);
  expect(needle?.x).toBeLessThan((seven+eight)/2);
  const platforms=page.getByRole("region",{name:"Dónde has jugado"});
  await expect(platforms).toContainText("73 selecciones en total");
  await expect(platforms).toContainText("22 juegos sin plataforma indicada");
  const slices=await platforms.locator(".insights-donut-slice").evaluateAll(nodes => nodes.map(node => Number.parseFloat(node.getAttribute("stroke-dasharray") ?? "0")));
  expect(slices).toHaveLength(5);
  expect(slices.reduce((sum,length) => sum+length,0)).toBeCloseTo(100,5);
  expect(slices[0]).toBeCloseTo(31/73*100,5);
  const disclosure=platforms.getByText("Tus puntuaciones por plataforma");
  await disclosure.focus();
  await page.keyboard.press("Enter");
  await expect(platforms.locator("ol").getByRole("listitem").filter({hasText:"Xbox Series X|S"})).toContainText("Sin puntuar");
  await accessible(page);
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
  // Scored and played-only segments stay distinct as fill against outline, not by colour.
  await expect(page.locator(".insights-platform-played > span").first()).toHaveCSS("background-color","rgb(0, 0, 0)");
  await expect(page.locator(".insights-platform-played").first()).toHaveCSS("border-top-style","solid");
  await expect(page.locator(".insights-donut-pattern-base").first()).toHaveCSS("fill","rgb(0, 0, 0)");
  await expect(page.locator(".insights-donut-pattern-mark").first()).toHaveCSS("fill","rgb(255, 255, 255)");
  await expect(platforms.locator(".insights-donut-legend li")).toHaveCount(5);
  await page.screenshot({path:testInfo.outputPath("personal-statistics-forced-colours.png"),fullPage:true});
  expect(await page.evaluate(() => document.getAnimations().filter(animation => animation.playState==="running").length)).toBe(0);
});

test("empty, sparse and failed states never fill charts with pretend data",async ({page},testInfo) => {
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
});

test("cold navigation waits for private statistics before mounting the page",async ({page},testInfo) => {
  await session(page);
  await page.setViewportSize({width:390,height:844});
  let releaseRead = () => {};
  const responseReady = new Promise<void>(resolve => { releaseRead=resolve; });
  let reads=0;
  await page.route("**/api/v1/me/rating-insights",async route => {
    reads++;
    await responseReady;
    await route.fulfill({json:ratingInsightsFixture()});
  });
  const requested = page.waitForRequest("**/api/v1/me/rating-insights");
  await page.goto(path);
  await requested;
  try {
    // The router owns cold-load waiting: its loader reads the private snapshot before
    // mounting the page so scroll restoration sees the destination's final height.
    await expect(page.getByRole("status").filter({hasText:"Cargando página…"})).toBeVisible();
    await expect(page.getByRole("heading",{name:"Así puntúas"})).toHaveCount(0);
    await expect(page.getByRole("button",{name:/de 10:/})).toHaveCount(0);
    await page.screenshot({path:testInfo.outputPath("personal-statistics-loading.png"),fullPage:true});
  } finally {
    releaseRead();
  }
  await expect(page.getByRole("heading",{name:"Así puntúas"})).toBeVisible();
  await expect(page.getByText("84",{exact:true})).toBeVisible();
  expect(reads).toBe(1);
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
    platforms:{...original.platforms,
      distribution: { ps5: 31*factor, switch2: 9*factor, xboxSeries: 4*factor, windowsPc: 18*factor, other: 11*factor },
      items:original.platforms.items.map(item=>({...item,playedGames:item.playedGames*factor,scoredGames:item.scoredGames*factor})),
      gamesWithoutPlatforms:original.platforms.gamesWithoutPlatforms*factor,
      gamesOnSeveralPlatforms:original.platforms.gamesOnSeveralPlatforms*factor},
  };
  await page.route("**/api/v1/me/rating-insights",route=>route.fulfill({json:data}));
  await page.goto(path);
  await expect(page.getByRole("button",{name:/^9 de 10: 180.000 juegos/})).toBeVisible();
  await expect(page.getByRole("region",{name:"Dónde has jugado"})).toContainText("730.000 selecciones en total");
  // Narrow chart labels never spill into a neighbour's column.
  for(const label of await page.locator(".insights-month-count, .insights-bin-fill > span").all()) {
    const [own,parent]=await label.evaluate(node => [node.scrollWidth,(node.closest(".insights-month, .insights-bin") as HTMLElement).clientWidth+8]);
    expect(own).toBeLessThanOrEqual(parent ?? 0);
  }
  await accessible(page);
});

test("zero and single-slice platform distributions retain all five numerical categories", async ({page}, testInfo) => {
  await session(page);
  const original=ratingInsightsFixture();
  let data={...original, platforms:{...original.platforms,items:[],gamesWithoutPlatforms:84,
    distribution:{ps5:0,switch2:0,xboxSeries:0,windowsPc:0,other:0}}};
  await page.route("**/api/v1/me/rating-insights", route=>route.fulfill({json:data}));
  await page.setViewportSize({width:320,height:900});
  await page.goto(path);
  const platforms=page.getByRole("region",{name:"Dónde has jugado"});
  await expect(platforms.locator(".insights-donut-legend li")).toHaveCount(5);
  await expect(platforms.locator(".insights-donut-slice")).toHaveCount(0);
  await expect(platforms).toContainText("0 selecciones en total");
  await accessible(page);
  data={...data,platforms:{...data.platforms,items:original.platforms.items.slice(3,4).map(item=>({...item,playedGames:84,scoredGames:0,mean:null})),gamesWithoutPlatforms:0,distribution:{ps5:0,switch2:0,xboxSeries:0,windowsPc:0,other:84}}};
  await page.reload();
  await expect(platforms.locator(".insights-donut-slice")).toHaveCount(1);
  await expect(platforms.locator('[data-slice="other"]')).toContainText("84 100 %");
  await accessible(page);
  await page.screenshot({path:testInfo.outputPath("platforms-single-slice-320.png"),fullPage:true});
});
