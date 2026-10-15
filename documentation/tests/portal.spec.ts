import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { readFileSync } from 'node:fs';
const manifest = JSON.parse(readFileSync('.cache/content-manifest.json','utf8'));

test.beforeEach(async ({page},info)=>{
  // Fail closed on external runtime requests; navigation links to GitHub are allowed.
  const origin = new URL(process.env.PORTAL_BASE_URL || 'http://127.0.0.1:4321').origin;
  await page.route('**/*',route=>{
    if (new URL(route.request().url()).origin === origin) return route.continue();
    info.annotations.push({type:'unexpected-external-request',description:route.request().url()});
    return route.abort();
  });
});

test.afterEach(async ({},info)=>{
  expect(info.annotations.filter(annotation=>annotation.type === 'unexpected-external-request')).toEqual([]);
});

test('landing, navigation, local search and keyboard access',async ({page},info)=>{
  const failures:string[]=[];
  page.on('pageerror',error=>failures.push(error.message));
  await page.goto('/');
  await expect(page.getByRole('heading',{level:1})).toHaveText('Gameómetro knowledge base');
  await page.getByRole('link',{name:'Get started locally →'}).click();
  await expect(page).toHaveURL(/docs\/development\/local-setup\//);
  await expect(page.getByRole('navigation',{name:'Breadcrumb'})).toContainText('Start here');
  await page.getByRole('button',{name:'Search',exact:true}).click();
  await page.getByRole('textbox',{name:'Search',exact:true}).fill('Structurizr');
  const result = page.locator('.pagefind-ui__result-link').first();
  await expect(result).toBeVisible();
  await result.click();
  await expect(page.getByRole('heading',{level:1})).toBeVisible();
  expect(failures).toEqual([]);
  await page.goto('/');
  await page.getByRole('combobox',{name:'Select theme'}).selectOption('dark');
  await expect(page.locator('html')).toHaveAttribute('data-theme','dark');
  await page.getByRole('combobox',{name:'Select theme'}).selectOption('light');
  const accessibility = await new AxeBuilder({page}).analyze();
  expect(accessibility.violations).toEqual([]);
  await page.screenshot({path:info.outputPath('landing-desktop.png'),fullPage:true});
});

for (const theme of ['light','dark']) {
  for (const width of [1320,390]) {
    test(`all catalogue diagrams render in ${theme} at ${width}px`,async ({page},info)=>{
      await page.setViewportSize({width,height:900});
      await page.addInitScript(theme=>localStorage.setItem('starlight-theme',theme),theme);
      const routes = ['/diagrams/c4/',...manifest.diagrams.map((name:string)=>`/diagrams/${name}/`)];
      for (const route of routes) {
        await page.goto(route);
        await expect(page.locator('html')).toHaveAttribute('data-theme',theme);
        const images = page.locator(`.diagram-viewport img.diagram-${theme}`);
        await expect(images.first()).toBeVisible();
        for (const img of await images.all()) {
          await img.scrollIntoViewIfNeeded();
          await expect.poll(()=>img.evaluate((element:HTMLImageElement)=>element.complete && element.naturalWidth > 0)).toBe(true);
        }
        expect(await page.evaluate(()=>document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
        const zoom = page.locator('.diagram').first().getByRole('button');
        await zoom.click();
        await expect(zoom).toHaveAttribute('aria-pressed','true');
        const viewport = page.locator('.diagram-viewport').first();
        await viewport.focus();
        await expect(viewport).toBeFocused();
        await page.getByRole('link',{name:'Original source',exact:true}).first().click();
        // Raw sources are served or downloaded, depending on browser MIME handling.
        await page.goto(route);
        const name = route.split('/').filter(Boolean).at(-1);
        await page.screenshot({path:info.outputPath(`${name}-${theme}-${width}.png`),fullPage:true});
      }
      await page.goto('/diagrams/c4/');
      expect((await new AxeBuilder({page}).analyze()).violations).toEqual([]);
    });
  }
}

for (const width of [320,834]) {
  test(`navigation and search reflow at ${width}px`,async ({page},info)=>{
    await page.setViewportSize({width,height:900});
    await page.goto('/');
    if (width < 800) await page.getByRole('button',{name:'Menu',exact:true}).click();
    await page.getByRole('navigation',{name:'Main',exact:true}).getByRole('link',{name:'Visual architecture',exact:true}).click();
    await expect(page.getByRole('heading',{level:1})).toHaveText('Architecture diagrams');
    await page.getByRole('button',{name:'Search',exact:true}).click();
    await page.getByRole('textbox',{name:'Search',exact:true}).fill('Flyway');
    await expect(page.locator('.pagefind-ui__result-link').first()).toBeVisible();
    await page.keyboard.press('Escape');
    expect(await page.evaluate(()=>document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await page.screenshot({path:info.outputPath(`navigation-${width}.png`),fullPage:true});
  });
}
